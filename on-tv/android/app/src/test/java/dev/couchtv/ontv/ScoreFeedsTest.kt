package dev.couchtv.ontv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The score bugs' rules (ScoreFeeds.kt), checked on the computer against answers recorded from
 * MLB's Stats API in November 2026 (src/test/resources/scores: facts only, no logos).
 *  - mlb-schedule-*.json: the schedule as the app asks for it. final, final-extras and national are
 *    Dodgers games of September 2026; postponed is 18 and 19 July 2026 (a postponed game and the
 *    day it was made up); pre is a game listed for February 2027; none is an off-season answer.
 *  - mlb-feed-*.json: one game's feed at recorded moments (feed/live?timecode=), games 823164
 *    (LAD at SF, 27 September 2026, 10 innings) and 823899.
 *  - the two *-app-url-* files were asked with exactly the addresses Scores.kt builds (its own
 *    "fields" lists); the others with a few more fields than the app asks for.
 *  - mlb-feed-EDITED-second-third.json is NOT a recorded state: no moment with a runner on third
 *    turned up, so "third" was added by hand to a recorded one. The file says so itself.
 * The status words in statusWords() are rows of the API's own list (/api/v1/gameStatus), not a
 * recorded game: no delayed or suspended Dodgers game was on record to take one from.
 */
class ScoreFeedsTest {
    private val min = 60_000L
    private val dodgers = 119

    private fun load(name: String): JSONObject =
        JSONObject(javaClass.getResourceAsStream("/scores/$name")!!.bufferedReader().readText())

    private fun at(iso: String) = ScoreFeeds.parseIso(iso)!!

    /** A game made by hand, for the rules that are about time and order rather than a feed. */
    private fun game(pk: Long, state: String, start: Long, off: Boolean = false, suspended: Boolean = false, score: Int? = null,
                     broadcasts: List<ScoreFeeds.Broadcast> = emptyList()) = ScoreFeeds.Game(
        league = "mlb", pk = pk, startMs = start, state = state, status = state, statusDetail = state, off = off, suspended = suspended,
        ourSide = "home", awayAbbr = "SF", awayId = 137, awayScore = score, homeAbbr = "LAD", homeId = 119, homeScore = score,
        broadcasts = broadcasts, inning = 0, scheduledInnings = 9, half = "top", outs = 0, first = false, second = false, third = false)

    /** Game 823164 as the schedule gives it, to lay a recorded feed moment over. */
    private fun sept27() = ScoreFeeds.mlbSchedule(load("mlb-schedule-final-extras.json"), dodgers).single()

    // ---------------------------------------------------------------- time

    @Test fun isoTimesAgreeWithJavasOwn() {
        for (s in listOf("2026-09-27T19:05:00Z", "2027-02-19T20:05:00Z", "2024-02-29T23:59:59Z", "1970-01-01T00:00:00Z", "2026-12-31T00:00:00Z"))  {
            assertEquals(s, java.time.Instant.parse(s).toEpochMilli(), ScoreFeeds.parseIso(s))
            assertEquals(s, ScoreFeeds.isoUtc(java.time.Instant.parse(s).toEpochMilli()))
        }
        assertEquals(at("2026-09-27T19:05:00Z"), ScoreFeeds.parseIso("2026-09-27T12:05:00-07:00"))
        assertEquals(at("2026-09-27T19:05:00Z"), ScoreFeeds.parseIso("2026-09-27T19:05:00.000+00:00"))
        assertNull(ScoreFeeds.parseIso("soon")); assertNull(ScoreFeeds.parseIso("")); assertNull(ScoreFeeds.parseIso(null))
        assertEquals("2026-09-27", ScoreFeeds.dayUtc(at("2026-09-27T19:05:00Z")))
    }

    // ---------------------------------------------------------------- the window

    @Test fun preGameShowsFrom60MinutesBeforeTheStart() {
        val start = at("2026-09-27T19:05:00Z")
        val g = game(1, "pre", start)
        val t = ScoreFeeds.track(g, null, start - 3 * 60 * min)
        assertFalse("61 minutes before", ScoreFeeds.showing(g, t, start - 61 * min))
        assertTrue("59 minutes before", ScoreFeeds.showing(g, t, start - 59 * min))
        assertTrue("at the start", ScoreFeeds.showing(g, t, start))
    }

