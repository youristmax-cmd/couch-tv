package dev.couchtv.ontv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for sending a channel link to a TV app (LinkRules.kt). The media session text below
 * was written by hand in the shape `dumpsys media_session` prints, around the one line seen on
 * the real Fire TV ("state=PlaybackState {state=3" in the block for package=com.att.tv): nothing
 * here was recorded from a TV, and nothing here starts an app. Time is a pretend clock.
 */
class LinkRulesTest {
    private fun sessions(directv: String, spotify: String) = """
        MEDIA SESSION SERVICE (dumpsys media_session)

        2 sessions listeners.
        Global priority session is null
        User Records:
        Record for full_user=0
          Sessions Stack - have 2 sessions:
            DIRECTV com.att.tv/DIRECTV (userId=0)
              ownerPid=5021, ownerUid=10089, userId=0
              package=com.att.tv
              launchIntent=null
              active=true
              flags=3
              rating type=0
              controllers: 1
              state=PlaybackState {state=$directv, position=0, buffered position=0, speed=1.0, updated=735211, actions=0, custom actions=[], active item id=-1, error=null}
              audioAttrs=AudioAttributes: usage=USAGE_MEDIA content=CONTENT_TYPE_UNKNOWN flags=0x0 tags= bundle=null
              volumeType=1, controlId=null, max=0, current=0
              metadata:size=0, description=null
            spotify-media-session com.spotify.tv.android/spotify-media-session (userId=0)
              ownerPid=4410, ownerUid=10102, userId=0
              package=com.spotify.tv.android
              active=true
              state=PlaybackState {state=$spotify, position=81234, buffered position=0, speed=1.0, updated=700112, actions=823, custom actions=[], active item id=3, error=null}
              metadata:size=7, description=Song, Artist, Album
    """.trimIndent()

    @Test fun aLinkToAnAppThatIsNotInFrontIsSentTwice() {
        // with ADB the front app is seen: in front, once; anywhere else (behind the home, or not running), twice
        assertFalse(LinkRules.again(appInFront = true, homeInFront = false)); assertFalse(LinkRules.again(appInFront = true, homeInFront = true))
        assertTrue(LinkRules.again(appInFront = false, homeInFront = true)); assertTrue(LinkRules.again(appInFront = false, homeInFront = false))
        // without ADB it is not: a link sent from the home (a score bug) twice, one sent while
        // the home is not in front (from the phone, over whatever app is showing) once
        assertTrue(LinkRules.again(appInFront = null, homeInFront = true))
        assertFalse(LinkRules.again(appInFront = null, homeInFront = false))
    }

    @Test fun isTheAppPlaying() {
        val tv = "com.att.tv"
        // Fire OS 7 (Android 9) prints the state as a number: 3 is playing
        assertTrue(LinkRules.playing(sessions("3", "2"), tv))
        // connecting (8), buffering (6), paused (2), stopped (1), none (0): not yet
        for (s in listOf("8", "6", "2", "1", "0")) assertFalse(s, LinkRules.playing(sessions(s, "2"), tv))
        // somebody else playing is not this app playing
        assertFalse(LinkRules.playing(sessions("6", "3"), tv)); assertTrue(LinkRules.playing(sessions("6", "3"), "com.spotify.tv.android"))
        // newer Android names the state as well
        assertTrue(LinkRules.playing(sessions("PLAYING(3)", "PAUSED(2)"), tv)); assertFalse(LinkRules.playing(sessions("BUFFERING(6)", "PLAYING(3)"), tv))
        // no session of that app at all, or nothing to read
        assertFalse(LinkRules.playing(sessions("3", "3"), "com.example.other")); assertFalse(LinkRules.playing("", tv))
        // a state that only begins with 3 is not 3
        assertFalse(LinkRules.playing(sessions("32", "2"), tv))
    }

