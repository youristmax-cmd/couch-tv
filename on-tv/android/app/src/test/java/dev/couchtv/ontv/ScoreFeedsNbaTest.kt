package dev.couchtv.ontv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The basketball half of the score bugs' rules (ScoreFeeds.kt), checked on the computer against
 * answers recorded from ESPN's public scoreboard JSON in November 2026 (src/test/resources/scores,
 * cut down to the fields the app reads: facts and logo addresses, no pictures).
 *  - nba-team-lac.json: teams/lac, the Clippers' entry with their next game. nba-team-none.json:
 *    what ESPN answers for a team it does not have ({}).
 *  - nba-event-*.json: one game as scoreboard/<event id> gives it. pre, final and postponed are
 *    Clippers games (the postponed one from January 2025); live-q2, live-q4, the two halftimes and
 *    end-of-3rd were recorded during the evening of 16 November 2026 (other teams' games: the
 *    Clippers did not play that night); final-ot and final-2ot are games of 15 and 12 November 2026.
 *  - nba-sched-*.json: three Clippers games from ESPN's season schedule for the team (the same
 *    kind of entry as "nextEvent"), for their broadcast names.
 *  - nba-event-EDITED-live-ot.json is NOT a recorded state: no game was in overtime while the
 *    recorder ran, so an overtime's period and clock were written by hand over a recorded
 *    4th-quarter moment. The file says so itself.
 * The status names in otherStatusNames() were not recorded either: they are names ESPN uses in
 * other sports or may use, put together by hand to check they are read sensibly.
 */
class ScoreFeedsNbaTest {
    private val min = 60_000L
    private val clippers = 12

    private fun load(name: String): JSONObject =
        JSONObject(javaClass.getResourceAsStream("/scores/$name")!!.bufferedReader().readText())

    private fun at(iso: String) = ScoreFeeds.parseIso(iso)!!
    private fun event(name: String, teamId: Int) = ScoreFeeds.nbaEvent(load(name), teamId)!!
    private fun out(g: ScoreFeeds.Game) = ScoreFeeds.json(g, emptyList(), null, null, null)
    private fun table(json: String) = ScoreFeeds.channels(JSONObject(json))

    // ---------------------------------------------------------------- the team's entry

    @Test fun theTeamsEntryGivesItsIdAndItsNextGame() {
        val t = ScoreFeeds.nbaTeam(load("nba-team-lac.json"))!!
        assertEquals(12, t.id); assertEquals("LAC", t.abbr)
        val g = t.games.single()
        assertEquals("nba-401910006", g.id); assertEquals("nba", g.league)
        assertEquals("pre", g.state); assertEquals("Scheduled", g.status)
        assertEquals(at("2026-11-18T03:30:00Z"), g.startMs)          // ESPN writes "2026-11-18T03:30Z", without seconds
        assertEquals("UTAH", g.awayAbbr); assertEquals(26, g.awayId); assertEquals("LAC", g.homeAbbr); assertEquals(12, g.homeId)
        assertEquals("home", g.ourSide)
        assertNull(g.awayScore); assertNull(g.homeScore); assertNull(g.finalLabel)
        assertEquals(listOf("ClipperVision", "KJZZ-TV", "Jazz+"), g.broadcasts.map { it.name })
        assertEquals(listOf("stream", "tv", "stream"), g.broadcasts.map { it.kind })
        // the logo drawn for a dark background, where the entry lists several
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500-dark/lac.png", g.homeLogo)
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500-dark/utah.png", g.awayLogo)
        val j = out(g)
        assertEquals("NBA", j.getString("league_name")); assertEquals("2026-11-18T03:30:00Z", j.getString("start"))
        assertTrue(j.getJSONObject("away").isNull("score")); assertFalse(j.has("final_label")); assertFalse(j.has("mlb"))
        val n = j.getJSONObject("nba")
        assertEquals("", n.getString("period")); assertEquals("", n.getString("clock"))
        assertEquals(0.0, n.getDouble("remaining"), 0.0); assertFalse(n.getBoolean("halftime"))
    }

