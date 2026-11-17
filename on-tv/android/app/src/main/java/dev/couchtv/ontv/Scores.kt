package dev.couchtv.ontv

import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.locks.ReentrantLock

/**
 * Game-day score bugs for the home (tv-home/web/scores.js): the configured teams' games, from
 * 60 minutes before the start until 15 minutes after the end. This file does the asking and the
 * timing; every rule about what a feed's answer means is in ScoreFeeds.kt.
 *
 * MLB: the league's own Stats API (statsapi.mlb.com), which needs no key. Its notice allows
 * "individual, non-commercial, non-bulk use" (http://gdx.mlb.com/components/copyright.txt). What is
 * asked, and how often:
 *  - the team list, once a day at most (1 KB): "LAD" to the team's id, so a new team is only a
 *    change of settings.
 *  - the team's schedule for yesterday, today and tomorrow, every 15 minutes, always (about 1.3 KB
 *    a game). This is the only work while no game is near.
 *  - the game's own feed (about 500 bytes): every 60 s before the game and every 15 s during it,
 *    and only while the home has asked GET /api/scores in the last 90 s.
 *  - the logos, once each, from MLB's own addresses into this app's cache folder. They are never
 *    part of the app or the repository.
 *
 * NBA: not here. In November 2026 the NBA's scoreboard file (cdn.nba.com) answered a plain request
 * without a key with 403 Access Denied, so nothing is built on it and no other feed stands in for
 * it. A team set to "nba" shows no game, and the answer says why in "problem". docs/SCORES.md.
 *
 * Another league later is one more League below (where its games, one game's newer reading and its
 * logos come from) and its rules in ScoreFeeds.kt. The timing, the window, the channels, the logo
 * cache and the routes do not change.
 *
 * A failing feed: a game's own feed is tried again at its usual pace, then half as often each time
 * (5 minutes at most); the schedule after 30 s, 1 min, 2 min... The last good reading of a game is
 * shown for 2 minutes, then the game is left out until a reading works.
 */
object Scores {
    private const val TAG = "couchtv.scores"
    private const val K_CONFIG = "scores"                       // {"teams": [...], "channels": {...}, "tv_app": "..."}
    private const val AGENT = "Mozilla/5.0 (compatible; tv-home-scores/1.0)"   // says nothing about the household
    private const val DEFAULT_TV_APP = "com.att.tv"             // DirecTV on a Fire TV
    private const val SCHEDULE_MS = 15 * 60_000L
    private const val PRE_MS = 60_000L
    private const val LIVE_MS = 15_000L
    private const val ASKED_MS = 90_000L
    private const val TEAMS_MS = 24 * 60 * 60_000L
    private const val MAX_BYTES = 256 * 1024

    // ---------------------------------------------------------------- the leagues

    /** The games a league's feed has for one team. games is null when the feed did not answer; note says what is wrong with the team. */
    private class Found(val games: List<ScoreFeeds.Game>?, val note: String? = null)

    private interface League {
        /** The team's games for yesterday, today and tomorrow. */
        fun games(team: String, t: Long): Found
        /** A newer reading of one game from its own feed, or null when the feed did not answer. quick: give up sooner. */
        fun reading(g: ScoreFeeds.Game, quick: Boolean): ScoreFeeds.Game?
        /** Where the league keeps its own picture for "league" or for a team id, drawn for a dark background. */
        fun logoUrl(what: String): String
    }

    private val leagues: Map<String, League> = mapOf("mlb" to Mlb)

    /** A league somebody will ask for that is not here, and why, in plain words (it goes to "problem"). */
    private val notHere = mapOf(
        "nba" to "NBA scores are not available: the NBA's scoreboard refuses a plain request without a key, so only MLB games show (docs/SCORES.md)")

    private object Mlb : League {
        private const val API = "https://statsapi.mlb.com/api"
        private const val LOGOS = "https://www.mlbstatic.com/team-logos"
        private const val SCHEDULE_FIELDS = "dates,date,games,gamePk,gameDate,status,abstractGameState,detailedState,statusCode," +
            "codedGameState,reason,startTimeTBD,teams,away,home,team,id,abbreviation,score,linescore,currentInning,inningHalf," +
            "isTopInning,outs,scheduledInnings,offense,first,second,third,broadcasts,name,type,homeAway,isNational,callSign"
        private const val FEED_FIELDS = "gamePk,gameData,status,abstractGameState,detailedState,statusCode,codedGameState,reason," +
            "datetime,dateTime,liveData,linescore,currentInning,inningHalf,isTopInning,outs,offense,first,second,third,teams," +
            "away,home,runs,scheduledInnings"
        private val ids = ConcurrentHashMap<String, Int>()

