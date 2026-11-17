package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject

/**
 * The thinking half of the score bugs (Scores.kt does the asking): a feed's JSON and "now" in, the
 * games to show out. Nothing here touches the network, a clock or Android, so every rule can be
 * checked on a computer against recorded answers (app/src/test).
 *
 * MLB: the feed is MLB's own Stats API, checked in November 2026 against recorded games:
 *  - the schedule (one entry per game: start, status, teams, score, TV broadcasts). A postponed
 *    game says abstractGameState "Final" with codedGameState "D", and its gamePk comes back again
 *    on the day it is made up, so the two are kept apart here.
 *  - the game feed (status, inning, half, outs, runners, runs), asked with "fields" so one answer
 *    is about 500 bytes.
 * Every status word the API uses is listed at /api/v1/gameStatus; mlbState() below follows that list.
 *
 * NBA: ESPN's public scoreboard JSON (not the NBA's own feed, which refuses a plain request, and
 * not an official or documented one), checked in November 2026 against recorded games:
 *  - a team's entry, whose "nextEvent" is its next game (start, teams, broadcasts);
 *  - one event (status with period and clock, both scores, broadcasts, logo addresses).
 *
 * Another league later: its own parsing here, its detail beside "mlb" and "nba" in json(), and
 * one more League in Scores.kt. The window, the order and the channel matching are not about any
 * one league.
 */
object ScoreFeeds {
    const val PRE_MS = 60 * 60_000L          // a game shows from 60 minutes before its scheduled start
    const val POST_MS = 15 * 60_000L         // until 15 minutes after it went final
    const val KEEP_MS = 2 * 60_000L          // the last good reading is kept this long while the feed fails
    const val STALL_MS = 3 * 60 * 60_000L    // not started, or stopped by a delay: gone after 3 hours of nothing
    const val MAX_GAMES = 2