    /** waitUntil with a pretend clock: each sleep moves it on, and each look takes `lookMs`. Gives back (answer, looks, time gone). */
    private fun waited(yesAtLook: Int, lookMs: Long = 0): Triple<Boolean, Int, Long> {
        var now = 1_000_000L; var looks = 0
        val start = now
        val ok = LinkRules.waitUntil(clock = { now }, sleep = { now += it }) { looks++; now += lookMs; looks >= yesAtLook }
        return Triple(ok, looks, now - start)
    }

    @Test fun lookingEvery2SecondsFor25AtMost() {
        // playing at once: no waiting
        assertEquals(Triple(true, 1, 0L), waited(yesAtLook = 1))
        // playing at the 8th look: 14 s (about what DirecTV took from cold)
        assertEquals(Triple(true, 8, 14_000L), waited(yesAtLook = 8))
        // just in time: the 13th look, at 24 s
        assertEquals(Triple(true, 13, 24_000L), waited(yesAtLook = 13))
        // never playing: 13 looks, the last at 24 s, and no 14th past 25 s
        assertEquals(Triple(false, 13, 24_000L), waited(yesAtLook = 99))
        // a slow look (dumpsys can take a second) counts as time too: still done by 25 s or so
        val slow = waited(yesAtLook = 99, lookMs = 1000)
        assertFalse(slow.first); assertTrue("${slow.third} ms", slow.third in 23_000L..26_000L)
        assertEquals(2000L, LinkRules.EVERY_MS); assertEquals(25_000L, LinkRules.GIVE_UP_MS)
    }

    /** soundAfter with a pretend clock. Sound begins `soundAtMs` after the link (null: never). Gives back (answer, how often it listened, time gone). */
    private fun listened(soundAtMs: Long?, lookMs: Long = 0): Triple<Long?, Int, Long> {
        var now = 5_000_000L; var looks = 0
        val sent = now
        val after = LinkRules.soundAfter(clock = { now }, sleep = { now += it }) {
            looks++; now += lookMs
            soundAtMs != null && now - sent >= soundAtMs
        }
        return Triple(after, looks, now - sent)
    }

    @Test fun listeningOnceASecondFor30AtMost() {
        // an app waiting in the background: playing about 3 s after the link
        assertEquals(Triple(3000L, 4, 3000L), listened(soundAtMs = 3000))
        // sound that begins between two listens is heard at the next one
        assertEquals(Triple(7000L, 8, 7000L), listened(soundAtMs = 6400))
        // an app started from cold: about 15 s
        assertEquals(Triple(15_000L, 16, 15_000L), listened(soundAtMs = 15_000))
        // just in time, at 30 s
        assertEquals(Triple(30_000L, 31, 30_000L), listened(soundAtMs = 30_000))
        // never: 31 listens, the last at 30 s, and then no more
        assertEquals(Triple<Long?, Int, Long>(null, 31, 30_000L), listened(soundAtMs = null))
        assertEquals(Triple<Long?, Int, Long>(null, 31, 30_000L), listened(soundAtMs = 31_000))
        // a slow listen counts as time too
        val slow = listened(soundAtMs = null, lookMs = 500)
        assertEquals(null, slow.first); assertTrue("${slow.third} ms", slow.third in 29_000L..31_000L)
        assertEquals(1000L, LinkRules.SOUND_EVERY_MS); assertEquals(30_000L, LinkRules.SOUND_GIVE_UP_MS)
    }

    // ---------------------------------------------------------------- the second send

    /**
     * One evening on a pretend TV. The link is sent at time 0. Sound (or "playing") begins at
     * `playAt`; somebody comes back to the home, or asks for another link, at `goneAt`; the TV app
     * is in front (so this app is not) unless `inPlace` says otherwise.
     */
    private class Evening(val playAt: Long?, val goneAt: Long? = null, val inPlace: Boolean = true) {
        var now = 0L
        val sends = ArrayList<Long>()          // when the link was sent again
        var asked = 0                          // how often the TV was listened to, or looked at
        val clock = { now }
        val sleep = { ms: Long -> now += ms }
        val playing = { asked++; playAt != null && now >= playAt }
        val wanted = { goneAt == null || now < goneAt }
        val send = { sends += now; Unit }
        fun bySound(soundBefore: Boolean = false) = LinkRules.againAfterSound(soundBefore, playing, wanted, { inPlace }, send, clock, sleep)
        fun byAdb() = LinkRules.againWhenPlaying(playing, wanted, { inPlace }, send, clock, sleep)
    }

