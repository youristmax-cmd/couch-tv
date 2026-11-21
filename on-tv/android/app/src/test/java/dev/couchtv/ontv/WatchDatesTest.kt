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
 * Keep watching's rules for what four days of use on a Fire TV showed (November 2026):
 *  - a show that is on every day names its episodes by their date, and nothing else is given;
 *  - a show's name passes by in previews at any time, not only just before, and a preview does
 *    not always have "(Trailer)" in its name;
 *  - a title looked up under its own name must be that very title, or the card gets a stranger's
 *    picture.
 * Everything named here is made up: "Up Late with Marlow Finch", "Harbour Lights" and the rest are
 * no programmes, and the answers in app/src/test/resources/watching are invented, in the shapes
 * JustWatch and TVmaze send.
 */
class WatchDatesTest {
    private val me = "dev.couchtv.ontv"
    private val peacock = "com.peacock.peacockfiretv"
    private val hulu = "com.hulu.plus"
    private val ex = WatchRules.DEFAULT_EXCLUDE
    private val playing = WatchRules.STATE_PLAYING
    private val paused = 2
    private val min = 60_000L
    private val day = 24 * 60 * min
    private val wall0 = 1_800_000_000_000L
    private val show = "Harbour Lights"                 // on once a week
    private val episode = "The Long Way Round"
    private val length = 2_590_000L
    private val daily = "Up Late with Marlow Finch"     // on every day; its episodes are named by their date
    private val half = 1_290_000L                       // 21 and a half minutes: a half-hour slot without the adverts

    private fun text(name: String) = javaClass.getResourceAsStream("/watching/$name")!!.bufferedReader().readText()
    private val tv by lazy { ShowRules.tvShow(JSONObject(text("tvmaze-show-made-up.json")))!! }
    private val thatDay by lazy { JSONArray(text("tvmaze-bydate-made-up.json")) }

    private fun seen(pkg: String, title: String?, state: Int = playing, at: Long, pos: Long = -1, dur: Long = 0) =
        WatchRules.Seen(pkg, state, pos, 1f, at, at, wall0 + at, title, null, dur, null, null, null, null, null)

    /** Plays a title for three minutes, a look every minute, and leaves it paused. Gives back the time after. */
    private fun watch(log: WatchRules.Log, pkg: String, title: String?, from: Long, dur: Long = 0, pos: Long = -1): Long {
        var t = from
        for (i in 0..3) { log.see(seen(pkg, title, at = t, pos = if (pos < 0) -1 else pos + i * min, dur = dur), ex); t += min }
        log.see(seen(pkg, title, state = paused, at = t - min + 1000, pos = if (pos < 0) -1 else pos + 3 * min + 1000, dur = dur), ex)
        return t
    }

    private fun entryOf(log: WatchRules.Log, pkg: String, title: String) = log.find(WatchRules.id(WatchRules.key(pkg, title)))!!
    private fun cards(log: WatchRules.Log, at: Long) = log.items(wall0 + at, true, ex)

    // ---------------------------------------------------------------- a title that is a date

    @Test fun aTitleThatIsADate() {
        for (t in listOf("November 10, 2030", "Nov 10, 2030", "Nov. 10, 2030", "november 10 2030", "10 November 2030", "10th Nov 2030", "2030-11-10",
            "11/10/2030", "Sunday, November 10, 2030", "Sun 10 Nov 2030", "  November  10,  2030 "))
            assertEquals(t, "2030-11-10", WatchRules.dateOf(t))
        assertEquals("2030-09-03", WatchRules.dateOf("Sept 3, 2030")); assertEquals("2030-05-05", WatchRules.dateOf("May 5th, 2030"))
        assertEquals("2032-02-29", WatchRules.dateOf("February 29, 2032")); assertEquals("2030-01-02", WatchRules.dateOf("1/2/2030"))
        // not a date, or not one there is: a name
        for (t in listOf("November 1962", "November 10", "10 November", "February 30, 2030", "February 29, 2030", "2030-13-01", "13/40/2030",
            "November 10, 2030: The Special", "The March 10, 2030", "Harbour Lights", "Episode 10, 2030", "1917", "2030", "10, 2030",
            "Novembruary 10, 2030", "Ma 10, 2030", "November 10, 30", "", "  ", null))
            assertNull("$t", WatchRules.dateOf(t))
    }