        override fun games(team: String, t: Long): Found {
            val id = teamId(team) ?: return if (listFailed) Found(null) else Found(emptyList(), "MLB has no team called $team")
            val r = get("$API/v1/schedule?sportId=1&teamId=$id&startDate=${ScoreFeeds.dayUtc(t - 86_400_000L)}&endDate=${ScoreFeeds.dayUtc(t + 86_400_000L)}" +
                "&hydrate=broadcasts(all),linescore,team&fields=$SCHEDULE_FIELDS")
            val j = (if (r.status == 200) runCatching { JSONObject(r.body) }.getOrNull() else null)
                ?: return Found(null).also { Log.w(TAG, "schedule $team: HTTP ${r.status}") }
            return Found(ScoreFeeds.mlbSchedule(j, id))
        }

        override fun reading(g: ScoreFeeds.Game, quick: Boolean): ScoreFeeds.Game? {
            val r = get("$API/v1.1/game/${g.pk}/feed/live?fields=$FEED_FIELDS", if (quick) 1500 else 4000, if (quick) 2000 else 6000)
            val j = (if (r.status == 200) runCatching { JSONObject(r.body) }.getOrNull() else null)?.takeIf { it.optLong("gamePk") == g.pk }
                ?: return null.also { Log.w(TAG, "game ${g.pk}: HTTP ${r.status}") }
            return ScoreFeeds.mlbFeed(j, g)
        }

        override fun logoUrl(what: String) = if (what == "league") "$LOGOS/league-on-dark/1.svg" else "$LOGOS/team-cap-on-dark/$what.svg"

        @Volatile private var listFailed = false

        /** "LAD" to 119, from MLB's own team list: kept in the app's files, asked again after a day. */
        private fun teamId(abbr: String): Int? {
            ids[abbr]?.let { return it }
            var list = Store.readJson("scores-mlb-teams.json") as? JSONObject
            if (list == null || now() - list.optLong("_at") > TEAMS_MS) {
                val r = get("$API/v1/teams?sportId=1&fields=teams,id,abbreviation")
                val j = if (r.status == 200) runCatching { JSONObject(r.body) }.getOrNull() else null
                // the list did not come: the one from before still does, however old; with none, the schedule waits
                listFailed = j?.optJSONArray("teams") == null && list == null
                if (j?.optJSONArray("teams") != null) { list = j.put("_at", now()); Store.writeJson("scores-mlb-teams.json", list) }
            } else listFailed = false
            return list?.let { ScoreFeeds.mlbTeamId(it, abbr) }?.also { ids[abbr] = it }
        }
    }

    // ---------------------------------------------------------------- what is kept

    /** One game the app is following. */
    private class Entry(@Volatile var game: ScoreFeeds.Game) {
        @Volatile var track: ScoreFeeds.Track? = null
        @Volatile var goodAt = 0L          // the last reading that worked
        @Volatile var failing = false
        @Volatile var failures = 0
        @Volatile var nextAt = 0L          // no feed reading before this
    }

    private val entries = ConcurrentHashMap<String, Entry>()
    private val lock = Object()                 // the loop sleeps on it; a question or new settings wake it
    private val reading = ReentrantLock()       // one feed reading at a time
    private var thread: Thread? = null
    @Volatile private var askedAt = 0L
    @Volatile private var poked = false         // a question or new settings came while the loop was busy
    @Volatile private var nextScheduleAt = 0L
    @Volatile private var scheduleFailures = 0
    @Volatile private var pageHasGames = false  // what the home's page was last told: it keeps no timer after "no games"
    @Volatile private var wokeAt = 0L
    @Volatile private var problem: String? = null

    private fun now() = System.currentTimeMillis()

    // ---------------------------------------------------------------- settings (home.scores)

    private fun saved(): JSONObject = Store.str(K_CONFIG)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: JSONObject()

    /** The teams, the channel table and the TV app, as kept on this TV, with the defaults filled in. */
    fun config(): JSONObject {
        val s = saved()
        return JSONObject()
            .put("teams", s.optJSONArray("teams") ?: JSONArray()
                .put(JSONObject().put("league", "mlb").put("team", "LAD")).put(JSONObject().put("league", "nba").put("team", "LAC")))
            .put("channels", s.optJSONObject("channels") ?: JSONObject())
            .put("tv_app", s.optString("tv_app").ifEmpty { DEFAULT_TV_APP })
    }