    @Test fun aTeamEspnDoesNotHave() {
        assertNull(ScoreFeeds.nbaTeam(load("nba-team-none.json")))
        assertNull(ScoreFeeds.nbaTeam(JSONObject("""{"team": {"abbreviation": "LAC"}}""")))
    }

    // ---------------------------------------------------------------- one game

    @Test fun beforeTheGameTheFeedsZeroIsNotAScore() {
        val g = event("nba-event-pre.json", clippers)
        assertEquals("pre", g.state); assertEquals(at("2026-11-18T03:30:00Z"), g.startMs)
        assertNull(g.awayScore); assertNull(g.homeScore)               // the feed says "0" and "0"
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500-dark/scoreboard/lac.png", g.homeLogo)   // "logoDark"
        assertEquals(listOf("ClipperVision", "KJZZ-TV", "Jazz+"), g.broadcasts.map { it.name })
        // somebody else's game is nobody's
        assertNull(ScoreFeeds.nbaEvent(load("nba-event-pre.json"), 13))
        // a game with no time yet does not show before it starts
        val tbd = load("nba-event-pre.json")
        tbd.getJSONArray("competitions").getJSONObject(0).put("timeValid", false)
        assertNull(ScoreFeeds.nbaEvent(tbd, clippers))
    }

    @Test fun inAQuarter() {
        var g = event("nba-event-live-q2.json", 16)                    // POR 45 at MIN 48, 4:53 left in the 2nd
        assertEquals("live", g.state); assertEquals("In Progress", g.status); assertEquals("4:53 - 2nd Quarter", g.statusDetail)
        assertEquals("POR", g.awayAbbr); assertEquals(45, g.awayScore); assertEquals("MIN", g.homeAbbr); assertEquals(48, g.homeScore)
        assertEquals("home", g.ourSide); assertEquals(2, g.period); assertEquals(293.0, g.clockSecs, 0.0); assertFalse(g.halftime)
        var n = out(g).getJSONObject("nba")
        assertEquals("2ND", n.getString("period")); assertEquals("4:53", n.getString("clock"))
        assertEquals(0.407, n.getDouble("remaining"), 0.0005)           // 293 s of 720
        assertFalse(n.getBoolean("halftime"))
        assertEquals(listOf("KUNP 16", "DAZN MIN", "BlazerVision"), g.broadcasts.map { it.name })
        assertEquals(listOf("away", "home", "away"), g.broadcasts.map { it.side })

        g = event("nba-event-live-q4.json", 5)                         // CHI 88 at CLE 104, 8:05 left in the 4th
        assertEquals(88, g.awayScore); assertEquals(104, g.homeScore)
        n = out(g).getJSONObject("nba")
        assertEquals("4TH", n.getString("period")); assertEquals("8:05", n.getString("clock"))
        assertEquals(0.674, n.getDouble("remaining"), 0.0005)           // 485 s of 720
        assertNull(g.finalLabel)
        // the same game seen from the other bench
        assertEquals("away", event("nba-event-live-q4.json", 4).ourSide)
    }

    @Test fun halftime() {
        val g = event("nba-event-halftime.json", 17)                   // PHI 78 at BKN 59
        assertEquals("live", g.state); assertEquals("Halftime", g.status); assertTrue(g.halftime)
        assertEquals(78, g.awayScore); assertEquals(59, g.homeScore)
        val n = out(g).getJSONObject("nba")
        assertTrue(n.getBoolean("halftime")); assertEquals("2ND", n.getString("period"))
        assertEquals("0:00", n.getString("clock")); assertEquals(0.0, n.getDouble("remaining"), 0.0)
        // another halftime the same evening, where the feed left a tenth of a second on the clock:
        // still halftime, still nothing left
        val h = event("nba-event-halftime-clock-left.json", 16)        // POR 51 at MIN 59, "0.1"
        assertTrue(h.halftime); assertEquals(0.1, h.clockSecs, 0.0)
        val m = out(h).getJSONObject("nba")
        assertTrue(m.getBoolean("halftime")); assertEquals("0:00", m.getString("clock")); assertEquals(0.0, m.getDouble("remaining"), 0.0)
    }

