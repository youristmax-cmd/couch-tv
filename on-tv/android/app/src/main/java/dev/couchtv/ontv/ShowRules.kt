package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject

/**
 * What JustWatch's answers about a show mean, and TVmaze's, for Keep watching (Finder.kt and
 * Tvmaze.kt do the asking, Watching.kt decides when). No network and no Android here, so it is
 * checked on a computer against made-up answers of the shapes the two send (app/src/test).
 *
 * Why it exists: some apps name only the episode in their media session (Peacock: the episode's
 * own name and nothing else). The show is then worked out: a name the app gave just before
 * (the preview on the show's page), or a show already being watched in that app, is looked up,
 * and its episode list is searched for an episode of that name.
 *
 * The list asked for is the light one: season number, and each episode's id, number, name and
 * length. Not the offers: with every episode's offers the same list was 2.3 MB for a show of 381
 * episodes, against 36 KB without. The offers are asked for afterwards, for the one episode.
 */
object ShowRules {
    const val RUNTIME_SLACK_MIN = 3                   // an episode's length and the session's must agree within this, when both are known
    const val LIST_MS = 24 * 60 * 60_000L             // a show's episode list is kept this long
    const val LIST_MAX_BYTES = 512 * 1024             // an episode list longer than this is not read
    const val LISTS_KEPT = 8                          // shows whose list is kept on the TV

    /** One episode of the light list. */
    class Ep(val id: String, val season: Int, val episode: Int, val title: String, val runtimeMin: Int?)

    private fun str(o: JSONObject?, k: String): String = if (o == null || o.isNull(k)) "" else o.optString(k).trim()

    /** The episodes out of JustWatch's answer ({"node": {"seasons": [...]}}), in order of season and number. */
    fun episodes(data: JSONObject?): List<Ep> {
        val seasons = data?.optJSONObject("node")?.optJSONArray("seasons") ?: return emptyList()
        val out = ArrayList<Ep>()
        for (i in 0 until seasons.length()) {
            val s = seasons.optJSONObject(i) ?: continue
            val season = s.optJSONObject("content")?.optInt("seasonNumber", -1) ?: -1
            val eps = s.optJSONArray("episodes") ?: continue
            for (j in 0 until eps.length()) {
                val e = eps.optJSONObject(j) ?: continue
                val c = e.optJSONObject("content") ?: continue
                val id = str(e, "id")
                if (id.isEmpty() || season < 0) continue
                out += Ep(id, season, c.optInt("episodeNumber", 0), str(c, "title"), c.optInt("runtime", 0).takeIf { it > 0 })
            }
        }
        return out.sortedWith(compareBy({ it.season }, { it.episode }))
    }

    /**
     * episode: the one episode it is. Null when several episodes carry the name and nothing tells
     * them apart (a look back at the season, once a season): the show is still known, the episode is not,
     * and it is not guessed.
     */
    class Match(val episode: Ep?, val named: Int)

    /**
     * Is there an episode of this name in the list? The names must be the same once capitals and
     * punctuation are gone, and when the session says how long the thing is and JustWatch says
     * how long the episode is, the two must agree within 3 minutes.
     *
     * after (an episode's id) and span: only the next few episodes after that one are looked at.
     * That is for a show found only because it is already being watched in the app: there a name
     * alone proves little (many shows have an episode called "Part One"), the next episode's name does.
     *
     * strict: for a show whose preview only passed by some time in the last week, not just before
     * the title. That is thinner ground, so more is asked: both lengths must be known and agree,
     * and exactly one episode may carry the name.
     * Null when no episode fits.
     */
    fun match(list: List<Ep>, name: String?, durationMs: Long, after: String? = null, span: Int = 0, strict: Boolean = false): Match? {
        val want = Finder.norm(name)
        if (want.isEmpty()) return null
        val pool = if (after == null) list else {
            val at = list.indexOfFirst { it.id == after }
            if (at < 0) return null
            list.subList(minOf(at + 1, list.size), minOf(at + 1 + span, list.size))
        }
        val mins = if (durationMs > 0) durationMs / 60_000.0 else null
        val named = pool.filter { Finder.norm(it.title) == want }
        val fit = named.filter { e ->
            if (strict) mins != null && e.runtimeMin != null && Math.abs(e.runtimeMin - mins) <= RUNTIME_SLACK_MIN
            else mins == null || e.runtimeMin == null || Math.abs(e.runtimeMin - mins) <= RUNTIME_SLACK_MIN
        }
        return when {
            fit.isEmpty() -> null
            fit.size == 1 && (!strict || named.size == 1) -> Match(fit[0], 1)
            strict -> null
            else -> Match(null, fit.size)
        }
    }

    // ---------------------------------------------------------------- a title that is a date (TVmaze)

    /**
     * A show that is on every day names its episodes by the day (WatchRules.dateOf), and JustWatch
     * lists them under other names (the guests'), so a date cannot be found in its list. TVmaze
     * (api.tvmaze.com: free, no key; its data is CC BY-SA) can say whether a show was on on a
     * given day: /singlesearch/shows?q=<name> gives the show (1.5 KB), and
     * /shows/<id>/episodesbydate?date=<day> that day's episodes (under 1 KB), or 404 for none.
     * Only the fact that it was on, and the season and episode numbers, are taken from it.
     */
    class TvShow(val id: Int, val name: String, val days: Int, val runtimeMin: Int?)

