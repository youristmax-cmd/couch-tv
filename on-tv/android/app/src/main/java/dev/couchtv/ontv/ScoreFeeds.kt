package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject

/**
 * The thinking half of the score bugs (Scores.kt does the asking): a feed's JSON and "now" in, the
 * games to show out. Nothing here touches the network, a clock or Android, so every rule can be
 * checked on a computer against recorded answers (app/src/test).
 *
 * MLB only for now. The feed is MLB's Stats API, checked in November 2026 against recorded games:
 *  - the schedule (one entry per game: start, status, teams, score, TV broadcasts). A postponed
 *    game says abstractGameState "Final" with codedGameState "D", and its gamePk comes back again
 *    on the day it is made up, so the two are kept apart here.
 *  - the game feed (status, inning, half, outs, runners, runs), asked with "fields" so one answer
 *    is about 500 bytes.
 * Every status word the API uses is listed at /api/v1/gameStatus; mlbState() below follows that list.
 *
 * Another league later: its own parsing here (as mlbSchedule and mlbFeed are), its detail beside
 * "mlb" in json(), and one more League in Scores.kt. The window, the order and the channel
 * matching are not about any one league.
 */
object ScoreFeeds {
    const val PRE_MS = 60 * 60_000L          // a game shows from 60 minutes before its scheduled start
    const val POST_MS = 15 * 60_000L         // until 15 minutes after it went final
    const val KEEP_MS = 2 * 60_000L          // the last good reading is kept this long while the feed fails
    const val STALL_MS = 3 * 60 * 60_000L    // not started, or stopped by a delay: gone after 3 hours of nothing
    const val MAX_GAMES = 2

    class Broadcast(val name: String, val callSign: String, val side: String, val national: Boolean) {
        /** "TBS (out-of-market only)": the feed's way of saying it is not shown where a team's own channel has the game. */
        val outOfMarket get() = name.contains("out-of-market", ignoreCase = true)
    }

    class Channel(val name: String, val number: Int, val link: String, val app: String, val aliases: List<String>) {
        /** The owner has put something in: a channel number, a link or an app. */
        val filled get() = number > 0 || link.isNotEmpty() || app.isNotEmpty()
    }

    class Game(
        val league: String, val pk: Long, val startMs: Long,
        val state: String,               // pre | live | final | delayed
        val status: String,              // the feed's own word, short: "In Progress", "Delayed", "Postponed"...
        val statusDetail: String,        // all of it: "Delayed: Rain", "Postponed: Inclement Weather"
        val off: Boolean,                // postponed or cancelled: it will not be played at this time
        val suspended: Boolean,          // stopped, to be finished another day
        val ourSide: String,             // "away" or "home": where the configured team is
        val awayAbbr: String, val awayId: Int, val awayScore: Int?,
        val homeAbbr: String, val homeId: Int, val homeScore: Int?,
        val broadcasts: List<Broadcast>,
        val inning: Int, val scheduledInnings: Int, val half: String,   // half: "top" | "bottom"
        val outs: Int, val first: Boolean, val second: Boolean, val third: Boolean,
    ) {
        /** League and the feed's game id. A postponed entry gets its own id: the game made up later reuses the gamePk. */
        val id get() = "$league-$pk" + if (off) "-off" else ""
        val finalLabel get() = if (state != "final") null else if (inning > 0 && inning != scheduledInnings) "F/$inning" else "FINAL"
    }

    /** What the app remembers about a game between readings (when it first saw it final, or stopped). */
    class Track(val finalAt: Long? = null, val stoppedAt: Long? = null)

    // ---------------------------------------------------------------- time

    /** "2026-09-27T19:05:00Z" (also with .123, +00:00 or -07:00) to milliseconds since 1970. Null when it is not a time. */
    fun parseIso(s: String?): Long? {
        val m = Regex("""^(\d{4})-(\d{2})-(\d{2})T(\d{2}):(\d{2})(?::(\d{2})(?:\.\d+)?)?(Z|[+-]\d{2}:?\d{2})?$""").find(s?.trim().orEmpty()) ?: return null
        val (y, mo, d, h, mi) = m.destructured
        val sec = m.groupValues[6].toIntOrNull() ?: 0
        val zone = m.groupValues[7]
        var offMin = 0
        if (zone.isNotEmpty() && zone != "Z") {
            val digits = zone.drop(1).replace(":", "")
            offMin = (digits.take(2).toInt() * 60 + digits.drop(2).toInt()) * if (zone[0] == '-') -1 else 1
        }
        if (mo.toInt() !in 1..12 || d.toInt() !in 1..31 || h.toInt() > 23 || mi.toInt() > 59 || sec > 60) return null
        return (days(y.toInt(), mo.toInt(), d.toInt()) * 86_400L + h.toInt() * 3600L + mi.toInt() * 60L + sec - offMin * 60L) * 1000L
    }