    @Test fun finalStays15MinutesFromWhenTheAppSawItEnd() {
        val start = at("2026-09-27T19:05:00Z")
        val live = game(1, "live", start, score = 1)
        var t = ScoreFeeds.track(live, null, start + 10 * min)
        assertTrue(ScoreFeeds.showing(live, t, start + 170 * min))
        val end = start + 171 * min
        val fin = game(1, "final", start, score = 5)
        t = ScoreFeeds.track(fin, t, end)
        assertEquals(end, t.finalAt)
        assertTrue("14 minutes after", ScoreFeeds.showing(fin, t, end + 14 * min))
        assertFalse("16 minutes after", ScoreFeeds.showing(fin, t, end + 16 * min))
        // later readings do not move the moment it ended
        t = ScoreFeeds.track(fin, t, end + 10 * min)
        assertEquals(end, t.finalAt)
        assertFalse(ScoreFeeds.showing(fin, t, end + 16 * min))
    }

    @Test fun aGameAlreadyOverWhenFirstHeardOfNeverShows() {
        val start = at("2026-09-27T19:05:00Z")
        val fin = game(1, "final", start, score = 5)
        var t = ScoreFeeds.track(fin, null, start + 300 * min)
        assertFalse(ScoreFeeds.showing(fin, t, start + 300 * min))
        // nor 15 minutes later, when the schedule is read again
        t = ScoreFeeds.track(fin, t, start + 315 * min)
        assertFalse(ScoreFeeds.showing(fin, t, start + 315 * min))
        assertFalse(ScoreFeeds.showing(fin, t, start + 316 * min))
    }

    @Test fun aGameThatNeverStartsOrStaysStoppedGoesAfter3Hours() {
        val start = at("2026-09-27T19:05:00Z")
        val pre = game(1, "pre", start)
        assertTrue(ScoreFeeds.showing(pre, ScoreFeeds.track(pre, null, start), start + 179 * min))
        assertFalse(ScoreFeeds.showing(pre, ScoreFeeds.track(pre, null, start), start + 181 * min))
        // a rain delay in the fifth: it stays while it may resume, and not for ever
        val rain = game(1, "delayed", start, score = 2)
        val t = ScoreFeeds.track(rain, ScoreFeeds.Track(), start + 90 * min)
        assertTrue(ScoreFeeds.showing(rain, t, start + 90 * min + 179 * min))
        assertFalse(ScoreFeeds.showing(rain, t, start + 90 * min + 181 * min))
        // suspended (finished another day): 15 minutes, like a final
        val susp = game(1, "delayed", start, suspended = true, score = 2)
        val ts = ScoreFeeds.track(susp, ScoreFeeds.Track(), start + 90 * min)
        assertTrue(ScoreFeeds.showing(susp, ts, start + 104 * min))
        assertFalse(ScoreFeeds.showing(susp, ts, start + 106 * min))
        // and one already suspended when first heard of does not show
        assertFalse(ScoreFeeds.showing(susp, ScoreFeeds.track(susp, null, start + 90 * min), start + 91 * min))
    }

    // ---------------------------------------------------------------- order

    @Test fun liveFirstAndTwoAtMost() {
        val start = at("2026-09-27T19:05:00Z")
        val pre = game(1, "pre", start + 30 * min)
        val live = game(2, "live", start, score = 3)
        assertEquals(listOf(2L, 1L), ScoreFeeds.order(listOf(pre, live)).map { it.pk })
        val fin = game(3, "final", start - 200 * min, score = 4)
        val rain = game(4, "delayed", start - 20 * min, score = 0)
        assertEquals(listOf(2L, 4L), ScoreFeeds.order(listOf(fin, pre, rain, live)).map { it.pk })
        assertEquals(listOf(1L, 3L), ScoreFeeds.order(listOf(fin, pre)).map { it.pk })
        assertTrue(ScoreFeeds.order(emptyList()).isEmpty())
    }

    // ---------------------------------------------------------------- the schedule

    @Test fun teamIdComesFromTheLeaguesOwnList() {
        val teams = load("mlb-teams.json")
        assertEquals(119, ScoreFeeds.mlbTeamId(teams, "LAD"))
        assertEquals(119, ScoreFeeds.mlbTeamId(teams, " lad "))
        assertEquals(137, ScoreFeeds.mlbTeamId(teams, "SF"))
        assertNull(ScoreFeeds.mlbTeamId(teams, "XXX"))
        assertNull(ScoreFeeds.mlbTeamId(JSONObject(), "LAD"))
    }

