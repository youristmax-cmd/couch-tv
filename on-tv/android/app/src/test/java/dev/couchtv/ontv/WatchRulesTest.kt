package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep watching's rules (WatchRules.kt), on a pretend TV: the looks at media sessions below are
 * made up by hand, in the shape Watching.kt hands them over. Nobody knows yet what Netflix, Hulu
 * and the others really put in their sessions on a Fire TV; GET /api/continue/log is how that
 * gets found out, and these tests are about the rules, not about any one app.
 */
class WatchRulesTest {
    private val me = "dev.couchtv.ontv"
    private val netflix = "com.netflix.ninja"
    private val hulu = "com.hulu.plus"
    private val ex = WatchRules.DEFAULT_EXCLUDE
    private val playing = WatchRules.STATE_PLAYING
    private val paused = 2
    private val min = 60_000L
    private val wall0 = 1_800_000_000_000L          // some day in 2027, by the clock on the wall

    /** One look. `at` is elapsed time; the wall clock runs alongside it. */
    private fun seen(pkg: String, title: String?, second: String? = null, state: Int = playing, at: Long, pos: Long = -1, dur: Long = 0,
                     updated: Long = at, speed: Float = 1f, picture: String? = null, artUri: String? = null, app: String? = null,
                     mediaId: String? = null) =
        WatchRules.Seen(pkg, state, pos, speed, updated, at, wall0 + at, title, second, dur, artUri, mediaId, null, picture, app)

    /** A log with one title already counted: played for three minutes, a look every minute. Leaves it paused. */
    private fun watched(log: WatchRules.Log, pkg: String, title: String, second: String? = null, from: Long, dur: Long = 0, pos: Long = -1): Long {
        var t = from
        for (i in 0..3) { log.see(seen(pkg, title, second, at = t, pos = if (pos < 0) -1 else pos + i * min, dur = dur), ex); t += min }
        log.see(seen(pkg, title, second, state = paused, at = t - min + 1000, pos = if (pos < 0) -1 else pos + 3 * min + 1000, dur = dur), ex)
        return t
    }

    private fun titles(log: WatchRules.Log, at: Long, lookup: Boolean = false, exclude: Collection<String> = ex) =
        log.items(wall0 + at, lookup, exclude).map { it.getString("title") }

    // ---------------------------------------------------------------- rule 1: which apps

    @Test fun whichAppsCount() {
        for (p in listOf(netflix, hulu, "com.amazon.avod", "com.disney.disneyplus", "com.wbd.stream", "com.peacocktv.peacockandroid", "tv.some.new.app"))
            assertTrue(p, WatchRules.watched(p, ex, me))
        // music and voice, YouTube in both its packages, live TV, and this app itself
        for (p in listOf("com.spotify.tv.android", "com.amazon.alexamediaplayer.runtime.ftv", "com.amazon.vizzini", "com.amazon.firetv.youtube",
            "com.google.android.youtube.tv", "com.att.tv", me, ""))
            assertFalse(p, WatchRules.watched(p, ex, me))
        // the list is a setting: with YouTube taken off it, YouTube counts; this app never does
        val mine = ex - "com.amazon.firetv.youtube"
        assertTrue(WatchRules.watched("com.amazon.firetv.youtube", mine, me))
        assertFalse(WatchRules.watched(me, emptyList(), me))
        assertTrue(WatchRules.watched("com.att.tv", emptyList(), me))
    }

    // ---------------------------------------------------------------- rule 5: season and episode

