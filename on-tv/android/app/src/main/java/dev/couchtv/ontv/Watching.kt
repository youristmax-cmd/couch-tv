package dev.couchtv.ontv

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.Rating
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.SystemClock
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Keep watching from what is really watched on the TV. Netflix, Hulu and the rest tell this app
 * nothing, and their own "continue watching" lists cannot be read. What Android does offer, to an
 * app the TV's owner has allowed (MediaAccess says how), is each app's media session: the title it
 * says it is playing, how long it is and how far in. This listens to those sessions and keeps its
 * own log of what was watched. Every rule about what a session's words mean is in WatchRules.kt;
 * this file is the listening, the pictures and the storage.
 *
 * It does not poll: Android calls it when a session comes, goes, or changes what it says. While a
 * video app is playing, it looks again once a minute to count the minute and write the log.
 *
 * What is kept stays on the TV, in this app's own files: the log (watching.json), a copy of each
 * app's own picture for a title (watching-art/, JPEG, 640 px wide at most), and the last 60 raw
 * looks for GET /api/continue/log (watching-seen.json), which is how what each app really reports
 * gets found out. Nothing is sent anywhere, with one exception that is off unless switched on:
 * with "lookup" true, JustWatch (Finder) is asked about a title: for a picture when its app gave
 * none, and, when an app names only the episode, for the show it belongs to (the names tried are
 * the ones that app gave just before, and the shows already being watched in it). That tells
 * JustWatch those names.
 */
object Watching {
    private const val TAG = "couchtv.watching"
    private const val K_CONFIG = "watching"              // {"exclude": [...], "lookup": false}
    private const val FILE = "watching.json"
    private const val SEEN_FILE = "watching-seen.json"
    private const val QUIET_MS = 5000L                    // a session that only reports its position moving on is looked at this often at most

