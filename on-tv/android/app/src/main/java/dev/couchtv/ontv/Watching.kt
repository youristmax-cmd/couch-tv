package dev.couchtv.ontv

import android.content.ComponentName
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
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
 * with "lookup" true, a title whose app gave no picture is looked up on JustWatch (Finder), which
 * tells JustWatch that title's name.
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

    /** What a session's metadata said, read once each time it changes (reading it again would fetch its picture again). */
    private class Meta(val json: JSONObject, val title: String?, val second: String?, val durationMs: Long, val artUri: String?,
                       val mediaId: String?, val mediaUri: String?, @Volatile var bitmap: Bitmap?, val picture: String?)

    /** One app's media session being listened to. Touched on the main thread only. */
    private class Held(val controller: MediaController) {
        var callback: MediaController.Callback? = null
        var meta: Meta? = null
        var state: PlaybackState? = null
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
        // switched on: the titles already in the log get their pictures now, not after a restart
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
     * OK on a card. With "lookup" on, and an app Finder knows: what Finder would do for "play
     * <title>" in that app, when it really finds that title there. Otherwise, and whenever that
     * does not work out, the app is opened. No link is ever made up from a session's ids.
     */
    fun open(id: String, who: String?): JSONObject {
        val e = synchronized(lock) { log.find(id) } ?: throw ApiError(404, "not in keep watching")
        val pkg = e.pkg; val title = e.title
        val app = Apps.BY_PACKAGE[pkg]
        val name = e.appName ?: app?.name ?: label(pkg)
        if (cfg.lookup && app != null) {
            try { return Finder.play(title, e.season, e.episode, app.id, who, resume = true, onlyThere = true) }
            catch (ex: Exception) { Log.i(TAG, "open: ${ex.message}; opening the app instead") }
        }
        if (!Tv.hasPackage(pkg)) throw ApiError(404, "$name is not installed on the TV")
        Tv.launchPackage(pkg)
        if (Adb.connected && Tv.waitForApp(setOf(pkg), 8000) == null) throw ApiError(504, "asked the TV to open $name but it did not come up")
        Now.push()
        return JSONObject().put("title", title).put("app", JSONObject().put("id", app?.id ?: JSONObject.NULL).put("name", name).put("package", pkg))
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

    /** Empties the watch log: the titles, their pictures and the raw looks. */
    fun forgetAll() {
        synchronized(lock) {
            log.clear()
            artDir().listFiles()?.forEach { it.delete() }
            evidence.clear()
            log.takeWrite(SystemClock.elapsedRealtime()); write(System.currentTimeMillis())
        }
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
                override fun onMetadataChanged(metadata: MediaMetadata?) { h.meta = read(metadata); look(h) }
                override fun onSessionDestroyed() { drop(tok) }
            }
            h.callback = cb
            held[tok] = h
            runCatching { c.registerCallback(cb, main) }.onFailure { Log.w(TAG, "${c.packageName}: ${it.message}") }
            h.meta = read(runCatching { c.metadata }.getOrNull())
            h.state = runCatching { c.playbackState }.getOrNull()
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

    /** Every key the metadata has, with its kind and (for words and numbers) its value: the evidence, and what the rules read. */
    private fun read(md: MediaMetadata?): Meta {
        val json = JSONObject()
        if (md == null) return Meta(json, null, null, 0, null, null, null, null, null)
        var bitmap: Bitmap? = null; var bitmapKey: String? = null
        val text = HashMap<String, String>()
        for (k in runCatching { md.keySet().sorted() }.getOrDefault(emptyList())) {
            try {
                when (k) {
                    in PICTURES -> {
                        val b = md.getBitmap(k)
                        if (b == null) json.put(k, JSONObject().put("type", "other"))
                        else {
                            json.put(k, JSONObject().put("type", "bitmap").put("width", b.width).put("height", b.height))
                            if (bitmapKey == null || PICTURES.indexOf(k) < PICTURES.indexOf(bitmapKey)) { bitmap = b; bitmapKey = k }
                        }
                    }
                    in NUMBERS -> json.put(k, JSONObject().put("type", "number").put("value", md.getLong(k)))
                    in RATINGS -> json.put(k, JSONObject().put("type", "rating"))
                    else -> {
                        // a key of the app's own, or a text one: Android does not say which kind a key holds, so each is tried
                        val t = md.getText(k)?.toString()
                        val b = if (t == null) md.getBitmap(k) else null
                        when {
                            t != null -> { json.put(k, JSONObject().put("type", "text").put("value", t.take(400))); t.trim().takeIf { it.isNotEmpty() }?.let { text[k] = it } }
                            b != null -> json.put(k, JSONObject().put("type", "bitmap").put("width", b.width).put("height", b.height))
                            md.getRating(k) != null -> json.put(k, JSONObject().put("type", "rating"))
                            else -> json.put(k, JSONObject().put("type", "number").put("value", md.getLong(k)))
                        }
                    }
                }
            } catch (e: Exception) { json.put(k, JSONObject().put("type", "unreadable")) }
        }
        val d = runCatching { md.description }.getOrNull()
        val desc = JSONObject()
        d?.mediaId?.let { desc.put("media_id", it) }
        d?.mediaUri?.let { desc.put("media_uri", it.toString()) }
        d?.iconUri?.let { desc.put("icon_uri", it.toString()) }
        if (desc.length() > 0) json.put("description", desc)
        val title = text[MediaMetadata.METADATA_KEY_DISPLAY_TITLE] ?: text[MediaMetadata.METADATA_KEY_TITLE]
        val second = listOf(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE, MediaMetadata.METADATA_KEY_ARTIST, MediaMetadata.METADATA_KEY_ALBUM)
            .mapNotNull { text[it] }.firstOrNull { !it.equals(title, ignoreCase = true) }
        val artUri = text[MediaMetadata.METADATA_KEY_ART_URI] ?: text[MediaMetadata.METADATA_KEY_ALBUM_ART_URI]
            ?: text[MediaMetadata.METADATA_KEY_DISPLAY_ICON_URI] ?: d?.iconUri?.toString()
        return Meta(json, title, second, if (md.containsKey(MediaMetadata.METADATA_KEY_DURATION)) md.getLong(MediaMetadata.METADATA_KEY_DURATION) else 0,
            artUri, text[MediaMetadata.METADATA_KEY_MEDIA_ID] ?: d?.mediaId, text[MediaMetadata.METADATA_KEY_MEDIA_URI] ?: d?.mediaUri?.toString(),
            bitmap, bitmap?.let { mark(it) })
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
        val line = JSONObject().put("time", Now.iso(wall)).put("package", pkg).put("app", label(pkg))
            .put("state", WatchRules.stateName(seen.state)).put("state_code", seen.state)
            .put("position_ms", WatchRules.position(seen) ?: JSONObject.NULL).put("speed", seen.speed.toDouble())
            .put("actions", st?.actions ?: 0L).put("metadata", JSONObject(meta.json.toString()))
            .put("picture", meta.picture ?: JSONObject.NULL)
        runCatching { st?.extras?.keySet()?.sorted() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { line.put("state_extras", JSONArray(it)) }
        runCatching { c.extras?.keySet()?.sorted() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { line.put("session_extras", JSONArray(it)) }
        // the same look again says nothing new: only a change of state, metadata or decision adds a line
        val mark = "${seen.state}|${meta.json}"
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
            if (v.entry != null && WatchRules.wantsLookup(v.entry, c.lookup, seen.wall)) want = v.entry.id
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

    // ---------------------------------------------------------------- looking a title up outside (only with "lookup" on)

    /** Every title in the log that wants a picture and has none of its app's own: asked for now, each once. */
    private fun lookUpAll() {
        if (!cfg.lookup) return
        val wall = System.currentTimeMillis()
        synchronized(lock) { log.all().filter { WatchRules.wantsLookup(it, true, wall) }.map { it.id } }.forEach { lookUp(it) }
    }

    /**
     * Asks JustWatch for one title's poster and backdrop, preferring the title as offered in the
     * app it was watched in. This sends that title's name out of the house, which is why it only
     * happens with "lookup" on. Found: kept with the entry and never asked again. Not found, or
     * no answer: not tried again for a day.
     */
    private fun lookUp(id: String) {
        if (!cfg.lookup || !looking.add(id)) return
        lookups.execute {
            try {
                val e = synchronized(lock) { log.find(id) } ?: return@execute
                val found = runCatching { Finder.pictures(e.title, Apps.BY_PACKAGE[e.pkg]?.id) }
                found.exceptionOrNull()?.let { Log.w(TAG, "lookup: ${it.javaClass.simpleName}") }
                synchronized(lock) {
                    val p = found.getOrNull()
                    e.lookedAt = System.currentTimeMillis()
                    e.lookedOk = p != null && (p.first != null || p.second != null)
                    if (e.lookedOk) { e.poster = p!!.first; e.backdrop = p.second }
                    log.touched()
                    if (log.takeWrite(SystemClock.elapsedRealtime())) write(System.currentTimeMillis())
                }
            } finally { looking.remove(id) }
        }
    }
}