    @Test fun theEndOfAQuarter() {
        val g = event("nba-event-end-of-3rd.json", 2)                  // ORL 75 at BOS 77
        assertEquals("live", g.state); assertEquals("End of Period", g.status); assertEquals("End of 3rd Quarter", g.statusDetail)
        assertFalse(g.halftime)
        val n = out(g).getJSONObject("nba")
        assertEquals("3RD", n.getString("period")); assertEquals("0:00", n.getString("clock"))     // the feed writes "0.0"
        assertEquals(0.0, n.getDouble("remaining"), 0.0); assertFalse(n.getBoolean("halftime"))
        assertEquals(listOf("NBCSN"), g.broadcasts.map { it.name }); assertTrue(g.broadcasts.single().national)
    }

    @Test fun overtime_fromAnEditedMomentNotARecordedOne() {
        val j = load("nba-event-EDITED-live-ot.json")
        assertTrue("the fixture must say it was edited", j.has("_edited"))
        val g = ScoreFeeds.nbaEvent(j, 5)!!
        assertEquals("live", g.state); assertEquals(5, g.period)
        val n = out(g).getJSONObject("nba")
        assertEquals("OT", n.getString("period")); assertEquals("2:31", n.getString("clock"))
        assertEquals(0.503, n.getDouble("remaining"), 0.0005)           // 151 s of an overtime's 300
        assertNull(g.finalLabel)
    }

    @Test fun finals() {
        var g = event("nba-event-final.json", clippers)                // CHA 105 at LAC 113
        assertEquals("final", g.state); assertEquals("Final", g.status); assertEquals("FINAL", g.finalLabel)
        assertEquals(105, g.awayScore); assertEquals(113, g.homeScore)
        var j = out(g)
        assertEquals("FINAL", j.getString("final_label"))
        assertEquals("4TH", j.getJSONObject("nba").getString("period")); assertEquals("0:00", j.getJSONObject("nba").getString("clock"))

        g = event("nba-event-final-ot.json", 18)                       // NY 112 at TOR 108 after one overtime
        assertEquals("final", g.state); assertEquals("F/OT", g.finalLabel); assertEquals("Final/OT", g.statusDetail)
        assertEquals(112, g.awayScore); assertEquals(108, g.homeScore); assertEquals("NY", g.awayAbbr)
        j = out(g)
        assertEquals("F/OT", j.getString("final_label")); assertEquals("OT", j.getJSONObject("nba").getString("period"))
        // the feed leaves "5:00" standing after the end: nothing is left of a game that is over
        assertEquals("0:00", j.getJSONObject("nba").getString("clock")); assertEquals(0.0, j.getJSONObject("nba").getDouble("remaining"), 0.0)
        assertTrue(g.broadcasts.isEmpty()); assertTrue(j.isNull("channel"))

        g = event("nba-event-final-2ot.json", 27)                      // DET 144 at WSH 140 after two
        assertEquals("F/2OT", g.finalLabel); assertEquals("2OT", out(g).getJSONObject("nba").getString("period"))
        assertEquals(144, g.awayScore); assertEquals(140, g.homeScore); assertEquals("WSH", g.homeAbbr)
    }