    /** home.scores from POST /api/settings. Only the keys that are sent change; a wrong shape is refused whole. */
    fun setConfig(b: JSONObject) {
        val s = saved()
        b.optJSONArray("teams")?.let { list ->
            val clean = JSONArray()
            for (i in 0 until minOf(list.length(), 8)) {
                val t = list.optJSONObject(i) ?: continue
                val league = t.optString("league").trim().lowercase(); val team = t.optString("team").trim().uppercase()
                if (!Regex("[a-z]{2,6}").matches(league) || !Regex("[A-Z0-9]{2,5}").matches(team))
                    throw ApiError(400, "scores.teams: each team needs a league (mlb) and a team abbreviation (LAD)")
                clean.put(JSONObject().put("league", league).put("team", team))
            }
            s.put("teams", clean)
        }
        b.optJSONObject("channels")?.let { table ->
            if (table.length() > 80 || table.toString().length > 16_000) throw ApiError(413, "scores.channels is too big")
            val clean = JSONObject()
            for (name in table.keys()) {
                val c = table.optJSONObject(name) ?: throw ApiError(400, "scores.channels: \"$name\" must be an object like {\"number\": 690, \"link\": \"\"}")
                val app = c.optString("app").trim()
                if (app.isNotEmpty() && !Regex("[A-Za-z0-9_.]{3,200}").matches(app)) throw ApiError(400, "scores.channels: \"$name\": app must be an Android package name")
                val al = JSONArray()
                c.optJSONArray("aliases")?.let { a -> for (i in 0 until minOf(a.length(), 12)) a.optString(i).trim().take(80).takeIf { it.isNotEmpty() }?.let { al.put(it) } }
                clean.put(name.trim().take(80), JSONObject().put("number", c.optInt("number", 0).coerceIn(0, 99_999))
                    .put("link", c.optString("link").trim().take(400)).put("aliases", al).put("app", app))
            }
            s.put("channels", clean)
        }
        if (b.has("tv_app")) {
            val p = b.optString("tv_app").trim()
            if (p.isNotEmpty() && !Regex("[A-Za-z0-9_.]{3,200}").matches(p)) throw ApiError(400, "scores.tv_app must be an Android package name")
            s.put("tv_app", p)
        }
        Store.putStr(K_CONFIG, s.toString())
        // other teams now: forget the games, and look again at once
        entries.clear(); nextScheduleAt = 0
        poke()
    }

    /** Wakes the loop, also when it is in the middle of a round and not yet asleep. */
    private fun poke() = synchronized(lock) { poked = true; lock.notifyAll() }

    private fun tvApp() = config().optString("tv_app", DEFAULT_TV_APP)

    /**
     * The app a link from the channel table should bring to the front: that channel's own app, or
     * the TV app. Null for a link that is not in the table. (/api/launch checks it came up.)
     */
    fun packageForLink(link: String): String? =
        ScoreFeeds.channels(config().optJSONObject("channels")).firstOrNull { it.link.isNotEmpty() && it.link == link }
            ?.let { it.app.ifEmpty { tvApp() } }

    // ---------------------------------------------------------------- what the home asks

    /** GET /api/scores: the games to show right now. An empty list means show nothing. */
    fun answer(): JSONObject {
        askedAt = now()
        start()
        // The first question after a while (the home is back in front): read the oldest game now,
        // with a short wait, so the bug is right the moment it shows. Not when the loop is already
        // reading: this never waits for more than its own short reading.
        if (reading.tryLock()) try {
            entries.values.filter { ScoreFeeds.showing(it.game, it.track, now()) && due(it, now()) }.minByOrNull { it.goodAt }
                ?.let { refresh(it, quick = true) }
        } finally { reading.unlock() }
        poke()
        val table = ScoreFeeds.channels(config().optJSONObject("channels"))
        val games = JSONArray()
        shown(now()).forEach { g ->
            games.put(ScoreFeeds.json(g, table, logo(g.league, "league"), logo(g.league, g.awayId.toString()), logo(g.league, g.homeId.toString())))
        }
        pageHasGames = games.length() > 0
        return JSONObject().put("tv_app", tvApp()).put("games", games).put("problem", problem ?: JSONObject.NULL)
    }