    @Test fun aFinishedGameFromTheSchedule() {
        val g = ScoreFeeds.mlbSchedule(load("mlb-schedule-final.json"), dodgers).single()
        assertEquals("mlb-824465", g.id)
        assertEquals("final", g.state); assertEquals("Final", g.status)
        assertEquals(at("2026-09-14T22:40:00Z"), g.startMs)
        assertEquals("LAD", g.awayAbbr); assertEquals(4, g.awayScore); assertEquals(119, g.awayId)
        assertEquals("CIN", g.homeAbbr); assertEquals(1, g.homeScore)
        assertEquals("away", g.ourSide)
        assertEquals("FINAL", g.finalLabel)
        // television only (the feed lists radio too), in the feed's order
        assertEquals(listOf("SportsNet LA", "Reds.TV", "Gray Media", "WXIX FOX19"), g.broadcasts.map { it.name })
        // somebody else's schedule: nothing
        assertTrue(ScoreFeeds.mlbSchedule(load("mlb-schedule-final.json"), 147).isEmpty())
    }

    @Test fun extraInningsFinalSaysTheInning() {
        val g = sept27()
        assertEquals("final", g.state); assertEquals("F/10", g.finalLabel)
        assertEquals(5, g.awayScore); assertEquals(1, g.homeScore)
        // the feed leaves the last runner and the third out standing: the answer does not
        val j = ScoreFeeds.json(g, emptyList(), null, null, null)
        assertEquals("F/10", j.getString("final_label"))
        val m = j.getJSONObject("mlb")
        assertEquals(10, m.getInt("inning")); assertEquals(0, m.getInt("outs"))
        assertFalse(m.getBoolean("first")); assertFalse(m.getBoolean("second")); assertFalse(m.getBoolean("third"))
    }

    @Test fun aGameNotYetPlayed() {
        val g = ScoreFeeds.mlbSchedule(load("mlb-schedule-pre.json"), dodgers).single()
        assertEquals("pre", g.state); assertEquals("Scheduled", g.status)
        assertEquals(at("2027-02-19T20:05:00Z"), g.startMs)
        assertEquals("CWS", g.awayAbbr); assertEquals("LAD", g.homeAbbr); assertEquals("home", g.ourSide)
        assertNull(g.awayScore); assertNull(g.homeScore); assertNull(g.finalLabel)
        assertTrue(g.broadcasts.isEmpty())
        val j = ScoreFeeds.json(g, emptyList(), null, null, null)
        assertTrue(j.getJSONObject("away").isNull("score")); assertTrue(j.getJSONObject("home").isNull("score"))
        assertTrue(j.isNull("channel")); assertFalse(j.has("final_label"))
        assertEquals("2027-02-19T20:05:00Z", j.getString("start"))
        assertEquals(0, j.getJSONArray("broadcasts").length())
    }

    @Test fun outOfSeasonThereIsNothing() {
        assertTrue(ScoreFeeds.mlbSchedule(load("mlb-schedule-none.json"), dodgers).isEmpty())
        assertTrue(ScoreFeeds.mlbSchedule(JSONObject(), dodgers).isEmpty())
    }

    @Test fun aPostponedGameAndTheDayItIsMadeUp() {
        val games = ScoreFeeds.mlbSchedule(load("mlb-schedule-postponed.json"), dodgers)
        assertEquals(listOf("mlb-823523-off", "mlb-823523", "mlb-823521"), games.map { it.id })
        val off = games[0]
        // the feed calls it "Final": it is not one
        assertEquals("delayed", off.state); assertTrue(off.off)
        assertEquals("Postponed", off.status); assertEquals("Postponed: Inclement Weather", off.statusDetail)
        assertNull(off.awayScore); assertNull(off.homeScore); assertNull(off.finalLabel)
        // on screen around the time it was to start, and not for hours
        val start = at("2026-07-19T00:08:00Z")
        assertEquals(start, off.startMs)
        val t = ScoreFeeds.track(off, null, start - 120 * min)
        assertFalse(ScoreFeeds.showing(off, t, start - 61 * min))
        assertTrue(ScoreFeeds.showing(off, t, start - 59 * min))
        assertTrue(ScoreFeeds.showing(off, t, start + 14 * min))
        assertFalse(ScoreFeeds.showing(off, t, start + 16 * min))
        // the same gamePk the next day is the game itself
        val made = games[1]
        assertEquals("final", made.state); assertFalse(made.off)
        assertEquals(at("2026-07-19T16:35:00Z"), made.startMs)
        assertEquals(8, made.awayScore); assertEquals(2, made.homeScore); assertEquals("FINAL", made.finalLabel)
    }