    /** TVmaze's show out of its answer to a search by name. Null for no answer, or one with no id or name. */
    fun tvShow(j: JSONObject?): TvShow? {
        val id = j?.optInt("id", 0) ?: 0
        val name = str(j, "name")
        if (id <= 0 || name.isEmpty()) return null
        val days = j!!.optJSONObject("schedule")?.optJSONArray("days")?.length() ?: 0
        val length = j.optInt("averageRuntime", 0).takeIf { it > 0 } ?: j.optInt("runtime", 0).takeIf { it > 0 }
        return TvShow(id, name, days, length)
    }

    /** season and number: null when TVmaze does not give them, or when more than one episode was on that day (which one is not known). */
    class Aired(val season: Int?, val number: Int?, val runtimeMin: Int?, val count: Int)

    /** What a show had on on one day (2030-11-10), out of TVmaze's list for that day. Null when nothing was. */
    fun tvAired(list: JSONArray?, date: String): Aired? {
        val on = (0 until (list?.length() ?: 0)).mapNotNull { list!!.optJSONObject(it) }.filter { str(it, "airdate") == date }
        if (on.isEmpty()) return null
        val num = { o: JSONObject, k: String -> if (o.isNull(k) || !o.has(k)) null else o.optInt(k, 0).takeIf { it > 0 } }
        val one = on.size == 1
        val seasons = on.map { num(it, "season") }.distinct()
        return Aired(seasons.singleOrNull(), if (one) num(on[0], "number") else null, num(on[0], "runtime"), on.size)
    }

    const val DAILY_DAYS = 4                          // a show that is on at least this many days a week is one that is "on every day"
    const val AIR_MIN = 0.5                           // what was watched may be this much of the slot TVmaze gives (no adverts), or more,
    const val AIR_MAX = 1.15                          // and up to this much of it

    /**
     * Is a title that is a date this show's episode of that day? The show must have been on that
     * day (aired). When both lengths are known, what was watched must be between half the slot
     * and a little over it (a half-hour slot is about 21 minutes without adverts; a two-hour film
     * is not an episode of it). And unless the show's preview came up just before the title
     * (near), it must be a show that is on at least four days a week: any weekly show was on on
     * some date, and a date alone would then give the card to whichever preview passed by last.
     */
    fun airedFits(show: TvShow, aired: Aired?, lengthMs: Long, near: Boolean): Boolean {
        if (aired == null) return false
        if (!near && show.days < DAILY_DAYS) return false
        val slot = aired.runtimeMin ?: show.runtimeMin ?: return true
        if (lengthMs <= 0) return true
        val mins = lengthMs / 60_000.0
        return mins >= slot * AIR_MIN && mins <= slot * AIR_MAX
    }

    /**
     * Is TVmaze's show the one that was asked for by name? (A search always answers with its best
     * guess.) The same title as an app and a catalogue may each write it (Finder.sameTitle), or
     * the same but for the host on the end: TVmaze lists a talk show under its short name where
     * the app calls it "<name> with <host>" (seen for a real one, November 2026).
     */
    fun sameShow(show: TvShow?, name: String): Boolean {
        if (show == null) return false
        if (Finder.sameTitle(show.name, name)) return true
        val a = Finder.norm(show.name); val b = Finder.norm(name)
        val (short, long) = if (a.length <= b.length) a to b else b to a
        return short.isNotEmpty() && long.startsWith("$short with ")
    }

    private val KIND_ORDER = mapOf("FREE" to 0, "ADS" to 0, "FAST" to 0, "FLATRATE" to 1, "RENT" to 2, "BUY" to 3)

    /**
     * The link one app is given for a title, out of JustWatch's offers: the first offer whose
     * package is one of that app's names (Peacock is "peacocktv" and "peacocktvpremium") and that
     * has a link, what is free or in a subscription before what is rented or bought. Null when the
     * app has none.
     */
    fun link(offers: JSONArray?, names: Collection<String>): String? {
        var best: String? = null; var rank = 99
        for (i in 0 until (offers?.length() ?: 0)) {
            val o = offers!!.optJSONObject(i) ?: continue
            if (str(o.optJSONObject("package"), "technicalName") !in names) continue
            val link = str(o, "deeplinkURL").takeIf { it.isNotEmpty() && it != "null" } ?: continue
            val r = KIND_ORDER[str(o, "monetizationType")] ?: 9
            if (r < rank) { best = link; rank = r }
        }
        return best
    }

    // ---------------------------------------------------------------- kept on the TV (a list is asked for once a day)

    fun toJson(list: List<Ep>): JSONArray = JSONArray().also { a ->
        list.forEach { a.put(JSONArray().put(it.id).put(it.season).put(it.episode).put(it.title).put(it.runtimeMin ?: 0)) }
    }

    fun fromJson(a: JSONArray?): List<Ep> = (0 until (a?.length() ?: 0)).mapNotNull { i ->
        val e = a!!.optJSONArray(i) ?: return@mapNotNull null
        val id = e.optString(0)
        if (id.isEmpty()) null else Ep(id, e.optInt(1), e.optInt(2), e.optString(3), e.optInt(4).takeIf { it > 0 })
    }
}
