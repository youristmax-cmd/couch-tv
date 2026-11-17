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
 * NBA: ESPN's public scoreboard JSON (site.api.espn.com), which needs no key. It is not the NBA's
 * own feed (cdn.nba.com answers a plain request with 403 Access Denied) and it is not an official
 * or documented one: no permission for this use has been given or asked for, and ESPN may change
 * or close it. The owner chose it knowing that (docs/SCORES.md). It is asked plainly, as this app,
 * and if it ever refuses a plain request that is the end of it. What is asked, and how often:
 *  - the team's entry, by its abbreviation, every 15 minutes, always (21 KB, 2.6 KB on the wire):
 *    its id and its next game. That entry names a game only until about tip-off, then the one
 *    after, so a game learned from it is remembered (also across a restart) and then followed by
 *    its own id.
 *  - one game (14 to 25 KB, 2.4 to 3.6 KB on the wire): every 60 s before the game and every 15 s
 *    during it while the home asks, as for MLB; and once with each 15 minute look from 60 minutes
 *    before the start until 30 minutes after the end, as the team's entry no longer has it.
 *  - where the league's logo is: one small scoreboard answer (17 KB), once a week.
 *  - the logos, once each, from the addresses ESPN's answers give, at 200 px (league 100 px)
 *    through ESPN's own resizer, into the cache folder. PNG.
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

    /**
     * The games a league's feed has for one team. games is null when the feed did not answer at
     * all; failed says something did not come (the games that did are still used); note says what
     * is wrong with the team.
     */
    private class Found(val games: List<ScoreFeeds.Game>?, val note: String? = null, val failed: Boolean = games == null)

    private interface League {
        /** "svg" or "png": what this league's logos are, and so how they are checked, named and served. */
        val logoExt: String
        /** The team's games near now (played lately, on now, next). */
        fun games(team: String, t: Long): Found
        /** A newer reading of one game from its own feed, or null when the feed did not answer. quick: give up sooner. */
        fun reading(g: ScoreFeeds.Game, quick: Boolean): ScoreFeeds.Game?
        /**
         * Where the picture for "league" or for a team id is, drawn for a dark background when
         * there is such a one. Null when that is not known (yet). It may ask the feed, so it is
         * only called from the logo fetcher's own thread.
         */
        fun logoUrl(what: String): String?
    }

    private val leagues: Map<String, League> = mapOf("mlb" to Mlb, "nba" to Nba)

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

        override val logoExt = "svg"
        override fun logoUrl(what: String): String? = if (what == "league") "$LOGOS/league-on-dark/1.svg" else "$LOGOS/team-cap-on-dark/$what.svg"

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

    /**
     * The NBA, from ESPN's public scoreboard JSON. Not an official feed: see the note at the top
     * and docs/SCORES.md.
     */
    private object Nba : League {
        private const val API = "https://site.api.espn.com/apis/site/v2/sports/basketball/nba"
        private const val KEPT = "scores-nba-following.json"   // [{"id", "team", "start", "done"}]
        private const val LEAGUE = "scores-nba-league.json"    // {"href", "_at"}: where ESPN says the league's logo is
        private const val WEEK_MS = 7 * 24 * 60 * 60_000L

        /** A game learned from the team's entry: followed by its own id once that entry has moved on. */
        private class Follow(val team: String, @Volatile var start: Long, @Volatile var doneAt: Long = 0)

        private val following = ConcurrentHashMap<Long, Follow>()
        private val logos = ConcurrentHashMap<Int, String>()    // team id to the logo address the feed gave
        @Volatile private var loaded = false

        override val logoExt = "png"

        override fun games(team: String, t: Long): Found {
            load()
            val r = get("$API/teams/${team.lowercase()}")
            val j = (if (r.status == 200) runCatching { JSONObject(r.body) }.getOrNull() else null)
                ?: return Found(null).also { Log.w(TAG, "nba $team: HTTP ${r.status}") }
            // ESPN answers {} for a team it does not have
            val entry = ScoreFeeds.nbaTeam(j) ?: return Found(emptyList(), "ESPN has no NBA team called $team")
            val out = ArrayList(entry.games)
            var failed = false
            var changed = false
            for (g in entry.games) {
                remember(g)
                val f = following[g.pk]
                if (f == null) { following[g.pk] = Follow(team, g.startMs); changed = true }
                else if (f.start != g.startMs) { f.start = g.startMs; changed = true }      // moved to another time
            }
            for ((pk, f) in following) {
                // The games the entry no longer names (ScoreFeeds.nbaFollow says what to do with each).
                // "forget" goes for any team's: a team taken out of the settings leaves nothing behind.
                val what = ScoreFeeds.nbaFollow(f.start, f.doneAt, t)
                if (what == "forget") { following.remove(pk); changed = true; continue }
                if (what == "wait" || f.team != team || out.any { it.pk == pk }) continue
                val g = event(pk, entry.id, quick = false)
                if (g == null) { failed = true; continue }
                if (f.doneAt == 0L) ScoreFeeds.nbaDoneAt(g, t).takeIf { it > 0 }?.let { f.doneAt = it; changed = true }
                out += g
            }
            if (changed) save()
            return Found(out, failed = failed)
        }

        override fun reading(g: ScoreFeeds.Game, quick: Boolean): ScoreFeeds.Game? =
            event(g.pk, if (g.ourSide == "home") g.homeId else g.awayId, quick)

        /** One game by its own id, as a game of that team. Null when ESPN did not answer or the answer is not that game. */
        private fun event(pk: Long, teamId: Int, quick: Boolean): ScoreFeeds.Game? {
            val r = get("$API/scoreboard/$pk", if (quick) 1500 else 4000, if (quick) 2000 else 6000)
            val g = (if (r.status == 200) runCatching { JSONObject(r.body) }.getOrNull() else null)
                ?.let { ScoreFeeds.nbaEvent(it, teamId) }?.takeIf { it.pk == pk }
            if (g == null) Log.w(TAG, "nba game $pk: HTTP ${r.status}") else remember(g)
            return g
        }

        private fun remember(g: ScoreFeeds.Game) {
            g.awayLogo?.let { logos[g.awayId] = it }
            g.homeLogo?.let { logos[g.homeId] = it }
        }

        /**
         * A team's logo: the address its last game's answer gave. The league's: the address in
         * ESPN's scoreboard (asked for one game only, once a week). Both at a modest size through
         * ESPN's own resizer, and only from espncdn.com (ScoreFeeds.espnLogo).
         */
        override fun logoUrl(what: String): String? {
            if (what != "league") return ScoreFeeds.espnLogo(logos[what.toIntOrNull() ?: return null], 200)
            var kept = Store.readJson(LEAGUE) as? JSONObject
            if (kept == null || now() - kept.optLong("_at") > WEEK_MS) {
                val r = get("$API/scoreboard?limit=1")
                val href = (if (r.status == 200) runCatching { JSONObject(r.body) }.getOrNull() else null)
                    ?.optJSONArray("leagues")?.optJSONObject(0)?.let { ScoreFeeds.espnLogoOf(it) }
                if (href != null) { kept = JSONObject().put("href", href).put("_at", now()); Store.writeJson(LEAGUE, kept) }
            }
            return ScoreFeeds.espnLogo(kept?.optString("href"), 100)
        }

        /** The games being followed are kept in the app's files: a TV switched on in the middle of a game still knows which one. */
        @Synchronized private fun load() {
            if (loaded) return
            loaded = true
            val list = Store.readJson(KEPT) as? JSONArray ?: return
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                if (o.optLong("id") > 0 && o.optString("team").isNotEmpty()) following[o.optLong("id")] = Follow(o.optString("team"), o.optLong("start"), o.optLong("done"))
            }
        }

        private fun save() {
            val list = JSONArray()
            following.forEach { (pk, f) -> list.put(JSONObject().put("id", pk).put("team", f.team).put("start", f.start).put("done", f.doneAt)) }
            Store.writeJson(KEPT, list)
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
                notes += "${name.uppercase()} scores are not available: only MLB and the NBA have a feed here (docs/SCORES.md)"
                continue
            }
            val f = league.games(team.optString("team"), t)
            f.note?.let { notes += it }
            if (f.failed) failed = true
            f.games?.forEach { found[it.id] = it }
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
    private val LOGO_FILE = Regex("([a-z]{2,6})-(league|[0-9]{1,12})\\.(svg|png)")
    private fun logoDir() = File(App.instance.cacheDir, "scores-logos").apply { mkdirs() }

    /**
     * The path the page puts in an <img> for a league's own mark ("league") or a team's (its id),
     * or null while the picture is not in the cache yet: it is fetched in the background and is
     * there for a later answer. The answer itself never waits for a picture. MLB's are SVG, ESPN's
     * PNG; the file's ending says which.
     */
    private fun logo(league: String, what: String): String? {
        val from = leagues[league] ?: return null
        val file = "$league-$what.${from.logoExt}"
        if (!LOGO_FILE.matches(file) || what == "0") return null
        val f = File(logoDir(), file)
        if (f.isFile && f.length() > 0) return "/api/scores/logo/$file"
        val t = now()
        if (t - (logoTried[file] ?: 0L) < 10 * 60_000L) return null   // asked for a moment ago: not again for 10 minutes
        logoTried[file] = t
        logoJobs.execute {
            val url = runCatching { from.logoUrl(what) }.getOrNull()
            if (url == null) { Log.w(TAG, "logo $file: the feed has not said where it is"); return@execute }
            val r = get(url)
            // Only what it should be is kept (ScoreFeeds.picture): a plain drawing or a PNG of a
            // sensible size, never an error page, never a drawing that carries a script.
            if (r.status != 200 || !ScoreFeeds.picture(from.logoExt, r.bytes)) {
                Log.w(TAG, "logo $file: HTTP ${r.status}, ${r.bytes.size} bytes, not kept"); return@execute
            }
            val tmp = File(logoDir(), "$file.tmp")
            runCatching { tmp.writeBytes(r.bytes); if (!tmp.renameTo(f)) tmp.delete() }.onFailure { Log.w(TAG, "logo $file: ${it.message}") }
        }
        return null
    }

    /** A cached logo for GET /api/scores/logo/<file>, or null. Only names this app made itself. */
    fun logoFile(file: String): File? {
        if (!LOGO_FILE.matches(file)) return null
        return File(logoDir(), file).takeIf { it.isFile && it.length() > 0 }
    }

    // ---------------------------------------------------------------- transport

    private class Reply(val status: Int, val bytes: ByteArray) {
        val body: String get() = String(bytes, Charsets.UTF_8)
    }
    private val NOTHING = ByteArray(0)

    /** One GET with no key and no cookie. Status -1 when the feed could not be reached, -2 when the answer was too long. */
    private fun get(url: String, connectMs: Int = 4000, readMs: Int = 6000): Reply {
        val c = URL(url).openConnection() as HttpURLConnection
        return try {
            c.connectTimeout = connectMs; c.readTimeout = readMs
            c.instanceFollowRedirects = false
            c.setRequestProperty("User-Agent", AGENT)
            val st = c.responseCode
            if (st != 200) return Reply(st, NOTHING)
            // Nothing asked for here is big (MLB's answers are a few KB, ESPN's 25 KB at most), and
            // a TV has little memory: reading stops at 256 KB, and an answer that long is not used.
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            c.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > MAX_BYTES) return Reply(-2, NOTHING)
                }
            }
            Reply(200, out.toByteArray())
        } catch (e: Exception) {
            Log.w(TAG, "${url.substringBefore('?').substringAfterLast('/')}: ${e.javaClass.simpleName}")
            Reply(-1, NOTHING)
        } finally { c.disconnect() }
    }
}