    @Test fun statusWords() {
        // rows of the API's own status list (/api/v1/gameStatus), with the reason the schedule adds
        val st = { abs: String, coded: String, code: String, word: String, reason: String ->
            ScoreFeeds.mlbState(JSONObject().put("abstractGameState", abs).put("codedGameState", coded).put("statusCode", code)
                .put("detailedState", word).also { if (reason.isNotEmpty()) it.put("reason", reason) })
        }
        st("Live", "I", "IR", "Delayed: Rain", "Rain")!!.let { assertEquals("delayed", it.state); assertEquals("Delayed", it.status); assertEquals("Delayed: Rain", it.detail); assertFalse(it.off) }
        st("Preview", "P", "PR", "Delayed Start: Rain", "Rain")!!.let { assertEquals("delayed", it.state); assertEquals("Delayed Start", it.status) }
        st("Live", "T", "TR", "Suspended: Rain", "Rain")!!.let { assertEquals("delayed", it.state); assertEquals("Suspended", it.status); assertTrue(it.suspended) }
        st("Final", "C", "CR", "Cancelled: Rain", "Rain")!!.let { assertEquals("delayed", it.state); assertEquals("Cancelled", it.status); assertTrue(it.off) }
        st("Final", "D", "DI", "Postponed", "Inclement Weather")!!.let { assertEquals("Postponed", it.status); assertEquals("Postponed: Inclement Weather", it.detail); assertTrue(it.off) }
        st("Live", "I", "I", "In Progress", "")!!.let { assertEquals("live", it.state); assertEquals("In Progress", it.status) }
        st("Live", "M", "MA", "Manager challenge: Tag play", "Tag play")!!.let { assertEquals("live", it.state) }
        st("Live", "P", "PW", "Warmup", "")!!.let { assertEquals("pre", it.state) }
        st("Preview", "P", "P", "Pre-Game", "")!!.let { assertEquals("pre", it.state); assertEquals("Pre-Game", it.status) }
        st("Final", "F", "FR", "Completed Early: Rain", "Rain")!!.let { assertEquals("final", it.state); assertEquals("Completed Early", it.status) }
        st("Final", "O", "O", "Game Over", "")!!.let { assertEquals("final", it.state) }
        // the feed itself does not know: the caller keeps what it had
        assertNull(st("Other", "X", "X", "Unknown", "")); assertNull(st("Other", "W", "W", "Writing", "")); assertNull(ScoreFeeds.mlbState(null))
    }

    // ---------------------------------------------------------------- one game's feed, at recorded moments

    private fun moment(name: String) = ScoreFeeds.mlbFeed(load(name), sept27())

    @Test fun beforeTheFirstPitch() {
        val g = moment("mlb-feed-pre.json")
        assertEquals("pre", g.state); assertEquals("Pre-Game", g.status)
        assertNull(g.awayScore); assertNull(g.homeScore)
        assertEquals(at("2026-09-27T19:05:00Z"), g.startMs)
        // the teams and the broadcasts stay the schedule's
        assertEquals("LAD", g.awayAbbr); assertEquals("SF", g.homeAbbr)
        assertEquals(listOf("NBCS BA", "SportsNet LA"), g.broadcasts.map { it.name })
    }