    @Test fun aDateIsNeverLookedUpUnderItsOwnName() {
        val log = WatchRules.Log(me)
        var t = watch(log, peacock, "November 10, 2030", from = 1_000_000, dur = half, pos = 0)
        val e = entryOf(log, peacock, "November 10, 2030")
        assertFalse(WatchRules.wantsLookup(e, true, wall0 + t)); assertFalse(WatchRules.wantsLookup(e, true, wall0 + t + 30 * day))
        // with no show to try for it there is nothing to ask anybody: the card says the date, with the app's icon
        assertTrue(log.unresolved(e)); assertTrue(log.candidates(e, wall0 + t).isEmpty()); assertFalse(log.wantsShow(e, wall0 + t))
        cards(log, t).single().let { assertEquals("November 10, 2030", it.getString("title")); assertEquals("/icon/$peacock", it.getString("icon")) }
        // any other title is looked up under its own name as before
        t = watch(log, peacock, "November 1962", from = t, dur = 118 * min, pos = 0)
        assertTrue(WatchRules.wantsLookup(entryOf(log, peacock, "November 1962"), true, wall0 + t))
    }

    // ---------------------------------------------------------------- a show that is on every day

    @Test fun aDailyShowIsFoundByTheDateItWasOn() {
        val log = WatchRules.Log(me)
        var t = 1_000_000L
        // the show's page plays its preview; half a minute later an episode starts, named by its date
        log.see(seen(peacock, "$daily (Trailer)", state = paused, at = t, dur = 110_000), ex)
        t = watch(log, peacock, "November 10, 2030", from = t + 30_000, dur = half, pos = 0)
        val e = entryOf(log, peacock, "November 10, 2030")
        val c = log.candidates(e, wall0 + t).single()
        assertEquals(daily, c.name); assertEquals(WatchRules.NEAR, c.kind); assertNull(c.showId)
        assertTrue(log.wantsShow(e, wall0 + t))
        // TVmaze: the show of that name, and what it had on that day
        assertEquals(77001, tv.id); assertEquals("Up Late", tv.name); assertEquals(5, tv.days); assertEquals(30, tv.runtimeMin)
        // (TVmaze leaves the host off the end of the name: the same show all the same, though the two names are not alike enough otherwise)
        assertFalse(Finder.sameTitle(tv.name, c.name))
        assertTrue(ShowRules.sameShow(tv, c.name))
        val aired = ShowRules.tvAired(thatDay, WatchRules.dateOf(e.title)!!)!!
        assertEquals(12, aired.season); assertEquals(183, aired.number); assertEquals(30, aired.runtimeMin); assertEquals(1, aired.count)
        assertTrue(ShowRules.airedFits(tv, aired, e.durationMs, near = true))
        // so the card is the show's, with the date for the episode's name and TVmaze's numbers
        log.resolve(e, daily, "ts90002", "https://images.justwatch.com/p2.jpg", "https://images.justwatch.com/b2.jpg", aired.season, aired.number, null, wall0 + t, byDate = true)
        cards(log, t).single().let { card ->
            assertEquals(daily, card.getString("title")); assertEquals("November 10, 2030", card.getString("episode_title"))
            assertEquals(12, card.getInt("season")); assertEquals(183, card.getInt("episode"))
            assertEquals("https://images.justwatch.com/p2.jpg", card.getString("poster")); assertEquals("continue", card.getString("kind"))
        }
        assertFalse(log.unresolved(e)); assertFalse(log.wantsShow(e, wall0 + t))

        // nine days on, another day's episode, with no preview anywhere near: the show that is on every day is tried, whatever the date
        t = watch(log, peacock, "November 19, 2030", from = t + 9 * day, dur = half, pos = 0)
        val next = entryOf(log, peacock, "November 19, 2030")
        val again = log.candidates(next, wall0 + t).single()
        assertEquals(daily, again.name); assertEquals(WatchRules.SHOW, again.kind); assertEquals("ts90002", again.showId); assertNull(again.after)
        assertEquals("https://images.justwatch.com/p2.jpg", again.poster)
        // it was on that day too: one card for the show, the newer day's
        val gone = log.resolve(next, again.name, again.showId!!, again.poster, again.backdrop, 12, 189, null, wall0 + t, byDate = true)
        assertEquals(listOf("November 10, 2030"), gone.map { it.title }); assertEquals(1, log.size)
        cards(log, t).single().let { assertEquals("November 19, 2030", it.getString("episode_title")); assertEquals(189, it.getInt("episode")) }
        // all of it comes back after a restart
        val back = WatchRules.Log(me).also { it.load(JSONObject(log.toJson().toString())) }
        assertTrue(back.all().single().byDate); assertEquals(cards(log, t).single().toString(), back.items(wall0 + t, true, ex).single().toString())

        // an episode that names itself, in the same app: a show found by a date has no episode to count on from, and is not offered
        t = watch(log, peacock, episode, from = t, dur = length, pos = 0)
        assertTrue(log.candidates(entryOf(log, peacock, episode), wall0 + t).isEmpty())
        // and a date watched in another app is not this app's show
        t = watch(log, hulu, "November 20, 2030", from = t, dur = half, pos = 0)
        assertTrue(log.candidates(entryOf(log, hulu, "November 20, 2030"), wall0 + t).isEmpty())
    }