    /** Days from 1970-01-01 to a date (the usual civil calendar sum; no time zone, no library). */
    private fun days(y0: Int, m: Int, d: Int): Long {
        val y = if (m <= 2) y0 - 1 else y0
        val era = Math.floorDiv(y, 400)
        val yoe = y - era * 400
        val doy = (153 * (m + (if (m > 2) -3 else 9)) + 2) / 5 + d - 1
        val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
        return era * 146_097L + doe - 719_468L
    }

    /** Milliseconds since 1970 to "2026-09-27T19:05:00Z". */
    fun isoUtc(ms: Long): String {
        val secs = Math.floorDiv(ms, 1000L)
        val z = Math.floorDiv(secs, 86_400L) + 719_468L
        val sod = Math.floorMod(secs, 86_400L).toInt()
        val era = Math.floorDiv(z, 146_097L)
        val doe = (z - era * 146_097L).toInt()
        val yoe = (doe - doe / 1460 + doe / 36_524 - doe / 146_096) / 365
        val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
        val mp = (5 * doy + 2) / 153
        val d = doy - (153 * mp + 2) / 5 + 1
        val m = if (mp < 10) mp + 3 else mp - 9
        val y = yoe + era * 400 + if (m <= 2) 1 else 0
        return "%04d-%02d-%02dT%02d:%02d:%02dZ".format(java.util.Locale.US, y, m, d, sod / 3600, sod % 3600 / 60, sod % 60)
    }

    /** The calendar day, UTC, as the schedule wants it: "2026-09-27". */
    fun dayUtc(ms: Long) = isoUtc(ms).take(10)

    // ---------------------------------------------------------------- MLB

    /** A team's id from the league's own team list (/api/v1/teams), by its abbreviation: "LAD" to 119. */
    fun mlbTeamId(teams: JSONObject, abbr: String): Int? {
        val list = teams.optJSONArray("teams") ?: return null
        for (i in 0 until list.length()) {
            val t = list.optJSONObject(i) ?: continue
            if (t.optString("abbreviation").equals(abbr.trim(), ignoreCase = true) && t.optInt("id") > 0) return t.optInt("id")
        }
        return null
    }

    class MlbState(val state: String, val status: String, val detail: String, val off: Boolean = false, val suspended: Boolean = false)

    /**
     * The feed's status in the bug's four states. Null when the feed itself does not know
     * ("Writing", "Unknown"): the caller keeps what it had.
     *  - coded D (postponed) and C (cancelled) come with abstractGameState "Final" and no score:
     *    they are "delayed" with the feed's word, never a final.
     *  - coded T and U: suspended, to be finished another day.
     *  - "Delayed..." while live keeps the score; "Delayed Start..." has none yet.
     *  - Warmup is live to the feed but nothing has been played: still pre-game.
     * status is the feed's word up to any colon ("Delayed", "Postponed": short enough for the bug);
     * detail is all of it, with the reason when the feed gives one ("Delayed: Rain").
     */
    fun mlbState(status: JSONObject?): MlbState? {
        val abs = status?.optString("abstractGameState").orEmpty()
        val coded = status?.optString("codedGameState").orEmpty()
        val code = status?.optString("statusCode").orEmpty()
        val word = status?.optString("detailedState").orEmpty().trim()
        val reason = status?.optString("reason").orEmpty().trim()
        val mk = { state: String, unnamed: String, off: Boolean, suspended: Boolean ->
            val short = word.substringBefore(':').trim().ifEmpty { unnamed }
            // "Postponed" with the reason beside it in the schedule, "Postponed: Rain" elsewhere: one way of saying it
            val all = if (word.isEmpty()) unnamed else if (reason.isNotEmpty() && ':' !in word) "$word: $reason" else word
            MlbState(state, short, all, off, suspended)
        }
        return when {
            coded == "D" || coded == "C" -> mk("delayed", if (coded == "C") "Cancelled" else "Postponed", true, false)
            abs == "Live" && (coded == "T" || coded == "U") -> mk("delayed", "Suspended", false, true)
            abs == "Final" -> mk("final", "Final", false, false)
            abs == "Live" && code == "PW" -> mk("pre", "Warmup", false, false)
            abs == "Live" && word.startsWith("Delayed") -> mk("delayed", "Delayed", false, false)
            abs == "Live" -> mk("live", "In Progress", false, false)
            abs == "Preview" && word.startsWith("Delayed") -> mk("delayed", "Delayed Start", false, false)
            abs == "Preview" -> mk("pre", "Scheduled", false, false)
            else -> null
        }
    }

