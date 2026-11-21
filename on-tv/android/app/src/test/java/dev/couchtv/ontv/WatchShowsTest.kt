package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep watching's rules for what two apps were seen to do on a Fire TV (16 November 2026):
 *  - Peacock names only the episode, and plays a preview named "<show> (Trailer)" on the show's
 *    page just before: the show is worked out from that;
 *  - Hulu plays and names nothing: it gets one card for the app itself.
 * Everything named here is made up: "Harbour Lights" is no programme, and nothing in these tests
 * or in app/src/test/resources/watching is anything anybody watched. The fixtures there have the
 * shapes that were seen (the raw looks of GET /api/continue/log, JustWatch's episode list), filled
 * with invented names, ids and times.
 */
class WatchShowsTest {
    private val me = "dev.couchtv.ontv"
    private val peacock = "com.peacock.peacockfiretv"
    private val hulu = "com.hulu.plus"
    private val ex = WatchRules.DEFAULT_EXCLUDE
    private val playing = WatchRules.STATE_PLAYING
    private val paused = 2
    private val stopped = 1
    private val min = 60_000L
    private val wall0 = 1_800_000_000_000L
    private val show = "Harbour Lights"
    private val episode = "The Long Way Round"
    private val length = 2_590_000L                   // 43 minutes and a bit, as a session gives it: in milliseconds

    private fun load(name: String): JSONObject = JSONObject(javaClass.getResourceAsStream("/watching/$name")!!.bufferedReader().readText())
    private fun at(iso: String): Long = ScoreFeeds.parseIso(iso)!!

    private fun seen(pkg: String, title: String?, state: Int = playing, at: Long, pos: Long = -1, dur: Long = 0, app: String? = null, artUri: String? = null) =
        WatchRules.Seen(pkg, state, pos, 1f, at, at, wall0 + at, title, null, dur, artUri, null, null, null, app)

    /** Plays a title for three minutes, a look every minute, and leaves it paused. Gives back the time after. */
    private fun watch(log: WatchRules.Log, pkg: String, title: String?, from: Long, dur: Long = 0, pos: Long = -1, app: String? = null): Long {
        var t = from
        for (i in 0..3) { log.see(seen(pkg, title, at = t, pos = if (pos < 0) -1 else pos + i * min, dur = dur, app = app), ex); t += min }
        log.see(seen(pkg, title, state = paused, at = t - min + 1000, pos = if (pos < 0) -1 else pos + 3 * min + 1000, dur = dur, app = app), ex)
        return t
    }

    private fun entryOf(log: WatchRules.Log, pkg: String, title: String) = log.find(WatchRules.id(WatchRules.key(pkg, title)))!!
    private fun cards(log: WatchRules.Log, at: Long, lookup: Boolean = true) = log.items(wall0 + at, lookup, ex)

    // ---------------------------------------------------------------- previews

    @Test fun aPreviewSaysSoItself() {
        assertEquals(show, WatchRules.trailerOf("Harbour Lights (Trailer)"))
        assertEquals("Quillfeather Bay", WatchRules.trailerOf("Quillfeather Bay (Trailer)"))
        assertEquals("Copper Sky", WatchRules.trailerOf("Copper Sky  (Official Trailer) "))
        assertEquals("Copper Sky", WatchRules.trailerOf("Copper Sky [teaser]"))
        assertEquals("Copper Sky", WatchRules.trailerOf("Copper Sky - (Preview)"))
        for (t in listOf("Trailer Park Summer", "Trailer: Copper Sky", "The Trailer", "(Trailer)", "Copper Sky (2021)", "Copper Sky Trailer", "", null))
            assertNull("$t", WatchRules.trailerOf(t))
    }

    @Test fun aPreviewIsNeverACardButItsNameIsKept() {
        val log = WatchRules.Log(me)
        log.see(seen(peacock, "$show (Trailer)", state = stopped, at = 1_000_000, dur = 150_000), ex).let {
            assertEquals("preview", it.decision); assertNull(it.entry); assertTrue(it.detail.contains(show)) }
        // not even one that plays for three minutes and gives no length
        val t = watch(log, peacock, "Some Show (Trailer)", from = 2_000_000)
        assertTrue(cards(log, t).isEmpty()); assertEquals(0, log.size)
        assertEquals(listOf(show, "Some Show"), log.hints().map { it.name })
        assertTrue(log.hints().all { it.pkg == peacock })
        // the same preview again: one name, the newer time
        log.see(seen(peacock, "$show (Trailer)", at = t), ex)
        assertEquals(2, log.hints().size); assertEquals(wall0 + t, log.hints().first { it.name == show }.at)
        // an app that is left out is not listened to for previews either
        log.see(seen("com.att.tv", "Something (Trailer)", at = t), ex)
        assertEquals(2, log.hints().size)
        // they are written with the log, and come back
        val again = WatchRules.Log(me).also { it.load(JSONObject(log.toJson().toString())) }
        assertEquals(listOf(show, "Some Show"), again.hints().map { it.name })
        // and go after a week
        again.prune(wall0 + t + 8 * 24 * 60 * min); assertTrue(again.hints().isEmpty())
    }

    // ---------------------------------------------------------------- which shows to try for a title

    @Test fun theShowOfThePreviewJustBeforeIsTried() {
        val log = WatchRules.Log(me)
        var t = 1_000_000L
        log.see(seen(peacock, "$show (Trailer)", state = stopped, at = t, dur = 150_000), ex)
        log.see(seen(peacock, null, state = stopped, at = t + 600), ex)
        // seven seconds later the episode starts, named by its own name and nothing else
        t = watch(log, peacock, episode, from = t + 7000, dur = length, pos = 0)
        val e = entryOf(log, peacock, episode)
        assertEquals(wall0 + 1_007_000, e.began)
        assertTrue(log.unresolved(e))
        val c = log.candidates(e, wall0 + t)
        assertEquals(listOf(show), c.map { it.name }); assertNull(c[0].showId); assertNull(c[0].after)
        // tried, and it was not it (or JustWatch could not be asked): not again for a day
        log.tried(e, show, wall0 + t)
        assertTrue(log.candidates(e, wall0 + t + 23 * 60 * min).isEmpty())
        assertEquals(listOf(show), log.candidates(e, wall0 + t + 25 * 60 * min).map { it.name })
    }

    @Test fun thePreviewsFromJustBeforeFirstThenTheRestOfTheWeek() {
        assertEquals(30 * min, WatchRules.HINT_WINDOW_MS)
        val log = WatchRules.Log(me)
        val start = 100 * min
        log.see(seen(peacock, "Too Early (Trailer)", at = start - 31 * min), ex)
        log.see(seen(peacock, "Third (Trailer)", at = start - 29 * min), ex)
        log.see(seen(peacock, "Fourth (Trailer)", at = start - 20 * min), ex)
        log.see(seen(hulu, "Other App (Trailer)", at = start - 5 * min), ex)
        log.see(seen(peacock, "Second (Trailer)", at = start - 2 * min), ex)
        log.see(seen(peacock, "First (Trailer)", at = start - 5000), ex)
        val t = watch(log, peacock, episode, from = start, dur = length, pos = 0)
        // seen after the episode began (the app's home, on the way out)
        log.see(seen(peacock, "Quillfeather Bay (Trailer)", state = paused, at = t, dur = 30_000), ex)
        val e = entryOf(log, peacock, episode)
        // from the half hour before, the newest first and three at most; then the others this app has named, the newest first
        assertEquals(listOf("First" to WatchRules.NEAR, "Second" to WatchRules.NEAR, "Fourth" to WatchRules.NEAR,
            "Quillfeather Bay" to WatchRules.KEPT, "Third" to WatchRules.KEPT, "Too Early" to WatchRules.KEPT),
            log.candidates(e, wall0 + t).map { it.name to it.kind })
        // never another app's
        assertTrue(log.candidates(e, wall0 + t).none { it.name == "Other App" })
    }

    @Test fun whatIsNotTakenForAnEpisodesName() {
        val log = WatchRules.Log(me)
        log.see(seen(peacock, "$show (Trailer)", at = 1_000_000), ex)
        // the app's own words name the episode: the title is the show
        var t = 1_010_000L
        for (i in 0..3) { log.see(WatchRules.Seen(peacock, playing, -1, 1f, t, t, wall0 + t, "Lantern Street", "S1:E2 The Night Shift"), ex); t += min }
        assertFalse(log.unresolved(entryOf(log, peacock, "Lantern Street")))
        assertTrue(log.candidates(entryOf(log, peacock, "Lantern Street"), wall0 + t).isEmpty())
        // found under its own name already: a film, most likely
        val t2 = watch(log, peacock, "The Glass Orchard", from = t, dur = 180 * min, pos = 0)
        val film = entryOf(log, peacock, "The Glass Orchard")
        assertEquals(listOf(show), log.candidates(film, wall0 + t2).map { it.name })       // (tried first: an episode's name is often some film's too)
        film.lookedOk = true
        assertTrue(log.candidates(film, wall0 + t2).isEmpty())
        // not yet counted: nothing is asked about a title that may be gone in a minute
        log.see(seen(peacock, "Just Started", at = t2), ex)
        assertNull(log.find(WatchRules.id(WatchRules.key(peacock, "Just Started"))))
    }

    @Test fun aTitleBeginsWhenItComesUpNotWhenItComesBack() {
        val log = WatchRules.Log(me)
        var t = 1_000_000L
        log.see(seen(peacock, episode, at = t, dur = length, pos = 0), ex)
        val began = wall0 + t
        // forty minutes of it, with the session empty for a moment in between (Peacock does that)
        for (i in 1..40) {
            t += min
            if (i == 20) log.see(seen(peacock, null, at = t - 500), ex)
            log.see(seen(peacock, episode, at = t, dur = length, pos = i * min), ex)
        }
        val e = entryOf(log, peacock, episode)
        assertEquals(began, e.began)
        // back to the show's page (its preview plays), and on with the episode: it has come up anew, and the preview counts for it
        log.see(seen(peacock, "$show (Trailer)", at = t + 5000, dur = 150_000), ex)
        log.see(seen(peacock, episode, at = t + 12_000, dur = length, pos = 40 * min), ex)
        assertEquals(wall0 + t + 12_000, e.began)
        assertEquals(listOf(show), log.candidates(e, wall0 + t + 12_000).map { it.name })
        // the next day, with nothing in between: a new sitting
        val next = t + 24 * 60 * min
        log.see(seen(peacock, episode, at = next, dur = length, pos = 41 * min), ex)
        assertEquals(wall0 + next, e.began)
    }

    // ---------------------------------------------------------------- the show's card

    @Test fun anEpisodeBecomesItsShowsCard() {
        val log = WatchRules.Log(me)
        val t = watch(log, peacock, episode, from = 1_000_000, dur = length, pos = 0, app = "Peacock TV")
        val e = entryOf(log, peacock, episode)
        // as it is before: the episode's name for a title, no picture, the app's icon to show instead
        cards(log, t).single().let { c ->
            assertEquals(episode, c.getString("title")); assertTrue(c.isNull("episode_title")); assertTrue(c.isNull("season"))
            assertTrue(c.isNull("poster")); assertTrue(c.isNull("backdrop")); assertEquals("/icon/$peacock", c.getString("icon"))
            c.getJSONObject("app").let { a -> assertEquals("peacock", a.getString("id")); assertEquals("Peacock TV", a.getString("name")); assertEquals(peacock, a.getString("package")) }
        }
        val id = e.id
        assertTrue(log.resolve(e, show, "ts90001", "https://images.justwatch.com/poster/1/s592/x.jpg", "https://images.justwatch.com/backdrop/2/s1440/x.jpg",
            5, 8, "tse90508", wall0 + t).isEmpty())
        cards(log, t).single().let { c ->
            assertEquals(id, c.getString("id"))
            assertEquals(show, c.getString("title")); assertEquals(episode, c.getString("episode_title"))
            assertEquals(5, c.getInt("season")); assertEquals(8, c.getInt("episode"))
            assertEquals("https://images.justwatch.com/poster/1/s592/x.jpg", c.getString("poster"))
            assertEquals("https://images.justwatch.com/backdrop/2/s1440/x.jpg", c.getString("backdrop"))
            assertTrue(c.isNull("icon"))
            assertEquals("peacock", c.getString("service")); assertEquals("continue", c.getString("kind"))
            assertEquals(length, c.getLong("duration_ms")); assertEquals(0.07, c.getDouble("progress"), 0.001)
        }
        // with looking up switched off again the name stays (it asks nobody), the pictures from outside do not show
        cards(log, t, lookup = false).single().let { c ->
            assertEquals(show, c.getString("title")); assertEquals(8, c.getInt("episode"))
            assertTrue(c.isNull("poster")); assertTrue(c.isNull("backdrop")); assertEquals("/icon/$peacock", c.getString("icon"))
        }
        // nothing more to ask about it
        assertFalse(log.unresolved(e)); assertTrue(log.candidates(e, wall0 + t).isEmpty()); assertFalse(WatchRules.wantsLookup(e, true, wall0 + t))
        // the session goes on naming the episode: the same entry, still the show's card
        log.see(seen(peacock, episode, at = t + min, dur = length, pos = 5 * min), ex)
        assertEquals(1, log.size); assertEquals(show, cards(log, t + min).single().getString("title"))
        // and all of it comes back after a restart
        val again = WatchRules.Log(me).also { it.load(JSONObject(log.toJson().toString())) }
        assertEquals(cards(log, t + min).single().toString(), again.items(wall0 + t + min, true, ex).single().toString())
        again.find(id)!!.let { assertEquals("ts90001", it.showId); assertEquals("tse90508", it.episodeId); assertEquals(wall0 + 1_000_000, it.began) }
    }

    @Test fun theShowIsSureAndTheEpisodeIsNot() {
        // "Looking Back (1)" is in every season: the card says the show and the episode's name, and no number
        val log = WatchRules.Log(me)
        val t = watch(log, peacock, "Looking Back (1)", from = 1_000_000, dur = 44 * min, pos = 0)
        val e = entryOf(log, peacock, "Looking Back (1)")
        log.resolve(e, show, "ts90001", null, null, null, null, null, wall0 + t)
        cards(log, t).single().let { c ->
            assertEquals(show, c.getString("title")); assertEquals("Looking Back (1)", c.getString("episode_title"))
            assertTrue(c.isNull("season")); assertTrue(c.isNull("episode"))
            assertEquals("/icon/$peacock", c.getString("icon"))        // the show had no picture either
        }
    }

    @Test fun theNextEpisodeTakesTheShowsCard() {
        val log = WatchRules.Log(me)
        var t = watch(log, peacock, episode, from = 1_000_000, dur = length, pos = 0)
        val first = entryOf(log, peacock, episode)
        first.art = "aaaaaaaaaaaa.jpg"
        log.resolve(first, show, "ts90001", "https://images.justwatch.com/p.jpg", "https://images.justwatch.com/b.jpg", 5, 8, "tse90508", wall0 + t)
        // forty minutes on the next episode plays by itself: no preview this time
        t = watch(log, peacock, "Low Tide at Noon", from = t + 40 * min, dur = 43 * min, pos = 0)
        val second = entryOf(log, peacock, "Low Tide at Noon")
        assertEquals(2, log.size)
        // the show already being watched in this app is tried, and only its next few episodes
        val c = log.candidates(second, wall0 + t).single()
        assertEquals(show, c.name); assertEquals("ts90001", c.showId); assertEquals("tse90508", c.after); assertEquals("https://images.justwatch.com/p.jpg", c.poster)
        // it is: one card for the show, the new episode's. The old one goes, and is handed back for its picture
        val gone = log.resolve(second, c.name, c.showId!!, c.poster, c.backdrop, 5, 9, "tse90509", wall0 + t)
        assertEquals(1, gone.size); assertSame(first, gone[0]); assertEquals("aaaaaaaaaaaa.jpg", gone[0].art)
        assertEquals(1, log.size)
        cards(log, t).single().let { assertEquals(show, it.getString("title")); assertEquals(9, it.getInt("episode")); assertEquals("Low Tide at Noon", it.getString("episode_title")) }
        // a show whose episode is not known cannot say which episode is next: it is not offered
        val t2 = watch(log, hulu, "Looking Back (1)", from = t, dur = 44 * min, pos = 0)
        log.resolve(entryOf(log, hulu, "Looking Back (1)"), "Some Show", "ts1", null, null, null, null, null, wall0 + t2)
        val t3 = watch(log, hulu, "Another Episode", from = t2, dur = 44 * min, pos = 0)
        assertTrue(log.candidates(entryOf(log, hulu, "Another Episode"), wall0 + t3).isEmpty())
        // and shows are per app: Peacock's is not tried for something watched in Hulu
        assertTrue(log.candidates(entryOf(log, hulu, "Another Episode"), wall0 + t3).none { it.showId == "ts90001" })
    }

    @Test fun aPreviewOfAShowAlreadyKnownNeedsNoSearch() {
        val log = WatchRules.Log(me)
        var t = watch(log, peacock, episode, from = 1_000_000, dur = length, pos = 0)
        log.resolve(entryOf(log, peacock, episode), show, "ts90001", null, null, 5, 8, "tse90508", wall0 + t)
        // the next day, from the show's page, an episode from another season
        t += 24 * 60 * min
        log.see(seen(peacock, "harbour lights (trailer)", at = t), ex)
        t = watch(log, peacock, "First Light", from = t + 7000, dur = 45 * min, pos = 0)
        val c = log.candidates(entryOf(log, peacock, "First Light"), wall0 + t)
        // once, with the id already known, and the whole list to look in (not only the next few)
        assertEquals(1, c.size); assertEquals(show, c[0].name); assertEquals("ts90001", c[0].showId); assertNull(c[0].after)
    }

    // ---------------------------------------------------------------- a title logged before any of this was built

    @Test fun aKeptEveningFindsItsShow() {
        // (a made-up evening, in the shape the kept looks have: a preview seven seconds before an episode that names only
        // itself, an app whose keys hold nothing, an app that is left out)
        // the log as the first version of this app would have left it: the episode's name, nothing found for it, no "began"
        val played = at("2030-01-01T19:16:00.000-08:00")
        val log = WatchRules.Log(me).also { it.load(JSONObject().put("v", 1).put("entries", JSONArray().put(JSONObject()
            .put("key", WatchRules.key(peacock, episode)).put("package", peacock).put("title", episode).put("app_name", "Peacock TV")
            .put("duration_ms", length).put("position_ms", 215_000).put("played_ms", 211_000).put("last_played", played)
            .put("looked_at", played).put("looked_ok", false)))) }
        val e = log.all().single()
        assertEquals(0L, e.began)
        // the raw looks kept on the TV (GET /api/continue/log) know the rest
        log.adopt(load("looks-made-up.json").getJSONArray("looks"))
        assertEquals(at("2030-01-01T19:12:17.000-08:00"), e.began)                 // the oldest look that names the episode
        assertEquals(setOf(show, "Quillfeather Bay"), log.hints().map { it.name }.toSet())
        assertEquals(at("2030-01-01T19:12:10.000-08:00"), log.hints().first { it.name == show }.at)
        // the preview seven seconds before the episode is the show to try first; the one seen ten minutes later, on the way
        // out, only after it, as one of the week's
        val c = log.candidates(e, at("2030-01-01T19:35:00-08:00"))
        assertEquals(listOf(show to WatchRules.NEAR, "Quillfeather Bay" to WatchRules.KEPT), c.map { it.name to it.kind })
        // and in the show's episode list (made up, in the shape JustWatch sends) there is one episode of that name and length
        val m = ShowRules.match(ShowRules.episodes(load("jw-episodes-light-made-up.json")), e.title, e.durationMs, c[0].after, WatchRules.NEXT_SPAN)!!
        assertEquals("tse90508", m.episode!!.id)
        log.resolve(e, show, "ts90001", null, "https://images.justwatch.com/b.jpg", m.episode!!.season, m.episode!!.episode, m.episode!!.id, played)
        log.items(played, true, ex).single().let { card ->
            assertEquals(show, card.getString("title")); assertEquals(episode, card.getString("episode_title"))
            assertEquals(5, card.getInt("season")); assertEquals(8, card.getInt("episode"))
            assertEquals("peacock", card.getJSONObject("app").getString("id")); assertEquals("Peacock TV", card.getJSONObject("app").getString("name"))
            assertEquals(0.083, card.getDouble("progress"), 0.001)
        }
        // Hulu's and YouTube's lines name nothing: nothing is made of them here
        assertTrue(log.hints().all { it.pkg == peacock })
    }

    @Test fun whenTheLooksHaveRolledAway() {
        val played = wall0 + 5 * min
        val log = WatchRules.Log(me).also { it.load(JSONObject().put("entries", JSONArray().put(JSONObject()
            .put("key", WatchRules.key(peacock, episode)).put("package", peacock).put("title", episode).put("last_played", played)))) }
        log.adopt(JSONArray()); assertEquals(played, log.all().single().began)
        // nothing to go by: no show to try, until it is played again from the show's page
        assertTrue(log.candidates(log.all().single(), played).isEmpty())
        log.adopt(null)
        // a look with no time or no title is passed over
        log.adopt(JSONArray().put(JSONObject().put("package", peacock)).put(JSONObject().put("package", peacock).put("time", "yesterday")
            .put("read", JSONObject().put("title", "X (Trailer)"))).put("not a look"))
        assertTrue(log.hints().isEmpty())
    }

    // ---------------------------------------------------------------- an app that names nothing

    @Test fun anAppThatPlaysAndNamesNothingGetsOneCard() {
        // Hulu on a Fire TV: four metadata keys with nothing in them, the state "playing", a position that moves on, no length
        val log = WatchRules.Log(me)
        var t = 1_000_000L
        log.see(seen(hulu, null, at = t, pos = 300_000, app = "Hulu"), ex).let {
            assertEquals("no title", it.decision); assertNull(it.entry)
            assertEquals("the session names nothing; played 0 s of the 120 s it takes to keep a card for the app", it.detail) }
        assertTrue(log.playing())                    // so it is looked at again in a minute, like a title
        t += min
        assertEquals("no title", log.see(seen(hulu, null, at = t, pos = 360_000, app = "Hulu"), ex).decision)
        assertTrue(cards(log, t).isEmpty())
        t += min
        val v = log.see(seen(hulu, null, at = t, pos = 420_000, app = "Hulu"), ex)
        assertEquals("app", v.decision); assertNotNull(v.entry); assertFalse(v.picture)
        t += 30_000
        assertTrue(log.see(seen(hulu, null, state = paused, at = t, pos = 450_000, app = "Hulu"), ex).write)
        val card = cards(log, t).single()
        assertEquals("app", card.getString("kind")); assertEquals("Hulu", card.getString("title"))
        card.getJSONObject("app").let { a -> assertEquals("hulu", a.getString("id")); assertEquals("Hulu", a.getString("name")); assertEquals(hulu, a.getString("package")) }
        assertEquals(WatchRules.id(WatchRules.appKey(hulu)), card.getString("id"))
        // where the app says it is, and no progress: there is no length, and nobody knows what it is a position in
        assertEquals(450_000L, card.getLong("position_ms")); assertTrue(card.isNull("progress")); assertTrue(card.isNull("duration_ms"))
        for (k in listOf("episode_title", "season", "episode", "poster", "backdrop")) assertTrue(k, card.isNull(k))
        assertEquals("/icon/$hulu", card.getString("icon")); assertEquals("tv", card.getString("source"))
        // nothing to ask anybody about it
        val e = log.find(card.getString("id"))!!
        assertFalse(WatchRules.wantsLookup(e, true, wall0 + t)); assertTrue(log.candidates(e, wall0 + t).isEmpty()); assertFalse(log.unresolved(e))
        // the next evening: still the one card, moved to the front of the row by its time
        val next = t + 24 * 60 * min
        log.see(seen(hulu, null, at = next, pos = 60_000, app = "Hulu"), ex)
        log.see(seen(hulu, null, state = paused, at = next + min, pos = 120_000, app = "Hulu"), ex)
        assertEquals(1, log.size)
        cards(log, next + min).single().let { assertEquals(120_000L, it.getLong("position_ms")); assertTrue(it.getString("last_played") > card.getString("last_played")) }
        // it is kept, comes back after a restart, and can be forgotten like any card
        val again = WatchRules.Log(me).also { it.load(JSONObject(log.toJson().toString())) }
        assertEquals("app", again.items(wall0 + next + min, false, ex).single().getString("kind"))
        assertNotNull(again.forget(card.getString("id"))); assertEquals(0, again.size)
    }

    @Test fun anAppsCardIsNeverFinishedOrShort() {
        val log = WatchRules.Log(me)
        // a position near what would be the end of something, with a length given: still there (it is not a title's progress)
        val t = watch(log, hulu, null, from = 1_000_000, dur = 20 * min, pos = 17 * min, app = "Hulu")
        cards(log, t).single().let { assertEquals("app", it.getString("kind")); assertTrue(it.isNull("progress")); assertTrue(it.isNull("duration_ms")) }
        // but something nameless that says it is a 30 second clip is not what the app was opened for
        val other = WatchRules.Log(me)
        val t2 = watch(other, hulu, null, from = 1_000_000, dur = 30_000, pos = 0, app = "Hulu")
        assertTrue(cards(other, t2).isEmpty())
        assertEquals("the session names nothing, and says it is 30 s long", other.see(seen(hulu, null, at = t2, dur = 30_000), ex).detail)
    }

    @Test fun theAppsCardGoesWhenTheAppHasATitleOfItsOwn() {
        val log = WatchRules.Log(me)
        var t = watch(log, hulu, null, from = 1_000_000, app = "Hulu")
        assertEquals("app", cards(log, t).single().getString("kind"))
        // a title that is gone again in a minute changes nothing
        log.see(seen(hulu, "Paper Boats", at = t), ex); t += min
        log.see(seen(hulu, "Paper Boats", state = paused, at = t), ex)
        assertEquals("app", cards(log, t).single().getString("kind"))
        // one that counts takes the app's card away
        t = watch(log, hulu, "Paper Boats", from = t)
        assertEquals(listOf("Paper Boats"), cards(log, t).map { it.getString("title") }); assertEquals(1, log.size)
        // and from then on an empty session is only a moment between two titles (Peacock's is empty now and then while an episode plays)
        val t2 = watch(log, hulu, null, from = t, app = "Hulu")
        assertEquals(listOf("Paper Boats"), cards(log, t2).map { it.getString("title") })
        assertEquals("no title", log.see(seen(hulu, null, at = t2), ex).decision)
        assertEquals("the session names nothing", log.see(seen(hulu, null, at = t2 + 1000), ex).detail)
    }

    @Test fun anAppLeftOutGetsNoCardOfItsOwn() {
        val log = WatchRules.Log(me)
        // YouTube on that TV: an empty title, playing. It is on the list of apps left out
        val t = watch(log, "com.amazon.firetv.youtube", "", from = 1_000_000, app = "YouTube")
        assertEquals(0, log.size); assertFalse(log.playing())
        // an app that had its card and is left out later: the card stops showing
        val t2 = watch(log, hulu, null, from = t, app = "Hulu")
        assertEquals(1, cards(log, t2).size)
        assertTrue(log.items(wall0 + t2, true, ex + hulu).isEmpty())
        // a title of nothing but spaces is no title
        val t3 = watch(log, peacock, "   ", from = t2, app = "Peacock TV")
        assertEquals("app", log.items(wall0 + t3, true, ex).first().getString("kind"))
    }

    // ---------------------------------------------------------------- a card with no picture

    @Test fun aCardWithNoPictureSaysWhichAppItIs() {
        val log = WatchRules.Log(me)
        val t = watch(log, "tv.some.new.app", "Copper Sky", from = 1_000_000)
        assertEquals("/icon/tv.some.new.app", cards(log, t).single().getString("icon"))
        assertEquals("tv.some.new.app", cards(log, t).single().getJSONObject("app").getString("package"))
        // with a picture of the app's own there is nothing to stand in for
        var t2 = t
        for (i in 0..3) { log.see(seen("tv.some.new.app", "Tin Whistle", at = t2, artUri = "https://img.example.com/tin-whistle.jpg"), ex); t2 += min }
        cards(log, t2).first().let { assertEquals("Tin Whistle", it.getString("title")); assertEquals("https://img.example.com/tin-whistle.jpg", it.getString("backdrop")); assertTrue(it.isNull("icon")) }
    }

    @Test fun aTitleWithNoLatinLetterInItIsItsOwnEntry() {
        // nothing is left of such a title once only letters a to z and figures are kept: it goes by its own characters
        val log = WatchRules.Log(me)
        var t = watch(log, peacock, "海辺の灯り", from = 1_000_000)
        t = watch(log, peacock, "遠い道のり", from = t)
        assertEquals(listOf("遠い道のり", "海辺の灯り"), cards(log, t).map { it.getString("title") })
        // and neither is taken for the app's own card
        assertTrue(cards(log, t).all { it.getString("kind") == "continue" })
        assertFalse(WatchRules.key(peacock, "海辺の灯り") == WatchRules.appKey(peacock))
        assertEquals(WatchRules.key(peacock, "海辺の灯り"), WatchRules.key(peacock, " 海辺の灯り "))
        // a title in Latin letters goes by them, as it always has
        assertEquals("$peacock|paper boats", WatchRules.key(peacock, "Paper Boats!"))
    }
}