    @Test fun aShowFoundByAnEpisodesNameIsNotTriedForADate() {
        val log = WatchRules.Log(me)
        var t = watch(log, peacock, episode, from = 1_000_000, dur = length, pos = 0)
        log.resolve(entryOf(log, peacock, episode), show, "ts90001", null, null, 5, 8, "tse90508", wall0 + t)
        t = watch(log, peacock, "November 10, 2030", from = t + day, dur = half, pos = 0)
        // (it was on on some date, like every show: that proves nothing)
        assertTrue(log.candidates(entryOf(log, peacock, "November 10, 2030"), wall0 + t).isEmpty())
    }

    @Test fun aDateWithNoEpisodeIsNotThatShow() {
        // TVmaze answers 404 for a day with nothing: no list at all
        assertNull(ShowRules.tvAired(null, "2030-11-09")); assertNull(ShowRules.tvAired(JSONArray(), "2030-11-09"))
        // a list that is of another day says nothing about this one
        assertNull(ShowRules.tvAired(thatDay, "2030-11-09"))
        assertFalse(ShowRules.airedFits(tv, null, half, near = true)); assertFalse(ShowRules.airedFits(tv, null, half, near = false))
    }

    @Test fun whatTvmazeSaysOfAShowAndADay() {
        // the show
        assertNull(ShowRules.tvShow(null)); assertNull(ShowRules.tvShow(JSONObject())); assertNull(ShowRules.tvShow(JSONObject("""{"id": 5, "name": ""}""")))
        assertNull(ShowRules.tvShow(JSONObject("""{"name": "No Number"}""")))
        ShowRules.tvShow(JSONObject("""{"id": 5, "name": "X", "runtime": 60, "averageRuntime": 45}"""))!!.let {
            assertEquals(45, it.runtimeMin); assertEquals(0, it.days) }
        assertNull(ShowRules.tvShow(JSONObject("""{"id": 5, "name": "X", "runtime": null}"""))!!.runtimeMin)
        // a search always answers with its best guess: it must be the show that was asked for
        assertTrue(ShowRules.sameShow(tv, "up late with marlow finch")); assertTrue(ShowRules.sameShow(tv, "Up Late!")); assertTrue(ShowRules.sameShow(tv, "The Up Late"))
        assertFalse(ShowRules.sameShow(tv, show)); assertFalse(ShowRules.sameShow(null, daily))
        // only the host on the end is passed over: any other longer name is another show
        assertFalse(ShowRules.sameShow(tv, "Up Late and Down Early")); assertFalse(ShowRules.sameShow(tv, "Up Later with Marlow Finch"))
        // two episodes on one day: the season when they share it, and no number (which of the two is not known)
        val two = JSONArray("""[{"season": 3, "number": 10, "airdate": "2030-11-10", "runtime": 30}, {"season": 3, "number": 11, "airdate": "2030-11-10", "runtime": 30},
            {"season": 3, "number": 12, "airdate": "2030-11-11", "runtime": 30}]""")
        ShowRules.tvAired(two, "2030-11-10")!!.let { assertEquals(3, it.season); assertNull(it.number); assertEquals(2, it.count) }
        ShowRules.tvAired(two, "2030-11-11")!!.let { assertEquals(12, it.number); assertEquals(1, it.count) }
        // a special with no number
        ShowRules.tvAired(JSONArray("""[{"season": 3, "number": null, "airdate": "2030-11-10", "runtime": null}]"""), "2030-11-10")!!.let {
            assertEquals(3, it.season); assertNull(it.number); assertNull(it.runtimeMin) }
    }