    /**
     * Every game of one team in a schedule answer. A game with no start time yet (startTimeTBD) or
     * a status the feed does not know is left out.
     */
    fun mlbSchedule(json: JSONObject, teamId: Int): List<Game> {
        val out = ArrayList<Game>()
        val dates = json.optJSONArray("dates") ?: return out
        for (i in 0 until dates.length()) {
            val games = dates.optJSONObject(i)?.optJSONArray("games") ?: continue
            for (k in 0 until games.length()) {
                val g = games.optJSONObject(k) ?: continue
                val teams = g.optJSONObject("teams") ?: continue
                val away = teams.optJSONObject("away") ?: continue
                val home = teams.optJSONObject("home") ?: continue
                val awayId = away.optJSONObject("team")?.optInt("id") ?: 0
                val homeId = home.optJSONObject("team")?.optInt("id") ?: 0
                if (awayId != teamId && homeId != teamId) continue
                val st = mlbState(g.optJSONObject("status")) ?: continue
                val start = parseIso(g.optString("gameDate")) ?: continue
                if (g.optJSONObject("status")?.optBoolean("startTimeTBD") == true && st.state == "pre") continue
                val ls = g.optJSONObject("linescore")
                val played = st.state == "live" || st.state == "final" || (st.state == "delayed" && !st.off && away.has("score"))
                out += Game(
                    league = "mlb", pk = g.optLong("gamePk"), startMs = start,
                    state = st.state, status = st.status, statusDetail = st.detail, off = st.off, suspended = st.suspended,
                    ourSide = if (homeId == teamId) "home" else "away",
                    awayAbbr = away.optJSONObject("team")?.optString("abbreviation").orEmpty(), awayId = awayId,
                    awayScore = if (played && away.has("score")) away.optInt("score") else null,
                    homeAbbr = home.optJSONObject("team")?.optString("abbreviation").orEmpty(), homeId = homeId,
                    homeScore = if (played && home.has("score")) home.optInt("score") else null,
                    broadcasts = mlbBroadcasts(g.optJSONArray("broadcasts")),
                    inning = ls?.optInt("currentInning") ?: 0,
                    scheduledInnings = (ls?.optInt("scheduledInnings") ?: 0).takeIf { it > 0 } ?: g.optInt("scheduledInnings", 9),
                    half = half(ls), outs = ls?.optInt("outs") ?: 0,
                    first = ls?.optJSONObject("offense")?.has("first") == true,
                    second = ls?.optJSONObject("offense")?.has("second") == true,
                    third = ls?.optJSONObject("offense")?.has("third") == true,
                )
            }
        }
        return out
    }

    /** TV only (the feed lists radio too), each name once, in the feed's order. */
    private fun mlbBroadcasts(list: JSONArray?): List<Broadcast> {
        val out = ArrayList<Broadcast>()
        if (list == null) return out
        for (i in 0 until list.length()) {
            val b = list.optJSONObject(i) ?: continue
            val name = b.optString("name").trim()
            if (b.optString("type") != "TV" || name.isEmpty() || out.any { it.name == name }) continue
            out += Broadcast(name, b.optString("callSign").trim(), b.optString("homeAway"), b.optBoolean("isNational"))
        }
        return out
    }

    private fun half(ls: JSONObject?): String = when {
        ls == null -> "top"
        ls.has("isTopInning") -> if (ls.optBoolean("isTopInning")) "top" else "bottom"
        else -> if (ls.optString("inningHalf").startsWith("B", ignoreCase = true)) "bottom" else "top"
    }