    /** side: "home" | "away" (whose broadcast it is). kind: "tv" | "stream" | "package" (a subscription such as NBA League Pass). */
    class Broadcast(val name: String, val callSign: String, val side: String, val national: Boolean, val kind: String = "tv") {
        /**
         * Not shown where a team's own channel has the game: "TBS (out-of-market only)" in MLB's
         * words, and an out-of-market package (NBA League Pass) in ESPN's list.
         */
        val outOfMarket get() = name.contains("out-of-market", ignoreCase = true) || kind == "package"
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
        // baseball
        val inning: Int = 0, val scheduledInnings: Int = 9, val half: String = "top",   // half: "top" | "bottom"
        val outs: Int = 0, val first: Boolean = false, val second: Boolean = false, val third: Boolean = false,
        // basketball: the period (1 to 4, then 5 for overtime, 6 for the second...), the clock as the
        // feed writes it and in seconds, and whether it is halftime
        val period: Int = 0, val clock: String = "", val clockSecs: Double = 0.0, val halftime: Boolean = false,
        // where the feed says each team's logo is (ESPN gives an address; MLB's is worked out from the team id)
        val awayLogo: String? = null, val homeLogo: String? = null,
    ) {
        /** League and the feed's game id. A postponed entry gets its own id: the game made up later reuses the gamePk. */
        val id get() = "$league-$pk" + if (off) "-off" else ""
        /** FINAL; baseball that did not end in its scheduled inning, F/10; basketball after overtime, F/OT, F/2OT. */
        val finalLabel get() = when {
            state != "final" -> null
            league == "nba" -> if (period <= 4) "FINAL" else if (period == 5) "F/OT" else "F/${period - 4}OT"
            else -> if (inning > 0 && inning != scheduledInnings) "F/$inning" else "FINAL"
        }
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

    // ---------------------------------------------------------------- NBA (ESPN's scoreboard)

    class NbaTeam(val id: Int, val abbr: String, val games: List<Game>)

    /**
     * ESPN's "teams/<abbreviation>": the team's id, and the game its "nextEvent" names (the next one
     * until about tip-off, then the one after). Null when ESPN has no such team: it answers {}.
     */
    fun nbaTeam(json: JSONObject): NbaTeam? {
        val t = json.optJSONObject("team") ?: return null
        val id = t.optString("id").toIntOrNull() ?: return null
        val out = ArrayList<Game>()
        val next = t.optJSONArray("nextEvent")
        if (next != null) for (i in 0 until next.length()) next.optJSONObject(i)?.let { e -> nbaEvent(e, id)?.let { out += it } }
        return NbaTeam(id, t.optString("abbreviation"), out)
    }

    class NbaState(val state: String, val status: String, val detail: String, val off: Boolean = false,
                   val suspended: Boolean = false, val halftime: Boolean = false)

    /**
     * ESPN's status in the bug's four states, from its type's "state" (pre, in, post) and "name".
     * Seen in November 2026: STATUS_SCHEDULED, STATUS_IN_PROGRESS, STATUS_END_PERIOD, STATUS_HALFTIME,
     * STATUS_FINAL, and STATUS_POSTPONED (state "post", not completed, no score). Other names are
     * taken by what they contain, as ESPN publishes no list. status is its "description"
     * ("In Progress", "Halftime", "Postponed"), detail its "detail" ("6:21 - 2nd Quarter", "Final/OT").
     */
    fun nbaState(status: JSONObject?): NbaState? {
        val type = status?.optJSONObject("type") ?: return null
        val name = type.optString("name").uppercase()
        val st = type.optString("state")
        val word = type.optString("description").trim()
        val detail = type.optString("detail").trim()
        val mk = { state: String, unnamed: String, off: Boolean, suspended: Boolean, half: Boolean ->
            NbaState(state, word.ifEmpty { unnamed }, detail.ifEmpty { word.ifEmpty { unnamed } }, off, suspended, half)
        }
        return when {
            "POSTPON" in name -> mk("delayed", "Postponed", true, false, false)
            "CANCEL" in name -> mk("delayed", "Canceled", true, false, false)
            "SUSPEND" in name -> mk("delayed", "Suspended", false, true, false)
            "DELAY" in name -> mk("delayed", "Delayed", false, false, false)
            "FINAL" in name || "FORFEIT" in name || (st == "post" && type.optBoolean("completed")) -> mk("final", "Final", false, false, false)
            st == "in" && "HALFTIME" in name -> mk("live", "Halftime", false, false, true)
            st == "in" -> mk("live", "In Progress", false, false, false)
            st == "pre" -> mk("pre", "Scheduled", false, false, false)
            // over without being played out, in words not seen yet: the feed's word, around the start time only
            st == "post" -> mk("delayed", "Off", true, false, false)
            else -> null
        }
    }

    /**
     * One ESPN event (an entry of "nextEvent", or the answer to scoreboard/<event id>) as a game
     * of the team with this id. Null when the team is not in it, the status is not understood, or
     * the game has no time yet (timeValid false).
     */
    fun nbaEvent(e: JSONObject, teamId: Int): Game? {
        val pk = e.optString("id").toLongOrNull() ?: return null
        val c = e.optJSONArray("competitions")?.optJSONObject(0) ?: return null
        val list = c.optJSONArray("competitors") ?: return null
        var away: JSONObject? = null; var home: JSONObject? = null
        for (i in 0 until list.length()) {
            val x = list.optJSONObject(i) ?: continue
            if (x.optString("homeAway") == "home") home = x else if (x.optString("homeAway") == "away") away = x
        }
        if (away == null || home == null) return null
        val awayTeam = away.optJSONObject("team"); val homeTeam = home.optJSONObject("team")
        val awayId = awayTeam?.optString("id")?.toIntOrNull() ?: 0
        val homeId = homeTeam?.optString("id")?.toIntOrNull() ?: 0
        if (awayId != teamId && homeId != teamId) return null
        val status = c.optJSONObject("status") ?: e.optJSONObject("status")
        val st = nbaState(status) ?: return null
        val start = parseIso(e.optString("date").ifEmpty { c.optString("date") }) ?: return null
        val noTime = (c.has("timeValid") && !c.optBoolean("timeValid")) || (e.has("timeValid") && !e.optBoolean("timeValid"))
        if (st.state == "pre" && noTime) return null
        val period = status?.optInt("period") ?: 0
        val secs = status?.optDouble("clock", 0.0)?.takeIf { !it.isNaN() } ?: 0.0
        // ESPN writes "0" for both scores before the game and for one that is off: no score then
        val played = st.state == "live" || st.state == "final" || (st.state == "delayed" && !st.off && period > 0)
        return Game(
            league = "nba", pk = pk, startMs = start,
            state = st.state, status = st.status, statusDetail = st.detail, off = st.off, suspended = st.suspended,
            ourSide = if (homeId == teamId) "home" else "away",
            awayAbbr = awayTeam?.optString("abbreviation").orEmpty(), awayId = awayId, awayScore = if (played) espnScore(away) else null,
            homeAbbr = homeTeam?.optString("abbreviation").orEmpty(), homeId = homeId, homeScore = if (played) espnScore(home) else null,
            broadcasts = espnBroadcasts(c),
            period = period, clock = nbaClock(status?.optString("displayClock").orEmpty(), secs), clockSecs = secs, halftime = st.halftime,
            awayLogo = espnLogoOf(awayTeam), homeLogo = espnLogoOf(homeTeam),
        )
    }

    /** A score as ESPN writes it in one answer or another: "41", 41, or {"value": 41.0, "displayValue": "41"}. */
    private fun espnScore(x: JSONObject): Int? = when (val s = x.opt("score")) {
        is Number -> s.toInt()
        is String -> s.trim().toIntOrNull()
        is JSONObject -> if (s.has("value")) s.optDouble("value").takeIf { !it.isNaN() }?.toInt() else s.optString("displayValue").trim().toIntOrNull()
        else -> null
    }

    /**
     * TV, streaming and packages (not radio), each name once, in the feed's order. ESPN lists them
     * two ways: {"market": "home", "names": [...]}, and one object each with type, market and
     * media. The second says more (TV or streaming), so "geoBroadcasts" is read when it is there.
     */
    private fun espnBroadcasts(c: JSONObject): List<Broadcast> {
        val out = ArrayList<Broadcast>()
        fun add(name: String, market: String, type: String) {
            val n = name.trim(); val m = market.lowercase(); val k = type.lowercase()
            if (n.isEmpty() || k == "radio" || out.any { it.name == n }) return
            out += Broadcast(n, "", if (m == "home" || m == "away") m else "", m == "national",
                if (k.startsWith("stream")) "stream" else if (k.contains("package")) "package" else "tv")
        }
        val geo = c.optJSONArray("geoBroadcasts")
        val list = if (geo != null && geo.length() > 0) geo else c.optJSONArray("broadcasts")
        if (list != null) for (i in 0 until list.length()) {
            val b = list.optJSONObject(i) ?: continue
            val names = b.optJSONArray("names")
            if (names != null) for (k in 0 until names.length()) add(names.optString(k), b.optString("market"), "")
            else add(b.optJSONObject("media")?.optString("shortName").orEmpty(), b.optJSONObject("market")?.optString("type").orEmpty(),
                b.optJSONObject("type")?.optString("shortName").orEmpty())
        }
        return out
    }

    /**
     * Where ESPN says a logo is, from a team object or the league's: the one drawn for a dark
     * background when it names one ("logoDark", or a "logos" entry marked dark), else the usual one.
     */
    fun espnLogoOf(o: JSONObject?): String? {
        if (o == null) return null
        o.optString("logoDark").takeIf { it.isNotEmpty() }?.let { return it }
        var dark: String? = null; var usual: String? = null
        val logos = o.optJSONArray("logos")
        if (logos != null) for (i in 0 until logos.length()) {
            val l = logos.optJSONObject(i) ?: continue
            val href = l.optString("href").takeIf { it.isNotEmpty() } ?: continue
            val rel = l.optJSONArray("rel")?.let { r -> (0 until r.length()).map { r.optString(it) } } ?: emptyList()
            if ("dark" in rel && dark == null) dark = href
            if ("default" in rel && usual == null) usual = href
        }
        return dark ?: o.optString("logo").takeIf { it.isNotEmpty() } ?: usual
    }

    /**
     * An ESPN logo address, asked at a modest size through ESPN's own resizer (its dark league
     * logo already comes that way, at 500 px; a team's 500 px picture is 60 KB, at 200 px 16 KB).
     * Only https addresses on espncdn.com are used at all: null for anything else. An address that
     * is not one of its /i/... pictures is used as it is.
     */
    fun espnLogo(href: String?, px: Int): String? {
        val h = href?.trim().orEmpty()
        val m = Regex("""^https://(?:[a-z0-9-]+\.)*espncdn\.com(/[^?#]*)(?:\?([^#]*))?$""").find(h) ?: return null
        val path = m.groupValues[1]
        val img = when {
            path == "/combiner/i" -> m.groupValues[2].split('&').firstOrNull { it.startsWith("img=") }?.removePrefix("img=")
            path.startsWith("/i/") -> path
            else -> null
        }
        if (img == null || !Regex("""/i/[A-Za-z0-9/_.-]+\.png""").matches(img)) return h
        return "https://a.espncdn.com/combiner/i?img=$img&w=$px&h=$px&transparent=true"
    }

    const val FOLLOW_DONE_MS = 30 * 60_000L          // a game that is over is read this much longer (its bug stays 15 minutes)
    const val FOLLOW_GIVE_UP_MS = 10 * 60 * 60_000L  // nobody follows a game more than 10 hours past its start

    /**
     * ESPN's team entry names a game only until about tip-off, so a game seen there is followed
     * by its own id afterwards. At each 15 minute look, for a followed game the entry no longer
     * names: "wait" (its hour has not come: 60 minutes before the start), "read" it by its id, or
     * "forget" it (30 minutes after it was seen over, or 10 hours past its start whatever happened).
     */
    fun nbaFollow(start: Long, doneAt: Long, now: Long): String = when {
        (doneAt > 0 && now > doneAt + FOLLOW_DONE_MS) || now > start + FOLLOW_GIVE_UP_MS -> "forget"
        now < start - PRE_MS -> "wait"
        else -> "read"
    }

    /**
     * When a followed game counts as over, for the 30 minutes above: now, the first time it is seen
     * final or suspended; for one that is off (postponed), not before the time it was to start, as
     * its word shows until 15 minutes after that. 0 while it is still to be played or being played.
     */
    fun nbaDoneAt(g: Game, now: Long): Long = when {
        g.off -> maxOf(now, g.startMs)
        g.state == "final" || g.suspended -> now
        else -> 0L
    }

    /** "1ST" to "4TH", then "OT", "2OT", "3OT"...; nothing before the game. */
    fun nbaPeriod(period: Int): String = when {
        period <= 0 -> ""
        period == 1 -> "1ST"; period == 2 -> "2ND"; period == 3 -> "3RD"; period == 4 -> "4TH"
        period == 5 -> "OT"
        else -> "${period - 4}OT"
    }

    /** How much of the period is left, 0 to 1: a quarter is 12 minutes, an overtime 5. */
    fun nbaRemaining(period: Int, secs: Double): Double = (secs / (if (period <= 4) 720.0 else 300.0)).coerceIn(0.0, 1.0)

    /** The clock as the feed writes it ("6:21", and "45.3" inside the last minute); "0:00" where it writes "0.0". */
    fun nbaClock(display: String, secs: Double): String {
        val d = display.trim()
        if (secs <= 0.0) return "0:00"
        if (d.isNotEmpty() && d != "0.0") return d
        val whole = secs.toInt()
        return "%d:%02d".format(java.util.Locale.US, whole / 60, whole % 60)
    }

    // ---------------------------------------------------------------- logo files

    /** Width and height from a PNG's own header, or null when the bytes are not a PNG. */
    fun pngSize(b: ByteArray): Pair<Int, Int>? {
        val sig = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        if (b.size < 24 || sig.indices.any { (b[it].toInt() and 0xFF) != sig[it] }) return null
        if (String(b, 12, 4, Charsets.US_ASCII) != "IHDR") return null
        val int = { at: Int -> ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF) }
        return int(16) to int(20)
    }