    @Test fun topAndBottomOutsAndRunners() {
        var g = moment("mlb-feed-live-bot6-2out.json")
        assertEquals("live", g.state); assertEquals("In Progress", g.status)
        assertEquals(6, g.inning); assertEquals("bottom", g.half); assertEquals(2, g.outs)
        assertFalse(g.first); assertFalse(g.second); assertFalse(g.third)
        assertEquals(1, g.awayScore); assertEquals(1, g.homeScore)

        g = moment("mlb-feed-live-bot7-first.json")
        assertEquals(7, g.inning); assertEquals("bottom", g.half); assertEquals(0, g.outs)
        assertTrue(g.first); assertFalse(g.second); assertFalse(g.third)

        g = moment("mlb-feed-live-top9-1out-first.json")
        assertEquals(9, g.inning); assertEquals("top", g.half); assertEquals(1, g.outs)
        assertTrue(g.first); assertFalse(g.second); assertFalse(g.third)

        g = moment("mlb-feed-live-top10-second.json")
        assertEquals("live", g.state); assertEquals(10, g.inning); assertEquals("top", g.half); assertEquals(0, g.outs)
        assertFalse(g.first); assertTrue(g.second); assertFalse(g.third)
        assertNull("still being played: no final label", g.finalLabel)

        g = moment("mlb-feed-live-top10-first-second.json")
        assertEquals(2, g.outs); assertTrue(g.first); assertTrue(g.second); assertFalse(g.third)
        assertEquals(2, g.awayScore); assertEquals(1, g.homeScore)
        val m = ScoreFeeds.json(g, emptyList(), null, null, null).getJSONObject("mlb")
        assertEquals(10, m.getInt("inning")); assertEquals("top", m.getString("half")); assertEquals(2, m.getInt("outs"))
        assertTrue(m.getBoolean("first")); assertTrue(m.getBoolean("second")); assertFalse(m.getBoolean("third"))
    }

    @Test fun thirdBase_fromAnEditedMomentNotARecordedOne() {
        val j = load("mlb-feed-EDITED-second-third.json")
        assertTrue("the fixture must say it was edited", j.has("_edited"))
        val g = ScoreFeeds.mlbFeed(j, sept27())
        assertFalse(g.first); assertTrue(g.second); assertTrue(g.third)
        assertTrue(ScoreFeeds.json(g, emptyList(), null, null, null).getJSONObject("mlb").getBoolean("third"))
    }

    @Test fun theFinalMomentOfTenInnings() {
        val g = moment("mlb-feed-final-10.json")
        assertEquals("final", g.state); assertEquals("F/10", g.finalLabel)
        assertEquals(5, g.awayScore); assertEquals(1, g.homeScore)
    }

    @Test fun threeOutsBetweenHalvesAndAnAnswerForAnotherGame() {
        val was = game(823899, "live", at("2026-09-20T01:10:00Z"), score = 0)
        val g = ScoreFeeds.mlbFeed(load("mlb-feed-live-3outs.json"), was)
        assertEquals(5, g.inning); assertEquals("bottom", g.half); assertEquals(3, g.outs)
        assertEquals(4, g.awayScore); assertEquals(7, g.homeScore)
        assertEquals(3, ScoreFeeds.json(g, emptyList(), null, null, null).getJSONObject("mlb").getInt("outs"))
        // game 823164's feed changes nothing about game 823899
        val same = ScoreFeeds.mlbFeed(load("mlb-feed-live-bot6-2out.json"), was)
        assertTrue(same === was)
    }

    @Test fun answersToTheAppsOwnAddresses() {
        // Asked exactly as Scores.kt asks (its "fields" lists, a three day schedule). With no "id"
        // among the feed's fields a runner comes back as an empty object: he is still on base.
        val games = ScoreFeeds.mlbSchedule(load("mlb-schedule-app-url.json"), dodgers)
        assertEquals(listOf("mlb-823165", "mlb-823164"), games.map { it.id })
        assertEquals(listOf("FINAL", "F/10"), games.map { it.finalLabel })
        assertEquals(listOf(4, 5), games.map { it.awayScore }); assertEquals(listOf(3, 1), games.map { it.homeScore })
        assertEquals(listOf("NBCS BA", "SportsNet LA"), games[1].broadcasts.map { it.name })
        assertEquals("SNLA", games[1].broadcasts[1].callSign); assertEquals("away", games[1].broadcasts[1].side)
        val g = ScoreFeeds.mlbFeed(load("mlb-feed-app-url-bot7-first.json"), games[1])
        assertEquals("live", g.state); assertEquals(7, g.inning); assertEquals("bottom", g.half); assertEquals(0, g.outs)
        assertTrue(g.first); assertFalse(g.second); assertFalse(g.third)
        assertEquals(1, g.awayScore); assertEquals(1, g.homeScore)
        assertEquals("LAD", g.awayAbbr); assertEquals("SF", g.homeAbbr)
    }