    @Test fun aPostponedGame() {
        val g = event("nba-event-postponed.json", clippers)            // CHA at LAC, January 2025
        assertEquals("delayed", g.state); assertTrue(g.off); assertEquals("Postponed", g.status)
        assertEquals("nba-401705104-off", g.id)
        assertNull(g.awayScore); assertNull(g.homeScore); assertNull(g.finalLabel)     // the feed says "0" and "0"
        val n = out(g).getJSONObject("nba")
        assertEquals("", n.getString("period")); assertEquals("", n.getString("clock")); assertFalse(n.getBoolean("halftime"))
        // on screen around the time it was to start, and not for hours
        val start = at("2025-01-12T03:30:00Z")
        assertEquals(start, g.startMs)
        val t = ScoreFeeds.track(g, null, start - 120 * min)
        assertFalse(ScoreFeeds.showing(g, t, start - 61 * min)); assertTrue(ScoreFeeds.showing(g, t, start - 59 * min))
        assertTrue(ScoreFeeds.showing(g, t, start + 14 * min)); assertFalse(ScoreFeeds.showing(g, t, start + 16 * min))
    }

    @Test fun otherStatusNames() {
        // not recorded: put together by hand, in the feed's shape
        val st = { name: String, state: String, completed: Boolean, word: String ->
            ScoreFeeds.nbaState(JSONObject().put("period", 2).put("type", JSONObject().put("name", name).put("state", state)
                .put("completed", completed).put("description", word).put("detail", word)))
        }
        st("STATUS_CANCELED", "post", false, "Canceled")!!.let { assertEquals("delayed", it.state); assertTrue(it.off); assertEquals("Canceled", it.status) }
        st("STATUS_DELAYED", "in", false, "Delayed")!!.let { assertEquals("delayed", it.state); assertFalse(it.off); assertFalse(it.suspended) }
        st("STATUS_SUSPENDED", "post", false, "Suspended")!!.let { assertEquals("delayed", it.state); assertTrue(it.suspended) }
        st("STATUS_FORFEIT", "post", true, "Forfeit")!!.let { assertEquals("final", it.state) }
        st("STATUS_END_OF_REGULATION", "in", false, "End of Regulation")!!.let { assertEquals("live", it.state); assertFalse(it.halftime); assertEquals("End of Regulation", it.status) }
        st("STATUS_ABANDONED", "post", false, "Abandoned")!!.let { assertEquals("delayed", it.state); assertTrue(it.off); assertEquals("Abandoned", it.status) }
        st("STATUS_FINAL", "post", true, "")!!.let { assertEquals("final", it.state); assertEquals("Final", it.status) }
        assertNull(st("STATUS_SOMETHING", "", false, "Something"))
        assertNull(ScoreFeeds.nbaState(JSONObject())); assertNull(ScoreFeeds.nbaState(null))
        // a delay in the second quarter keeps the score and the clock where they stood
        val j = load("nba-event-live-q2.json")
        j.getJSONArray("competitions").getJSONObject(0).getJSONObject("status").getJSONObject("type").put("name", "STATUS_DELAYED").put("description", "Delayed")
        val g = ScoreFeeds.nbaEvent(j, 16)!!
        assertEquals("delayed", g.state); assertEquals("Delayed", g.status); assertEquals(48, g.homeScore)
        assertEquals("4:53", out(g).getJSONObject("nba").getString("clock")); assertEquals(0.0, out(g).getJSONObject("nba").getDouble("remaining"), 0.0)
    }

    // ---------------------------------------------------------------- the period and the clock

    @Test fun periodLabels() {
        assertEquals(listOf("", "1ST", "2ND", "3RD", "4TH", "OT", "2OT", "3OT", "4OT"), (0..8).map { ScoreFeeds.nbaPeriod(it) })
    }

