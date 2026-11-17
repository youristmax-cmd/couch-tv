package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject

/**
 * The thinking half of Keep watching (Watching.kt does the listening): what other apps' media
 * sessions said in, the watch log and the row's cards out. Nothing here touches Android, the
 * network or a clock, so every rule can be checked on a computer (app/src/test).
 *
 * The rules, in the order a look at a session goes through them:
 *  1. which apps count (watched): every app with a media session except this one and the ones in
 *     the "exclude" setting: music and voice players, YouTube, live TV.
 *  2. what the thing is called (describe): the title, and the season and episode when the text
 *     says them ("S1 E2", "Season 1 Episode 2", "1x02", "E3 · Name": episode).
 *  3. where it is (position): worked out from the playback state the app last reported.
 *  4. whether it was watched (Log.see): in the playing state for 2 minutes in all, and, when the
 *     app says how long it is, at least 5 minutes long. Until then it is only a candidate, kept
 *     in memory: the previews an app plays on its own home screen never get as far as the log.
 *  5. one entry per app and title: a new episode of the same series replaces the old one's.
 *  6. the row (Log.items): the newest first, without what is finished (95% or more) or older
 *     than 60 days; 40 entries are kept.
 * Nobody outside the TV is asked or told anything here.
 */
object WatchRules {
    const val MIN_PLAYED_MS = 2 * 60_000L             // in the playing state this long, in all, before a title counts as watched
    const val MIN_DURATION_MS = 5 * 60_000L           // a thing the app says is shorter than this is a trailer or a clip
    const val FINISHED = 0.95                         // this far in, or further: done, it leaves the row
    const val MAX_ENTRIES = 40                        // titles kept
    const val MAX_AGE_MS = 60L * 24 * 60 * 60_000     // a title not played for 60 days is dropped
    const val LIST_MAX = 20                           // cards given to the home at most
    const val WRITE_EVERY_MS = 60_000L                // while something plays, the log is written at most this often
    const val MAX_GAP_MS = 90_000L                    // a longer gap between two looks at a playing session was not watching (the TV slept)
    const val CANDIDATE_MS = 6 * 60 * 60_000L         // a title that has not reached its 2 minutes within this long starts again
    const val MAX_CANDIDATES = 30
    const val SEEN_MAX = 60                           // raw looks kept for GET /api/continue/log
    const val ART_WIDTH = 640                         // the app's own picture is kept no wider than this
    const val LOOKUP_RETRY_MS = 24 * 60 * 60_000L     // a lookup that failed is not tried again for a day

    const val STATE_PLAYING = 3                       // android.media.session.PlaybackState.STATE_PLAYING
    private val STATES = listOf("none", "stopped", "paused", "playing", "fast_forwarding", "rewinding", "buffering", "error",
        "connecting", "skipping_to_previous", "skipping_to_next", "skipping_to_queue_item")

    /** PlaybackState's number in words, for the evidence log. */
    fun stateName(state: Int): String = STATES.getOrNull(state) ?: "state_$state"

    /**
     * Left out unless the "exclude" setting says otherwise: music and voice (Spotify, Amazon Music,
     * Alexa's own players), YouTube in all its packages, and live TV (DirecTV, YouTube TV).
     */
    val DEFAULT_EXCLUDE = listOf(
        "com.spotify.tv.android", "com.amazon.bueller.music", "com.amazon.music.tv",
        "com.amazon.alexamediaplayer.runtime.ftv", "com.amazon.vizzini",
        "com.google.android.youtube.tv", "com.amazon.firetv.youtube", "com.google.android.youtube.tvkids",
        "com.google.android.youtube.tvmusic", "com.google.android.youtube.tvunplugged",
        "com.att.tv",
    )

    /** Does this app's session count? Every app's does, except this app's own and the excluded ones. */
    fun watched(pkg: String, exclude: Collection<String>, self: String): Boolean = pkg.isNotEmpty() && pkg != self && pkg !in exclude

    // ---------------------------------------------------------------- season and episode

    /** A season and/or an episode found in a text, and where (start, end) so the rest can be read around it. */
    class Ep(val season: Int?, val episode: Int?, val start: Int, val end: Int)