    @Test fun aDateAloneDoesNotGiveTheCardToWhicheverPreviewPassedBy() {
        val on = ShowRules.tvAired(thatDay, "2030-11-10")!!
        // a show that is on once a week was on on that date too. Its preview from just before the title is good enough...
        val weekly = ShowRules.TvShow(77002, show, 1, 60)
        val sunday = ShowRules.Aired(5, 8, 60, 1)
        assertTrue(ShowRules.airedFits(weekly, sunday, 43 * min, near = true))
        // ...one that only passed by some time this week is not: for that the show must be on at least four days a week
        assertFalse(ShowRules.airedFits(weekly, sunday, 43 * min, near = false))
        assertTrue(ShowRules.airedFits(tv, on, half, near = false))
        assertEquals(4, ShowRules.DAILY_DAYS)
        assertTrue(ShowRules.airedFits(ShowRules.TvShow(1, "X", 4, 30), on, half, near = false)); assertFalse(ShowRules.airedFits(ShowRules.TvShow(1, "X", 3, 30), on, half, near = false))
        // the length has to be an episode's: from half the slot (no adverts) to a little over it
        assertTrue(ShowRules.airedFits(tv, on, 15 * min, near = true)); assertTrue(ShowRules.airedFits(tv, on, 30 * min, near = true)); assertTrue(ShowRules.airedFits(tv, on, 34 * min, near = true))
        assertFalse(ShowRules.airedFits(tv, on, 14 * min, near = true)); assertFalse(ShowRules.airedFits(tv, on, 35 * min, near = true)); assertFalse(ShowRules.airedFits(tv, on, 118 * min, near = true))
        // a length that is not known on one side or the other decides nothing
        assertTrue(ShowRules.airedFits(tv, on, 0, near = true))
        assertTrue(ShowRules.airedFits(ShowRules.TvShow(1, "X", 5, null), ShowRules.Aired(null, null, null, 1), 118 * min, near = true))
    }

    // ---------------------------------------------------------------- previews, wider