    @Test fun seasonAndEpisodeOutOfTheText() {
        val both = mapOf(
            "S1 E2" to (1 to 2), "S1:E2" to (1 to 2), "S01E02" to (1 to 2), "s3e10" to (3 to 10), "S2 · E5" to (2 to 5),
            "Season 1 Episode 2" to (1 to 2), "Season 1, Episode 2" to (1 to 2), "Season 2 Ep. 5" to (2 to 5), "season 12 episode 103" to (12 to 103),
            "1x02" to (1 to 2), "Bluey 3x14 Sleepytime" to (3 to 14), "Bluey - S2 E5 - Sleepytime" to (2 to 5), "S1:E3 \"Holly, Jolly\"" to (1 to 3))
        for ((text, want) in both) {
            val e = WatchRules.episode(text)
            assertNotNull(text, e); assertEquals(text, want.first, e!!.season); assertEquals(text, want.second, e.episode)
        }
        // the episode alone
        for ((text, ep) in mapOf("E3 · The Name" to 3, "E12: Finale" to 12, "E7" to 7, "Episode 7" to 7, "Ep. 12" to 12, "The Bear, Episode 4" to 4)) {
            val e = WatchRules.episode(text)
            assertNotNull(text, e); assertNull(text, e!!.season); assertEquals(text, ep, e.episode)
        }
        // the season alone
        WatchRules.episode("Season 4")!!.let { assertEquals(4, it.season); assertNull(it.episode) }
        // nothing that only looks like one: what is not said stays unknown
        for (text in listOf("Stranger Things", "4x4 Adventures", "1920x1080 test card", "E3 2026 Highlights", "Se7en", "Top 10 Episodes",
            "Station Eleven", "Class of 09", "", "   ", null))
            assertNull(text ?: "null", WatchRules.episode(text))
        // where it was found, so the words around it can be read
        WatchRules.episode("Bluey - S2 E5 - Sleepytime")!!.let { assertEquals("Bluey - ", "Bluey - S2 E5 - Sleepytime".substring(0, it.start)); assertEquals(" - Sleepytime", "Bluey - S2 E5 - Sleepytime".substring(it.end)) }
    }

    @Test fun theTitleAndTheEpisodeFromASessionsTwoLines() {
        val told = { t: String?, s: String? -> WatchRules.describe(t, s)?.let { listOf(it.title, it.episodeTitle, it.season, it.episode) } }
        // the second line names the episode
        assertEquals(listOf("Stranger Things", "The Weirdo on Maple Street", 1, 2), told("Stranger Things", "S1:E2 The Weirdo on Maple Street"))
        assertEquals(listOf("Stranger Things", null, 1, 2), told("Stranger Things", "Season 1, Episode 2"))
        assertEquals(listOf("The Bear", "Review", null, 7), told("The Bear", "E7 · Review"))
        // the title does, after the show's name
        assertEquals(listOf("Bluey", "Sleepytime", 2, 5), told("Bluey - S2 E5 - Sleepytime", null))
        assertEquals(listOf("Bluey", "Sleepytime", 2, 5), told("Bluey: Season 2 Episode 5: Sleepytime", ""))
        // the title is only the episode, and the second line is all there is to call the show
        assertEquals(listOf("Stranger Things", "The Weirdo on Maple Street", 1, 2), told("S1:E2 The Weirdo on Maple Street", "Stranger Things"))
        // ...and with no second line it stays as the app wrote it
        assertEquals(listOf("S1 E2 · Pilot", "Pilot", 1, 2), told("S1 E2 · Pilot", null))
        // no episode anywhere: a film, or an app that does not say
        assertEquals(listOf("Dune: Part Two", null, null, null), told("Dune: Part Two", null))
        assertEquals(listOf("Dune: Part Two", "2024 · PG-13", null, null), told("  Dune:  Part Two ", "2024 · PG-13"))
        // a second line that only repeats the title is not an episode's name
        assertEquals(listOf("Dune", null, null, null), told("Dune", "dune"))
        // no title, no entry
        assertNull(told(null, "S1 E2")); assertNull(told("   ", null))
    }

    // ---------------------------------------------------------------- rule 6: position

    @Test fun positionIsWorkedOutFromThePlaybackState() {
        // reported 1:00 at second 1; looked at 30 s later, still playing: 1:30
        assertEquals(90_000L, WatchRules.position(seen(netflix, "X", at = 31_000, pos = 60_000, updated = 1000)))
        // at twice the speed: 2:00. At "speed 0" while playing (some apps): taken as playing normally
        assertEquals(120_000L, WatchRules.position(seen(netflix, "X", at = 31_000, pos = 60_000, updated = 1000, speed = 2f)))
        assertEquals(90_000L, WatchRules.position(seen(netflix, "X", at = 31_000, pos = 60_000, updated = 1000, speed = 0f)))
        // paused: where it stopped, however long ago
        assertEquals(60_000L, WatchRules.position(seen(netflix, "X", state = paused, at = 600_000, pos = 60_000, updated = 1000)))
        // never past the end, never before the start
        assertEquals(100_000L, WatchRules.position(seen(netflix, "X", at = 500_000, pos = 60_000, updated = 1000, dur = 100_000)))
        assertEquals(0L, WatchRules.position(seen(netflix, "X", at = 31_000, pos = 5000, updated = 1000, speed = -8f)))
        // the app gives no position: unknown, not zero; no time of its report: as reported
        assertNull(WatchRules.position(seen(netflix, "X", at = 31_000, pos = -1)))
        assertEquals(60_000L, WatchRules.position(seen(netflix, "X", at = 31_000, pos = 60_000, updated = 0)))
        // how far in
        assertEquals(0.25, WatchRules.progress(300_000, 1_200_000)!!, 1e-9)
        assertNull(WatchRules.progress(null, 1_200_000)); assertNull(WatchRules.progress(300_000, 0))
        assertEquals(1.0, WatchRules.progress(2_000_000, 1_200_000)!!, 0.0)
    }