    @Test fun withoutAdb_aSilentStartThenSoundGetsTheLinkAgain() {
        assertEquals(1500L, LinkRules.AGAIN_AFTER_MS)
        // DirecTV waiting behind the home: sound about 3 s after the link, the second send 1.5 s later
        var e = Evening(playAt = 3000)
        assertTrue(e.bySound()); assertEquals(listOf(4500L), e.sends)
        // DirecTV started from cold: sound at about 15 s. The same rule, whatever the time
        e = Evening(playAt = 15_000)
        assertTrue(e.bySound()); assertEquals(listOf(16_500L), e.sends)
        // as late as the 30th second
        e = Evening(playAt = 30_000)
        assertTrue(e.bySound()); assertEquals(listOf(31_500L), e.sends)
    }

    @Test fun withoutAdb_soundAlreadyPlayingOrNoneAtAllMeansOnce() {
        // music was playing on the TV before the link: nothing is listened for, nothing sent again
        var e = Evening(playAt = 0)
        assertFalse(e.bySound(soundBefore = true)); assertTrue(e.sends.isEmpty()); assertEquals(0, e.asked); assertEquals(0L, e.now)
        // never any sound: 30 s of listening, then nothing
        e = Evening(playAt = null)
        assertFalse(e.bySound()); assertTrue(e.sends.isEmpty()); assertEquals(31, e.asked); assertEquals(30_000L, e.now)
    }

    @Test fun withoutAdb_noSecondSendOnceTheHomeIsBackOrAnotherLinkWasAsked() {
        // back at the home 2 s after the link, before DirecTV made a sound: the listening stops there
        var e = Evening(playAt = 3000, goneAt = 2000)
        assertFalse(e.bySound()); assertTrue(e.sends.isEmpty()); assertEquals(2000L, e.now)
        // back at the home in the moment between the first sound and the second send
        e = Evening(playAt = 3000, goneAt = 4000)
        assertFalse(e.bySound()); assertTrue(e.sends.isEmpty())
        // already back before anything was heard at all
        e = Evening(playAt = 3000, goneAt = 0)
        assertFalse(e.bySound()); assertTrue(e.sends.isEmpty()); assertEquals(0L, e.now)
        // sound came, but this app is still in front: the link brought nothing forward, so not again
        e = Evening(playAt = 3000, inPlace = false)
        assertFalse(e.bySound()); assertTrue(e.sends.isEmpty())
        // back at the home long after the second send: that one was sent
        e = Evening(playAt = 3000, goneAt = 60_000)
        assertTrue(e.bySound()); assertEquals(listOf(4500L), e.sends)
    }

    @Test fun withAdb_theSecondSendFollowsPlayingOrThe25Seconds() {
        // the media session says playing at the look 4 s after the link: again 1.5 s later
        var e = Evening(playAt = 3000)
        assertTrue(e.byAdb()); assertEquals(listOf(5500L), e.sends)
        // from cold, playing at 15 s: seen at the 16 s look
        e = Evening(playAt = 15_000)
        assertTrue(e.byAdb()); assertEquals(listOf(17_500L), e.sends)
        // it never says playing: the 25 s run out (the last look is at 24 s), and the link is sent again all the same
        e = Evening(playAt = null)
        assertTrue(e.byAdb()); assertEquals(listOf(25_500L), e.sends); assertEquals(13, e.asked)
        // the home came back, or a newer link was asked for: nothing more
        e = Evening(playAt = 15_000, goneAt = 5000)
        assertFalse(e.byAdb()); assertTrue(e.sends.isEmpty()); assertEquals(6000L, e.now)
        e = Evening(playAt = 3000, goneAt = 5000)
        assertFalse(e.byAdb()); assertTrue(e.sends.isEmpty())
        // the app is no longer in front when the moment comes: nothing more
        e = Evening(playAt = 3000, inPlace = false)
        assertFalse(e.byAdb()); assertTrue(e.sends.isEmpty())
    }