    @Test fun theClockAsAFractionOfThePeriod() {
        // a quarter is 12:00
        assertEquals(1.0, ScoreFeeds.nbaRemaining(1, 720.0), 0.0); assertEquals(0.5, ScoreFeeds.nbaRemaining(4, 360.0), 0.0)
        assertEquals(0.35, ScoreFeeds.nbaRemaining(3, 252.0), 1e-9)     // 4:12 left
        assertEquals(0.0, ScoreFeeds.nbaRemaining(2, 0.0), 0.0)
        // an overtime is 5:00, the second and third too
        assertEquals(1.0, ScoreFeeds.nbaRemaining(5, 300.0), 0.0); assertEquals(0.5, ScoreFeeds.nbaRemaining(5, 150.0), 0.0)
        assertEquals(0.1, ScoreFeeds.nbaRemaining(6, 30.0), 1e-9); assertEquals(0.2, ScoreFeeds.nbaRemaining(7, 60.0), 1e-9)
        // never outside 0 to 1, whatever the feed says
        assertEquals(1.0, ScoreFeeds.nbaRemaining(1, 800.0), 0.0); assertEquals(0.0, ScoreFeeds.nbaRemaining(1, -5.0), 0.0)
        assertEquals(1.0, ScoreFeeds.nbaRemaining(5, 720.0), 0.0)
        // the clock's words: the feed's, "0:00" for its "0.0", and made from the seconds when it gives none
        assertEquals("4:53", ScoreFeeds.nbaClock("4:53", 293.0)); assertEquals("45.3", ScoreFeeds.nbaClock("45.3", 45.3))
        assertEquals("0:00", ScoreFeeds.nbaClock("0.0", 0.0)); assertEquals("0:00", ScoreFeeds.nbaClock("", 0.0))
        assertEquals("2:05", ScoreFeeds.nbaClock("", 125.0)); assertEquals("12:00", ScoreFeeds.nbaClock("12:00", 720.0))
    }

    // ---------------------------------------------------------------- the window, as for baseball

    @Test fun theWindowIsTheSameAsBaseballs() {
        val pre = event("nba-event-pre.json", clippers)
        val start = pre.startMs
        var t = ScoreFeeds.track(pre, null, start - 5 * 60 * min)
        assertFalse("61 minutes before", ScoreFeeds.showing(pre, t, start - 61 * min))
        assertTrue("59 minutes before", ScoreFeeds.showing(pre, t, start - 59 * min))
        // the game ends 2 h 20 min later, while the app is watching
        val fin = event("nba-event-final.json", clippers)
        val end = start + 140 * min
        t = ScoreFeeds.track(fin, t, end)
        assertTrue("14 minutes after", ScoreFeeds.showing(fin, t, end + 14 * min))
        assertFalse("16 minutes after", ScoreFeeds.showing(fin, t, end + 16 * min))
        // already over when first heard of: never
        assertFalse(ScoreFeeds.showing(fin, ScoreFeeds.track(fin, null, end), end + min))
        // a live game comes before one that has not started
        val live = event("nba-event-live-q4.json", 5)
        assertEquals(listOf(live.pk, pre.pk), ScoreFeeds.order(listOf(pre, live)).map { it.pk })
    }

    // ---------------------------------------------------------------- following a game the team's entry no longer names