    // ---------------------------------------------------------------- rule 2: what counts as watched

    @Test fun twoMinutesOfPlayingNotTwoMinutesOfClock() {
        assertEquals(120_000L, WatchRules.MIN_PLAYED_MS)
        val log = WatchRules.Log(me)
        var t = 1_000_000L
        // a preview on the app's home screen: 30 seconds of playing
        assertEquals("not yet", log.see(seen(netflix, "Bluey", at = t), ex).decision)
        t += 30_000
        log.see(seen(netflix, "Bluey", state = paused, at = t), ex).let { assertEquals("not yet", it.decision); assertEquals("played 30 s of the 120 s it takes", it.detail) }
        assertTrue(titles(log, t).isEmpty())
        // ten minutes go by with it paused: none of that counts
        t += 10 * min
        log.see(seen(netflix, "Bluey", at = t), ex).let { assertEquals("not yet", it.decision); assertEquals("played 30 s of the 120 s it takes", it.detail) }
        // really watching now: a minute more is not enough, another half minute is
        t += min
        assertEquals("not yet", log.see(seen(netflix, "Bluey", at = t), ex).decision)
        t += 30_000
        assertEquals("counted", log.see(seen(netflix, "Bluey", at = t), ex).decision)
        assertEquals(listOf("Bluey"), titles(log, t))
    }

    @Test fun aTitleThatNeverGetsItsTwoMinutesStartsAgainAfterSixHours() {
        val log = WatchRules.Log(me)
        var t = 1_000_000L
        log.see(seen(netflix, "Preview", at = t), ex); t += min
        assertEquals("played 60 s of the 120 s it takes", log.see(seen(netflix, "Preview", state = paused, at = t), ex).detail)
        // the same preview the next morning: its minute from yesterday is gone
        t += 7 * 60 * min
        log.see(seen(netflix, "Preview", at = t), ex); t += min
        log.see(seen(netflix, "Preview", state = paused, at = t), ex).let { assertEquals("not yet", it.decision); assertEquals("played 60 s of the 120 s it takes", it.detail) }
        assertTrue(titles(log, t).isEmpty())
    }

    @Test fun aLookAfterALongGapCountsAMinuteAndAHalfAtMost() {
        // the TV slept with a session still saying "playing": ten minutes later only 90 s are counted
        val log = WatchRules.Log(me)
        log.see(seen(netflix, "Bluey", at = 1_000_000), ex)
        assertEquals("played 90 s of the 120 s it takes", log.see(seen(netflix, "Bluey", at = 1_000_000 + 10 * min), ex).detail)
    }

    @Test fun aShortThingIsNotKept() {
        assertEquals(300_000L, WatchRules.MIN_DURATION_MS)
        val log = WatchRules.Log(me)
        // a 3 minute trailer, watched to the end
        val t = watched(log, netflix, "Trailer: Dune", from = 1_000_000, dur = 3 * min, pos = 0)
        assertTrue(titles(log, t).isEmpty())
        assertEquals("too short", log.see(seen(netflix, "Trailer: Dune", state = paused, at = t, dur = 3 * min, pos = 3 * min), ex).decision)
        // exactly 5 minutes is long enough; an app that gives no length is given the benefit of the doubt
        val t2 = watched(log, netflix, "Short Film", from = t, dur = 5 * min, pos = 0)
        val t3 = watched(log, hulu, "No Length Given", from = t2)
        assertEquals(listOf("No Length Given", "Short Film"), titles(log, t3))
    }

    // ---------------------------------------------------------------- rule 3: finished