    @Test fun aShortClipWithNoTrailerInItsNameIsAPreviewToo() {
        val log = WatchRules.Log(me)
        val v = log.see(seen(peacock, show, state = paused, at = 1_000_000, dur = 150_000), ex)
        assertEquals("too short", v.decision); assertTrue(v.hint)
        assertEquals(listOf(show), log.hints().map { it.name })
        // the same again a moment later is no news
        assertFalse(log.see(seen(peacock, show, at = 1_005_000, dur = 150_000), ex).hint)
        // a date, however short, is no show's name; nor is anything of 5 minutes or more, or of no known length
        assertFalse(log.see(seen(peacock, "November 9, 2030", at = 1_010_000, dur = 60_000), ex).hint)
        assertFalse(log.see(seen(peacock, "A Short Film", at = 1_020_000, dur = 5 * min), ex).hint)
        assertFalse(log.see(seen(peacock, "No Length", at = 1_030_000), ex).hint)
        assertEquals(1, log.hints().size)
        // one that says it is a preview is kept under its name without the "(Trailer)", as before
        log.see(seen(peacock, "Quillfeather Bay (Trailer)", at = 1_040_000, dur = 30_000), ex).let { assertEquals("preview", it.decision); assertTrue(it.hint) }
        // an app that is left out is not listened to for these either
        assertFalse(log.see(seen("com.att.tv", "Some Channel Promo", at = 1_050_000, dur = 30_000), ex).hint)
        // then an episode that names only itself: both are shows to try, the newest first
        val t = watch(log, peacock, episode, from = 1_070_000, dur = length, pos = 0)
        assertEquals(listOf("Quillfeather Bay", show), log.candidates(entryOf(log, peacock, episode), wall0 + t).map { it.name })
        // the title's own name is not a show to try for itself
        log.see(seen(peacock, episode, state = paused, at = t, dur = 4 * min), ex)
        assertTrue(log.candidates(entryOf(log, peacock, episode), wall0 + t).none { it.name == episode })
    }

    @Test fun theLooksKeptFromBeforeGiveShortTitlesToo() {
        val look = { time: String, pkg: String, title: String, ms: Long, decision: String ->
            JSONObject().put("time", "2030-01-01T$time-08:00").put("package", pkg).put("decision", decision)
                .put("metadata", JSONObject().put("android.media.metadata.DURATION", JSONObject().put("type", "number").put("value", ms)))
                .put("read", JSONObject().put("title", title)) }
        val log = WatchRules.Log(me)
        log.adopt(JSONArray().put(look("19:05:00.000", peacock, "Quillfeather Bay (Trailer)", 30_000, "too short"))
            .put(look("19:04:00.000", peacock, show, 150_000, "too short"))
            .put(look("19:03:00.000", peacock, "A Whole Film", 118 * min, "counted"))
            .put(look("19:02:00.000", peacock, "November 9, 2030", 60_000, "too short"))
            .put(look("19:01:00.000", "com.att.tv", "Some Channel Promo", 30_000, "excluded")))
        assertEquals(listOf("Quillfeather Bay", show), log.hints().map { it.name })
        assertTrue(log.hints().all { it.pkg == peacock })
    }

    @Test fun aLatePreviewFindsTheShowOfATitleLoggedDaysAgo() {
        val log = WatchRules.Log(me)
        var t = watch(log, peacock, episode, from = 1_000_000, dur = length, pos = 0)
        val e = entryOf(log, peacock, episode)
        // no preview was seen for it: nothing to try, nothing asked
        assertTrue(log.candidates(e, wall0 + t).isEmpty()); assertFalse(log.wantsShow(e, wall0 + t))
        // three days later the show's page is opened for something else, and its preview plays
        t += 3 * day
        assertTrue(log.see(seen(peacock, "$show (Trailer)", state = paused, at = t, dur = 150_000), ex).hint)
        val c = log.candidates(e, wall0 + t).single()
        assertEquals(show, c.name); assertEquals(WatchRules.KEPT, c.kind); assertNull(c.showId); assertNull(c.after)
        assertTrue(log.wantsShow(e, wall0 + t))
        // a preview from another day has to fit exactly: one episode of that name, both lengths known and agreeing
        val list = ShowRules.episodes(JSONObject(text("jw-episodes-light-made-up.json")))
        assertEquals("tse90508", ShowRules.match(list, e.title, e.durationMs, c.after, WatchRules.NEXT_SPAN, strict = true)!!.episode!!.id)
        log.resolve(e, show, "ts90001", null, "https://images.justwatch.com/b.jpg", 5, 8, "tse90508", wall0 + t)
        cards(log, t).single().let { assertEquals(show, it.getString("title")); assertEquals(episode, it.getString("episode_title")); assertEquals(8, it.getInt("episode")) }
        // a preview from more than a week ago is no longer a name to try
        val other = WatchRules.Log(me)
        other.see(seen(peacock, "$show (Trailer)", at = 500_000, dur = 150_000), ex)
        val t2 = watch(other, peacock, episode, from = 500_000 + 8 * day, dur = length, pos = 0)
        assertTrue(other.candidates(entryOf(other, peacock, episode), wall0 + t2).isEmpty())
    }