    private val SEP = """[\s:.,|·•–—-]*"""
    private val EP_PATTERNS = listOf(
        // S1 E2, S1:E2, S01E02, Season 1 Episode 2, Season 1, Ep. 2
        Regex("""\bS(?:eason)?\s*(\d{1,2})$SEP(?:E|Ep|Episode)\.?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE) to "se",
        // 1x02 (two figures for the episode, so "4x4" is left alone)
        Regex("""\b(\d{1,2})x(\d{2,3})\b""", RegexOption.IGNORE_CASE) to "se",
        // Episode 3, Ep. 3
        Regex("""\bEp(?:isode)?\.?\s*(\d{1,3})\b""", RegexOption.IGNORE_CASE) to "e",
        // E3 · Name: only at the start, and only before a separator (so "E3 2026" the trade fair is not episode 3)
        Regex("""^\s*E(\d{1,3})\s*(?=[:.|·•–—-]|$)""", RegexOption.IGNORE_CASE) to "e",
        // Season 2, and nothing about the episode
        Regex("""\bSeason\s*(\d{1,2})\b""", RegexOption.IGNORE_CASE) to "s",
    )

    /** The season and episode a text names, or null when it names neither. What is not said stays null. */
    fun episode(text: String?): Ep? {
        if (text.isNullOrBlank()) return null
        for ((re, kind) in EP_PATTERNS) {
            val m = re.find(text) ?: continue
            val a = m.groupValues[1].toIntOrNull() ?: continue
            return when (kind) {
                "se" -> Ep(a, m.groupValues[2].toIntOrNull() ?: continue, m.range.first, m.range.last + 1)
                "e" -> Ep(null, a, m.range.first, m.range.last + 1)
                else -> Ep(a, null, m.range.first, m.range.last + 1)
            }
        }
        return null
    }

    /** What a session's two lines of text come to: the title to file it under, and the episode. */
    class Told(val title: String, val episodeTitle: String?, val season: Int?, val episode: Int?)

    private fun clean(s: String?): String? = s?.replace(Regex("""\s+"""), " ")?.trim()?.takeIf { it.isNotEmpty() }
    private fun strip(s: String): String = s.trim { it.isWhitespace() || it in "-–—:|·•,\"“”'" }

    /**
     * The title and the second line (subtitle, or whatever the app puts there) as a title and an
     * episode. Nobody knows yet what each app writes where, so this only reads what is plain:
     *  - the second line names the episode ("S1:E2 The Weirdo on Maple Street"): the title is the
     *    title, and what is left of the second line is the episode's name;
     *  - the title itself does, after the show's name ("Bluey - S2 E5 - Sleepytime"): the name
     *    before it is the title, what follows is the episode's name;
     *  - the title is only the episode ("S1 E2 · Name") and there is a second line: the second
     *    line is taken for the show. Without one, the title stays as the app wrote it;
     *  - no episode anywhere: the title, and the second line as it is.
     * Null when there is no title at all.
     */
    fun describe(title: String?, second: String?): Told? {
        val t = clean(title) ?: return null
        val s = clean(second)?.takeIf { !it.equals(t, ignoreCase = true) }
        episode(s)?.let { e ->
            val rest = strip(s!!.substring(0, e.start) + " " + s.substring(e.end)).replace(Regex("""\s+"""), " ")
            return Told(t, rest.ifEmpty { null }, e.season, e.episode)
        }
        episode(t)?.let { e ->
            val before = strip(t.substring(0, e.start)); val after = strip(t.substring(e.end))
            return when {
                before.isNotEmpty() -> Told(before, after.ifEmpty { s }, e.season, e.episode)
                s != null -> Told(s, after.ifEmpty { null }, e.season, e.episode)
                else -> Told(t, after.ifEmpty { null }, e.season, e.episode)
            }
        }
        return Told(t, s, null, null)
    }

    // ---------------------------------------------------------------- one look at one session

    /** What one app's media session said at one moment, with nothing of Android in it. */
    class Seen(
        val pkg: String,
        val state: Int,                 // PlaybackState's state
        val positionMs: Long,           // as the app last reported it; below 0 when it does not say
        val speed: Float,
        val updatedAt: Long,            // when it reported that (elapsed time, as PlaybackState gives it); 0 when it does not say
        val at: Long,                   // when this look was taken (elapsed time)
        val wall: Long,                 // and by the clock on the wall
        val title: String?, val second: String?,
        val durationMs: Long = 0,       // 0 or less when the app does not say
        val artUri: String? = null, val mediaId: String? = null, val mediaUri: String? = null,
        val picture: String? = null,    // a mark of the picture the session carries ("640x360:1a2b3c"); null when it carries none
        val appName: String? = null,    // the app's name as the TV shows it
    )

    /**
     * Where the thing is now: the position the app last reported, moved on by the time since at
     * the speed it gave, while it says it is playing. Never past the end. Null when the app
     * reports no position.
     */
    fun position(s: Seen, at: Long = s.at): Long? {
        if (s.positionMs < 0) return null
        var p = s.positionMs
        if (s.state == STATE_PLAYING && s.updatedAt > 0 && at > s.updatedAt) p += ((at - s.updatedAt) * (if (s.speed == 0f) 1f else s.speed)).toLong()
        if (s.durationMs > 0) p = minOf(p, s.durationMs)
        return maxOf(p, 0L)
    }

    /** How far in, 0 to 1, or null when the position or the length is not known. */
    fun progress(positionMs: Long?, durationMs: Long): Double? =
        if (positionMs == null || durationMs <= 0) null else (positionMs.toDouble() / durationMs).coerceIn(0.0, 1.0)

    /** Only an https address is handed to the home as a picture: never a file, a content: address or plain http. */
    fun httpsOnly(uri: String?): String? = uri?.trim()?.takeIf { Regex("""https://[^\s"'<>]{4,600}""").matches(it) }

    /** One entry per app and title, whatever the capitals and the punctuation (Finder.norm). */
    fun key(pkg: String, title: String): String = pkg + "|" + Finder.norm(title)

    /** The card's id: "w-" and twelve hex figures made from the key, the same every time. */
    fun id(key: String): String {
        val d = java.security.MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return "w-" + d.take(6).joinToString("") { "%02x".format(it) }
    }

    /** The file an entry's picture is kept in, and the only kind of name GET /api/continue/art/<file> serves. */
    fun artFile(id: String): String = id.removePrefix("w-") + ".jpg"
    val ART_FILE = Regex("""[0-9a-f]{12}\.jpg""")

    // ---------------------------------------------------------------- the log

    class Entry(val key: String, val pkg: String) {
        var title = ""; var appName: String? = null
        var episodeTitle: String? = null; var season: Int? = null; var episode: Int? = null
        var durationMs = 0L; var positionMs: Long? = null
        var playedMs = 0L                 // in the playing state, in all
        var lastPlayed = 0L               // by the clock on the wall
        var counted = false
        var art: String? = null           // the app's own picture, kept as a file of this name
        var artMark: String? = null; var artAt = 0L
        var artUri: String? = null        // or the address the session gave for one (https)
        var mediaId: String? = null; var mediaUri: String? = null
        // what a lookup outside found (only ever asked for when the setting allows it)
        var poster: String? = null; var backdrop: String? = null; var lookedAt = 0L; var lookedOk = false
        val id get() = id(key)
        val tooShort get() = durationMs in 1 until MIN_DURATION_MS
        val progress get() = progress(positionMs, durationMs)
        val finished get() = (progress ?: 0.0) >= FINISHED
        /** Has the app given a picture of its own (a file kept, or an address)? */
        val hasOwnPicture get() = art != null || artUri != null

        fun toJson(): JSONObject = JSONObject().put("key", key).put("package", pkg).put("title", title).put("app_name", appName ?: JSONObject.NULL)
            .put("episode_title", episodeTitle ?: JSONObject.NULL).put("season", season ?: JSONObject.NULL).put("episode", episode ?: JSONObject.NULL)
            .put("duration_ms", durationMs).put("position_ms", positionMs ?: JSONObject.NULL).put("played_ms", playedMs).put("last_played", lastPlayed)
            .put("art", art ?: JSONObject.NULL).put("art_mark", artMark ?: JSONObject.NULL).put("art_at", artAt).put("art_uri", artUri ?: JSONObject.NULL)
            .put("media_id", mediaId ?: JSONObject.NULL).put("media_uri", mediaUri ?: JSONObject.NULL)
            .put("poster", poster ?: JSONObject.NULL).put("backdrop", backdrop ?: JSONObject.NULL).put("looked_at", lookedAt).put("looked_ok", lookedOk)
    }

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
    private fun int(o: JSONObject, k: String): Int? = if (o.isNull(k) || !o.has(k)) null else o.optInt(k)

    fun entry(o: JSONObject): Entry? {
        val key = str(o, "key") ?: return null; val pkg = str(o, "package") ?: return null
        return Entry(key, pkg).apply {
            title = o.optString("title"); appName = str(o, "app_name")
            episodeTitle = str(o, "episode_title"); season = int(o, "season"); episode = int(o, "episode")
            durationMs = o.optLong("duration_ms"); positionMs = if (o.isNull("position_ms") || !o.has("position_ms")) null else o.optLong("position_ms")
            playedMs = o.optLong("played_ms"); lastPlayed = o.optLong("last_played"); counted = true
            art = str(o, "art"); artMark = str(o, "art_mark"); artAt = o.optLong("art_at"); artUri = str(o, "art_uri")
            mediaId = str(o, "media_id"); mediaUri = str(o, "media_uri")
            poster = str(o, "poster"); backdrop = str(o, "backdrop"); lookedAt = o.optLong("looked_at"); lookedOk = o.optBoolean("looked_ok")
        }.takeIf { it.title.isNotEmpty() }
    }

    /**
     * What the rules made of one look. decision: "excluded", "no title", "too short", "not yet"
     * (under its 2 minutes), "counted" or "finished". write: now is a moment to write the log
     * (it paused or stopped, the title changed, or a minute of playing has gone by). picture: the
     * session carries a picture this entry does not have a copy of yet.
     */
    class Verdict(val decision: String, val detail: String, val entry: Entry?, val told: Told?, val write: Boolean, val picture: Boolean)

    /** The watch log itself: the titles that count, the ones not there yet, and what each app is playing now. */
    class Log(private val self: String) {
        private val entries = LinkedHashMap<String, Entry>()      // counted: these are written to the TV's storage
        private val candidates = LinkedHashMap<String, Entry>()   // not yet 2 minutes: memory only
        private class Current(val key: String?, val since: Long?, val last: Seen)   // since: playing from then (elapsed time)
        private val current = HashMap<String, Current>()
        private var dirty = false
        private var writtenAt = 0L

        val size get() = entries.size
        fun all(): List<Entry> = entries.values.toList()
        fun find(id: String): Entry? = entries.values.firstOrNull { it.id == id }
        /** Is any app that counts playing right now? Then the listener looks again in a minute. */
        fun playing(): Boolean = current.values.any { it.since != null }

        private fun known(key: String): Entry? = entries[key] ?: candidates[key]

        /** The stretch of playing that ends now is added to its title, and its position moved to now. */
        private fun close(pkg: String, at: Long) {
            val cur = current[pkg] ?: return
            val e = cur.key?.let { known(it) } ?: return
            if (cur.since != null) {
                // no more than a minute and a half at a time: a longer gap was the TV asleep, not watching
                val stretch = (at - cur.since).coerceIn(0L, MAX_GAP_MS)
                e.playedMs += stretch
                position(cur.last, cur.last.at + stretch)?.let { e.positionMs = it }
                if (e.counted) dirty = true
            }
            settle(e)
        }

        /** A candidate that has been played for its 2 minutes, and is not a short clip, joins the log. */
        private fun settle(e: Entry) {
            if (e.counted || e.tooShort || e.playedMs < MIN_PLAYED_MS) return
            candidates.remove(e.key); entries[e.key] = e
            e.counted = true; dirty = true
        }

        fun see(s: Seen, exclude: Collection<String>): Verdict {
            if (!watched(s.pkg, exclude, self)) { current.remove(s.pkg); return Verdict("excluded", "this app is left out", null, null, false, false) }
            val was = current[s.pkg]
            close(s.pkg, s.at)
            val playing = s.state == STATE_PLAYING
            val told = describe(s.title, s.second)
            if (told == null) {
                current[s.pkg] = Current(null, null, s)
                return Verdict("no title", "the session names nothing", null, null, was?.key != null && take(s.at), false)
            }
            val key = key(s.pkg, told.title)
            val e = known(key) ?: Entry(key, s.pkg).also { candidates[key] = it; tidy(s.wall) }
            // a candidate last played more than 6 hours ago starts its 2 minutes again
            if (!e.counted && e.lastPlayed > 0 && s.wall - e.lastPlayed > CANDIDATE_MS) e.playedMs = 0
            val another = told.season != e.season || told.episode != e.episode || (told.episodeTitle != null && told.episodeTitle != e.episodeTitle)
            val names = told.season != null || told.episode != null || told.episodeTitle != null
            e.title = told.title
            s.appName?.let { e.appName = it }
            if (names && another) {
                // another episode of the same title: it takes the place of the last one, with nothing carried over
                e.season = told.season; e.episode = told.episode; e.episodeTitle = told.episodeTitle
                e.durationMs = 0; e.positionMs = null; e.mediaId = null; e.mediaUri = null
            }
            if (s.durationMs > 0) e.durationMs = s.durationMs
            position(s)?.let { e.positionMs = it }
            httpsOnly(s.artUri)?.let { e.artUri = it }
            s.mediaId?.takeIf { it.isNotEmpty() }?.let { e.mediaId = it }
            s.mediaUri?.takeIf { it.isNotEmpty() }?.let { e.mediaUri = it }
            if (playing) e.lastPlayed = s.wall
            settle(e)
            if (e.counted) dirty = true
            current[s.pkg] = Current(key, if (playing) s.at else null, s)
            val decision = when {
                e.tooShort -> "too short"
                !e.counted -> "not yet"
                e.finished -> "finished"
                else -> "counted"
            }
            val detail = when (decision) {
                "too short" -> "the app says it is ${e.durationMs / 1000} s long; under ${MIN_DURATION_MS / 60_000} minutes is not kept"
                "not yet" -> "played ${e.playedMs / 1000} s of the ${MIN_PLAYED_MS / 1000} s it takes"
                "finished" -> "at ${Math.round((e.progress ?: 0.0) * 100)}%: done, not in the row"
                else -> e.progress?.let { "at ${Math.round(it * 100)}%" } ?: "the app gives no position or no length"
            }
            // a moment to write: it has just paused or stopped, the title has changed, or a minute of playing has gone by
            val stopped = was?.since != null && !playing
            val changed = was?.key != null && was.key != key
            val write = (stopped || changed || s.at - writtenAt >= WRITE_EVERY_MS) && take(s.at)
            return Verdict(decision, detail, e, told, write, e.counted && s.picture != null && s.picture != e.artMark)
        }

        /** The app's session has gone: what it was playing is closed, and it is a moment to write. */
        fun gone(pkg: String, at: Long): Boolean {
            if (current[pkg] == null) return false
            close(pkg, at)
            current.remove(pkg)
            return take(at)
        }

        /** True once when there is something new to write; the caller then writes. */
        private fun take(at: Long): Boolean {
            if (!dirty) return false
            dirty = false; writtenAt = at
            return true
        }

        /** Something outside a look changed an entry (a picture kept, a lookup's answer): it wants writing. */
        fun touched() { dirty = true }
        fun takeWrite(at: Long): Boolean = take(at)

        /** Candidates that never got to their 2 minutes are let go after 6 hours, and never more than 30 are kept. */
        private fun tidy(wall: Long) {
            candidates.values.removeAll { it.lastPlayed > 0 && wall - it.lastPlayed > CANDIDATE_MS }
            while (candidates.size > MAX_CANDIDATES) candidates.remove(candidates.keys.first())
        }

        /** Drops what has not been played for 60 days and all but the 40 newest. Gives back what went, for their pictures. */
        fun prune(wall: Long): List<Entry> {
            val out = ArrayList<Entry>()
            entries.values.filter { wall - it.lastPlayed > MAX_AGE_MS }.forEach { out += it }
            entries.values.filter { it !in out }.sortedByDescending { it.lastPlayed }.drop(MAX_ENTRIES).forEach { out += it }
            out.forEach { entries.remove(it.key) }
            return out
        }

        /** Takes one card out (and the candidate of the same title, so it does not walk straight back in). */
        fun forget(id: String): Entry? {
            val e = find(id) ?: return null
            entries.remove(e.key); candidates.remove(e.key)
            current.entries.removeAll { it.value.key == e.key }
            dirty = true
            return e
        }

        /** Empties the log. Gives back what was in it, for their pictures. */
        fun clear(): List<Entry> {
            val out = entries.values.toList()
            entries.clear(); candidates.clear(); current.clear(); dirty = true
            return out
        }

        fun toJson(): JSONObject {
            val list = JSONArray(); entries.values.forEach { list.put(it.toJson()) }
            return JSONObject().put("v", 1).put("entries", list)
        }

        fun load(j: JSONObject?) {
            entries.clear()
            val list = j?.optJSONArray("entries") ?: return
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { entry(it) }?.let { entries[it.key] = it }
        }

        /**
         * The cards for the home, newest first: what counts, is not a clip, is not finished, was
         * played in the last 60 days, and is not from an app that has since been left out.
         * lookup: may pictures that were looked up outside be handed out? (When the setting is
         * off they are not, as showing one asks for it outside.)
         */
        fun items(wall: Long, lookup: Boolean, exclude: Collection<String> = emptyList()): List<JSONObject> = entries.values
            .filter { it.counted && !it.tooShort && !it.finished && wall - it.lastPlayed <= MAX_AGE_MS && watched(it.pkg, exclude, self) }
            .sortedByDescending { it.lastPlayed }.take(LIST_MAX).map { item(it, lookup) }
    }

    /** One card, in the shape GET /api/continue has always had (bridge/API.md), with the app's own picture first. */
    fun item(e: Entry, lookup: Boolean): JSONObject {
        val known = Apps.BY_PACKAGE[e.pkg]
        val app = JSONObject().put("id", known?.id ?: JSONObject.NULL)
            .put("name", e.appName ?: known?.name ?: Apps.describe(e.pkg)?.get("name") ?: e.pkg).put("package", e.pkg)
        val own = e.art?.let { "/api/continue/art/$it?v=${e.artAt}" } ?: e.artUri
        return JSONObject().put("id", e.id).put("title", e.title)
            .put("episode_title", e.episodeTitle ?: JSONObject.NULL).put("season", e.season ?: JSONObject.NULL).put("episode", e.episode ?: JSONObject.NULL)
            .put("app", app).put("service", known?.id ?: JSONObject.NULL)
            .put("poster", (if (lookup && own == null) e.poster else null) ?: JSONObject.NULL)
            .put("backdrop", own ?: (if (lookup) e.backdrop else null) ?: JSONObject.NULL)
            .put("progress", e.progress?.let { Math.round(it * 1000) / 1000.0 } ?: JSONObject.NULL)
            .put("position_ms", e.positionMs ?: JSONObject.NULL).put("duration_ms", if (e.durationMs > 0) e.durationMs else JSONObject.NULL)
            .put("kind", "continue").put("last_played", ScoreFeeds.isoUtc(e.lastPlayed)).put("source", "tv")
    }

    /**
     * Should this entry be looked up outside now? Only when the setting allows it, the app gave no
     * picture of its own, nothing has been found yet, and the last try (if it failed) was more
     * than a day ago.
     */
    fun wantsLookup(e: Entry, lookup: Boolean, wall: Long): Boolean =
        lookup && e.counted && !e.hasOwnPicture && !e.lookedOk && (e.lookedAt == 0L || wall - e.lookedAt > LOOKUP_RETRY_MS)

    // ---------------------------------------------------------------- the evidence (GET /api/continue/log)

    /**
     * The last 60 raw looks, newest first. A look that says the same as the last one from the
     * same app (same state, same metadata, same decision) does not add a line: that line's time,
     * position and count move on instead. An app that is left out keeps one line only, so a
     * playlist of songs cannot push the video apps' lines out.
     */
    class Evidence {
        private val list = ArrayList<JSONObject>()
        private val marks = java.util.IdentityHashMap<JSONObject, String>()

        fun add(look: JSONObject, mark: String, onlyOne: Boolean) {
            val pkg = look.optString("package")
            val last = list.firstOrNull { it.optString("package") == pkg }
            if (last != null && marks[last] == mark) {
                for (k in listOf("time", "position_ms", "detail", "speed")) if (look.has(k)) last.put(k, look.get(k))
                last.put("looks", last.optInt("looks", 1) + 1)
                return
            }
            if (onlyOne) list.filter { it.optString("package") == pkg }.forEach { list.remove(it); marks.remove(it) }
            look.put("looks", 1)
            list.add(0, look); marks[look] = mark
            while (list.size > SEEN_MAX) marks.remove(list.removeAt(list.size - 1))
        }

        fun clear() { list.clear(); marks.clear() }
        fun json(): JSONArray = JSONArray().also { a -> list.forEach { a.put(it) } }
        /** The looks kept from before a restart (their marks are not: the next look from each app adds a line). */
        fun load(a: JSONArray?) {
            clear()
            if (a != null) for (i in 0 until minOf(a.length(), SEEN_MAX)) a.optJSONObject(i)?.let { list += it }
        }
    }
}