    /**
     * A game feed answer laid over what the schedule said about the same game: the newer status,
     * inning, outs, runners and runs. The teams and the broadcasts stay the schedule's. An answer
     * for another game, or one whose status the feed does not know, changes nothing.
     */
    fun mlbFeed(json: JSONObject, was: Game): Game {
        if (json.optLong("gamePk") != was.pk) return was
        val gd = json.optJSONObject("gameData")
        val st = mlbState(gd?.optJSONObject("status")) ?: return was
        val ls = json.optJSONObject("liveData")?.optJSONObject("linescore")
        val runs = { side: String -> ls?.optJSONObject("teams")?.optJSONObject(side)?.let { if (it.has("runs")) it.optInt("runs") else null } }
        val played = st.state == "live" || st.state == "final" || (st.state == "delayed" && !st.off && (ls?.optInt("currentInning") ?: 0) > 0 && gd?.optJSONObject("status")?.optString("abstractGameState") == "Live")
        return Game(
            league = was.league, pk = was.pk,
            startMs = parseIso(gd?.optJSONObject("datetime")?.optString("dateTime")) ?: was.startMs,
            state = st.state, status = st.status, statusDetail = st.detail, off = st.off, suspended = st.suspended, ourSide = was.ourSide,
            awayAbbr = was.awayAbbr, awayId = was.awayId, awayScore = if (played) runs("away") ?: was.awayScore else null,
            homeAbbr = was.homeAbbr, homeId = was.homeId, homeScore = if (played) runs("home") ?: was.homeScore else null,
            broadcasts = was.broadcasts,
            inning = ls?.optInt("currentInning") ?: was.inning,
            scheduledInnings = (ls?.optInt("scheduledInnings") ?: 0).takeIf { it > 0 } ?: was.scheduledInnings,
            half = if (ls != null) half(ls) else was.half, outs = ls?.optInt("outs") ?: was.outs,
            first = ls?.optJSONObject("offense")?.has("first") == true,
            second = ls?.optJSONObject("offense")?.has("second") == true,
            third = ls?.optJSONObject("offense")?.has("third") == true,
        )
    }

    // ---------------------------------------------------------------- which games show, and in what order

    /**
     * What to remember about a game now that this reading is in: the first moment it was final, or
     * stopped. `was` is null the first time the app hears of the game: a game that is already over
     * (or already suspended) then gets time 0, "nobody knows when", and so never shows, however
     * many readings follow.
     */
    fun track(g: Game, was: Track?, now: Long): Track = Track(
        finalAt = if (g.state == "final") was?.finalAt ?: (if (was == null) 0L else now) else null,
        stoppedAt = if (g.state == "delayed" && !g.off) was?.stoppedAt ?: (if (was == null && g.suspended) 0L else now) else null,
    )

    /**
     * Is this game on screen now?
     *  - postponed or cancelled: from 60 minutes before the time it was to start until 15 minutes
     *    after it, so the reason is seen and then it goes.
     *  - final: for 15 minutes from when the app saw it end. A game that was already over when the
     *    app first heard of it does not show (nobody knows when it ended).
     *  - suspended: like a final, for 15 minutes.
     *  - being played: always.
     *  - delayed, or not started: from 60 minutes before the start, but never more than 3 hours of
     *    nothing happening.
     */
    fun showing(g: Game, t: Track?, now: Long): Boolean = when {
        g.off -> now >= g.startMs - PRE_MS && now < g.startMs + POST_MS
        g.state == "final" -> t?.finalAt != null && now < t.finalAt + POST_MS
        g.suspended -> t?.stoppedAt != null && now < t.stoppedAt + POST_MS
        g.state == "live" -> true
        g.state == "delayed" -> now >= g.startMs - PRE_MS && now < maxOf(g.startMs, t?.stoppedAt ?: 0L) + STALL_MS
        else -> now >= g.startMs - PRE_MS && now < g.startMs + STALL_MS
    }

    /** The games to send: a live one first, then delayed, then the next to start, then a final. Two at most. */
    fun order(games: List<Game>): List<Game> {
        val rank = { g: Game -> when (g.state) { "live" -> 0; "delayed" -> 1; "pre" -> 2; else -> 3 } }
        return games.sortedWith(compareBy<Game>({ rank(it) }, { it.startMs }, { it.pk })).take(MAX_GAMES)
    }

    /**
     * Is a game's last good reading still good enough to show? Always while its feed answers; once
     * it fails, for 2 minutes from that last good reading. So an old score is never shown as new.
     */
    fun keep(goodAt: Long, failing: Boolean, now: Long): Boolean = !failing || now - goodAt <= KEEP_MS

    /**
     * How long to wait after this many failures in a row: the usual pause after the first, then
     * doubled each time, 5 minutes at most (never less than the usual pause).
     */
    fun backOff(failures: Int, usual: Long): Long =
        if (failures <= 1) usual else minOf(usual * (1L shl minOf(failures - 1, 6)), 5 * 60_000L).coerceAtLeast(usual)

