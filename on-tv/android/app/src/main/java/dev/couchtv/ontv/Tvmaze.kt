package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap

/**
 * Whether a show was on on a given day, from TVmaze (api.tvmaze.com: free, no account, no key).
 * Keep watching needs it for one thing: an app that names an episode only by its date (a show
 * that is on every day; ShowRules says why JustWatch cannot settle that). It is asked only when
 * the household has switched looking titles up on (watching.lookup), and what it is told is a
 * show's name, and then the show's number there and a date.
 *
 * TVmaze's data is licensed CC BY-SA 4.0 (tvmaze.com/api, "Licensing": free for any purpose with
 * TVmaze credited as the source, and share-alike). All that is taken from it is the fact that a
 * show was on that day, and that episode's season and number. Its limit is 20 calls in 10 seconds
 * for one address; this asks a handful a day at most:
 *  - a show's name is asked about once a week, a miss as well;
 *  - a show and a day are asked about once and kept for good. (A day that is not yet a week old is
 *    kept for a day only when the answer is "nothing": TVmaze may not have that episode yet.)
 * What an answer means is in ShowRules.kt.
 */
object Tvmaze {
    private const val BASE = "https://api.tvmaze.com"
    private const val AGENT = "couch-tv-on-tv/1"            // the same name the app gives JustWatch; it says nothing about the household
    private const val FILE = "watching-tvmaze.json"         // {"shows": {"<name>": {"at", "id", "name", "days", "runtime"} or {"at", "none": true}},
                                                            //  "aired": {"<id>|<date>": {"at", "season", "number", "runtime", "count"} or {"at", "none": true}}}
    private const val NAME_MS = 7L * 24 * 60 * 60_000
    private const val DAY_MS = 24 * 60 * 60_000L
    private const val KEPT = 200

    private class Kept<T>(val at: Long, val value: T?)
    private val shows = ConcurrentHashMap<String, Kept<ShowRules.TvShow>>()
    private val aired = ConcurrentHashMap<String, Kept<ShowRules.Aired>>()
    private var read = false

    /** TVmaze's show of this name (its best guess for it: ShowRules.sameShow checks it). Null when it has none. Throws when it could not be asked. */
    @Synchronized fun show(name: String): ShowRules.TvShow? {
        load()
        val now = System.currentTimeMillis()
        val key = Finder.norm(name)
        shows[key]?.takeIf { now - it.at < NAME_MS }?.let { return it.value }
        val body = get("$BASE/singlesearch/shows?q=" + URLEncoder.encode(name, "UTF-8"))
        val found = ShowRules.tvShow(body?.let { runCatching { JSONObject(it) }.getOrNull() })
        shows[key] = Kept(now, found)
        save()
        return found
    }

    /** What that show had on on that day (2030-11-10). Null when nothing. Throws when TVmaze could not be asked. */
    @Synchronized fun aired(showId: Int, date: String): ShowRules.Aired? {
        load()
        val now = System.currentTimeMillis()
        val key = "$showId|$date"
        aired[key]?.let { k ->
            // an episode found is kept for good; "nothing" too, once the day is far enough back that TVmaze will not add to it
            val settled = k.value != null || (ScoreFeeds.parseIso(date + "T00:00:00Z")?.let { now - it > NAME_MS } ?: true)
            if (settled || now - k.at < DAY_MS) return k.value
        }
        val body = get("$BASE/shows/$showId/episodesbydate?date=" + URLEncoder.encode(date, "UTF-8"))
        val found = ShowRules.tvAired(body?.let { runCatching { JSONArray(it) }.getOrNull() }, date)
        aired[key] = Kept(now, found)
        save()
        return found
    }

    /** What is kept goes (it says which shows were looked into): part of "forget what was watched". */
    @Synchronized fun forget() {
        shows.clear(); aired.clear(); read = true
        runCatching { Store.writeJson(FILE, JSONObject()) }
    }

    /** One plain GET. The body for a 200, null for a 404 (TVmaze's "there is none"); anything else throws. */
    private fun get(url: String): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 6000; c.readTimeout = 8000
            c.setRequestProperty("User-Agent", AGENT)
            val st = c.responseCode
            if (st == 404) return null
            if (st != 200) throw ApiError(502, "TVmaze answered $st")
            val out = java.io.ByteArrayOutputStream()
            c.inputStream.use { s ->
                val buf = ByteArray(8192)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 64 * 1024) throw ApiError(502, "TVmaze's answer is too long")
                }
            }
            return out.toString("UTF-8")
        } finally { c.disconnect() }
    }

    private fun load() {
        if (read) return
        read = true
        runCatching {
            val j = Store.readJson(FILE) as? JSONObject ?: return
            val num = { o: JSONObject, k: String -> if (o.isNull(k) || !o.has(k)) null else o.optInt(k).takeIf { it > 0 } }
            j.optJSONObject("shows")?.let { s -> s.keys().forEach { k -> s.optJSONObject(k)?.let { o ->
                shows[k] = Kept(o.optLong("at"), if (o.optBoolean("none")) null else ShowRules.TvShow(o.optInt("id"), o.optString("name"), o.optInt("days"), num(o, "runtime")))
            } } }
            j.optJSONObject("aired")?.let { a -> a.keys().forEach { k -> a.optJSONObject(k)?.let { o ->
                aired[k] = Kept(o.optLong("at"), if (o.optBoolean("none")) null else ShowRules.Aired(num(o, "season"), num(o, "number"), num(o, "runtime"), o.optInt("count", 1)))
            } } }
        }
    }

    private fun save() {
        runCatching {
            // only so much is kept: the newest
            shows.entries.sortedByDescending { it.value.at }.drop(KEPT).forEach { shows.remove(it.key) }
            aired.entries.sortedByDescending { it.value.at }.drop(KEPT).forEach { aired.remove(it.key) }
            val s = JSONObject(); val a = JSONObject()
            shows.forEach { (k, v) ->
                val x = v.value
                s.put(k, if (x == null) JSONObject().put("at", v.at).put("none", true)
                else JSONObject().put("at", v.at).put("id", x.id).put("name", x.name).put("days", x.days).put("runtime", x.runtimeMin ?: 0))
            }
            aired.forEach { (k, v) ->
                val x = v.value
                a.put(k, if (x == null) JSONObject().put("at", v.at).put("none", true)
                else JSONObject().put("at", v.at).put("season", x.season ?: 0).put("number", x.number ?: 0).put("runtime", x.runtimeMin ?: 0).put("count", x.count))
            }
            Store.writeJson(FILE, JSONObject().put("shows", s).put("aired", a))
        }
    }
}