    @Test fun finishedLeavesTheRowAndTheNextEpisodeBringsItBack() {
        assertEquals(0.95, WatchRules.FINISHED, 0.0)
        val log = WatchRules.Log(me)
        var t = watched(log, netflix, "Bluey", "S1 E2 · Hospital", from = 1_000_000, dur = 20 * min, pos = 0)
        assertEquals(listOf("Bluey"), titles(log, t))
        // 94% in: still there. 95%: done
        assertEquals("counted", log.see(seen(netflix, "Bluey", "S1 E2 · Hospital", state = paused, at = t, dur = 20 * min, pos = 18 * min + 48_000), ex).decision)
        assertEquals(listOf("Bluey"), titles(log, t))
        t += 1000
        log.see(seen(netflix, "Bluey", "S1 E2 · Hospital", state = paused, at = t, dur = 20 * min, pos = 19 * min), ex).let {
            assertEquals("finished", it.decision); assertEquals("at 95%: done, not in the row", it.detail) }
        assertTrue(titles(log, t).isEmpty())
        // the app plays the next episode by itself: the same card, back in the row
        t += 5000
        assertEquals("counted", log.see(seen(netflix, "Bluey", "S1 E3 · Keepy Uppy", at = t, dur = 21 * min, pos = 4000), ex).decision)
        val card = log.items(wall0 + t, false, ex).single()
        assertEquals(3, card.getInt("episode")); assertEquals("Keepy Uppy", card.getString("episode_title")); assertEquals(0.003, card.getDouble("progress"), 0.0005)
    }

    // ---------------------------------------------------------------- rule 4: the same thing twice

    @Test fun oneCardPerAppAndTitle() {
        val log = WatchRules.Log(me)
        var t = watched(log, netflix, "Bluey", "S1 E2 · Hospital", from = 1_000_000, dur = 20 * min, pos = 0)
        // another episode, written a little differently: still the one card, with the new episode and nothing of the old
        log.see(seen(netflix, "BLUEY!", "S1 E3 · Keepy Uppy", at = t, mediaId = "81234"), ex)
        var cards = log.items(wall0 + t, false, ex)
        assertEquals(1, cards.size)
        cards[0].let {
            assertEquals("BLUEY!", it.getString("title")); assertEquals(1, it.getInt("season")); assertEquals(3, it.getInt("episode"))
            assertEquals("Keepy Uppy", it.getString("episode_title")); assertTrue(it.isNull("progress")); assertTrue(it.isNull("duration_ms"))
        }
        assertEquals(1, log.size)
        // a look that says nothing about the episode (an advert, say) leaves the episode as it was
        log.see(seen(netflix, "Bluey", null, at = t + 1000), ex)
        assertEquals(3, log.items(wall0 + t, false, ex).single().getInt("episode"))
        // the same title in another app is another card, once it has its own two minutes there
        t = watched(log, "com.disney.disneyplus", "Bluey", "S2 E1 · Dance Mode", from = t + 2000)
        cards = log.items(wall0 + t, false, ex)
        assertEquals(listOf("com.disney.disneyplus", netflix), cards.map { it.getJSONObject("app").getString("package") })
        assertEquals(WatchRules.key(netflix, "Bluey"), WatchRules.key(netflix, " bluey!! "))
        assertFalse(WatchRules.key(netflix, "Bluey") == WatchRules.key(hulu, "Bluey"))
    }

    // ---------------------------------------------------------------- rule 6: when it is written, and how much is kept

    @Test fun writtenWhenItStopsOrChangesAndOnceAMinuteWhilePlaying() {
        assertEquals(60_000L, WatchRules.WRITE_EVERY_MS)
        val log = WatchRules.Log(me)
        var t = 1_000_000L
        // not counted yet: nothing to write
        assertFalse(log.see(seen(netflix, "Bluey", at = t), ex).write); t += min
        assertFalse(log.see(seen(netflix, "Bluey", at = t), ex).write); t += min
        // counted at the two minute look: written then
        log.see(seen(netflix, "Bluey", at = t), ex).let { assertEquals("counted", it.decision); assertTrue(it.write) }
        // looks in the next minute do not write again (an app may report every second)...
        for (i in 1..5) { t += 10_000; assertFalse("look $i", log.see(seen(netflix, "Bluey", at = t), ex).write) }
        // ...the look a minute after the last write does
        t += 10_000
        assertTrue(log.see(seen(netflix, "Bluey", at = t), ex).write)
        // a pause is written at once, and a second look at the same pause is not
        t += 5000
        assertTrue(log.see(seen(netflix, "Bluey", state = paused, at = t), ex).write)
        assertFalse(log.see(seen(netflix, "Bluey", state = paused, at = t + 1000), ex).write)
        // back to playing, then another title starts: written, so the first one's place is kept
        t += 10_000
        log.see(seen(netflix, "Bluey", at = t), ex); t += 20_000
        assertTrue(log.see(seen(netflix, "Something Else", at = t), ex).write)
        // the session goes while that one plays (the app was closed): nothing of it counted, nothing to write
        assertFalse(log.gone(netflix, t + 30_000))
        // a counted title's session going is written
        t = watched(log, hulu, "The Bear", from = t + min)
        log.see(seen(hulu, "The Bear", at = t), ex)
        assertTrue(log.gone(hulu, t + 30_000))
        assertFalse(log.gone(hulu, t + 40_000)); assertFalse(log.playing())
    }