    // ---------------------------------------------------------------- channels

    private fun table(json: String) = ScoreFeeds.channels(JSONObject(json))

    @Test fun channelByNameAliasAndCallSign() {
        val g = sept27()   // NBCS BA (the Giants', home) and SportsNet LA (the Dodgers', away)
        // by its name, whatever the case, spaces and punctuation
        var c = ScoreFeeds.channel(g, table("""{"Sports-Net  L.A.": {"number": 690, "link": ""}}"""))
        assertEquals(690, c!!.number); assertEquals("Sports-Net  L.A.", c.name)
        // by an alias
        c = ScoreFeeds.channel(g, table("""{"Dodgers channel": {"number": 690, "aliases": ["Spectrum SportsNet LA", "sportsnet la"]}}"""))
        assertEquals("Dodgers channel", c!!.name)
        // by the feed's call sign (SNLA)
        c = ScoreFeeds.channel(g, table("""{"SNLA": {"number": 690}}"""))
        assertEquals(690, c!!.number)
        // nothing in the table for this game
        assertNull(ScoreFeeds.channel(g, table("""{"ESPN": {"number": 206}, "TNT": {"number": 245}}""")))
        assertNull(ScoreFeeds.channel(g, emptyList()))
        assertTrue(ScoreFeeds.json(g, table("""{"ESPN": {"number": 206}}"""), null, null, null).isNull("channel"))
    }

    @Test fun aFilledInChannelBeatsOneThatIsOnlyNamed() {
        val games = ScoreFeeds.mlbSchedule(load("mlb-schedule-national.json"), dodgers)
        val tbs = games.single { it.pk == 823897L }   // Padres.TV, SportsNet LA (ours), TBS/HBO MAX, TBS (out-of-market only)
        // both known and filled in: the team's own channel
        var c = ScoreFeeds.channel(tbs, table("""{"TBS": {"number": 247}, "SportsNet LA": {"number": 690}}"""))
        assertEquals("SportsNet LA", c!!.name)
        // the team's own is named but empty, the national one is filled in: the one that can be opened
        c = ScoreFeeds.channel(tbs, table("""{"TBS": {"number": 247}, "SportsNet LA": {"number": 0, "link": ""}}"""))
        assertEquals("TBS", c!!.name); assertEquals(247, c.number)
        // neither filled in: still a match (the pill can name it), the team's own first
        c = ScoreFeeds.channel(tbs, table("""{"TBS": {}, "SportsNet LA": {}}"""))
        assertEquals("SportsNet LA", c!!.name); assertEquals(0, c.number); assertEquals("", c.link)
        // a shared name ("TBS/HBO MAX") and a remark in brackets both find TBS
        c = ScoreFeeds.channel(tbs, table("""{"TBS": {"number": 247}}"""))
        assertEquals(247, c!!.number)
    }