    /** In the window, read well enough lately, in order, two at most. */
    private fun shown(t: Long): List<ScoreFeeds.Game> = ScoreFeeds.order(entries.values
        .filter { ScoreFeeds.showing(it.game, it.track, t) && ScoreFeeds.keep(it.goodAt, it.failing, t) }.map { it.game })

    // ---------------------------------------------------------------- the loop

    /** Started with the app's service, so the 15 minute look at the schedule runs whether or not the home is in front. */
    fun start() {
        synchronized(lock) {
            if (thread?.isAlive == true) return
            thread = Thread({ loop() }, "scores").apply { isDaemon = true; start() }
        }
    }

    private fun loop() {
        while (true) {
            try {
                if (now() >= nextScheduleAt) { reading.lock(); try { schedule() } finally { reading.unlock() } }
                val asked = now() - askedAt < ASKED_MS
                if (asked) entries.values.filter { ScoreFeeds.showing(it.game, it.track, now()) && due(it, now()) }.forEach {
                    reading.lock(); try { if (due(it, now())) refresh(it, quick = false) } finally { reading.unlock() }
                }
                // There is a game, and the home's page was last told there was none (or never asked):
                // tell it, as it keeps no timer of its own after "no games". This covers a game
                // coming into its window and one coming back after its feed failed. Not more than
                // once in 10 s; with the home out of sight it does nothing, and the page asks by
                // itself when it is back.
                if (!pageHasGames && now() - wokeAt > 10_000L && shown(now()).isNotEmpty()) { wokeAt = now(); HomeActivity.wakeScores() }
                synchronized(lock) {
                    if (!poked) lock.wait(pause(now(), asked).coerceIn(1000L, SCHEDULE_MS))
                    poked = false
                }
            } catch (e: InterruptedException) { return }
            catch (e: Exception) {
                // something unforeseen in one round: say so, wait, go round again
                Log.w(TAG, "loop: ${e.javaClass.simpleName} ${e.message}")
                try { synchronized(lock) { lock.wait(30_000) } } catch (i: InterruptedException) { return }
            }
        }
    }

    /** How long until there is something to do: the next schedule look, a window opening or closing, a reading that is due. */
    private fun pause(t: Long, asked: Boolean): Long {
        var next = nextScheduleAt
        for (e in entries.values) {
            val g = e.game
            val opens = g.startMs - ScoreFeeds.PRE_MS
            if (opens > t) next = minOf(next, opens)                                    // the bug is due on the minute, not up to 15 late
            else if (ScoreFeeds.showing(g, e.track, t)) {
                e.track?.finalAt?.let { next = minOf(next, it + ScoreFeeds.POST_MS) }
                if (asked && reads(g)) next = minOf(next, maxOf(e.nextAt, e.goodAt + every(g)))
            }
        }
        return next - t
    }

    /** A game whose feed is worth asking: not one that is off, and not a final (the schedule look settles those). */
    private fun reads(g: ScoreFeeds.Game) = !g.off && g.state != "final"
    private fun every(g: ScoreFeeds.Game) = if (g.state == "live" || (g.state == "delayed" && g.awayScore != null)) LIVE_MS else PRE_MS
    private fun due(e: Entry, t: Long) = reads(e.game) && t >= e.nextAt && t - e.goodAt >= every(e.game)

    // ---------------------------------------------------------------- reading the feeds

    /** The 15 minute look: every configured team's games for yesterday, today and tomorrow. */
    private fun schedule() {
        val t = now()
        val teams = config().optJSONArray("teams") ?: JSONArray()
        val found = HashMap<String, ScoreFeeds.Game>()
        var failed = false
        val notes = LinkedHashSet<String>()
        for (i in 0 until teams.length()) {
            val team = teams.optJSONObject(i) ?: continue
            val name = team.optString("league")
            val league = leagues[name]
            if (league == null) {
                notes += notHere[name] ?: "${name.uppercase()} scores are not available: only MLB has a feed here (docs/SCORES.md)"
                continue
            }
            val f = league.games(team.optString("team"), t)
            f.note?.let { notes += it }
            if (f.games == null) failed = true else f.games.forEach { found[it.id] = it }
        }
        problem = notes.joinToString(". ").ifEmpty { null }
        if (failed) {
            // A team's schedule did not come: sooner again (30 s, 1 min, 2 min... 5 at most), and
            // nothing is forgotten meanwhile, as a game being followed still has its own feed.
            scheduleFailures++
            nextScheduleAt = t + ScoreFeeds.backOff(scheduleFailures, 30_000L)
        } else {
            scheduleFailures = 0
            nextScheduleAt = t + SCHEDULE_MS
            entries.keys.filter { it !in found }.forEach { entries.remove(it) }
        }
        for ((id, g) in found) {
            val e = entries[id]
            if (e == null) {
                // First heard of: a game already over then never shows (ScoreFeeds.track, time 0).
                entries[id] = Entry(g).also { it.goodAt = t; it.track = ScoreFeeds.track(g, null, t) }
            } else {
                e.track = ScoreFeeds.track(g, e.track, t); e.game = g; e.goodAt = t; e.failing = false; e.failures = 0
            }
        }
    }