    @Test fun aTitleThatFoundNothingIsAskedAboutAgainOnlyForANewName() {
        val log = WatchRules.Log(me)
        log.see(seen(peacock, "Quillfeather Bay (Trailer)", at = 900_000, dur = 30_000), ex)
        var t = watch(log, peacock, episode, from = 1_000_000, dur = length, pos = 0)
        val e = entryOf(log, peacock, episode)
        assertEquals(0L, e.showAskedAt); assertTrue(log.wantsShow(e, wall0 + t))
        // tried: it is not that show's
        log.tried(e, "Quillfeather Bay", wall0 + t); log.asked(e, wall0 + t)
        assertTrue(log.candidates(e, wall0 + t + min).isEmpty()); assertFalse(log.wantsShow(e, wall0 + t + min))
        // the same preview again an hour later is not a new name
        t += 60 * min
        log.see(seen(peacock, "Quillfeather Bay (Trailer)", at = t, dur = 30_000), ex)
        assertFalse(log.wantsShow(e, wall0 + t))
        // a name not seen before: at once
        log.see(seen(peacock, "$show (Trailer)", at = t + 1000, dur = 150_000), ex)
        assertEquals(listOf(show), log.candidates(e, wall0 + t + 1000).map { it.name }); assertTrue(log.wantsShow(e, wall0 + t + 1000))
        log.tried(e, show, wall0 + t + 2000); log.asked(e, wall0 + t + 2000)
        assertFalse(log.wantsShow(e, wall0 + t + 3000))
        // a day later both may be tried once more
        assertTrue(log.wantsShow(e, wall0 + t + 25 * 60 * min))
        assertEquals(setOf(show, "Quillfeather Bay"), log.candidates(e, wall0 + t + 25 * 60 * min).map { it.name }.toSet())
        // and what was asked when comes back after a restart
        val again = WatchRules.Log(me).also { it.load(JSONObject(log.toJson().toString())) }
        val e2 = again.all().single()
        assertEquals(wall0 + t + 2000, e2.showAskedAt); assertFalse(again.wantsShow(e2, wall0 + t + 3000)); assertTrue(again.wantsShow(e2, wall0 + t + 25 * 60 * min))
    }