    // ---------------------------------------------------------------- channels

    /** Compared without case, spaces or punctuation: "Sports-Net LA" is "sportsnetla". */
    fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    /** The table from home.scores.channels: {"SportsNet LA": {"number": 690, "link": "", "aliases": [...], "app": ""}}. */
    fun channels(table: JSONObject?): List<Channel> {
        val out = ArrayList<Channel>()
        if (table == null) return out
        for (name in table.keys()) {
            val c = table.optJSONObject(name) ?: continue
            val al = c.optJSONArray("aliases")
            out += Channel(name.trim(), c.optInt("number", 0).coerceAtLeast(0), c.optString("link").trim(), c.optString("app").trim(),
                if (al == null) emptyList() else (0 until al.length()).map { al.optString(it).trim() }.filter { it.isNotEmpty() })
        }
        return out
    }

    /**
     * The names one broadcast goes by: its name, its call sign, the name without a remark in
     * brackets ("TBS (out-of-market only)"), and each part of a shared one ("NBC/Peacock").
     */
    private fun names(b: Broadcast): Set<String> {
        val plain = b.name.replace(Regex("""\([^)]*\)"""), " ")
        return (listOf(b.name, b.callSign, plain) + plain.split('/')).map { norm(it) }.filter { it.isNotEmpty() }.toSet()
    }

    /**
     * How well a broadcast suits the household of the configured team, best first: the team's own
     * channel; a national one listed for the team's side; a national one listed for the other
     * side; one the feed marks "out-of-market only"; the other team's own channel.
     */
    fun place(g: Game, b: Broadcast): Int = when {
        b.outOfMarket -> 3
        b.side == g.ourSide && !b.national -> 0
        b.national -> if (b.side == g.ourSide) 1 else 2
        else -> 4
    }

    /**
     * The channel to open for a game, or null when the table has none of its broadcasts. A channel
     * the owner has filled in (a number, a link or an app) beats one that is only named; after
     * that, the order of place() above.
     */
    fun channel(g: Game, table: List<Channel>): Channel? {
        var best: Channel? = null
        var bestRank = Int.MAX_VALUE
        for (b in g.broadcasts) {
            val ns = names(b)
            val c = table.firstOrNull { ch -> (listOf(ch.name) + ch.aliases).any { norm(it) in ns } } ?: continue
            val rank = (if (c.filled) 0 else 10) + place(g, b)
            if (rank < bestRank) { best = c; bestRank = rank }
        }
        return best
    }

    // ---------------------------------------------------------------- the answer's shape

    /** One game as GET /api/scores sends it (bridge/API.md). The logos are paths the app serves, or null. */
    fun json(g: Game, table: List<Channel>, leagueLogo: String?, awayLogo: String?, homeLogo: String?): JSONObject {
        // the names as the feed gives them, the most useful to this household first
        val names = JSONArray(); g.broadcasts.sortedBy { place(g, it) }.forEach { names.put(it.name) }
        val ch = channel(g, table)
        val team = { abbr: String, score: Int?, logo: String? ->
            JSONObject().put("abbr", abbr).put("score", score ?: JSONObject.NULL).put("logo", logo ?: JSONObject.NULL)
        }
        val o = JSONObject().put("id", g.id).put("league", g.league).put("league_name", g.league.uppercase())
            .put("league_logo", leagueLogo ?: JSONObject.NULL)
            .put("state", g.state).put("status", g.status).put("status_detail", g.statusDetail).put("start", isoUtc(g.startMs))
            .put("away", team(g.awayAbbr, g.awayScore, awayLogo)).put("home", team(g.homeAbbr, g.homeScore, homeLogo))
            .put("broadcasts", names)
            .put("channel", if (ch == null) JSONObject.NULL else JSONObject().put("name", ch.name).put("number", ch.number)
                .put("link", ch.link).put("app", if (ch.app.isEmpty()) JSONObject.NULL else ch.app))
        g.finalLabel?.let { o.put("final_label", it) }
        // Outs and runners only mean something while the game is on (or stopped part-way). The feed
        // leaves the last ones standing after the final out, so they are cleared here.
        val on = g.state == "live" || (g.state == "delayed" && g.awayScore != null)
        if (g.league == "mlb") o.put("mlb", JSONObject().put("inning", g.inning).put("half", g.half).put("outs", if (on) g.outs.coerceIn(0, 3) else 0)
            .put("first", on && g.first).put("second", on && g.second).put("third", on && g.third))
        return o
    }
}