    @Test fun fortyTitlesAndSixtyDays() {
        assertEquals(40, WatchRules.MAX_ENTRIES); assertEquals(60L * 24 * 60 * min, WatchRules.MAX_AGE_MS)
        val day = 24 * 60 * min
        val list = JSONArray()
        // 45 titles, one a day, the oldest 44 days ago; and two from long ago
        for (i in 0 until 45) list.put(JSONObject().put("key", "$netflix|show $i").put("package", netflix).put("title", "Show $i").put("played_ms", 200_000).put("last_played", wall0 - i * day))
        list.put(JSONObject().put("key", "$hulu|old").put("package", hulu).put("title", "Old").put("played_ms", 200_000).put("last_played", wall0 - 61 * day))
        list.put(JSONObject().put("key", "$hulu|older").put("package", hulu).put("title", "Older").put("played_ms", 200_000).put("last_played", wall0 - 200 * day))
        val log = WatchRules.Log(me)
        log.load(JSONObject().put("entries", list))
        assertEquals(47, log.size)
        // the row itself never shows what is older than 60 days, and 20 cards at most, newest first
        val row = log.items(wall0, false, ex).map { it.getString("title") }
        assertEquals(20, row.size); assertEquals("Show 0", row.first()); assertEquals("Show 19", row.last())
        // writing drops the two old ones and all but the 40 newest
        val gone = log.prune(wall0).map { it.title }.toSet()
        assertEquals(setOf("Old", "Older", "Show 40", "Show 41", "Show 42", "Show 43", "Show 44"), gone)
        assertEquals(40, log.size)
        assertTrue(log.prune(wall0).isEmpty())
    }

    @Test fun theLogComesBackAfterARestart() {
        val log = WatchRules.Log(me)
        val t = watched(log, netflix, "Bluey", "S1 E2 · Hospital", from = 1_000_000, dur = 20 * min, pos = 5 * min)
        log.find(log.items(wall0 + t, false, ex).single().getString("id"))!!.let { it.art = "0123456789ab.jpg"; it.artAt = 77; it.artMark = "640x360:1f"; it.appName = "Netflix" }
        val again = WatchRules.Log(me)
        again.load(JSONObject(log.toJson().toString()))
        assertEquals(log.items(wall0 + t, false, ex).toString(), again.items(wall0 + t, false, ex).toString())
        again.items(wall0 + t, false, ex).single().let {
            assertEquals("Bluey", it.getString("title")); assertEquals(2, it.getInt("episode")); assertEquals("/api/continue/art/0123456789ab.jpg?v=77", it.getString("backdrop"))
            assertEquals(0.4, it.getDouble("progress"), 0.01)
        }
        // nothing to load, or something that is not a log: an empty log, not an error
        again.load(null); assertEquals(0, again.size)
        again.load(JSONObject().put("entries", JSONArray().put(JSONObject().put("title", "No key")).put("not an entry"))); assertEquals(0, again.size)
    }

    // ---------------------------------------------------------------- the card