    @Test fun eightOfTheWeeksPreviewsAtOneGo() {
        assertEquals(8, WatchRules.KEPT_TRIES); assertEquals(7 * day, WatchRules.HINT_KEEP_MS)
        val log = WatchRules.Log(me)
        var t = watch(log, peacock, episode, from = 1_000_000, dur = length, pos = 0)
        val e = entryOf(log, peacock, episode)
        for (i in 1..12) { t += 60 * min; log.see(seen(peacock, "Show Number $i (Trailer)", at = t, dur = 60_000), ex) }
        // the eight newest
        val first = log.candidates(e, wall0 + t)
        assertEquals((12 downTo 5).map { "Show Number $it" }, first.map { it.name }); assertTrue(first.all { it.kind == WatchRules.KEPT })
        first.forEach { log.tried(e, it.name, wall0 + t) }; log.asked(e, wall0 + t)
        // the other four are not new: they wait
        assertEquals((4 downTo 1).map { "Show Number $it" }, log.candidates(e, wall0 + t + min).map { it.name })
        assertFalse(log.wantsShow(e, wall0 + t + min))
        // a day later it is their turn, before the ones already tried once
        assertTrue(log.wantsShow(e, wall0 + t + 25 * 60 * min))
        assertEquals(listOf(4, 3, 2, 1, 12, 11, 10, 9).map { "Show Number $it" }, log.candidates(e, wall0 + t + 25 * 60 * min).map { it.name })
        // after a week none of them is a name to try any more
        assertTrue(log.candidates(e, wall0 + t + 8 * day).isEmpty())
    }

    // ---------------------------------------------------------------- a title's own name

    private fun found(id: String, name: String, type: String, minutes: Int?, vararg services: String) =
        Finder.Title(id, name, null, type, "https://images.justwatch.com/p-$id.jpg", "https://images.justwatch.com/b-$id.jpg", null, minutes,
            services.map { Finder.Provider(it, it, "subscription", true, null, null) })

    @Test fun onlyTheVeryTitleCountsUnderItsOwnName() {
        val film = "The Glass Orchard"
        assertTrue(WatchRules.ownName(film, 118, false, "the glass orchard!", 110 * min, false))
        // a name that is merely alike is another title
        assertFalse(WatchRules.ownName("The Glass Orchard 2", 118, false, film, 118 * min, false))
        assertFalse(WatchRules.ownName("Glass Orchard", 118, false, film, 118 * min, false))
        assertFalse(WatchRules.ownName(null, 118, false, film, 118 * min, false)); assertFalse(WatchRules.ownName("", null, false, "", 0, false))
        // the length must be within a fifth of what the app gave, when both are known
        assertTrue(WatchRules.ownName("X", 120, false, "X", 100 * min, false)); assertFalse(WatchRules.ownName("X", 121, false, "X", 100 * min, false))
        assertTrue(WatchRules.ownName("X", 80, false, "X", 100 * min, false)); assertFalse(WatchRules.ownName("X", 79, false, "X", 100 * min, false))
        assertTrue(WatchRules.ownName(film, null, false, film, half, false)); assertTrue(WatchRules.ownName(film, 118, false, film, 0, false))
        // a 21 minute episode is not a 2 hour film of the same name
        assertFalse(WatchRules.ownName(film, 118, false, film, half, false))
        // the app's own words named an episode: the title is a show's, whose usual length says nothing about this episode
        assertTrue(WatchRules.ownName("Lantern Street", 51, true, "Lantern Street", 95 * min, true))
        assertFalse(WatchRules.ownName("Lantern Street", 51, true, "Lantern Street", 95 * min, false))
        assertFalse(WatchRules.ownName("Lantern Street", 110, false, "Lantern Street", 45 * min, true))      // (a film of the same name)
    }

