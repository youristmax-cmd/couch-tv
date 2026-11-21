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
 *     says them ("S1 E2", "Season 1 Episode 2", "1x02", "E3 · Name": episode). A title that says
 *     it is a preview ("<show> (Trailer)", trailerOf) is never a card, but its name is kept
 *     for half an hour: it may be the show of the episode that starts next (rule 7).
 *  3. where it is (position): worked out from the playback state the app last reported.
 *  4. whether it was watched (Log.see): in the playing state for 2 minutes in all, and, when the
 *     app says how long it is, at least 5 minutes long. Until then it is only a candidate, kept
 *     in memory: the previews an app plays on its own home screen never get as far as the log.
 *  5. one entry per app and title: a new episode of the same series replaces the old one's.
 *  6. the row (Log.items): the newest first, without what is finished (95% or more) or older
 *     than 60 days; 40 entries are kept.
 *  7. an app that names only the episode (Peacock, seen on a Fire TV), by its own name or, for a
 *     show that is on every day, by the date it was first on (dateOf): which shows to try for it
 *     (Log.candidates, Log.wantsShow), and what happens when one fits (Log.resolve). A name the
 *     same app gave for something short (a preview, with or without "(Trailer)" after it) is a
 *     show to try, for a week. The looking up itself is outside (Finder, Tvmaze, ShowRules) and
 *     only happens with the "lookup" setting on.
 *  8. an app that plays and names nothing (Hulu, seen on a Fire TV): after the same 2 minutes,
 *     one card for the app itself, until that app has a card with a real title.
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

    const val HINT_WINDOW_MS = 30 * 60_000L           // a preview's name is tried first for a title that began within this long after it
    const val HINT_KEEP_MS = 7L * 24 * 60 * 60_000    // it stays a name to try for this long, and is then forgotten
    const val MAX_HINTS = 40
    const val HINT_TRIES = 3                          // previews from just before, tried for one title, the newest first
    const val KEPT_TRIES = 8                          // previews from the last week, tried for one title at one go, the newest first
    const val SHOW_TRIES = 5                          // shows already worked out in the same app, tried for one title
    const val NEXT_SPAN = 3                           // for those, the episode must be one of the next 3 after the last one watched
    const val MAX_TRIED = 48
    const val OWN_RULE = 3                            // the rule a title found under its own name was found by (Finder.own): older finds are not kept

    const val NEAR = "near"                           // a show to try: its preview came up in the half hour before the title
    const val KEPT = "kept"                           // its preview came up some time in the last week
    const val SHOW = "show"                           // it is already being watched in that app

    const val KIND_TITLE = "title"                    // an entry for something the app named
    const val KIND_APP = "app"                        // the one entry for an app that plays and names nothing

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
     * episode. This only reads what is plain:
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

    private val TRAILER = Regex("""^(.*\S)\s*[(\[]\s*(?:official\s+)?(?:trailer|teaser|preview)\s*[)\]]\s*$""", RegexOption.IGNORE_CASE)

    /**
     * "Harbour Lights (Trailer)" is a preview, and what it is a preview of is "Harbour Lights"
     * (a made-up show). Peacock plays one on a show's page and names it this way
     * (seen on a Fire TV). Null for a title that does not say it is a preview.
     */
    fun trailerOf(title: String?): String? = TRAILER.find(title?.trim().orEmpty())?.groupValues?.get(1)?.let { strip(it) }?.takeIf { it.isNotEmpty() }

    // ---------------------------------------------------------------- a title that is a date

    private val MONTHS = listOf("january", "february", "march", "april", "may", "june", "july", "august", "september", "october", "november", "december")
    private val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    private const val WEEKDAY = """(?:([A-Za-z]{3,9})\.?,?\s+)?"""
    private val MONTH_FIRST = Regex("""^$WEEKDAY([A-Za-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(\d{4})$""")
    private val DAY_FIRST = Regex("""^$WEEKDAY(\d{1,2})(?:st|nd|rd|th)?\s+([A-Za-z]{3,9})\.?,?\s+(\d{4})$""")
    private val ISO_DATE = Regex("""^(\d{4})-(\d{2})-(\d{2})$""")
    private val US_DATE = Regex("""^(\d{1,2})/(\d{1,2})/(\d{4})$""")

    /** A month's number from its name, or from its first three letters or more ("Nov", "Sept"). 0 when it is no month. */
    private fun month(word: String): Int {
        val w = word.lowercase()
        return if (w.length < 3) 0 else MONTHS.indexOfFirst { it.startsWith(w) } + 1
    }
    private fun weekday(word: String): Boolean = word.isEmpty() || (word.length >= 3 && DAYS.any { it.startsWith(word.lowercase()) })

    /**
     * A show that is on every day names its episodes by the day they were first on, and an app that
     * gives only the episode's name then gives only that: "November 10, 2030" (Peacock, seen on a
     * Fire TV). The date such a title is, as 2030-11-10, or null when the title is anything else.
     * Read: "November 10, 2030", "Nov 10, 2030", "10 November 2030", "2030-11-10", and
     * "11/10/2030" in the order of the United States (month first); a weekday in front is passed
     * over. The whole title must be the date, and the date must be one there is: "November 1962"
     * (no day) is a name, and so is "February 30, 2030".
     */
    fun dateOf(title: String?): String? {
        val t = title?.replace(Regex("""\s+"""), " ")?.trim().orEmpty()
        if (t.isEmpty()) return null
        var y = 0; var m = 0; var d = 0
        val a = MONTH_FIRST.find(t); val b = DAY_FIRST.find(t); val c = ISO_DATE.find(t); val u = US_DATE.find(t)
        when {
            a != null && weekday(a.groupValues[1]) -> { m = month(a.groupValues[2]); d = a.groupValues[3].toInt(); y = a.groupValues[4].toInt() }
            b != null && weekday(b.groupValues[1]) -> { d = b.groupValues[2].toInt(); m = month(b.groupValues[3]); y = b.groupValues[4].toInt() }
            c != null -> { y = c.groupValues[1].toInt(); m = c.groupValues[2].toInt(); d = c.groupValues[3].toInt() }
            u != null -> { m = u.groupValues[1].toInt(); d = u.groupValues[2].toInt(); y = u.groupValues[3].toInt() }
            else -> return null
        }
        if (y !in 1900..2100 || m !in 1..12 || d < 1) return null
        val leap = y % 4 == 0 && (y % 100 != 0 || y % 400 == 0)
        val last = when (m) { 2 -> if (leap) 29 else 28; 4, 6, 9, 11 -> 30; else -> 31 }
        if (d > last) return null
        return "%04d-%02d-%02d".format(java.util.Locale.US, y, m, d)
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

    /**
     * One entry per app and title, whatever the capitals and the punctuation (Finder.norm). A title
     * with no Latin letter or figure in it (Finder.norm leaves nothing of "海辺の灯り") goes by its
     * own characters, so two such titles are not one entry, and none is taken for the app's own.
     */
    fun key(pkg: String, title: String): String = pkg + "|" + Finder.norm(title).ifEmpty { "=" + title.trim().lowercase() }

    /** The key of the one entry an app gets when it names nothing. No title's key ends like this. */
    fun appKey(pkg: String): String = "$pkg|"

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
        var kind = KIND_TITLE
        var title = ""; var appName: String? = null
        var episodeTitle: String? = null; var season: Int? = null; var episode: Int? = null
        var durationMs = 0L; var positionMs: Long? = null
        var playedMs = 0L                 // in the playing state, in all
        var lastPlayed = 0L               // by the clock on the wall
        var began = 0L                    // when this title last came up in its app (by the clock on the wall): previews just before it count
        var counted = false
        var art: String? = null           // the app's own picture, kept as a file of this name
        var artMark: String? = null; var artAt = 0L
        var artUri: String? = null        // or the address the session gave for one (https)
        var mediaId: String? = null; var mediaUri: String? = null
        // what a lookup outside found (only ever asked for when the setting allows it)
        var poster: String? = null; var backdrop: String? = null; var lookedAt = 0L; var lookedOk = false
        var ownRule = 0                   // for a title found under its own name: the rule it was found by (OWN_RULE); 0 for an older find
        // the title turned out to be an episode's name: the show it belongs to, and which episode (JustWatch's ids)
        var showTitle: String? = null; var showId: String? = null
        var showSeason: Int? = null; var showEpisode: Int? = null; var episodeId: String? = null
        var byDate = false                // the show was found by the date the title is: a show that is on every day
        /** Shows already tried for this title without success (normalised name to when), so none is asked about twice in a day. */
        val tried = LinkedHashMap<String, Long>()
        var showAskedAt = 0L              // when shows were last tried for it: after that, only a name that is new brings another go (or a day)
        val id get() = id(key)
        val tooShort get() = durationMs in 1 until MIN_DURATION_MS
        val progress get() = progress(positionMs, durationMs)
        /** (An app's own card is never finished: nobody knows what its position is a position in.) */
        val finished get() = kind == KIND_TITLE && (progress ?: 0.0) >= FINISHED
        /** Has the app given a picture of its own (a file kept, or an address)? */
        val hasOwnPicture get() = art != null || artUri != null
        /** Did the app's own words name an episode? Then the title is the show, and is not taken for an episode's name. */
        val namesEpisode get() = season != null || episode != null || episodeTitle != null

        fun toJson(): JSONObject = JSONObject().put("key", key).put("package", pkg).put("kind", kind).put("title", title).put("app_name", appName ?: JSONObject.NULL)
            .put("episode_title", episodeTitle ?: JSONObject.NULL).put("season", season ?: JSONObject.NULL).put("episode", episode ?: JSONObject.NULL)
            .put("duration_ms", durationMs).put("position_ms", positionMs ?: JSONObject.NULL).put("played_ms", playedMs).put("last_played", lastPlayed)
            .put("began", began)
            .put("art", art ?: JSONObject.NULL).put("art_mark", artMark ?: JSONObject.NULL).put("art_at", artAt).put("art_uri", artUri ?: JSONObject.NULL)
            .put("media_id", mediaId ?: JSONObject.NULL).put("media_uri", mediaUri ?: JSONObject.NULL)
            .put("poster", poster ?: JSONObject.NULL).put("backdrop", backdrop ?: JSONObject.NULL).put("looked_at", lookedAt).put("looked_ok", lookedOk)
            .put("own_rule", ownRule)
            .put("show_title", showTitle ?: JSONObject.NULL).put("show_id", showId ?: JSONObject.NULL)
            .put("show_season", showSeason ?: JSONObject.NULL).put("show_episode", showEpisode ?: JSONObject.NULL).put("episode_id", episodeId ?: JSONObject.NULL)
            .put("by_date", byDate).put("show_asked_at", showAskedAt)
            .put("tried", JSONObject().also { t -> tried.forEach { (k, v) -> t.put(k, v) } })
    }

    private fun str(o: JSONObject, k: String): String? = if (o.isNull(k)) null else o.optString(k).takeIf { it.isNotEmpty() }
    private fun int(o: JSONObject, k: String): Int? = if (o.isNull(k) || !o.has(k)) null else o.optInt(k)

    fun entry(o: JSONObject): Entry? {
        val key = str(o, "key") ?: return null; val pkg = str(o, "package") ?: return null
        return Entry(key, pkg).apply {
            kind = if (str(o, "kind") == KIND_APP) KIND_APP else KIND_TITLE
            title = o.optString("title"); appName = str(o, "app_name")
            episodeTitle = str(o, "episode_title"); season = int(o, "season"); episode = int(o, "episode")
            durationMs = o.optLong("duration_ms"); positionMs = if (o.isNull("position_ms") || !o.has("position_ms")) null else o.optLong("position_ms")
            playedMs = o.optLong("played_ms"); lastPlayed = o.optLong("last_played"); began = o.optLong("began"); counted = true
            art = str(o, "art"); artMark = str(o, "art_mark"); artAt = o.optLong("art_at"); artUri = str(o, "art_uri")
            mediaId = str(o, "media_id"); mediaUri = str(o, "media_uri")
            poster = str(o, "poster"); backdrop = str(o, "backdrop"); lookedAt = o.optLong("looked_at"); lookedOk = o.optBoolean("looked_ok")
            ownRule = o.optInt("own_rule")
            showTitle = str(o, "show_title"); showId = str(o, "show_id")
            showSeason = int(o, "show_season"); showEpisode = int(o, "show_episode"); episodeId = str(o, "episode_id")
            byDate = o.optBoolean("by_date"); showAskedAt = o.optLong("show_asked_at")
            o.optJSONObject("tried")?.let { t -> t.keys().forEach { k -> tried[k] = t.optLong(k) } }
        }.takeIf { it.title.isNotEmpty() }
    }

    /**
     * What the rules made of one look. decision: "excluded", "no title", "preview" (the title says
     * so), "too short", "not yet" (under its 2 minutes), "counted", "finished", or "app" (the app
     * names nothing and has its own card). write: now is a moment to write the log (it paused or
     * stopped, the title changed, or a minute of playing has gone by). picture: the session
     * carries a picture this entry does not have a copy of yet. hint: this look gave a name that
     * is new as a show to try (a preview's), so titles still without their show may now find it.
     */
    class Verdict(val decision: String, val detail: String, val entry: Entry?, val told: Told?, val write: Boolean, val picture: Boolean,
                  val hint: Boolean = false)

    /** A preview's name, kept as a show that a title in the same app may belong to. */
    class Hint(val pkg: String, val name: String, var at: Long)

    /**
     * A show to try for a title that may be an episode's name, or its date. kind: NEAR (its
     * preview came up just before the title), KEPT (some time in the last week) or SHOW (already
     * being watched in the app). showId: already known (JustWatch's id), so no search is needed.
     * after: only the episodes just after this one are looked at (ShowRules.match); null where the
     * whole list is. at: when the name came up, or the show was worked out.
     */
    class Cand(val name: String, val showId: String?, val poster: String?, val backdrop: String?, val after: String?,
               val kind: String = NEAR, val at: Long = 0)

    /** The watch log itself: the titles that count, the ones not there yet, and what each app is playing now. */
    class Log(private val self: String) {
        private val entries = LinkedHashMap<String, Entry>()      // counted: these are written to the TV's storage
        private val candidates = LinkedHashMap<String, Entry>()   // not yet 2 minutes: memory only
        private class Current(val key: String?, val since: Long?, val last: Seen)   // since: playing from then (elapsed time)
        private val current = HashMap<String, Current>()
        private val hints = ArrayList<Hint>()
        private val lastTitled = HashMap<String, String>()         // per app, the key of the title it named last (a preview in between clears it)
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
            // an app that has a card with a real title needs no card of its own
            if (e.kind == KIND_TITLE) drop(appKey(e.pkg))
        }

        private fun drop(key: String) {
            if (entries.remove(key) != null) dirty = true
            candidates.remove(key)
            current.entries.removeAll { it.value.key == key }
        }

        fun see(s: Seen, exclude: Collection<String>): Verdict {
            if (!watched(s.pkg, exclude, self)) { current.remove(s.pkg); return Verdict("excluded", "this app is left out", null, null, false, false) }
            val was = current[s.pkg]
            close(s.pkg, s.at)
            val playing = s.state == STATE_PLAYING
            val told = describe(s.title, s.second) ?: return nameless(s, was, playing)
            trailerOf(told.title)?.let { name ->
                // a preview by its own words: never a card. Its name is kept: it may be the show of an episode that names only itself
                val noted = note(s.pkg, name, s.wall)
                lastTitled.remove(s.pkg)
                current[s.pkg] = Current(null, null, s)
                return Verdict("preview", "the title says it is a preview; \"$name\" is kept for ${HINT_KEEP_MS / (24 * 60 * 60_000)} days as a show a title in this app may belong to",
                    null, told, was?.key != null && take(s.at), false, noted)
            }
            // something short with a name and no "(Trailer)" after it is a preview all the same (Peacock plays those too): its
            // name is kept like the others'. (A date is no show's name.)
            val noted = s.durationMs in 1 until MIN_DURATION_MS && dateOf(told.title) == null && note(s.pkg, told.title, s.wall)
            val key = key(s.pkg, told.title)
            val e = known(key) ?: Entry(key, s.pkg).also { candidates[key] = it; tidy(s.wall) }
            // a candidate last played more than 6 hours ago starts its 2 minutes again
            if (!e.counted && e.lastPlayed > 0 && s.wall - e.lastPlayed > CANDIDATE_MS) e.playedMs = 0
            // it has just come up in this app (not merely come back after a moment with no title): previews before now may name its show
            if (lastTitled[s.pkg] != key || e.began == 0L || (e.lastPlayed > 0 && s.wall - e.lastPlayed > HINT_WINDOW_MS)) e.began = s.wall
            lastTitled[s.pkg] = key
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
            return Verdict(decision, detail, e, told, write, e.counted && s.picture != null && s.picture != e.artMark, noted)
        }

        /**
         * A session with no title. An app that goes on playing like that for the usual 2 minutes
         * gets one card of its own ("Continue in Hulu"): it names nothing, but it was watched.
         * Not when the app already has a card with a real title: then this is only a moment
         * between two titles (Peacock's session is empty now and then while an episode plays).
         */
        private fun nameless(s: Seen, was: Current?, playing: Boolean): Verdict {
            val key = appKey(s.pkg)
            val stopped = was?.since != null && !playing
            val changed = was?.key != null && was.key != key
            if (entries.values.any { it.pkg == s.pkg && it.kind == KIND_TITLE }) {
                current[s.pkg] = Current(null, null, s)
                return Verdict("no title", "the session names nothing", null, null, was?.key != null && take(s.at), false)
            }
            val e = known(key) ?: Entry(key, s.pkg).also { it.kind = KIND_APP; candidates[key] = it; tidy(s.wall) }
            if (!e.counted && e.lastPlayed > 0 && s.wall - e.lastPlayed > CANDIDATE_MS) e.playedMs = 0
            s.appName?.let { e.appName = it }
            e.title = e.appName ?: s.pkg
            e.durationMs = maxOf(s.durationMs, 0L)       // whatever is playing now: a clip that says it is short does not count
            e.positionMs = position(s)                   // where the app says it is, in whatever it is
            if (playing) e.lastPlayed = s.wall
            settle(e)
            if (e.counted) dirty = true
            current[s.pkg] = Current(key, if (playing) s.at else null, s)
            val write = (stopped || changed || s.at - writtenAt >= WRITE_EVERY_MS) && take(s.at)
            return when {
                e.counted -> Verdict("app", "the session names nothing: one card for the app itself", e, null, write, false)
                e.tooShort -> Verdict("no title", "the session names nothing, and says it is ${e.durationMs / 1000} s long", null, null, write, false)
                else -> Verdict("no title", "the session names nothing; played ${e.playedMs / 1000} s of the ${MIN_PLAYED_MS / 1000} s it takes to keep a card for the app",
                    null, null, write, false)
            }
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
            hints.removeAll { wall - it.at > HINT_KEEP_MS }
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
            entries.clear(); candidates.clear(); current.clear(); hints.clear(); lastTitled.clear(); dirty = true
            return out
        }

        fun toJson(): JSONObject {
            val list = JSONArray(); entries.values.forEach { list.put(it.toJson()) }
            val hs = JSONArray(); hints.forEach { hs.put(JSONObject().put("package", it.pkg).put("name", it.name).put("at", it.at)) }
            return JSONObject().put("v", 2).put("entries", list).put("hints", hs)
        }

        fun load(j: JSONObject?) {
            entries.clear(); hints.clear()
            val list = j?.optJSONArray("entries") ?: return
            for (i in 0 until list.length()) list.optJSONObject(i)?.let { entry(it) }?.let { entries[it.key] = it }
            // A title found under its own name by an older, looser rule: the find is not kept (the first rule gave an
            // episode named by its date the poster of a film with a name like that date). The title is as if it had never
            // been looked up, and is looked up again by the rule there is now, the shows it may belong to first.
            for (e in entries.values) if (e.lookedOk && e.showId == null && e.ownRule < OWN_RULE) {
                e.poster = null; e.backdrop = null; e.lookedOk = false; e.lookedAt = 0; dirty = true
            }
            val hs = j.optJSONArray("hints") ?: return
            for (i in 0 until hs.length()) hs.optJSONObject(i)?.let { h ->
                val pkg = h.optString("package"); val name = h.optString("name")
                if (pkg.isNotEmpty() && name.isNotEmpty()) hints += Hint(pkg, name, h.optLong("at"))
            }
        }

        // ------------------------------------------------------------ rule 7: a title that may be an episode's name

        /**
         * A preview's name is kept (the newest time, when the app shows the same one again). True
         * when that is news: a name not kept before, or one last seen more than a minute ago.
         */
        private fun note(pkg: String, name: String, wall: Long): Boolean {
            val n = Finder.norm(name)
            if (n.isEmpty()) return false
            val old = hints.firstOrNull { it.pkg == pkg && Finder.norm(it.name) == n }
            if (old != null) {
                if (wall <= old.at) return false
                val news = wall - old.at > 60_000
                old.at = wall; dirty = true
                return news
            }
            hints += Hint(pkg, name, wall); dirty = true
            while (hints.size > MAX_HINTS) hints.remove(hints.minByOrNull { it.at })
            return true
        }

        fun hints(): List<Hint> = hints.toList()

        /**
         * What the raw looks kept from before know (GET /api/continue/log's lines, which outlive a
         * restart): the previews in them are remembered (a title that says it is one, or anything
         * named that was under 5 minutes long), and a title that does not yet know when it began
         * is told (the oldest look that names it, or failing that when it was last played). So a
         * title logged before the app knew any of this can still find its show.
         */
        fun adopt(looks: JSONArray?) {
            val first = HashMap<String, Long>()
            for (i in 0 until (looks?.length() ?: 0)) {
                val l = looks!!.optJSONObject(i) ?: continue
                val pkg = l.optString("package")
                val title = l.optJSONObject("read")?.optString("title").orEmpty()
                val at = ScoreFeeds.parseIso(l.optString("time")) ?: continue
                if (pkg.isEmpty() || title.isEmpty() || l.optString("decision") == "excluded") continue
                val length = l.optJSONObject("metadata")?.optJSONObject("android.media.metadata.DURATION")?.optLong("value") ?: 0L
                val preview = trailerOf(title)
                if (preview != null) note(pkg, preview, at)
                else if (length in 1 until MIN_DURATION_MS && dateOf(title) == null) note(pkg, title, at)
                val k = key(pkg, title)
                if (at < (first[k] ?: Long.MAX_VALUE)) first[k] = at
            }
            for (e in entries.values) if (e.began == 0L && e.kind == KIND_TITLE) { e.began = first[e.key] ?: e.lastPlayed; dirty = true }
        }

        private fun untried(e: Entry, name: String, wall: Long): Boolean = e.tried[Finder.norm(name)]?.let { wall - it > LOOKUP_RETRY_MS } ?: true

        /**
         * May this title be an episode's name (or its date), with its show still to be found? Only
         * something the app named without saying which episode it is, that nothing has been found
         * for yet.
         */
        fun unresolved(e: Entry): Boolean = e.counted && e.kind == KIND_TITLE && e.showId == null && !e.lookedOk && !e.namesEpisode

        /**
         * The shows to try for such a title at one go, in order. Each still has to be confirmed
         * outside (the show has an episode of that name, or was on on that date): a name alone
         * never makes the card.
         *  1. NEAR: the previews the same app named in the half hour before the title came up,
         *     the newest first, three at most (on Peacock the show's page plays one just before
         *     the episode starts);
         *  2. KEPT: the other preview names kept for that app from the last week, the newest
         *     first, eight at most at one go. A show's name does not only pass by just before:
         *     this is also how a title logged days ago finds its show when the show's preview
         *     next comes up;
         *  3. SHOW: the shows already worked out for that app, five at most. For a title that is
         *     an episode's name: the next episode of something being watched (only the next few
         *     episodes are looked at). For a title that is a date: a show found by a date before,
         *     which is on every day, whatever the date.
         * Without the ones tried in the last day. Empty when there is nothing to try.
         */
        fun candidates(e: Entry, wall: Long): List<Cand> {
            if (!unresolved(e)) return emptyList()
            val dated = dateOf(e.title) != null
            val own = Finder.norm(e.title)
            val shows = entries.values.filter { it !== e && it.pkg == e.pkg && it.showId != null && it.showTitle != null }
                .sortedByDescending { it.lastPlayed }.distinctBy { it.showId }
            val mine = hints.filter { it.pkg == e.pkg && wall - it.at <= HINT_KEEP_MS && Finder.norm(it.name) != own }
                .sortedByDescending { it.at }.distinctBy { Finder.norm(it.name) }
            // a preview of a show that is already known needs no search
            val cand = { h: Hint, kind: String ->
                val known = shows.firstOrNull { Finder.norm(it.showTitle) == Finder.norm(h.name) }
                Cand(known?.showTitle ?: h.name, known?.showId, known?.poster, known?.backdrop, null, kind, h.at)
            }
            val near = mine.filter { it.at <= e.began && e.began - it.at <= HINT_WINDOW_MS }.take(HINT_TRIES)
            val out = ArrayList<Cand>()
            near.filter { untried(e, it.name, wall) }.forEach { out += cand(it, NEAR) }
            // (the ones never tried before the ones whose day is up, so that with more than eight every one gets its turn)
            mine.filter { it !in near && untried(e, it.name, wall) }.sortedBy { e.tried[Finder.norm(it.name)] ?: 0L }
                .take(KEPT_TRIES).forEach { out += cand(it, KEPT) }
            shows.filter { if (dated) it.byDate else it.episodeId != null }.take(SHOW_TRIES).forEach { x ->
                if (untried(e, x.showTitle!!, wall) && out.none { Finder.norm(it.name) == Finder.norm(x.showTitle) })
                    out += Cand(x.showTitle!!, x.showId, x.poster, x.backdrop, if (dated) null else x.episodeId, SHOW, x.lookedAt)
            }
            return out
        }

        /**
         * Is it time to try shows for this title? When there is one to try, and either none has
         * been tried yet, or the last go was more than a day ago, or one of them is new since the
         * last go (its preview has come up, or it has been worked out, after that). So a title
         * that found nothing is not asked about over and over, and is asked about again the
         * moment a new name turns up for its app. Not for a title that is finished: it has no
         * card to put a show's name on.
         */
        fun wantsShow(e: Entry, wall: Long): Boolean {
            if (e.finished) return false
            val c = candidates(e, wall)
            return c.isNotEmpty() && (e.showAskedAt == 0L || wall - e.showAskedAt > LOOKUP_RETRY_MS || c.any { it.at > e.showAskedAt })
        }

        /** Shows have just been tried for this title. */
        fun asked(e: Entry, wall: Long) { e.showAskedAt = wall; if (e.counted) dirty = true }

        /** This show was tried for the title and did not fit, or could not be asked about: not again for a day. */
        fun tried(e: Entry, name: String, wall: Long) {
            val n = Finder.norm(name)
            e.tried.remove(n); e.tried[n] = wall
            while (e.tried.size > MAX_TRIED) e.tried.remove(e.tried.keys.first())
            if (e.counted) dirty = true
        }

        /**
         * The title is an episode of this show. The card becomes the show's (WatchRules.item), and
         * an older card of the same show in the same app goes: one card per app and show, the
         * newest. season, episode and episodeId are null when the show is sure and the episode is
         * not. byDate: the title is a date and the show was on that day (a show that is on every
         * day: it is then tried for any other date watched in that app). Gives back the entries
         * that went, for their pictures.
         */
        fun resolve(e: Entry, showTitle: String, showId: String, poster: String?, backdrop: String?,
                    season: Int?, episode: Int?, episodeId: String?, wall: Long, byDate: Boolean = false): List<Entry> {
            e.showTitle = showTitle; e.showId = showId; e.showSeason = season; e.showEpisode = episode; e.episodeId = episodeId
            e.poster = poster; e.backdrop = backdrop; e.lookedAt = wall; e.lookedOk = true; e.byDate = byDate
            dirty = true
            val out = entries.values.filter { it.pkg == e.pkg && it.showId == showId }.sortedByDescending { it.lastPlayed }.drop(1)
            out.forEach { drop(it.key) }
            return out
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

    /**
     * One card, in the shape GET /api/continue has always had (bridge/API.md), with the app's own
     * picture first.
     *  - a title that turned out to be an episode's name: "title" is the show, "episode_title"
     *    what the app called it, with the season and episode when they are known;
     *  - an app that names nothing: "kind" is "app", "title" the app's name, and of the rest only
     *    "position_ms" (where the app says it is; in what, nobody knows, so there is no progress);
     *  - "icon": the app's icon on this server, given when the card has no picture of any kind,
     *    so the home can still show which app it is.
     */
    fun item(e: Entry, lookup: Boolean): JSONObject {
        val known = Apps.BY_PACKAGE[e.pkg]
        val name = e.appName ?: known?.name ?: Apps.describe(e.pkg)?.get("name") ?: e.pkg
        val app = JSONObject().put("id", known?.id ?: JSONObject.NULL).put("name", name).put("package", e.pkg)
        val own = e.art?.let { "/api/continue/art/$it?v=${e.artAt}" } ?: e.artUri
        val isApp = e.kind == KIND_APP
        val poster = if (!isApp && lookup && own == null) e.poster else null
        val backdrop = if (isApp) null else own ?: (if (lookup) e.backdrop else null)
        val shown = e.showTitle != null
        return JSONObject().put("id", e.id).put("title", if (isApp) name else e.showTitle ?: e.title)
            .put("episode_title", (if (isApp) null else if (shown) e.title else e.episodeTitle) ?: JSONObject.NULL)
            .put("season", (if (isApp) null else e.season ?: e.showSeason) ?: JSONObject.NULL)
            .put("episode", (if (isApp) null else e.episode ?: e.showEpisode) ?: JSONObject.NULL)
            .put("app", app).put("service", known?.id ?: JSONObject.NULL)
            .put("poster", poster ?: JSONObject.NULL).put("backdrop", backdrop ?: JSONObject.NULL)
            .put("icon", if (poster == null && backdrop == null) "/icon/${e.pkg}" else JSONObject.NULL)
            .put("progress", (if (isApp) null else e.progress?.let { Math.round(it * 1000) / 1000.0 }) ?: JSONObject.NULL)
            .put("position_ms", e.positionMs ?: JSONObject.NULL)
            .put("duration_ms", if (!isApp && e.durationMs > 0) e.durationMs else JSONObject.NULL)
            .put("kind", if (isApp) "app" else "continue").put("last_played", ScoreFeeds.isoUtc(e.lastPlayed)).put("source", "tv")
    }

    /**
     * Should this entry be looked up outside now, under its own name? Only when the setting
     * allows it, the app gave no picture of its own, nothing has been found yet, and the last try
     * (if it failed) was more than a day ago. Never for an app's own card, and never for a title
     * that is a date: a date is no title's name, and whatever is found under one is something else.
     */
    fun wantsLookup(e: Entry, lookup: Boolean, wall: Long): Boolean =
        lookup && e.counted && e.kind == KIND_TITLE && !e.hasOwnPicture && !e.lookedOk && dateOf(e.title) == null &&
            (e.lookedAt == 0L || wall - e.lookedAt > LOOKUP_RETRY_MS)

    /**
     * Is what was found under a title's own name really it? The names must be the same once
     * capitals and punctuation are gone (not merely alike), and when both lengths are known, what
     * was found must be within a fifth of the length the app gave: a 21 minute episode is not a
     * 2 hour film of the same name. episode: the app's own words named a season or an episode;
     * the title is then a show's, and a show's usual length says nothing about one episode.
     */
    fun ownName(found: String?, foundMin: Int?, foundIsShow: Boolean, title: String?, lengthMs: Long, episode: Boolean): Boolean {
        val a = Finder.norm(found); val b = Finder.norm(title)
        if (a.isEmpty() || a != b) return false
        if (episode && foundIsShow) return true
        if (foundMin == null || foundMin <= 0 || lengthMs <= 0) return true
        val mins = lengthMs / 60_000.0
        return Math.abs(foundMin - mins) <= 0.2 * mins
    }

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