    @Test fun aGameIsFollowedByItsOwnIdFromItsHourUntilItIsOver() {
        val start = at("2026-11-18T03:30:00Z")
        // the entry moved on early (it does so at about tip-off): nothing to read before the game's hour
        assertEquals("wait", ScoreFeeds.nbaFollow(start, 0, start - 61 * min))
        assertEquals("read", ScoreFeeds.nbaFollow(start, 0, start - 59 * min))
        assertEquals("read", ScoreFeeds.nbaFollow(start, 0, start + 150 * min))
        // seen over at 2 h 25 min: read for 30 minutes more (its bug shows for 15), then let go
        val over = start + 145 * min
        assertEquals("read", ScoreFeeds.nbaFollow(start, over, over + 29 * min))
        assertEquals("forget", ScoreFeeds.nbaFollow(start, over, over + 31 * min))
        // never seen to end (the TV slept through it): let go 10 hours after its start
        assertEquals("read", ScoreFeeds.nbaFollow(start, 0, start + 599 * min))
        assertEquals("forget", ScoreFeeds.nbaFollow(start, 0, start + 601 * min))

        // when a game counts as over
        val pre = event("nba-event-pre.json", clippers)
        assertEquals(0L, ScoreFeeds.nbaDoneAt(pre, start - 10 * min))
        assertEquals(0L, ScoreFeeds.nbaDoneAt(event("nba-event-live-q4.json", 5), start))
        assertEquals(0L, ScoreFeeds.nbaDoneAt(event("nba-event-halftime.json", 17), start))
        assertEquals(over, ScoreFeeds.nbaDoneAt(event("nba-event-final.json", clippers), over))
        // a postponed one, first read an hour before its time: counted from that time, so its word
        // can show until 15 minutes after it
        val off = event("nba-event-postponed.json", clippers)
        assertEquals(off.startMs, ScoreFeeds.nbaDoneAt(off, off.startMs - 60 * min))
        assertEquals("read", ScoreFeeds.nbaFollow(off.startMs, off.startMs, off.startMs + 16 * min))
        assertEquals("forget", ScoreFeeds.nbaFollow(off.startMs, off.startMs, off.startMs + 31 * min))
        assertEquals(off.startMs + 5 * min, ScoreFeeds.nbaDoneAt(off, off.startMs + 5 * min))
    }

    // ---------------------------------------------------------------- channels, by ESPN's names

    @Test fun theClippersOwnBroadcastsComeFirst() {
        val g = event("nba-sched-league-pass.json", clippers)          // SAC at LAC: NBA League Pass, KTLA, ClipperVision
        assertEquals(121, g.homeScore); assertEquals(96, g.awayScore)   // this answer writes a score as {"value": 121.0, ...}
        assertEquals(listOf("package", "tv", "stream"), g.broadcasts.map { it.kind })
        val names = out(g).getJSONArray("broadcasts")
        assertEquals(listOf("KTLA", "ClipperVision", "NBA League Pass"), (0 until names.length()).map { names.getString(it) })
        // both of the team's own are in the table and filled in: the one the feed lists first
        assertEquals("KTLA", ScoreFeeds.channel(g, table("""{"ClipperVision": {"app": "com.example.clippers"}, "KTLA": {"number": 5}}"""))!!.name)
        // only one of them filled in: that one
        assertEquals("ClipperVision", ScoreFeeds.channel(g, table("""{"ClipperVision": {"app": "com.example.clippers"}, "KTLA": {}}"""))!!.name)
        // a package for people out of town comes after anything the household can watch...
        assertEquals("KTLA", ScoreFeeds.channel(g, table("""{"NBA League Pass": {"app": "com.example.pass"}, "KTLA": {"number": 5}}"""))!!.name)
        // ...but before a channel that is only named
        assertEquals("NBA League Pass", ScoreFeeds.channel(g, table("""{"NBA League Pass": {"app": "com.example.pass"}, "KTLA": {}}"""))!!.name)
        // by an alias, whatever the capitals; and nothing when the table has none of them
        assertEquals("Clippers", ScoreFeeds.channel(g, table("""{"Clippers": {"aliases": ["clipper vision"], "number": 0, "link": "x://clippers"}}"""))!!.name)
        assertNull(ScoreFeeds.channel(g, table("""{"ESPN": {"number": 206}, "TNT": {"number": 245}}""")))
    }

