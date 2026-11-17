package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject

/**
 * What JustWatch's answers about a show mean, for Keep watching (Finder.kt does the asking,
 * Watching.kt decides when). No network and no Android here, so it is checked on a computer
 * against made-up answers of the shape JustWatch sends (app/src/test).
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
     * Null when no episode fits.
     */
    fun match(list: List<Ep>, name: String?, durationMs: Long, after: String? = null, span: Int = 0): Match? {
        val want = Finder.norm(name)
        if (want.isEmpty()) return null
        val pool = if (after == null) list else {
            val at = list.indexOfFirst { it.id == after }
            if (at < 0) return null
            list.subList(minOf(at + 1, list.size), minOf(at + 1 + span, list.size))
        }
        val mins = if (durationMs > 0) durationMs / 60_000.0 else null
        val fit = pool.filter { Finder.norm(it.title) == want }
            .filter { mins == null || it.runtimeMin == null || Math.abs(it.runtimeMin - mins) <= RUNTIME_SLACK_MIN }
        return when (fit.size) { 0 -> null; 1 -> Match(fit[0], 1); else -> Match(null, fit.size) }
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
