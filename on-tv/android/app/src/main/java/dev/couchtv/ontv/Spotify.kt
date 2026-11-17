package dev.couchtv.ontv

import android.os.SystemClock
import android.util.Base64
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Spotify "now playing" for the home's card (tv-home/web/nowplaying.js): what is playing on any
 * device (phone, speaker, this TV's own Spotify app), and play, pause, next and previous.
 * Spotify's Web API, checked against its docs in November 2026: GET /v1/me/player and
 * /v1/me/player/devices (scope user-read-playback-state), PUT /v1/me/player/play and /pause,
 * POST /v1/me/player/next and /previous (scope user-modify-playback-state; these need Spotify
 * Premium). All still open to a Development Mode app, whose owner needs Premium.
 *
 * One household, more than one Spotify account: each person signs in once with
 * scripts/spotify-auth.py, and the card shows whichever account is playing (the one playing on a
 * TV first, as that is what the room hears). The TV's own Spotify app is often logged in as someone
 * other than the person who set this up; with both accounts here, it shows either way.
 *
 * The keys (client id, client secret, one refresh token per account) stay in this app's private
 * storage and never go to the home page: it only sees GET /api/now-playing. A refresh token lasts
 * 6 months from its sign-in (Spotify's rule since July 2026); after that the script is run again.
 *
 * Spotify is only asked while the home is asking: an account that is playing every 3 s, the others
 * every 10 s, and not at all once the home has been out of sight for 30 s. A 429 is obeyed
 * (Retry-After): Development Mode has a quota per developer account that Spotify does not publish.
 */
object Spotify {
    private const val TAG = "couchtv.spotify"
    private const val K_ID = "spotify_client_id"
    private const val K_SECRET = "spotify_client_secret"
    private const val K_ACCOUNTS = "spotify_accounts"       // [{id, name, refresh, since}]
    private const val K_REFRESH = "spotify_refresh_token"   // one account, as first stored: read once, then moved
    private const val K_SINCE = "spotify_authorized_at"
    private const val API = "https://api.spotify.com/v1"
    private const val TV_APP = "com.spotify.tv.android"
    private const val PLAYING_MS = 3000L
    private const val IDLE_MS = 10_000L
    private const val AWAY_MS = 30_000L

    private class Account(val id: String, val name: String, @Volatile var refresh: String, val since: Long) {
        @Volatile var access: String? = null
        @Volatile var accessUntil = 0L
        @Volatile var tokenRetryAt = 0L
        @Volatile var state: JSONObject? = null   // the last reading, already in the page's shape
        @Volatile var readAt = 0L                 // when it was read (elapsedRealtime)
        @Volatile var waitUntil = 0L              // Spotify said 429: nothing before this
        @Volatile var problem: String? = null
        val playing get() = state?.optBoolean("playing") == true && state?.has("item") == true
        val hasSong get() = state?.has("item") == true
    }

    private val accounts = CopyOnWriteArrayList<Account>()
    @Volatile private var loaded = false
    @Volatile private var shown: String? = null       // the account on the card
    @Volatile private var wantedAt = 0L               // when the home last asked
    @Volatile private var gone: String? = null        // an account whose sign-in ran out
    private var poller: Thread? = null
    private val wake = Object()
    private val reading = Object()

    private fun now() = SystemClock.elapsedRealtime()

    @Synchronized private fun load() {
        if (loaded) return
        loaded = true
        val saved = runCatching { JSONArray(Store.str(K_ACCOUNTS) ?: "[]") }.getOrDefault(JSONArray())
        for (i in 0 until saved.length()) {
            val a = saved.optJSONObject(i) ?: continue
            if (a.optString("refresh").isNotEmpty()) accounts += Account(a.optString("id"), a.optString("name"), a.optString("refresh"), a.optLong("since"))
        }
        // the first version kept one account under its own keys
        val old = Store.str(K_REFRESH)
        if (accounts.isEmpty() && !old.isNullOrEmpty()) { accounts += Account("main", "", old, Store.str(K_SINCE)?.toLongOrNull() ?: 0); save() }
        if (!old.isNullOrEmpty()) { Store.putStr(K_REFRESH, null); Store.putStr(K_SINCE, null) }
    }

    private fun save() {
        val arr = JSONArray()
        accounts.forEach { arr.put(JSONObject().put("id", it.id).put("name", it.name).put("refresh", it.refresh).put("since", it.since)) }
        Store.putStr(K_ACCOUNTS, arr.toString())
    }

    val configured: Boolean
        get() { load(); return !Store.str(K_ID).isNullOrEmpty() && !Store.str(K_SECRET).isNullOrEmpty() && accounts.isNotEmpty() }

    // ---------- setup ----------

    /** The app's two keys and every account's sign-in (this replaces the accounts that were here). */
    fun setup(id: String, secret: String, signIns: List<Pair<String, Long>>) {
        if (id.isBlank() || secret.isBlank() || signIns.isEmpty()) throw ApiError(400, "client_id, client_secret and at least one refresh_token are required")
        load()
        Store.putStr(K_ID, id.trim()); Store.putStr(K_SECRET, secret.trim())
        val fresh = ArrayList<Account>()
        for ((refresh, at) in signIns) {
            // prove each one works now, and ask whose it is: a wrong key is reported to the script, not found out later
            val probe = Account("", "", refresh.trim(), if (at > 0) at else System.currentTimeMillis())
            val tok = token(probe) ?: throw ApiError(502, probe.problem ?: "Spotify did not accept these keys")
            val me = http("GET", "$API/me", bearer = tok)
            val j = if (me.status == 200) runCatching { JSONObject(me.body) }.getOrNull() else null
            val uid = j?.optString("id").orEmpty().ifEmpty { "account${fresh.size + 1}" }
            if (fresh.any { it.id == uid }) continue   // the same person twice
            fresh += Account(uid, j?.optString("display_name").orEmpty(), probe.refresh, probe.since).also { it.access = probe.access; it.accessUntil = probe.accessUntil }
        }
        accounts.clear(); accounts.addAll(fresh); save()
        shown = null; gone = null
        Log.i(TAG, "set up: ${accounts.size} account(s)")
    }

    fun forget() {
        load()
        Store.putStr(K_ID, null); Store.putStr(K_SECRET, null); Store.putStr(K_ACCOUNTS, null)
        accounts.clear(); shown = null; gone = null
    }

    /** Whether it is set up, for whom and since when. Never the keys themselves. */
    fun status(): JSONObject {
        load()
        val arr = JSONArray()
        accounts.forEach { arr.put(JSONObject().put("name", it.name.ifEmpty { it.id }).put("authorized_at", it.since).put("problem", it.problem ?: JSONObject.NULL)) }
        return JSONObject().put("configured", configured).put("accounts", arr)
            .put("authorized_at", accounts.minOfOrNull { it.since } ?: JSONObject.NULL)
            .put("problem", problem() ?: JSONObject.NULL)
    }

    private fun problem(): String? = accounts.firstNotNullOfOrNull { it.problem } ?: gone

    // ---------- what the home asks ----------

    fun nowPlaying(): JSONObject {
        if (!configured) return JSONObject().put("configured", false).put("playing", false).put("problem", problem() ?: JSONObject.NULL)
        wantedAt = now()
        // the first look after a while (the home is back in front): read now, so the answer is fresh
        val stale = { a: Account -> a.state == null || now() - a.readAt > IDLE_MS + 5000 }
        if (accounts.any(stale)) synchronized(reading) { accounts.filter(stale).forEach { read(it) } }
        startPoller()
        val a = pick()
        val s = a?.state ?: return JSONObject().put("configured", true).put("playing", false).put("problem", problem() ?: JSONObject.NULL)
        return JSONObject(s.toString()).put("configured", true).put("age_ms", now() - a.readAt)
            .put("account", a.name.ifEmpty { a.id }).put("problem", problem() ?: JSONObject.NULL)
    }

    /**
     * The account on the card: one that is playing (on a TV first, as that is what the room hears;
     * else the one already showing), and with nothing playing, the one last shown if it still has a
     * song, else any that has one.
     */
    private fun pick(): Account? {
        val live = accounts.filter { it.playing }
        if (live.isNotEmpty()) {
            val p = live.firstOrNull { isThisTv(it.state?.optJSONObject("device")?.optString("name").orEmpty()) }
                ?: live.firstOrNull { it.id == shown } ?: live.first()
            shown = p.id
            return p
        }
        return accounts.firstOrNull { it.id == shown && it.hasSong } ?: accounts.firstOrNull { it.hasSong }
    }

    /** play, pause, next or previous for the account on the card. Answers with the new state. */
    fun control(action: String): JSONObject {
        if (!configured) throw ApiError(409, "Spotify is not set up on this TV")
        val (method, path) = when (action) {
            "play" -> "PUT" to "/play"; "pause" -> "PUT" to "/pause"
            "next" -> "POST" to "/next"; "previous" -> "POST" to "/previous"
            else -> throw ApiError(404, "unknown Spotify control: $action")
        }
        val a = pick()
        var done = false
        if (a != null) {
            val tok = token(a) ?: throw ApiError(502, a.problem ?: "Spotify did not sign in")
            val r = http(method, "$API/me/player$path", bearer = tok)
            when (r.status) {
                200, 202, 204 -> done = true
                401 -> { a.access = null; throw ApiError(502, "Spotify signed this TV out for a moment. Try again.") }
                403 -> throw ApiError(403, reason(r.body) ?: "Spotify would not do that. Controls need Spotify Premium.")
                404 -> if (action != "play") throw ApiError(404, "Nothing is playing on Spotify right now")   // play: see below
                429 -> { backOff(a, r); throw ApiError(429, "Spotify asked for a pause. Try again in a little while.") }
                -1 -> throw ApiError(502, "Spotify did not answer")
                else -> throw ApiError(502, reason(r.body) ?: "Spotify said no (HTTP ${r.status})")
            }
        } else if (action != "play") throw ApiError(404, "Nothing is playing on Spotify right now")
        // Play with nothing to resume: this TV's own Spotify, by Spotify (the TV as one of an
        // account's devices) or, failing that, by the TV app's own play button.
        if (!done) done = playOnTv()
        if (!done && Tv.mediaButtonTo(TV_APP, android.view.KeyEvent.KEYCODE_MEDIA_PLAY)) {
            Thread.sleep(1800)
            synchronized(reading) { accounts.forEach { read(it) } }
            done = accounts.any { it.playing }
        }
        if (!done) throw ApiError(404, "Nothing to resume. Open Spotify and pick something.")
        // Spotify takes a moment to report the change
        Thread.sleep(if (action == "next" || action == "previous") 650 else 350)
        synchronized(reading) { if (a != null) read(a) else accounts.forEach { read(it) } }
        // still the old answer: trust the command, the next reading settles it
        if (a != null && (action == "play" || action == "pause")) a.state?.let { if (it.has("item")) it.put("playing", action == "play") }
        synchronized(wake) { wake.notifyAll() }
        return nowPlaying()
    }

    /**
     * Shuffle on or off for the account on the card, through Spotify's servers. They will not change
     * it on every device: a TV's own Spotify app reports "toggling_shuffle" as not allowed, and a
     * request is accepted and then ignored (seen on a Fire TV, November 2026; asking that app
     * through its Android media session changed nothing either). So the answer is what Spotify
     * reports afterwards, never what was asked for: no change is an error. The home's card only
     * shows the state; this is here for a caller that plays on a phone or a speaker.
     */
    fun shuffle(on: Boolean): JSONObject {
        if (!configured) throw ApiError(409, "Spotify is not set up on this TV")
        val a = pick() ?: throw ApiError(404, "Nothing is playing on Spotify right now")
        val tok = token(a) ?: throw ApiError(502, a.problem ?: "Spotify did not sign in")
        val settled = {
            var ok = false
            for (wait in longArrayOf(450, 800, 1300)) {
                Thread.sleep(wait)
                synchronized(reading) { read(a) }
                if (a.state?.optBoolean("shuffle") == on) { ok = true; break }
            }
            ok
        }
        var done = false
        if (!done) {
            val r = http("PUT", "$API/me/player/shuffle?state=$on", bearer = tok)
            when (r.status) {
                200, 202, 204 -> done = settled()
                401 -> { a.access = null; throw ApiError(502, "Spotify signed this TV out for a moment. Try again.") }
                403 -> throw ApiError(403, reason(r.body) ?: "Spotify would not do that. Controls need Spotify Premium.")
                404 -> throw ApiError(404, "Nothing is playing on Spotify right now")
                429 -> { backOff(a, r); throw ApiError(429, "Spotify asked for a pause. Try again in a little while.") }
                -1 -> throw ApiError(502, "Spotify did not answer")
                else -> throw ApiError(502, reason(r.body) ?: "Spotify said no (HTTP ${r.status})")
            }
        }
        synchronized(wake) { wake.notifyAll() }
        if (!done) throw ApiError(409, "Spotify does not let shuffle be changed on this device")
        return nowPlaying()
    }

    /**
     * Is this Spotify device this TV? By name only (the TV's own device name, or the name it has in
     * couch-tv): an account can have other TVs, in other homes, and "some TV" must never do.
     */
    private fun isThisTv(name: String): Boolean {
        val norm = { s: String -> s.lowercase().filter { it.isLetterOrDigit() } }
        val n = norm(name)
        return n.isNotEmpty() && (n == norm(Store.tvName()) || n == norm(Tv.deviceName()))
    }

    /** Starts playback on this TV, where an account offers it as one of its devices. False when none does. */
    private fun playOnTv(): Boolean {
        for (a in accounts) {
            val tok = token(a) ?: continue
            val r = http("GET", "$API/me/player/devices", bearer = tok)
            if (r.status != 200) continue
            val list = runCatching { JSONObject(r.body).optJSONArray("devices") }.getOrNull() ?: continue
            val devs = (0 until list.length()).mapNotNull { list.optJSONObject(it) }.filter { !it.optBoolean("is_restricted") && it.optString("id").isNotEmpty() }
            val d = devs.firstOrNull { isThisTv(it.optString("name")) } ?: continue
            val p = http("PUT", "$API/me/player/play?device_id=" + URLEncoder.encode(d.optString("id"), "UTF-8"), bearer = tok)
            if (p.status in 200..204) { shown = a.id; Thread.sleep(600); synchronized(reading) { read(a) }; return true }
        }
        return false
    }

    // ---------- the poller ----------

    private fun startPoller() {
        synchronized(wake) {
            if (poller?.isAlive == true) return
            poller = Thread({ loop() }, "spotify").apply { isDaemon = true; start() }
        }
    }

    private fun loop() {
        while (configured && now() - wantedAt < AWAY_MS) {
            // each account in its own time: 3 s while it plays, 10 s otherwise
            var next = IDLE_MS
            for (a in accounts) {
                val due = (if (a.playing) PLAYING_MS else IDLE_MS) - (now() - a.readAt)
                if (due <= 0) {
                    synchronized(reading) { read(a) }
                    next = minOf(next, if (now() - a.readAt > 1000) 3000L else if (a.playing) PLAYING_MS else IDLE_MS)   // it did not read (no token, a 429): no spinning
                } else next = minOf(next, due)
            }
            synchronized(wake) { wake.wait(maxOf(next, 250L)) }
        }
    }

    private fun read(a: Account) {
        if (System.currentTimeMillis() < a.waitUntil) return
        val tok = token(a) ?: return
        val r = http("GET", "$API/me/player?additional_types=episode", bearer = tok)
        when (r.status) {
            200 -> try {
                val s = shape(JSONObject(r.body))
                // Playing on another TV (the same account in someone else's home): not this room's
                // music, so the card neither shows it nor can pause it.
                val dev = s.optJSONObject("device")
                val elsewhere = dev != null && dev.optString("type").equals("TV", true) && !isThisTv(dev.optString("name"))
                a.state = if (elsewhere) JSONObject().put("playing", false) else s
                a.readAt = now(); a.problem = null
            } catch (e: Exception) { Log.w(TAG, "reading: ${e.message}") }
            202, 204 -> { a.state = JSONObject().put("playing", false); a.readAt = now(); a.problem = null }   // nothing playing anywhere
            401 -> a.access = null                                                                           // a new token next time
            429 -> backOff(a, r)
            else -> Log.w(TAG, "player: HTTP ${r.status}")
        }
    }

    private fun backOff(a: Account, r: Reply) {
        val secs = maxOf(r.retryAfter, 30)
        // the allowance is the developer account's, so every account waits
        accounts.forEach { it.waitUntil = System.currentTimeMillis() + secs * 1000L }
        a.problem = if (r.body.contains("QUOTA_EXCEEDED")) "Spotify's daily allowance for this developer account is used up" else "Spotify asked for a pause"
        Log.w(TAG, "429: waiting $secs s")
    }

    /** Spotify's answer in the page's shape: playing, progress_ms, device, and the song or episode. */
    private fun shape(j: JSONObject): JSONObject {
        val out = JSONObject().put("playing", j.optBoolean("is_playing")).put("progress_ms", j.optLong("progress_ms"))
            .put("shuffle", j.optBoolean("shuffle_state"))
        j.optJSONObject("device")?.let { out.put("device", JSONObject().put("name", it.optString("name")).put("type", it.optString("type"))) }
        val item = j.optJSONObject("item") ?: return out   // an advert, or something Spotify will not name
        val episode = item.optString("type") == "episode"
        val images = if (episode) item.optJSONArray("images") ?: item.optJSONObject("show")?.optJSONArray("images")
            else item.optJSONObject("album")?.optJSONArray("images")
        val artist = if (episode) item.optJSONObject("show")?.optString("name").orEmpty()
            else item.optJSONArray("artists")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.joinToString(", ") }.orEmpty()
        return out.put("item", JSONObject().put("id", item.optString("id")).put("title", item.optString("name")).put("artist", artist)
            .put("art", images?.optJSONObject(0)?.optString("url") ?: JSONObject.NULL)   // widest first: 640 px
            .put("duration_ms", item.optLong("duration_ms")))
    }

    // ---------- signing in ----------

    private fun token(a: Account): String? { synchronized(a) {
        a.access?.let { if (System.currentTimeMillis() < a.accessUntil - 60_000) return it }
        if (System.currentTimeMillis() < a.tokenRetryAt) return null
        val id = Store.str(K_ID); val secret = Store.str(K_SECRET)
        if (id.isNullOrEmpty() || secret.isNullOrEmpty() || a.refresh.isEmpty()) return null
        val r = http("POST", "https://accounts.spotify.com/api/token",
            basic = Base64.encodeToString("$id:$secret".toByteArray(), Base64.NO_WRAP),
            form = "grant_type=refresh_token&refresh_token=" + URLEncoder.encode(a.refresh, "UTF-8"))
        if (r.status == 200) {
            val j = JSONObject(r.body)
            a.access = j.getString("access_token")
            a.accessUntil = System.currentTimeMillis() + j.optLong("expires_in", 3600) * 1000
            // Spotify may hand out a new refresh token; when it does, that is the one to keep
            j.optString("refresh_token").takeIf { it.isNotEmpty() && it != a.refresh }?.let { a.refresh = it; if (accounts.contains(a)) save() }
            a.problem = null
            return a.access
        }
        a.access = null
        if (r.status == 400 && r.body.contains("invalid_grant")) {
            // the 6 months are up, or access was taken away in the Spotify account: no retrying
            a.problem = "Spotify's sign-in ran out (it lasts 6 months). Run scripts/spotify-auth.py again on the computer."
            if (accounts.remove(a)) { save(); gone = (a.name.ifEmpty { "One account" }) + ": " + a.problem }
        } else {
            a.tokenRetryAt = System.currentTimeMillis() + 60_000
            a.problem = if (r.status == -1) "Spotify could not be reached" else "Spotify did not accept the keys (HTTP ${r.status})"
        }
        Log.w(TAG, "token: HTTP ${r.status}")
        return null
    } }

    // ---------- transport ----------

    private class Reply(val status: Int, val body: String, val retryAfter: Int)

    private fun reason(body: String): String? =
        runCatching { JSONObject(body).optJSONObject("error")?.optString("message") }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { "Spotify: $it" }

    /** One call. Status -1 when Spotify could not be reached. Tokens are never logged. */
    private fun http(method: String, url: String, bearer: String? = null, basic: String? = null, form: String? = null): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.requestMethod = method; c.connectTimeout = 4000; c.readTimeout = 5000
            bearer?.let { c.setRequestProperty("Authorization", "Bearer $it") }
            basic?.let { c.setRequestProperty("Authorization", "Basic $it") }
            if (form != null) {
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray()) }
            } else if (method != "GET") {
                c.doOutput = true; c.setFixedLengthStreamingMode(0); c.outputStream.close()   // Content-Length: 0, which Spotify insists on
            }
            val st = c.responseCode
            val body = (if (st < 400) c.inputStream else c.errorStream)?.use { it.bufferedReader().readText() }.orEmpty()
            Reply(st, body, c.getHeaderField("Retry-After")?.toIntOrNull() ?: 0)
        } catch (e: Exception) {
            Log.w(TAG, "$method ${url.substringBefore('?').substringAfterLast('/')}: ${e.javaClass.simpleName}")
            Reply(-1, "", 0)
        } finally { c.disconnect() }
    }
}