    @Test fun outOfMarketOnlyComesAfterABroadcastThatCanBeWatched() {
        // the recorded game: the Padres' channel, SportsNet LA (ours), TBS for the Padres' side, and
        // "TBS (out-of-market only)" for ours. The names go out with the most useful first.
        val tbs = ScoreFeeds.mlbSchedule(load("mlb-schedule-national.json"), dodgers).single { it.pk == 823897L }
        val names = ScoreFeeds.json(tbs, emptyList(), null, null, null).getJSONArray("broadcasts")
        assertEquals(listOf("SportsNet LA", "TBS/HBO MAX", "TBS (out-of-market only)", "Padres.TV Presented by UC San Diego Health"),
            (0 until names.length()).map { names.getString(it) })
        // names of this kind from the Dodgers' 2026 season, put together by hand to tell the places apart
        val g = game(1, "pre", at("2026-09-27T19:05:00Z"), broadcasts = listOf(
            ScoreFeeds.Broadcast("Rockies.TV", "COLR", "away", false),
            ScoreFeeds.Broadcast("MLBN (out-of-market only)", "MLBN", "home", true),
            ScoreFeeds.Broadcast("FS1 / FOX ONE", "FS1", "away", true)))
        val all = """{"Rockies.TV": {"number": 5}, "MLB Network": {"number": 213, "aliases": ["MLBN"]}, "FS1": {"number": 219}}"""
        assertEquals("FS1", ScoreFeeds.channel(g, table(all))!!.name)
        // without FS1 in the table: the out-of-market one still comes before the other team's own channel
        assertEquals("MLB Network", ScoreFeeds.channel(g, table("""{"Rockies.TV": {"number": 5}, "MLB Network": {"number": 213, "aliases": ["MLBN"]}}"""))!!.name)
        // "FOX" is not "Fox Deportes", and both are found by their own names
        val fox = game(2, "pre", at("2026-09-27T19:05:00Z"), broadcasts = listOf(
            ScoreFeeds.Broadcast("Fox Deportes / FOX ONE", "FOX Dep.", "home", true), ScoreFeeds.Broadcast("FOX / FOX ONE", "FOX", "home", true)))
        assertEquals(11, ScoreFeeds.channel(fox, table("""{"FOX": {"number": 11}}"""))!!.number)
        assertEquals(465, ScoreFeeds.channel(game(3, "pre", 0, broadcasts = fox.broadcasts.take(1)), table("""{"FOX": {"number": 11}, "Fox Deportes": {"number": 465}}"""))!!.number)
        assertNull(ScoreFeeds.channel(game(3, "pre", 0, broadcasts = fox.broadcasts.take(1)), table("""{"FOX": {"number": 11}}""")))
    }

    @Test fun aStreamingOnlyGameOpensThatApp() {
        val apple = ScoreFeeds.mlbSchedule(load("mlb-schedule-national.json"), dodgers).single { it.pk == 823898L }
        assertEquals(listOf("Apple TV"), apple.broadcasts.map { it.name })   // listed for both sides: once
        val t = table("""{"Apple TV": {"app": "com.apple.atve.amazon.appletv"}, "SportsNet LA": {"number": 690}}""")
        val j = ScoreFeeds.json(apple, t, "/api/scores/logo/mlb-league.svg", null, "/api/scores/logo/mlb-119.svg")
        val ch = j.getJSONObject("channel")
        assertEquals("Apple TV", ch.getString("name")); assertEquals(0, ch.getInt("number")); assertEquals("", ch.getString("link"))
        assertEquals("com.apple.atve.amazon.appletv", ch.getString("app"))
        // a channel with no app of its own says null, so the page opens the TV app
        val snla = ScoreFeeds.json(sept27(), t, null, null, null).getJSONObject("channel")
        assertEquals(690, snla.getInt("number")); assertTrue(snla.isNull("app"))
        // and the rest of the shape
        assertEquals("mlb", j.getString("league")); assertEquals("MLB", j.getString("league_name"))
        assertEquals("/api/scores/logo/mlb-league.svg", j.getString("league_logo"))
        assertTrue(j.getJSONObject("away").isNull("logo")); assertEquals("/api/scores/logo/mlb-119.svg", j.getJSONObject("home").getString("logo"))
        assertEquals("SF", j.getJSONObject("away").getString("abbr")); assertEquals(8, j.getJSONObject("home").getInt("score"))
    }

    // ---------------------------------------------------------------- a failing feed

    @Test fun theLastGoodReadingIsKeptFor2MinutesThenDropped() {
        val good = at("2026-09-27T20:00:00Z")
        assertTrue(ScoreFeeds.keep(good, failing = true, now = good + 119_000))
        assertFalse(ScoreFeeds.keep(good, failing = true, now = good + 121_000))
        // a feed that answers is never "too old": pre-game, it is only read every 15 minutes while nobody looks
        assertTrue(ScoreFeeds.keep(good, failing = false, now = good + 14 * min))
    }

    @Test fun failuresAreRetriedMoreAndMoreSlowly() {
        assertEquals(listOf(15_000L, 15_000L, 30_000L, 60_000L, 120_000L, 240_000L, 300_000L, 300_000L),
            (0..7).map { ScoreFeeds.backOff(it, 15_000L) })
        assertEquals(listOf(60_000L, 120_000L, 240_000L, 300_000L), (1..4).map { ScoreFeeds.backOff(it, 60_000L) })
        // the 15 minute schedule look, failing: from 30 s up to 5 minutes, and never a tight loop
        assertEquals(300_000L, ScoreFeeds.backOff(40, 30_000L))
    }
}