    @Test fun aNationalBroadcastAndTheOtherTeamsChannel() {
        val g = event("nba-sched-espn-national.json", clippers)        // LAC at LAL: ESPN, Spectrum Sports Net (+), KTLA, ClipperVision
        assertEquals("away", g.ourSide)
        assertEquals(listOf("ESPN", "Spectrum Sports Net", "Spectrum Sports Net +", "KTLA", "ClipperVision"), g.broadcasts.map { it.name })
        val names = out(g).getJSONArray("broadcasts")
        assertEquals(listOf("KTLA", "ClipperVision", "ESPN", "Spectrum Sports Net", "Spectrum Sports Net +"), (0 until names.length()).map { names.getString(it) })
        // national before the other team's own channel
        var c = ScoreFeeds.channel(g, table("""{"ESPN": {"number": 206}, "Spectrum SportsNet": {"number": 691}}"""))
        assertEquals("ESPN", c!!.name); assertEquals(206, c.number)
        // the team's own before national
        c = ScoreFeeds.channel(g, table("""{"ESPN": {"number": 206}, "KTLA": {"number": 5}}"""))
        assertEquals("KTLA", c!!.name)
        // the other team's channel, when it is all the table knows
        c = ScoreFeeds.channel(g, table("""{"Spectrum SportsNet": {"number": 691}}"""))
        assertEquals(691, c!!.number)
        val ch = ScoreFeeds.json(g, table("""{"ESPN": {"number": 206}}"""), null, null, null).getJSONObject("channel")
        assertEquals("ESPN", ch.getString("name")); assertEquals(206, ch.getInt("number")); assertEquals("", ch.getString("link")); assertTrue(ch.isNull("app"))
    }

    @Test fun aGameOnlyOnAStreamingService() {
        val g = event("nba-sched-prime-only.json", clippers)           // LAC at MIA: Prime Video and nothing else
        assertEquals(listOf("Prime Video"), g.broadcasts.map { it.name }); assertEquals("stream", g.broadcasts.single().kind)
        val ch = ScoreFeeds.json(g, table("""{"Prime Video": {"app": "com.example.video"}, "ClipperVision": {"number": 0}}"""), null, null, null).getJSONObject("channel")
        assertEquals("Prime Video", ch.getString("name")); assertEquals("com.example.video", ch.getString("app")); assertEquals(0, ch.getInt("number"))
        assertTrue(ScoreFeeds.json(g, table("""{"ClipperVision": {"number": 0}}"""), null, null, null).isNull("channel"))
    }

    // ---------------------------------------------------------------- logos

    @Test fun logoAddressesAreEspnsOwnAndAskedSmall() {
        // a team's, as a game's answer gives it, at 200 px through ESPN's resizer
        assertEquals("https://a.espncdn.com/combiner/i?img=/i/teamlogos/nba/500-dark/scoreboard/lac.png&w=200&h=200&transparent=true",
            ScoreFeeds.espnLogo("https://a.espncdn.com/i/teamlogos/nba/500-dark/scoreboard/lac.png", 200))
        // the league's dark one already comes through the resizer at 500 px: the same picture at 100
        assertEquals("https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500-dark/nba.png&w=100&h=100&transparent=true",
            ScoreFeeds.espnLogo("https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500-dark/nba.png&w=500&h=500&transparent=true", 100))
        // another of its pictures: as it is
        assertEquals("https://a.espncdn.com/guid/083a58a6/logos/primary_logo_on_black_color.png",
            ScoreFeeds.espnLogo("https://a.espncdn.com/guid/083a58a6/logos/primary_logo_on_black_color.png", 200))
        // nothing that is not https on espncdn.com is ever fetched
        for (bad in listOf("http://a.espncdn.com/i/teamlogos/nba/500/lac.png", "https://example.org/i/teamlogos/nba/500/lac.png",
            "https://a.espncdn.com.example.org/i/x.png", "https://notespncdn.com/i/x.png", "https://example.org/?u=https://a.espncdn.com/i/x.png",
            "file:///data/x.png", "", "lac.png")) assertNull(bad, ScoreFeeds.espnLogo(bad, 200))
        assertNull(ScoreFeeds.espnLogo(null, 200))
        // the league's logos as ESPN's scoreboard lists them (recorded): the dark one
        val league = JSONObject("""{"logos": [
            {"href": "https://a.espncdn.com/i/teamlogos/leagues/500/nba.png", "rel": ["full", "default"]},
            {"href": "https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500-dark/nba.png&w=500&h=500&transparent=true", "rel": ["full", "dark"]}]}""")
        assertEquals("https://a.espncdn.com/combiner/i?img=/i/teamlogos/leagues/500-dark/nba.png&w=500&h=500&transparent=true", ScoreFeeds.espnLogoOf(league))
        // only the usual one listed: that one; nothing listed: nothing
        assertEquals("https://a.espncdn.com/i/teamlogos/nba/500/lac.png", ScoreFeeds.espnLogoOf(JSONObject("""{"logo": "https://a.espncdn.com/i/teamlogos/nba/500/lac.png"}""")))
        assertNull(ScoreFeeds.espnLogoOf(JSONObject())); assertNull(ScoreFeeds.espnLogoOf(null))
    }