    /**
     * Is a downloaded logo worth keeping? "svg": a real, plain drawing, never an error page, never
     * one that carries a script. "png": the PNG signature and header, at most 1024 px a side.
     * Either: 200 KB at most.
     */
    fun picture(ext: String, bytes: ByteArray): Boolean = when (ext) {
        "svg" -> {
            val text = String(bytes, Charsets.UTF_8).trimStart()
            bytes.size in 50..200_000 && (text.startsWith("<svg") || text.startsWith("<?xml")) && !text.contains("<script", ignoreCase = true)
        }
        "png" -> bytes.size in 67..200_000 && pngSize(bytes)?.let { (w, h) -> w in 1..1024 && h in 1..1024 } == true
        else -> false
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
        // Basketball: the period's label, the clock, and how much of the period is left. The clock
        // runs only while a period is being played: 0 left at halftime (where the feed can leave
        // "0.1" standing), at the end of a period and after the game (where it leaves "12:00" or
        // "5:00").
        if (g.league == "nba") {
            val playing = g.state == "live" && !g.halftime
            o.put("nba", JSONObject().put("period", nbaPeriod(g.period))
                .put("clock", if (g.state == "final" || (g.state == "live" && g.halftime)) "0:00" else if (on) g.clock else "")
                .put("remaining", if (playing) Math.round(nbaRemaining(g.period, g.clockSecs) * 1000.0) / 1000.0 else 0.0)
                .put("halftime", g.state == "live" && g.halftime))
        }
        return o
    }
}