    @Test fun aFilmWhoseNameResemblesADate() {
        // "November 1962" is a name (there is no day in it), and a film is called that
        assertNull(WatchRules.dateOf("November 1962"))
        val results = listOf(found("tm1", "November 1962", "movie", 118), found("tm2", "The Glass Orchard 2", "movie", 100),
            found("tm3", "The Glass Orchard", "movie", 104, "netflix"), found("tm4", "The Glass Orchard", "movie", 101, "peacock"),
            found("ts5", "The Glass Orchard", "tv", 22, "peacock"))
        // watched under that name, at about that length: it is the film
        assertEquals("tm1", Finder.own(results, "November 1962", "peacock", 115 * min, false)!!.jwId)
        // an episode named by its date is not that film, whatever the search for the date brings up
        assertNull(Finder.own(results, "November 10, 2030", "peacock", half, false))
        assertNull(Finder.own(results, "November 1962", "peacock", half, false))            // nor is 21 minutes of something called the same
        // of several of the very name and a fitting length: the one in the app it was watched in, then the first offered anywhere
        assertEquals("tm4", Finder.own(results, "The Glass Orchard", "peacock", 100 * min, false)!!.jwId)
        assertEquals("tm3", Finder.own(results, "The Glass Orchard", "hulu", 100 * min, false)!!.jwId)
        assertEquals("ts5", Finder.own(results, "The Glass Orchard", "peacock", 21 * min, false)!!.jwId)   // (the show of that name, by its length)
        // "Glass Orchard" is not "The Glass Orchard" here, though it is near enough for a preview's name (which is confirmed otherwise)
        assertNull(Finder.own(results, "Glass Orchard", "peacock", 100 * min, false))
        assertNotNull(Finder.pick(results, "Glass Orchard", "peacock"))
        assertNull(Finder.own(emptyList(), "The Glass Orchard", null, 0, false))
    }

    @Test fun aFindByAnOlderRuleIsNotKept() {
        val played = wall0
        val old = { title: String -> JSONObject().put("key", WatchRules.key(peacock, title)).put("package", peacock).put("title", title)
            .put("duration_ms", half).put("position_ms", 231_000).put("last_played", played)
            .put("poster", "https://images.justwatch.com/poster/found.jpg").put("backdrop", "https://images.justwatch.com/backdrop/found.jpg")
            .put("looked_at", played - day).put("looked_ok", true) }
        val log = WatchRules.Log(me).also { it.load(JSONObject().put("v", 2).put("entries", JSONArray()
            // as an earlier version left them: an episode named by its date, with the poster of a film whose name is like that date
            .put(old("November 10, 2030"))
            // another title found under its own name by the same loose rule
            .put(old("The Glass Orchard"))
            // one found by the rule there is now
            .put(old("Copper Sky").put("own_rule", WatchRules.OWN_RULE))
            // and a show worked out from an episode's name: not an own-name find at all
            .put(old(episode).put("show_title", show).put("show_id", "ts90001").put("show_season", 5).put("show_episode", 8)))) }
        val dated = entryOf(log, peacock, "November 10, 2030")
        assertNull(dated.poster); assertNull(dated.backdrop); assertFalse(dated.lookedOk); assertEquals(0L, dated.lookedAt)
        // the card is back to no picture: the date, and the app's icon
        log.items(played, true, ex).first { it.getString("title") == "November 10, 2030" }.let {
            assertTrue(it.isNull("poster")); assertTrue(it.isNull("backdrop")); assertEquals("/icon/$peacock", it.getString("icon")) }
        // it is tried through the shows it may belong to again, and never again under its own name
        assertTrue(log.unresolved(dated)); assertFalse(WatchRules.wantsLookup(dated, true, played))
        // the other loose find is looked up afresh, by the rule there is now
        val film = entryOf(log, peacock, "The Glass Orchard")
        assertNull(film.poster); assertTrue(WatchRules.wantsLookup(film, true, played))
        // what was found by that rule already, and what was worked out as a show, stay
        assertEquals("https://images.justwatch.com/poster/found.jpg", entryOf(log, peacock, "Copper Sky").poster); assertTrue(entryOf(log, peacock, "Copper Sky").lookedOk)
        assertEquals(show, log.items(played, true, ex).first { it.optString("episode_title") == episode }.getString("title"))
        assertEquals("https://images.justwatch.com/poster/found.jpg", entryOf(log, peacock, episode).poster)
        // this is written down once, and reading it back changes nothing more
        assertTrue(log.takeWrite(1))
        val again = WatchRules.Log(me).also { it.load(JSONObject(log.toJson().toString())) }
        assertFalse(again.takeWrite(2)); assertEquals(4, again.size)
    }
}