    /** The smallest PNG there is (one clear pixel), written out by hand: no logo is kept in the repository. */
    private fun png(width: Int = 1, height: Int = 1): ByteArray {
        val b = intArrayOf(0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 0x49, 0x48, 0x44, 0x52,
            0, 0, 0, 1, 0, 0, 0, 1, 8, 6, 0, 0, 0, 0x1F, 0x15, 0xC4, 0x89,
            0, 0, 0, 0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, 0x9C, 0x63, 0, 1, 0, 0, 5, 0, 1, 0x0D, 0x0A, 0x2D, 0xB4,
            0, 0, 0, 0, 0x49, 0x45, 0x4E, 0x44, 0xAE, 0x42, 0x60, 0x82).map { it.toByte() }.toByteArray()
        b[18] = (width shr 8).toByte(); b[19] = width.toByte(); b[22] = (height shr 8).toByte(); b[23] = height.toByte()
        return b
    }

    @Test fun onlyARealPictureIsKept() {
        assertEquals(67, png().size)
        assertEquals(1 to 1, ScoreFeeds.pngSize(png())); assertEquals(200 to 200, ScoreFeeds.pngSize(png(200, 200)))
        assertTrue(ScoreFeeds.picture("png", png())); assertTrue(ScoreFeeds.picture("png", png(500, 500)))
        // far bigger than a logo needs to be
        assertFalse(ScoreFeeds.picture("png", png(4096, 4096)))
        // not a PNG at all: an error page, a drawing, a few bytes, a cut-off header
        val page = "<html><head><title>Access Denied</title></head><body>You don't have permission</body></html>".toByteArray()
        assertNull(ScoreFeeds.pngSize(page)); assertFalse(ScoreFeeds.picture("png", page))
        assertFalse(ScoreFeeds.picture("png", png().copyOf(20))); assertFalse(ScoreFeeds.picture("png", ByteArray(0)))
        assertFalse(ScoreFeeds.picture("png", png().also { it[1] = 0x51 }))
        // a PNG with 200 KB of anything after its header is too much
        assertFalse(ScoreFeeds.picture("png", png() + ByteArray(200_000)))
        // MLB's drawings: a plain SVG and nothing else
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><path d="M0 0h10v10z"/></svg>""".toByteArray()
        assertTrue(ScoreFeeds.picture("svg", svg))
        assertFalse(ScoreFeeds.picture("svg", page)); assertFalse(ScoreFeeds.picture("svg", "<svg/>".toByteArray()))
        assertFalse(ScoreFeeds.picture("svg", """<svg xmlns="http://www.w3.org/2000/svg"><script>alert(1)</script><path d="M0 0h10v10z"/></svg>""".toByteArray()))
        // each kind only under its own name
        assertFalse(ScoreFeeds.picture("svg", png())); assertFalse(ScoreFeeds.picture("png", svg)); assertFalse(ScoreFeeds.picture("gif", png()))
    }
}
