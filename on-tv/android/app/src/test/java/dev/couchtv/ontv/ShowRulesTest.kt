package dev.couchtv.ontv

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What JustWatch's answers about a show mean (ShowRules.kt). The answers in
 * app/src/test/resources/watching are MADE UP: a show that does not exist ("Harbour Lights", six
 * seasons), in the shape JustWatch was seen to answer in November 2026 (its episode list without
 * offers; the offers of one episode and of its show). What the made-up list has, because real
 * ones do: names used for more than one episode, an episode with no length, seasons out of order.
 */
class ShowRulesTest {
    private fun load(name: String): JSONObject = JSONObject(javaClass.getResourceAsStream("/watching/$name")!!.bufferedReader().readText())
    private val list by lazy { ShowRules.episodes(load("jw-episodes-light-made-up.json")) }
    private val min = 60_000L
    private fun ep(season: Int, episode: Int) = list.first { it.season == season && it.episode == episode }

    @Test fun theListInOrderOfSeasonAndNumber() {
        assertEquals(51, list.size)
        list.first().let { assertEquals(1, it.season); assertEquals(1, it.episode); assertEquals("First Light", it.title); assertEquals(45, it.runtimeMin); assertEquals("tse90101", it.id) }
        list.last().let { assertEquals(6, it.season); assertEquals(7, it.episode); assertEquals("The Open Sea", it.title) }
        // whatever order the seasons came in
        assertEquals(list.sortedWith(compareBy({ it.season }, { it.episode })).map { it.id }, list.map { it.id })
        assertTrue(list.all { it.id.isNotEmpty() })
        // no length given for some (it comes as 0): unknown, not zero
        assertNull(ep(4, 9).runtimeMin)
        // an answer with nothing in it, or none
        assertTrue(ShowRules.episodes(JSONObject()).isEmpty()); assertTrue(ShowRules.episodes(null).isEmpty())
        assertTrue(ShowRules.episodes(JSONObject("""{"node": {}}""")).isEmpty())
        assertTrue(ShowRules.episodes(JSONObject("""{"node": {"seasons": [{"content": {"seasonNumber": 1}, "episodes": [{"content": {"title": "No id"}}, "x"]}]}}""")).isEmpty())
    }

    @Test fun anEpisodeByItsNameAndItsLength() {
        assertEquals(3, ShowRules.RUNTIME_SLACK_MIN)
        // what a session says: the name, and 2,590,000 ms (43.2 minutes; the list says 43)
        val m = ShowRules.match(list, "The Long Way Round", 2_590_000)!!
        m.episode!!.let { assertEquals(5, it.season); assertEquals(8, it.episode); assertEquals("tse90508", it.id) }
        assertEquals(1, m.named)
        // capitals and punctuation do not matter
        assertEquals("tse90508", ShowRules.match(list, "  the long way ROUND! ", 2_590_000)!!.episode!!.id)
        assertEquals("tse90507", ShowRules.match(list, "Yes But Not Yet", 43 * min)!!.episode!!.id)
        // the session gives no length: the name alone
        assertEquals("tse90508", ShowRules.match(list, "The Long Way Round", 0)!!.episode!!.id)
        // within 3 minutes either way; further off it is something else of that name
        assertNotNull(ShowRules.match(list, "The Long Way Round", 46 * min)); assertNotNull(ShowRules.match(list, "The Long Way Round", 40 * min))
        assertNull(ShowRules.match(list, "The Long Way Round", 47 * min)); assertNull(ShowRules.match(list, "The Long Way Round", 22 * min))
        // an episode with no length in the list goes by its name
        assertEquals(ep(4, 9).id, ShowRules.match(list, "Episode 9", 90 * min)!!.episode!!.id)
        // not in the list at all
        assertNull(ShowRules.match(list, "Some Other Episode", 43 * min))
        assertNull(ShowRules.match(list, "The Long", 43 * min))
        assertNull(ShowRules.match(list, null, 0)); assertNull(ShowRules.match(list, "  ", 0)); assertNull(ShowRules.match(emptyList(), "The Long Way Round", 0))
    }

    @Test fun aNameUsedEverySeasonGivesTheShowAndNotTheEpisode() {
        // "Looking Back (1)" is in seasons 2, 3, 4 and 5, each 43 or 44 minutes long
        val m = ShowRules.match(list, "Looking Back (1)", 44 * min)!!
        assertNull(m.episode); assertEquals(4, m.named)
        ShowRules.match(list, "Loose Ends", 0)!!.let { assertNull(it.episode); assertEquals(2, it.named) }
    }