    @Test fun theCardsShape() {
        val log = WatchRules.Log(me)
        val t = watched(log, netflix, "Stranger Things", "S1:E2 The Weirdo on Maple Street", from = 1_000_000, dur = 50 * min, pos = 10 * min)
        val it = log.items(wall0 + t, false, ex).single()
        assertTrue(Regex("w-[0-9a-f]{12}").matches(it.getString("id")))
        assertEquals("Stranger Things", it.getString("title")); assertEquals("The Weirdo on Maple Street", it.getString("episode_title"))
        assertEquals(1, it.getInt("season")); assertEquals(2, it.getInt("episode"))
        // an app the table knows: its id and name; the row's old fields are all there
        it.getJSONObject("app").let { a -> assertEquals("netflix", a.getString("id")); assertEquals("Netflix", a.getString("name")); assertEquals(netflix, a.getString("package")) }
        assertEquals("netflix", it.getString("service")); assertEquals("continue", it.getString("kind")); assertEquals("tv", it.getString("source"))
        assertTrue(it.isNull("poster")); assertTrue(it.isNull("backdrop"))
        assertEquals(50 * min, it.getLong("duration_ms")); assertEquals(13 * min + 1000, it.getLong("position_ms"))
        assertEquals(0.26, it.getDouble("progress"), 0.001)
        assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\dZ""").matches(it.getString("last_played")))
        // an app the table does not know goes by the name the TV gives it, with no service
        val t2 = watched(log, hulu, "The Bear", from = t)
        log.see(seen(hulu, "The Bear", state = paused, at = t2, app = "Hulu"), ex)
        log.items(wall0 + t2, false, ex).first().let { c ->
            assertEquals("Hulu", c.getJSONObject("app").getString("name")); assertTrue(c.getJSONObject("app").isNull("id")); assertTrue(c.isNull("service"))
            assertTrue(c.isNull("progress")); assertTrue(c.isNull("position_ms")); assertTrue(c.isNull("duration_ms")) }
        // the id is the same every time, and differs from app to app
        assertEquals(WatchRules.id(WatchRules.key(netflix, "Stranger Things")), it.getString("id"))
        assertFalse(WatchRules.id(WatchRules.key(hulu, "Stranger Things")) == it.getString("id"))
    }

    @Test fun whichPictureACardGets() {
        val log = WatchRules.Log(me)
        val t = watched(log, netflix, "Bluey", from = 1_000_000)
        val e = log.all().single()
        val card = { lookup: Boolean -> log.items(wall0 + t, lookup, ex).single() }
        // nothing from the app, nothing looked up: no picture (the page draws a title card)
        assertTrue(card(true).isNull("backdrop")); assertTrue(card(true).isNull("poster"))
        // looked up outside: handed out only while looking up is allowed
        e.poster = "https://images.justwatch.com/poster/1/s592/bluey.jpg"; e.backdrop = "https://images.justwatch.com/backdrop/2/s1440/bluey.jpg"; e.lookedOk = true
        assertEquals(e.backdrop, card(true).getString("backdrop")); assertEquals(e.poster, card(true).getString("poster"))
        assertTrue(card(false).isNull("backdrop")); assertTrue(card(false).isNull("poster"))
        // an address the session gave (https only) comes before anything looked up...
        log.see(seen(netflix, "Bluey", state = paused, at = t, artUri = "https://img.example.com/bluey/wide.jpg"), ex)
        assertEquals("https://img.example.com/bluey/wide.jpg", card(true).getString("backdrop")); assertTrue(card(true).isNull("poster"))
        assertEquals("https://img.example.com/bluey/wide.jpg", card(false).getString("backdrop"))
        // ...and a copy of the picture the session carried comes first of all
        e.art = WatchRules.artFile(e.id); e.artAt = 1234
        assertEquals("/api/continue/art/${e.id.removePrefix("w-")}.jpg?v=1234", card(false).getString("backdrop"))
        // only https is ever handed to the page
        assertEquals("https://a.example/x.jpg", WatchRules.httpsOnly(" https://a.example/x.jpg "))
        for (bad in listOf("http://a.example/x.jpg", "content://media/external/1", "file:///sdcard/x.jpg", "android.resource://x/1", "https://a.example/x y.jpg", "", null))
            assertNull(bad ?: "null", WatchRules.httpsOnly(bad))
    }

    @Test fun whenTheSessionsPictureIsKept() {
        val log = WatchRules.Log(me)
        var t = 1_000_000L
        // a candidate's picture is not kept: previews must not fill the TV's storage
        assertFalse(log.see(seen(netflix, "Bluey", at = t, picture = "640x360:aa"), ex).picture); t += min
        assertFalse(log.see(seen(netflix, "Bluey", at = t, picture = "640x360:aa"), ex).picture); t += min
        // counted: this picture is wanted
        val v = log.see(seen(netflix, "Bluey", at = t, picture = "640x360:aa"), ex)
        assertEquals("counted", v.decision); assertTrue(v.picture)
        v.entry!!.artMark = "640x360:aa"       // Watching.kt kept it
        // the same picture again is not; another one (the next episode's) is; no picture, nothing
        assertFalse(log.see(seen(netflix, "Bluey", at = t + 1000, picture = "640x360:aa"), ex).picture)
        assertTrue(log.see(seen(netflix, "Bluey", at = t + 2000, picture = "640x360:bb"), ex).picture)
        assertFalse(log.see(seen(netflix, "Bluey", at = t + 3000), ex).picture)
        // the file's name is made from the id and is the only kind the route serves
        val name = WatchRules.artFile(v.entry!!.id)
        assertTrue(WatchRules.ART_FILE.matches(name))
        for (bad in listOf("../watching.json", "0123456789ab.png", "0123456789AB.jpg", "0123456789abc.jpg", "x.jpg", "0123456789ab.jpg/..", ""))
            assertFalse(bad, WatchRules.ART_FILE.matches(bad))
    }

    // ---------------------------------------------------------------- rule 8: looking up outside

    @Test fun lookingATitleUpOutsideIsOffUnlessSwitchedOn() {
        assertEquals(24 * 60 * min, WatchRules.LOOKUP_RETRY_MS)
        val log = WatchRules.Log(me)
        val t = watched(log, netflix, "Bluey", from = 1_000_000)
        val e = log.all().single()
        val now = wall0 + t
        // off: never, whatever else is true
        assertFalse(WatchRules.wantsLookup(e, false, now))
        // on, no picture of the app's own, never asked: yes, and so for a title that was in the log before the setting changed
        assertTrue(WatchRules.wantsLookup(e, true, now))
        // asked and nothing found (or no answer): not again for a day, then once more
        e.lookedAt = now; e.lookedOk = false
        assertFalse(WatchRules.wantsLookup(e, true, now + 23 * 60 * min)); assertTrue(WatchRules.wantsLookup(e, true, now + 25 * 60 * min))
        // found: never again
        e.lookedOk = true
        assertFalse(WatchRules.wantsLookup(e, true, now + 400 * 60 * min))
        // the app gave a picture of its own: nothing to look up
        e.lookedOk = false; e.lookedAt = 0; e.artUri = "https://img.example.com/x.jpg"
        assertFalse(WatchRules.wantsLookup(e, true, now))
        e.artUri = null; e.art = "0123456789ab.jpg"
        assertFalse(WatchRules.wantsLookup(e, true, now))
    }

    // ---------------------------------------------------------------- rule 12, and an app left out later

    @Test fun forgettingACardAndAllOfThem() {
        val log = WatchRules.Log(me)
        var t = watched(log, netflix, "Bluey", from = 1_000_000)
        t = watched(log, hulu, "The Bear", from = t)
        val id = log.items(wall0 + t, false, ex).first { it.getString("title") == "Bluey" }.getString("id")
        assertEquals("Bluey", log.forget(id)!!.title)
        assertEquals(listOf("The Bear"), titles(log, t)); assertNull(log.forget(id)); assertNull(log.forget("w-000000000000"))
        assertTrue(log.takeWrite(t))
        // it does not walk straight back in: it needs its two minutes again
        assertEquals("not yet", log.see(seen(netflix, "Bluey", state = paused, at = t), ex).decision)
        // an app left out later: its cards leave the row at once, and come back if it is let in again
        assertTrue(titles(log, t, exclude = ex + hulu).isEmpty())
        assertEquals("excluded", log.see(seen(hulu, "The Bear", at = t), ex + hulu).decision)
        assertEquals(listOf("The Bear"), titles(log, t))
        // all of them
        assertEquals(listOf("The Bear"), log.clear().map { it.title })
        assertEquals(0, log.size); assertTrue(titles(log, t).isEmpty())
    }

    @Test fun whatIsNotATitleOrNotWatchedHere() {
        val log = WatchRules.Log(me)
        // a session with nothing in it (an app's player between two things)
        log.see(seen(netflix, null, at = 1_000_000), ex).let { assertEquals("no title", it.decision); assertNull(it.entry); assertFalse(it.write) }
        // music, YouTube, live TV and this app: seen, and left out
        for (p in listOf("com.spotify.tv.android", "com.amazon.firetv.youtube", "com.att.tv", me))
            log.see(seen(p, "Something", at = 1_000_000), ex).let { assertEquals(p, "excluded", it.decision); assertNull(it.entry) }
        assertFalse(log.playing())
        log.see(seen(netflix, "Bluey", at = 1_000_000), ex); assertTrue(log.playing())
        log.see(seen(netflix, "Bluey", state = paused, at = 1_010_000), ex); assertFalse(log.playing())
        assertEquals("playing", WatchRules.stateName(3)); assertEquals("paused", WatchRules.stateName(2)); assertEquals("buffering", WatchRules.stateName(6))
        assertEquals("state_42", WatchRules.stateName(42))
    }

    // ---------------------------------------------------------------- rule 10: the evidence

    @Test fun theLast60LooksNewestFirst() {
        assertEquals(60, WatchRules.SEEN_MAX)
        val ev = WatchRules.Evidence()
        val look = { pkg: String, n: Int -> JSONObject().put("package", pkg).put("time", "t$n").put("position_ms", n * 1000).put("detail", "d$n") }
        ev.add(look(netflix, 1), "playing|Bluey|not yet", false)
        // the same thing said again: no new line, the first one moves on
        ev.add(look(netflix, 2), "playing|Bluey|not yet", false)
        ev.add(look(netflix, 3), "playing|Bluey|not yet", false)
        assertEquals(1, ev.json().length())
        ev.json().getJSONObject(0).let { assertEquals("t3", it.getString("time")); assertEquals(3000, it.getInt("position_ms")); assertEquals("d3", it.getString("detail")); assertEquals(3, it.getInt("looks")) }
        // a change (here: the rules' decision) adds a line, on top
        ev.add(look(netflix, 4), "playing|Bluey|counted", false)
        ev.add(look(hulu, 5), "paused|The Bear|not yet", false)
        assertEquals(listOf("t5", "t4", "t3"), (0 until 3).map { ev.json().getJSONObject(it).getString("time") })
        // another app in between does not hide that Netflix is still saying the same
        ev.add(look(netflix, 6), "playing|Bluey|counted", false)
        assertEquals(3, ev.json().length()); assertEquals("t6", ev.json().getJSONObject(1).getString("time"))
        // an app that is left out keeps one line, however many songs it plays
        for (n in 10..30) ev.add(look("com.spotify.tv.android", n), "playing|song $n|excluded", true)
        assertEquals(4, ev.json().length()); assertEquals("t30", ev.json().getJSONObject(0).getString("time"))
        // never more than 60
        for (n in 100 until 200) ev.add(look(netflix, n), "playing|show $n|not yet", false)
        assertEquals(60, ev.json().length()); assertEquals("t199", ev.json().getJSONObject(0).getString("time")); assertEquals("t140", ev.json().getJSONObject(59).getString("time"))
        // kept over a restart, and emptied with the log
        val again = WatchRules.Evidence(); again.load(JSONArray(ev.json().toString()))
        assertEquals(60, again.json().length()); assertEquals("t199", again.json().getJSONObject(0).getString("time"))
        again.add(look(netflix, 200), "playing|show 199|not yet", false)
        assertEquals("t200", again.json().getJSONObject(0).getString("time")); assertEquals(60, again.json().length())
        ev.clear(); assertEquals(0, ev.json().length())
    }

    // ---------------------------------------------------------------- Finder's part: the right title, in the right app

    private fun title(name: String, vararg services: String) = Finder.Title("id-$name-${services.joinToString()}", name, 2020, "tv", "poster:$name:${services.joinToString()}", null, null, null,
        services.map { Finder.Provider(it, it, "subscription", true, null, null) })

    @Test fun theSameTitleAsAnAppAndJustWatchWriteIt() {
        for ((a, b) in listOf("Bluey" to "bluey", "The Bear" to "Bear", "The Office (2005)" to "The Office", "Marvel's Daredevil" to "Daredevil",
            "Star Wars: Andor" to "Andor", "The Office" to "The Office US", "Law & Order" to "Law Order", "Shameless (US)" to "shameless"))
            assertTrue("$a / $b", Finder.sameTitle(a, b))
        for ((a, b) in listOf("Bluey" to "Blue", "The Bear" to "The Bear Necessities of Life in the Far North", "Dune" to "Dune: Part Two",
            "Up" to "Up in the Air", "House" to "House of the Dragon", "" to "Bluey", "Bluey" to null))
            assertFalse("$a / $b", Finder.sameTitle(a, b))
    }

    @Test fun theResultInTheAppItWasWatchedIn() {
        val results = listOf(title("The Office", "peacock"), title("The Office", "netflix"), title("The Office (UK)", "hulu"), title("Office Space", "netflix"))
        // the same name on the same app first
        assertEquals("poster:The Office:netflix", Finder.pick(results, "The Office", "netflix")!!.poster)
        assertEquals("poster:The Office:peacock", Finder.pick(results, "The Office", "peacock")!!.poster)
        // an app Finder does not know, or that has none of them: the first of that name
        assertEquals("poster:The Office:peacock", Finder.pick(results, "The Office", null)!!.poster)
        assertEquals("poster:The Office:peacock", Finder.pick(results, "the office", "disney-plus")!!.poster)
        // a result nobody offers comes after one somebody does
        assertEquals("poster:Bluey:stan", Finder.pick(listOf(title("Bluey"), title("Bluey", "stan")), "Bluey", "netflix")!!.poster)
        // nothing of that name: nothing, rather than the wrong show
        assertNull(Finder.pick(results, "Parks and Recreation", "netflix")); assertNull(Finder.pick(emptyList(), "The Office", "netflix"))
    }
}