    // ---------------------------------------------------------------- which app a title's link goes to

    // links of the forms JustWatch gave in November 2026 (United States), with made-up ids
    private val peacockLink = "intent://www.peacocktv.com/deeplink?deeplinkData=%7B%22pvid%22%3A%2200000000-0000-4000-8000-000000000508%22%2C%22type%22%3A%22EPISODE%22%2C%22action%22%3A%22PDP%22%7D#Intent;launchFlags=0x00800000;scheme=https;end"
    private val netflixLink = "intent://www.netflix.com/watch/12345678#Intent;launchFlags=0x00800000;scheme=https;package=com.netflix.ninja;S.source=30;end"
    private val huluLink = "intent:#Intent;launchFlags=0x00800000;package=com.hulu.livingroomplus;action=hulu.intent.action.PLAY_CONTENT;S.content_id=00000000-0000-4000-8000-000000000042;end"
    private val fuboLink = "intent://www.fubo.tv/welcome/series/123456789/x/?season=5#Intent;launchFlags=0x00800000;scheme=https;component=com.fubo.firetv.screen/tv.fubo.mobile.presentation.onboarding.dispatch.controller.DispatchActivity;end"

    @Test fun aLinkThatNamesNoAppIsGivenOne() {
        // a plain https address inside an intent: it would go to a browser, or nowhere
        assertEquals(peacockLink.removeSuffix(";end") + ";package=com.peacock.peacockfiretv;end", LinkRules.aim(peacockLink, "com.peacock.peacockfiretv"))
        // one that names its app is left alone
        assertEquals(netflixLink, LinkRules.aim(netflixLink, "com.netflix.ninja"))
        assertEquals(netflixLink, LinkRules.aim(netflixLink, "com.other.app"))
        assertEquals(fuboLink, LinkRules.aim(fuboLink, "com.other.app"))
    }

    @Test fun aLinkForOneEditionOfAnAppIsSentToTheOneInstalled() {
        // JustWatch names the Android TV edition; a Fire TV has another package for the same app
        assertEquals("intent:#Intent;launchFlags=0x00800000;action=hulu.intent.action.PLAY_CONTENT;S.content_id=00000000-0000-4000-8000-000000000042;package=com.hulu.plus;end",
            LinkRules.aim(huluLink, "com.hulu.plus", always = true))
        // a screen named inside the other edition goes too
        val aimed = LinkRules.aim(fuboLink, "com.fubo.other", always = true)
        assertFalse(aimed.contains("component=")); assertTrue(aimed.endsWith(";scheme=https;package=com.fubo.other;end"))
        assertTrue(aimed.startsWith("intent://www.fubo.tv/welcome/series/123456789/x/?season=5#Intent;"))
        // already the right one: the same link, with the package where it now goes
        assertEquals("intent://www.netflix.com/watch/12345678#Intent;launchFlags=0x00800000;scheme=https;S.source=30;package=com.netflix.ninja;end",
            LinkRules.aim(netflixLink, "com.netflix.ninja", always = true))
    }

    @Test fun whatIsNotSuchALinkIsLeftAsItIs() {
        for (u in listOf("https://www.netflix.com/watch/12345678", "dtvnow://deeplink.directvnow.com/play/channel/KTLA/123", "", "intent://x/y",
            "intent://x#Intent;scheme=https;SEL;package=com.browser;end"))
            for (always in listOf(false, true)) assertEquals(u, u, LinkRules.aim(u, "com.some.app", always))
        // a package name that is not one is never written into a link
        assertEquals(peacockLink, LinkRules.aim(peacockLink, "com.x;S.evil=1"))
        assertEquals(peacockLink, LinkRules.aim(peacockLink, ""))
    }
}