    @Test fun onlyTheNextFewEpisodesForAShowAlreadyBeingWatched() {
        val watched = "tse90508"                      // season 5 episode 8
        // the next one, and up to three on
        assertEquals("tse90509", ShowRules.match(list, "Low Tide at Noon", 43 * min, watched, 3)!!.episode!!.id)
        assertEquals(ep(5, 11).id, ShowRules.match(list, "The Gull Knows", 43 * min, watched, 3)!!.episode!!.id)
        // four on, an earlier one, or the same one again: a name alone is not enough to say it is this show
        assertNull(ShowRules.match(list, "Fog on the Slipway", 43 * min, watched, 3))
        assertNull(ShowRules.match(list, "Borrowed Oars", 43 * min, watched, 3))
        assertNull(ShowRules.match(list, "The Long Way Round", 43 * min, watched, 3))
        // (looked for in the whole list, each of them is there)
        assertNotNull(ShowRules.match(list, "Fog on the Slipway", 43 * min)); assertNotNull(ShowRules.match(list, "Borrowed Oars", 43 * min))
        // across the end of a season
        assertEquals(ep(6, 1).id, ShowRules.match(list, "New Moorings", 0, list.last { it.season == 5 }.id, 3)!!.episode!!.id)
        // "Looking Back (1)" right after episode 12 of season 5 is that season's, for sure
        assertEquals(ep(5, 13).id, ShowRules.match(list, "Looking Back (1)", 43 * min, ep(5, 12).id, 3)!!.episode!!.id)
        // the last one watched is not in the list, or is the last there is: nothing follows it
        assertNull(ShowRules.match(list, "Low Tide at Noon", 43 * min, "tse0", 3))
        assertNull(ShowRules.match(list, "Low Tide at Noon", 43 * min, list.last().id, 3))
    }

    @Test fun theListIsKeptAndReadBack() {
        val back = ShowRules.fromJson(JSONArray(ShowRules.toJson(list).toString()))
        assertEquals(list.map { listOf(it.id, it.season, it.episode, it.title, it.runtimeMin) }, back.map { listOf(it.id, it.season, it.episode, it.title, it.runtimeMin) })
        assertTrue(ShowRules.fromJson(null).isEmpty())
        assertTrue(ShowRules.fromJson(JSONArray().put("x").put(JSONArray())).isEmpty())
        assertEquals(24 * 60 * min, ShowRules.LIST_MS); assertEquals(512 * 1024, ShowRules.LIST_MAX_BYTES)
    }

    @Test fun theLinkForOneAppOutOfTheOffers() {
        val links = load("jw-links-made-up.json")
        val episode = links.getJSONObject("episode").getJSONArray("offers"); val show = links.getJSONObject("show").getJSONArray("offers")
        val peacock = listOf("peacocktv", "peacocktvpremium")
        // the episode's own link, and the show's (another id)
        ShowRules.link(episode, peacock)!!.let {
            assertTrue(it.startsWith("intent://www.peacocktv.com/deeplink?deeplinkData=")); assertTrue(it.contains("00000000-0000-4000-8000-000000000508"))
            assertTrue(it.endsWith("#Intent;launchFlags=0x00800000;scheme=https;end")) }
        assertTrue(ShowRules.link(show, peacock)!!.contains("00000000-0000-4000-8000-000000000001"))
        // an app that does not have it
        assertNull(ShowRules.link(episode, listOf("hulu"))); assertNull(ShowRules.link(episode, emptyList()))
        // bought from a shop: its link, when that is the app
        assertTrue(ShowRules.link(episode, listOf("amazon"))!!.startsWith("intent://watch.amazon.com/watch?asin="))
        // an app with both a subscription and a shop: the subscription's, wherever it stands in the list
        assertTrue(ShowRules.link(episode, listOf("amazon", "itunes", "peacocktv"))!!.contains("peacocktv.com"))
        // no offers
        assertNull(ShowRules.link(null, peacock)); assertNull(ShowRules.link(JSONArray(), peacock))
        assertNull(ShowRules.link(JSONArray().put(JSONObject().put("package", JSONObject().put("technicalName", "peacocktv")).put("deeplinkURL", JSONObject.NULL)), peacock))
        // asked for the show alone, the episode's part of the answer is empty
        assertNull(ShowRules.link(JSONObject("{}").optJSONArray("offers"), peacock))
    }
}