    /** One game's own feed. quick: the home is waiting for the answer, so give up sooner. */
    private fun refresh(e: Entry, quick: Boolean) {
        val t = now()
        val g = leagues[e.game.league]?.reading(e.game, quick)
        if (g == null) {
            e.failing = true; e.failures++
            e.nextAt = t + ScoreFeeds.backOff(e.failures, every(e.game))
            return
        }
        e.track = ScoreFeeds.track(g, e.track, t); e.game = g
        e.goodAt = t; e.failing = false; e.failures = 0; e.nextAt = 0
    }

    // ---------------------------------------------------------------- logos

    private val logoJobs = Executors.newSingleThreadExecutor { r -> Thread(r, "scores-logos").apply { isDaemon = true } }
    private val logoTried = ConcurrentHashMap<String, Long>()
    private val LOGO_FILE = Regex("([a-z]{2,6})-(league|[0-9]{1,12})\\.svg")
    private fun logoDir() = File(App.instance.cacheDir, "scores-logos").apply { mkdirs() }

    /**
     * The path the page puts in an <img> for a league's own mark ("league") or a team's (its id),
     * or null while the picture is not in the cache yet: it is fetched in the background and is
     * there for a later answer. The answer itself never waits for a picture.
     */
    private fun logo(league: String, what: String): String? {
        val file = "$league-$what.svg"
        val from = leagues[league]?.takeIf { LOGO_FILE.matches(file) && what != "0" }?.logoUrl(what) ?: return null
        val f = File(logoDir(), file)
        if (f.isFile && f.length() > 0) return "/api/scores/logo/$file"
        val t = now()
        if (t - (logoTried[file] ?: 0L) < 10 * 60_000L) return null   // asked for a moment ago: not again for 10 minutes
        logoTried[file] = t
        logoJobs.execute {
            val r = get(from)
            val text = r.body.trimStart()
            // only a real, plain drawing is kept: never an error page, never one that carries a script
            if (r.status != 200 || r.body.length !in 50..200_000 || !(text.startsWith("<svg") || text.startsWith("<?xml")) || text.contains("<script", ignoreCase = true)) {
                Log.w(TAG, "logo $file: HTTP ${r.status}, ${r.body.length} bytes, not kept"); return@execute
            }
            val tmp = File(logoDir(), "$file.tmp")
            runCatching { tmp.writeText(r.body); if (!tmp.renameTo(f)) tmp.delete() }.onFailure { Log.w(TAG, "logo $file: ${it.message}") }
        }
        return null
    }

    /** A cached logo for GET /api/scores/logo/<file>, or null. Only names this app made itself. */
    fun logoFile(file: String): File? {
        if (!LOGO_FILE.matches(file)) return null
        return File(logoDir(), file).takeIf { it.isFile && it.length() > 0 }
    }

    // ---------------------------------------------------------------- transport

    private class Reply(val status: Int, val body: String)

    /** One GET with no key and no cookie. Status -1 when the feed could not be reached, -2 when the answer was too long. */
    private fun get(url: String, connectMs: Int = 4000, readMs: Int = 6000): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = connectMs; c.readTimeout = readMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", AGENT)
            val st = c.responseCode
            if (st != 200) return Reply(st, "")
            // Nothing asked for here is big (a few KB at most), and a TV has little memory: reading
            // stops at 256 KB, and an answer that long is not used.
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            c.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return Reply(-2, "")
                }
            }
            Reply(200, out.toString("UTF-8"))
        } catch (e: Exception) {
            Log.w(TAG, "${url.substringBefore('?').substringAfterLast('/')}: ${e.javaClass.simpleName}")
            Reply(-1, "")
        } finally { c.disconnect() }
    }
}