    private val main = Handler(Looper.getMainLooper())
    private val work = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "watching").apply { isDaemon = true } }
    private val lookups = Executors.newSingleThreadExecutor { r -> Thread(r, "watching-lookup").apply { isDaemon = true } }
    private val lock = Any()
    private val log = WatchRules.Log(BuildConfig.APPLICATION_ID)
    private val evidence = WatchRules.Evidence()
    private val looking: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val labels = ConcurrentHashMap<String, String>()
    private val refusedAt = ConcurrentHashMap<String, Long>()    // per app: when Android last said it would not take a title's link

    /** What a session's metadata said, read once each time it changes (reading it again would fetch its picture again). */
    private class Meta(val json: JSONObject, val title: String?, val second: String?, val durationMs: Long, val artUri: String?,
                       val mediaId: String?, val mediaUri: String?, @Volatile var bitmap: Bitmap?, val picture: String?)

    /** One app's media session being listened to. Touched on the main thread only. */
    private class Held(val controller: MediaController) {
        var callback: MediaController.Callback? = null
        var meta: Meta? = null
        var state: PlaybackState? = null
        var about: JSONObject? = null       // what else the session offers (queue, extras, an activity): read when it changes, not at every look
        var lookedAt = 0L
    }

    private val held = HashMap<MediaSession.Token, Held>()
    @Volatile private var listening = false
    @Volatile private var since = 0L
    @Volatile private var tickDue = false
    @Volatile private var seenWrittenAt = 0L
    @Volatile private var started = false
    private val listener = MediaSessionManager.OnActiveSessionsChangedListener { list -> sessions(list ?: emptyList()) }

    // ---------------------------------------------------------------- settings (watching)

    private class Config(val exclude: Set<String>, val lookup: Boolean)
    @Volatile private var cached: Config? = null

    private val cfg: Config
        get() = cached ?: run {
            val s = Store.str(K_CONFIG)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
            val ex = s.optJSONArray("exclude")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotEmpty() } } ?: WatchRules.DEFAULT_EXCLUDE
            Config(ex.toSet(), s.optBoolean("lookup", false)).also { cached = it }
        }

    /** The settings as kept on this TV, with the defaults filled in, and whether the access is there. */
    fun config(): JSONObject = JSONObject().put("exclude", JSONArray(cfg.exclude.toList())).put("lookup", cfg.lookup)
        .put("access", listening).put("since", if (since > 0) Now.iso(since) else JSONObject.NULL)

    /** "watching" from POST /api/settings. Only the keys that are sent change; a wrong shape is refused. Takes effect at once. */
    fun setConfig(b: JSONObject) {
        val s = Store.str(K_CONFIG)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()
        b.optJSONArray("exclude")?.let { list ->
            val clean = JSONArray()
            for (i in 0 until minOf(list.length(), 80)) {
                val p = list.optString(i).trim()
                if (!Regex("[A-Za-z0-9_.]{3,200}").matches(p)) throw ApiError(400, "watching.exclude must be a list of Android package names")
                clean.put(p)
            }
            s.put("exclude", clean)
        }
        if (b.has("lookup")) {
            if (b.opt("lookup") !is Boolean) throw ApiError(400, "watching.lookup must be true or false")
            s.put("lookup", b.optBoolean("lookup"))
        }
        Store.putStr(K_CONFIG, s.toString())
        cached = null
        // switched on: the titles already in the log get their shows and pictures now, not after a restart
        lookUpAll()
    }

    // ---------------------------------------------------------------- what the home asks

    /** The cards from the watch log, newest first (Now.continueItems puts the old Finder ones after them). */
    fun items(): List<JSONObject> {
        ensure()
        lookUpAll()
        return synchronized(lock) { log.items(System.currentTimeMillis(), cfg.lookup, cfg.exclude) }
    }

    /** For GET /api/continue and GET /api/settings: is the access there, and since when. */
    fun status(): JSONObject = JSONObject().put("access", listening).put("since", if (since > 0) Now.iso(since) else JSONObject.NULL)
        .put("titles", synchronized(lock) { log.size }).put("lookup", cfg.lookup)

    /** GET /api/continue/log: the last 60 raw looks, newest first. Stays on the TV. */
    fun looks(): JSONArray = synchronized(lock) { JSONArray(evidence.json().toString()) }

    /**
     * OK on a card. The app it was watched in is opened, and it is opened on the title when all of
     * this holds: "lookup" is on, the app is one Finder knows, JustWatch has a link for the title
     * in that app, and the app on this TV takes that link (Android is asked: Tv.canOpen).
     *  - a title worked out from an episode's name: that one episode's link for the app, else the
     *    show's;
     *  - a title found under its own name: what Finder would do for "play <title>" in that app.
     * Otherwise, and whenever any of that does not work out, the app is opened as it is. No link
     * is ever made up from a session's ids, and a link the app cannot take is not sent anywhere.
     * (Peacock's Fire TV app takes none: seen 16 November 2026. OK on its cards opens Peacock.)
     */
    fun open(id: String, who: String?): JSONObject {
        val e = synchronized(lock) { log.find(id) } ?: throw ApiError(404, "not in keep watching")
        val pkg = e.pkg; val title = e.title
        val app = Apps.BY_PACKAGE[pkg]
        val name = e.appName ?: app?.name ?: label(pkg)
        val appJson = { JSONObject().put("id", app?.id ?: JSONObject.NULL).put("name", name).put("package", pkg) }
        val showId = e.showId
        if (e.kind == WatchRules.KIND_TITLE && cfg.lookup && app != null) {
            if (showId != null) {
                // an app that refused a link is not asked about for a day: JustWatch need not be asked each time OK is pressed
                // only to find, again, that the app takes nothing (Peacock on a Fire TV)
                val refused = refusedAt[pkg]?.let { System.currentTimeMillis() - it < WatchRules.LOOKUP_RETRY_MS } ?: false
                if (!refused) try {
                    val data = Finder.links(e.episodeId, showId)
                    val ofEpisode = ShowRules.link(data.optJSONObject("episode")?.optJSONArray("offers"), app.jw)
                    val link = ofEpisode ?: ShowRules.link(data.optJSONObject("show")?.optJSONArray("offers"), app.jw)
                    // to the app it was watched in, whatever edition of it JustWatch names
                    val uri = link?.let { LinkRules.aim(it, pkg, always = true) }
                    if (uri != null && Tv.canOpen(uri, pkg)) {
                        Tv.startUri(uri, pkg)
                        Now.push()
                        return JSONObject().put("title", e.showTitle ?: title).put("app", appJson()).put("service", app.id).put("method", "deeplink")
                            .put("note", if (ofEpisode != null) "Opened $title in $name." else "Opened ${e.showTitle ?: title} in $name.")
                    }
                    if (uri != null) refusedAt[pkg] = System.currentTimeMillis()
                    Log.i(TAG, "open: " + (if (uri == null) "no link for $name" else "$name takes no such link") + "; opening the app")
                } catch (ex: Exception) { Log.i(TAG, "open: ${ex.message}; opening the app instead") }
            } else if (e.lookedOk || e.lookedAt == 0L) {
                // (a title that was looked up and not found is not asked about again here)
                try { return Finder.play(title, e.season, e.episode, app.id, who, resume = true, onlyThere = true) }
                catch (ex: Exception) { Log.i(TAG, "open: ${ex.message}; opening the app instead") }
            }
        }
        if (!Tv.hasPackage(pkg)) throw ApiError(404, "$name is not installed on the TV")
        Tv.launchPackage(pkg)
        if (Adb.connected && Tv.waitForApp(setOf(pkg), 8000) == null) throw ApiError(504, "asked the TV to open $name but it did not come up")
        Now.push()
        return JSONObject().put("title", if (e.kind == WatchRules.KIND_APP) name else e.showTitle ?: title).put("app", appJson())
            .put("service", app?.id ?: JSONObject.NULL).put("method", "app").put("note", "Opened $name.")
    }

    /** Takes one card out of the log, with its picture. False when there is no such card. */
    fun forget(id: String): Boolean {
        synchronized(lock) {
            val e = log.forget(id) ?: return false
            e.art?.let { File(artDir(), it).delete() }
            log.takeWrite(SystemClock.elapsedRealtime()); write(System.currentTimeMillis())
            return true
        }
    }

    /** Empties the watch log: the titles, their pictures, the raw looks, and the episode lists kept for their shows. */
    fun forgetAll() {
        synchronized(lock) {
            log.clear()
            artDir().listFiles()?.forEach { it.delete() }
            evidence.clear()
            log.takeWrite(SystemClock.elapsedRealtime()); write(System.currentTimeMillis())
        }
        Finder.forgetEpisodes()
    }

    /** A kept picture for GET /api/continue/art/<file>, or null. Only names this app made itself. */
    fun artFile(file: String): File? {
        if (!WatchRules.ART_FILE.matches(file)) return null
        return File(artDir(), file).takeIf { it.isFile && it.length() > 0 }
    }

    private fun artDir() = File(App.instance.filesDir, "watching-art").apply { mkdirs() }

    // ---------------------------------------------------------------- listening

    /** Started with the app's service: reads the log back and starts listening, if that is allowed. */
    fun start() {
        if (started) return
        started = true
        work.execute {
            synchronized(lock) {
                log.load(Store.readJson(FILE) as? JSONObject)
                evidence.load((Store.readJson(SEEN_FILE) as? JSONObject)?.optJSONArray("looks"))
                // what the kept looks know: the previews in them, and when each title began (for a
                // title logged by an older version of this app, which kept neither)
                log.adopt(evidence.json())
            }
            lookUpAll()
        }
        main.post { relisten() }
    }

    /** The access was given or taken back (MediaAccess): start again from what is allowed now. */
    fun accessChanged() { main.post { relisten() } }

    /** Not listening although it may be allowed by now: try once more. Cheap, and asked for whenever the home asks for its row. */
    private fun ensure() { if (!listening) main.post { if (!listening) relisten() } }

    private fun relisten() {
        val ctx = App.instance
        val msm = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        val me = ComponentName(ctx, MediaAccess::class.java)
        runCatching { msm.removeOnActiveSessionsChangedListener(listener) }
        try {
            msm.addOnActiveSessionsChangedListener(listener, me, main)
            val now = msm.getActiveSessions(me)
            if (!listening) { listening = true; since = System.currentTimeMillis(); Log.i(TAG, "listening to media sessions") }
            sessions(now)
        } catch (e: SecurityException) {
            // not allowed (yet, or any more): let go of whatever was held
            if (listening) Log.i(TAG, "media access was taken back")
            listening = false; since = 0
            sessions(emptyList())
        }
    }

    /** The sessions there are now (main thread): new ones are listened to, gone ones let go. */
    private fun sessions(list: List<MediaController>) {
        val now = list.associateBy { it.sessionToken }
        for (tok in held.keys.filter { it !in now }) drop(tok)
        for ((tok, c) in now) {
            if (tok in held) continue
            val h = Held(c)
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    val changed = state?.state != h.state?.state
                    h.state = state
                    // an app may report its position every second: only a change of state is looked at at once
                    if (changed || SystemClock.elapsedRealtime() - h.lookedAt >= QUIET_MS) look(h)
                }
                override fun onMetadataChanged(metadata: MediaMetadata?) { h.meta = read(metadata); h.about = about(c); look(h) }
                override fun onQueueTitleChanged(title: CharSequence?) { h.about = about(c); look(h) }
                override fun onExtrasChanged(extras: Bundle?) { h.about = about(c); look(h) }
                override fun onSessionDestroyed() { drop(tok) }
            }
            h.callback = cb
            held[tok] = h
            runCatching { c.registerCallback(cb, main) }.onFailure { Log.w(TAG, "${c.packageName}: ${it.message}") }
            h.meta = read(runCatching { c.metadata }.getOrNull())
            h.state = runCatching { c.playbackState }.getOrNull()
            h.about = about(c)
            look(h)
        }
    }

    private fun drop(tok: MediaSession.Token) {
        val h = held.remove(tok) ?: return
        h.callback?.let { cb -> runCatching { h.controller.unregisterCallback(cb) } }
        val pkg = h.controller.packageName
        if (held.values.none { it.controller.packageName == pkg }) work.execute {
            synchronized(lock) { if (log.gone(pkg, SystemClock.elapsedRealtime())) write(System.currentTimeMillis()) }
        }
    }

    // ---------------------------------------------------------------- one look

    private val PICTURES = listOf(MediaMetadata.METADATA_KEY_ART, MediaMetadata.METADATA_KEY_ALBUM_ART, MediaMetadata.METADATA_KEY_DISPLAY_ICON)
    private val NUMBERS = setOf(MediaMetadata.METADATA_KEY_DURATION, MediaMetadata.METADATA_KEY_YEAR, MediaMetadata.METADATA_KEY_TRACK_NUMBER,
        MediaMetadata.METADATA_KEY_NUM_TRACKS, MediaMetadata.METADATA_KEY_DISC_NUMBER, MediaMetadata.METADATA_KEY_BT_FOLDER_TYPE,
        "android.media.metadata.ADVERTISEMENT", "android.media.metadata.DOWNLOAD_STATUS")
    private val RATINGS = setOf(MediaMetadata.METADATA_KEY_USER_RATING, MediaMetadata.METADATA_KEY_RATING)

    /**
     * The metadata's values as they really are. MediaMetadata only hands a value out through a
     * getter for one kind (text, number, picture, rating), and a getter for the wrong kind says
     * "nothing" just as it does for a key that holds nothing. So a key that is there with nothing
     * in it (Hulu on a Fire TV: four keys, all null) could not be told from a kind nobody thought
     * of. Written out and read back, it is a plain Bundle, and each value says what it is.
     * Null when that did not work; the getters are then all there is.
     */
    private fun raw(md: MediaMetadata): Bundle? = try {
        val p = Parcel.obtain()
        try { md.writeToParcel(p, 0); p.setDataPosition(0); p.readBundle(MediaMetadata::class.java.classLoader)?.also { it.keySet() } }
        finally { p.recycle() }
    } catch (e: Throwable) { Log.w(TAG, "metadata could not be read back: ${e.javaClass.simpleName}"); null }

    /**
     * Every key the metadata has, with what its value is: the evidence, and what the rules read.
     * For each key: "class" (the Java class of the value, "null" for a key with nothing in it,
     * "unknown" when the values could not be read back), "type" in words, and the value itself
     * for words and numbers (a picture gives its size only).
     */
    private fun read(md: MediaMetadata?): Meta {
        val json = JSONObject()
        if (md == null) return Meta(json, null, null, 0, null, null, null, null, null)
        var bitmap: Bitmap? = null; var bitmapKey: String? = null
        val text = HashMap<String, String>()
        val raw = raw(md)
        for (k in runCatching { md.keySet().sorted() }.getOrDefault(emptyList())) {
            try {
                val o = JSONObject()
                if (raw != null) {
                    val v = raw.get(k)
                    o.put("class", v?.javaClass?.name ?: "null")
                    when (v) {
                        null -> o.put("type", "null")
                        is CharSequence -> { o.put("type", "text").put("value", v.toString().take(400)); v.toString().trim().takeIf { it.isNotEmpty() }?.let { text[k] = it } }
                        is Number -> o.put("type", "number").put("value", v.toLong())
                        is Bitmap -> { o.put("type", "bitmap").put("width", v.width).put("height", v.height); runCatching { v.recycle() } }   // (this one is the copy)
                        is Rating -> o.put("type", "rating")
                        else -> o.put("type", "other")
                    }
                    // the picture for the card is the session's own, not the copy that was read back
                    if (v is Bitmap && k in PICTURES) md.getBitmap(k)?.let { b ->
                        if (bitmapKey == null || PICTURES.indexOf(k) < PICTURES.indexOf(bitmapKey)) { bitmap = b; bitmapKey = k }
                    }
                } else {
                    o.put("class", "unknown")
                    when (k) {
                        in PICTURES -> {
                            val b = md.getBitmap(k)
                            if (b == null) o.put("type", "other")
                            else {
                                o.put("type", "bitmap").put("width", b.width).put("height", b.height)
                                if (bitmapKey == null || PICTURES.indexOf(k) < PICTURES.indexOf(bitmapKey)) { bitmap = b; bitmapKey = k }
                            }
                        }
                        in NUMBERS -> o.put("type", "number").put("value", md.getLong(k))
                        in RATINGS -> o.put("type", "rating")
                        else -> {
                            // a key of the app's own, or a text one: each kind is tried
                            val t = md.getText(k)?.toString()
                            val b = if (t == null) md.getBitmap(k) else null
                            when {
                                t != null -> { o.put("type", "text").put("value", t.take(400)); t.trim().takeIf { it.isNotEmpty() }?.let { text[k] = it } }
                                b != null -> o.put("type", "bitmap").put("width", b.width).put("height", b.height)
                                md.getRating(k) != null -> o.put("type", "rating")
                                else -> o.put("type", "number").put("value", md.getLong(k))
                            }
                        }
                    }
                }
                json.put(k, o)
            } catch (e: Exception) { json.put(k, JSONObject().put("type", "unreadable").put("class", e.javaClass.simpleName)) }
        }
        // the description Android makes of the metadata (what a car screen or a lock screen would show): it may name what the keys do not
        val d = runCatching { md.description }.getOrNull()
        val desc = JSONObject()
        d?.title?.let { desc.put("title", it.toString().take(200)) }
        d?.subtitle?.let { desc.put("subtitle", it.toString().take(200)) }
        d?.description?.let { desc.put("description", it.toString().take(200)) }
        d?.mediaId?.let { desc.put("media_id", it) }
        d?.mediaUri?.let { desc.put("media_uri", it.toString()) }
        d?.iconUri?.let { desc.put("icon_uri", it.toString()) }
        if (desc.length() > 0) json.put("description", desc)
        val title = text[MediaMetadata.METADATA_KEY_DISPLAY_TITLE] ?: text[MediaMetadata.METADATA_KEY_TITLE]
        val second = listOf(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM)
            .mapNotNull { text[it] }.firstOrNull { !it.equals(title, ignoreCase = true) }
        val artUri = text[MediaMetadata.METADATA_KEY_ART_URI] ?: text[MediaMetadata.METADATA_KEY_ALBUM_ART_URI]
            ?: text[MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI] ?: d?.iconUri?.toString()
        val duration = runCatching { if (md.containsKey(MediaMetadata.METADATA_KEY_DURATION)) md.getLong(MediaMetadata.METADATA_KEY_DURATION) else 0L }.getOrDefault(0L)
        return Meta(json, title, second, duration,
            artUri, text[MediaMetadata.METADATA_KEY_MEDIA_ID] ?: d?.mediaId, text[MediaMetadata.METADATA_KEY_MEDIA_URI] ?: d?.mediaUri?.toString(),
            bitmap, bitmap?.let { mark(it) })
    }

    /** The names in a Bundle and what kind of thing each holds: never the values. */
    private fun kinds(b: Bundle?): JSONObject? {
        if (b == null) return null
        val o = JSONObject()
        try { for (k in b.keySet().sorted().take(40)) o.put(k, runCatching { b.get(k)?.javaClass?.name ?: "null" }.getOrDefault("unreadable")) }
        catch (e: Throwable) { o.put("unreadable", e.javaClass.simpleName) }
        return o.takeIf { it.length() > 0 }
    }

    /**
     * What else a session offers that might say what is playing, besides its metadata: the name
     * of its queue and the first things in it, the names (and kinds, not values) of its extras,
     * and whether it has a screen of its own to go to. For the evidence only: no rule reads it
     * until an app has been seen to put something there.
     */
    private fun about(c: MediaController): JSONObject {
        val o = JSONObject()
        runCatching { o.put("queue_title", c.queueTitle?.toString()?.take(200) ?: JSONObject.NULL) }
        runCatching {
            val q = c.queue
            o.put("queue_size", q?.size ?: JSONObject.NULL)
            if (!q.isNullOrEmpty()) o.put("queue", JSONArray().also { a ->
                q.take(3).forEach { it.description?.let { d -> a.put(JSONObject().put("title", d.title?.toString()?.take(200) ?: JSONObject.NULL)
                    .put("subtitle", d.subtitle?.toString()?.take(200) ?: JSONObject.NULL).put("media_id", d.mediaId ?: JSONObject.NULL)) } }
            })
        }
        runCatching { kinds(c.extras)?.let { o.put("extras", it) } }
        runCatching { o.put("session_activity", c.sessionActivity != null) }
        return o
    }

    /** A cheap mark of a picture (its size and sixteen of its dots), to tell a new one from the one already kept. */
    private fun mark(b: Bitmap): String {
        var h = 17
        runCatching { for (i in 1..4) for (j in 1..4) h = h * 31 + b.getPixel(b.width * i / 5, b.height * j / 5) }
        return "${b.width}x${b.height}:${Integer.toHexString(h)}"
    }

    private fun label(pkg: String): String = labels.getOrPut(pkg) {
        runCatching { App.instance.packageManager.let { pm -> pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: Apps.BY_PACKAGE[pkg]?.name ?: pkg
    }

    /** Takes what a session says now (main thread) and hands it to the rules (work thread). */
    private fun look(h: Held) {
        val c = h.controller
        val meta = h.meta ?: return
        val st = h.state
        val at = SystemClock.elapsedRealtime(); val wall = System.currentTimeMillis()
        h.lookedAt = at
        val pkg = c.packageName ?: return
        val seen = WatchRules.Seen(pkg, st?.state ?: PlaybackState.STATE_NONE, st?.position ?: -1L, st?.playbackSpeed ?: 0f,
            st?.lastPositionUpdateTime ?: 0L, at, wall, meta.title, meta.second, meta.durationMs, meta.artUri, meta.mediaId, meta.mediaUri,
            meta.picture, label(pkg))
        val about = h.about
        val line = JSONObject().put("time", Now.iso(wall)).put("package", pkg).put("app", label(pkg))
            .put("state", WatchRules.stateName(seen.state)).put("state_code", seen.state)
            .put("position_ms", WatchRules.position(seen) ?: JSONObject.NULL).put("speed", seen.speed.toDouble())
            .put("actions", st?.actions ?: 0L).put("metadata", JSONObject(meta.json.toString()))
            .put("picture", meta.picture ?: JSONObject.NULL)
        if (about != null) line.put("session", JSONObject(about.toString()))
        runCatching { kinds(st?.extras) }.getOrNull()?.let { line.put("state_extras", it) }
        // the same look again says nothing new: only a change of state, metadata, session or decision adds a line
        val mark = "${seen.state}|${meta.json}|$about"
        work.execute { runCatching { handle(seen, line, mark, meta) }.onFailure { Log.w(TAG, "look: ${it.message}") } }
    }

    private fun handle(seen: WatchRules.Seen, line: JSONObject, mark: String, meta: Meta) {
        val c = cfg
        var want: String? = null
        var playing = false
        synchronized(lock) {
            val v = log.see(seen, c.exclude)
            line.put("decision", v.decision).put("detail", v.detail)
            v.told?.let { line.put("read", JSONObject().put("title", it.title).put("episode_title", it.episodeTitle ?: JSONObject.NULL)
                .put("season", it.season ?: JSONObject.NULL).put("episode", it.episode ?: JSONObject.NULL)) }
            evidence.add(line, "$mark|${v.decision}", v.decision == "excluded")
            var write = v.write
            val b = meta.bitmap
            if (v.picture && b != null && v.entry != null && keep(v.entry, b, seen.picture!!, seen.wall)) {
                meta.bitmap = null            // the copy is kept: the big one need not stay in memory
                log.touched(); write = log.takeWrite(seen.at) || write
            }
            if (write) write(seen.wall)
            else if (seen.at - seenWrittenAt >= WatchRules.WRITE_EVERY_MS) writeSeen(seen.at)
            if (v.entry != null && wantsAsking(v.entry, c.lookup, seen.wall)) want = v.entry.id
            playing = log.playing()
        }
        want?.let { lookUp(it) }
        if (playing) tick()
    }

    /** While something that counts is playing: one more look in a minute, to count the minute and write the log. */
    private fun tick() {
        if (tickDue) return
        tickDue = true
        work.schedule({
            tickDue = false
            main.post { held.values.filter { it.state?.state == PlaybackState.STATE_PLAYING }.forEach { look(it) } }
        }, WatchRules.WRITE_EVERY_MS, TimeUnit.MILLISECONDS)
    }

    // ---------------------------------------------------------------- storage and pictures (called with the lock held)

    private fun write(wall: Long) {
        log.prune(wall).forEach { e -> e.art?.let { File(artDir(), it).delete() } }
        Store.writeJson(FILE, log.toJson())
        writeSeen(SystemClock.elapsedRealtime())
    }

    private fun writeSeen(at: Long) {
        seenWrittenAt = at
        Store.writeJson(SEEN_FILE, JSONObject().put("looks", evidence.json()))
    }

    /** Keeps a JPEG copy of the app's own picture for a title, 640 px wide at most. */
    private fun keep(e: WatchRules.Entry, b: Bitmap, mark: String, wall: Long): Boolean = try {
        val w = b.width; val h = b.height
        if (w <= 0 || h <= 0 || b.isRecycled) false else {
            val small = if (w > WatchRules.ART_WIDTH) Bitmap.createScaledBitmap(b, WatchRules.ART_WIDTH, maxOf(1, h * WatchRules.ART_WIDTH / w), true) else b
            val name = WatchRules.artFile(e.id)
            val tmp = File(artDir(), "$name.tmp")
            val ok = tmp.outputStream().use { small.compress(Bitmap.CompressFormat.JPEG, 85, it) }
            if (small !== b) small.recycle()
            if (ok && tmp.renameTo(File(artDir(), name))) { e.art = name; e.artMark = mark; e.artAt = wall; true } else { tmp.delete(); false }
        }
    } catch (ex: Exception) { Log.w(TAG, "picture: ${ex.message}"); false }

    // ---------------------------------------------------------------- asking outside about a title (only with "lookup" on)

    /** Is there anything to ask JustWatch about this entry now: a show to try for it, or the title itself? (Lock held.) */
    private fun wantsAsking(e: WatchRules.Entry, lookup: Boolean, wall: Long): Boolean =
        lookup && (WatchRules.wantsLookup(e, true, wall) || log.candidates(e, wall).isNotEmpty())

    /** Every title in the log with something to ask about: asked now, each once. */
    private fun lookUpAll() {
        if (!cfg.lookup) return
        val wall = System.currentTimeMillis()
        synchronized(lock) { log.all().filter { wantsAsking(it, true, wall) }.map { it.id } }.forEach { lookUp(it) }
    }

    /**
     * Asks JustWatch about one title. This sends names out of the house, which is why it only
     * happens with "lookup" on.
     *  1. The title may be an episode's name (Peacock gives nothing else). The shows it may
     *     belong to are tried in turn (WatchRules.Log.candidates): the show is searched for, its
     *     episode list asked for (once a day for a show), and the list searched for an episode of
     *     that name and length. A fit makes the card the show's, with its pictures.
     *  2. Otherwise the title is looked up under its own name, for a poster and a backdrop, when
     *     its app gave no picture.
     * Whatever was tried and did not work is not tried again for a day.
     */
    private fun lookUp(id: String) {
        if (!cfg.lookup || !looking.add(id)) return
        lookups.execute {
            try {
                val e = synchronized(lock) { log.find(id) } ?: return@execute
                val service = Apps.BY_PACKAGE[e.pkg]?.id
                var shown = false
                for (c in synchronized(lock) { log.candidates(e, System.currentTimeMillis()) }) {
                    val fit = runCatching { tryShow(e, c, service) }
                    fit.exceptionOrNull()?.let { Log.w(TAG, "show: ${it.javaClass.simpleName}: ${it.message}") }
                    if (fit.getOrNull() == true) { shown = true; break }
                    synchronized(lock) {
                        log.tried(e, c.name, System.currentTimeMillis())
                        if (log.takeWrite(SystemClock.elapsedRealtime())) write(System.currentTimeMillis())
                    }
                }
                if (!shown && synchronized(lock) { WatchRules.wantsLookup(e, true, System.currentTimeMillis()) }) {
                    val found = runCatching { Finder.pictures(e.title, service) }
                    found.exceptionOrNull()?.let { Log.w(TAG, "lookup: ${it.javaClass.simpleName}") }
                    synchronized(lock) {
                        val p = found.getOrNull()
                        e.lookedAt = System.currentTimeMillis()
                        e.lookedOk = p != null && (p.first != null || p.second != null)
                        if (e.lookedOk) { e.poster = p!!.first; e.backdrop = p.second }
                        log.touched()
                        if (log.takeWrite(SystemClock.elapsedRealtime())) write(System.currentTimeMillis())
                    }
                }
                // a show just worked out may be the show of another title of the same app (its next episode)
                if (shown) work.execute { lookUpAll() }
            } finally { looking.remove(id) }
        }
    }

    /** Is the title an episode of this show? True when it is, and the entry has been made the show's. */
    private fun tryShow(e: WatchRules.Entry, c: WatchRules.Cand, service: String?): Boolean {
        var showId = c.showId; var showTitle = c.name; var poster = c.poster; var backdrop = c.backdrop
        if (showId == null) {
            val t = Finder.show(c.name, service) ?: return false
            showId = t.jwId; showTitle = t.title; poster = t.poster; backdrop = t.backdrop
        }
        val list = Finder.episodes(showId)
        val (name, length) = synchronized(lock) { e.title to e.durationMs }
        val m = ShowRules.match(list, name, length, c.after, WatchRules.NEXT_SPAN) ?: return false
        synchronized(lock) {
            if (log.find(e.id) !== e) return false          // forgotten meanwhile
            val wall = System.currentTimeMillis()
            log.resolve(e, showTitle, showId, poster, backdrop, m.episode?.season, m.episode?.episode, m.episode?.id, wall)
                .forEach { x -> x.art?.let { File(artDir(), it).delete() } }
            log.takeWrite(SystemClock.elapsedRealtime()); write(wall)
        }
        Log.i(TAG, "an episode's name was matched to its show" + (if (m.episode == null) " (${m.named} episodes of that name: which one is not known)" else ""))
        return true
    }
}
