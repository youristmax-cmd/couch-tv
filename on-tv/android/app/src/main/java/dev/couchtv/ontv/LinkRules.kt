package dev.couchtv.ontv

/**
 * The rules for opening a channel link in a TV app (Server.kt, launch), with no Android in them so
 * they can be checked on a computer (app/src/test).
 *
 * Why they exist. Seen on a Fire TV in November 2026 with DirecTV (com.att.tv, links of the form
 * dtvnow://deeplink.directvnow.com/play/channel/<call sign>/<resource id>; only the id matters):
 *  - with DirecTV in front, a link tunes every time, in about 6 seconds;
 *  - with DirecTV not in front, the link brings it forward (playing about 3 s later when it was
 *    waiting in the background, about 15 s when it had to start), and it then sometimes ignores
 *    the link and shows the channel it had before. That happened from the background as well as
 *    from cold, so how long the app takes says nothing about whether the link was taken;
 *  - the same link sent again once the app is playing tunes it, and when the first one had
 *    worked, a second one leaves it on the same channel.
 * So a link sent to an app that is not already in front is always sent once more, a moment after
 * the app is playing. What "in front" and "playing" are told by depends on what the TV allows:
 *  - with ADB on the TV: the front app is seen, and the app's media session says when it is
 *    playing (playing, waitUntil, againWhenPlaying). If it never says so, the second send comes
 *    when the 25 s are up.
 *  - without ADB (a Fire TV that refuses the TV its own ADB) the front app cannot be seen. A link
 *    sent from this app's own home gets the second send; one sent while the home is not in front
 *    (from the phone, over some app) is sent once. "Playing" is sound: if the TV was silent
 *    before the link, it is listened to once a second, and the second send follows the first
 *    sound. If something was already playing (music on the TV), or no sound comes in 30 s, there
 *    is nothing to go by and the link stays sent once (soundAfter, againAfterSound).
 * Either way the second send is dropped when a newer link has been asked for or somebody has come
 * back to the home since the first.
 *
 * What nothing here can tell: whether the link itself is right. A link with an id the account
 * cannot tune does nothing visible and reports no error (docs/SCORES.md).
 */
object LinkRules {
    const val EVERY_MS = 2000L       // with ADB: how often the app's media session is looked at
    const val GIVE_UP_MS = 25_000L   // and for how long at most

    const val SOUND_EVERY_MS = 1000L      // without ADB: how often the TV is listened to for sound
    const val SOUND_GIVE_UP_MS = 30_000L  // and for how long at most

    const val AGAIN_AFTER_MS = 1500L      // the second send comes this long after the app is first seen, or heard, playing

    /**
     * Send the link a second time, once the app is playing? Yes whenever the app was not already
     * in front. appInFront is what ADB saw before the send; null without ADB, and then the answer
     * is whether this app's own home was in front (the link came from a score bug, so the TV app
     * was behind it or not running at all).
     */
    fun again(appInFront: Boolean?, homeInFront: Boolean): Boolean = if (appInFront != null) !appInFront else homeInFront

    /**
     * Does `dumpsys media_session` say this app is playing? In the app's block, after its
     * "package=<pkg>" line: "state=PlaybackState {state=3, ..." (Android 9, Fire OS 7) or
     * "state=PlaybackState {state=PLAYING(3), ..." (newer Android).
     */
    fun playing(dumpsys: String, pkg: String): Boolean {
        var mine = false
        for (raw in dumpsys.lineSequence()) {
            val line = raw.trim()
            if (line.startsWith("package=")) mine = line.substringAfter('=').trim() == pkg
            else if (mine && line.startsWith("state=PlaybackState") && Regex("""\{state=(?:PLAYING\b|3\b)""").containsMatchIn(line)) return true
        }
        return false
    }

    /**
     * Looks every 2 s until the check says yes, for 25 s at most. True when it said yes, false
     * when the time was up. The clock and the sleep are handed in so this can be tested.
     */
    fun waitUntil(everyMs: Long = EVERY_MS, giveUpMs: Long = GIVE_UP_MS, clock: () -> Long = { System.currentTimeMillis() },
                  sleep: (Long) -> Unit = { Thread.sleep(it) }, check: () -> Boolean): Boolean {
        val end = clock() + giveUpMs
        while (true) {
            if (check()) return true
            if (clock() + everyMs > end) return false
            sleep(everyMs)
        }
    }

    /**
     * Listens once a second, for 30 s at most, from the moment the link was sent. How long after
     * that sound began (milliseconds, to the second), or null when none came.
     */
    fun soundAfter(everyMs: Long = SOUND_EVERY_MS, giveUpMs: Long = SOUND_GIVE_UP_MS, clock: () -> Long = { System.currentTimeMillis() },
                   sleep: (Long) -> Unit = { Thread.sleep(it) }, sounding: () -> Boolean): Long? {
        val sent = clock()
        while (true) {
            if (sounding()) return clock() - sent
            if (clock() - sent + everyMs > giveUpMs) return null
            sleep(everyMs)
        }
    }

    // ---------------------------------------------------------------- the second send

    /**
     * The second send with ADB: waits until the app's media session says it is playing (or the
     * 25 s are up), 1.5 s more, and sends. Everything it looks at is handed in:
     *  - wanted: still this link's turn, and nobody back at the home since the first send. Asked
     *    all along; once it says no, nothing is sent.
     *  - inPlace: asked once, just before sending: the app is in front.
     * True when the link was sent again.
     */
    fun againWhenPlaying(playing: () -> Boolean, wanted: () -> Boolean, inPlace: () -> Boolean, send: () -> Unit,
                         clock: () -> Long = { System.currentTimeMillis() }, sleep: (Long) -> Unit = { Thread.sleep(it) }): Boolean {
        waitUntil(clock = clock, sleep = sleep) { !wanted() || playing() }
        return afterAMoment(wanted, inPlace, send, sleep)
    }

    /**
     * The second send without ADB: by sound. Nothing when something was already playing before
     * the link (its sound says nothing about the app), or when no sound comes within 30 s.
     * Otherwise 1.5 s after the first sound, with the same two questions as above (inPlace here:
     * this app is no longer in front, so the link did bring something forward).
     * True when the link was sent again.
     */
    fun againAfterSound(soundBefore: Boolean, sounding: () -> Boolean, wanted: () -> Boolean, inPlace: () -> Boolean, send: () -> Unit,
                        clock: () -> Long = { System.currentTimeMillis() }, sleep: (Long) -> Unit = { Thread.sleep(it) }): Boolean {
        if (soundBefore) return false
        soundAfter(clock = clock, sleep = sleep) { !wanted() || sounding() } ?: return false
        return afterAMoment(wanted, inPlace, send, sleep)
    }

    private fun afterAMoment(wanted: () -> Boolean, inPlace: () -> Boolean, send: () -> Unit, sleep: (Long) -> Unit): Boolean {
        if (!wanted()) return false
        sleep(AGAIN_AFTER_MS)
        if (!wanted() || !inPlace()) return false
        send()
        return true
    }

    // ---------------------------------------------------------------- a title's link, and which app gets it

    /**
     * Makes an "intent:" link go to one app. The links JustWatch gives are of two kinds: some name
     * the app they are for ("package=com.netflix.ninja"), many name none and are a plain https
     * address ("intent://www.peacocktv.com/deeplink?...#Intent;scheme=https;end"), which a TV may
     * hand to a browser, or to nothing.
     *  - always = false: the package is added only to a link that names no app of its own.
     *  - always = true: the link is sent to this package whatever it names. That is for a title
     *    watched in a known app: JustWatch names the Android TV edition ("com.hulu.livingroomplus")
     *    where a Fire TV has another ("com.hulu.plus").
     * Anything that is not an intent: link, or has a second intent inside it (SEL), is left as it is.
     */
    fun aim(uri: String, pkg: String, always: Boolean = false): String {
        if (!uri.startsWith("intent:") || !Regex("[A-Za-z0-9_.]+").matches(pkg)) return uri
        val at = uri.indexOf("#Intent;")
        if (at < 0) return uri
        val parts = uri.substring(at + "#Intent;".length).split(';').filter { it.isNotEmpty() && it != "end" }
        if ("SEL" in parts) return uri
        val named = parts.any { it.startsWith("package=") || it.startsWith("component=") }
        if (named && !always) return uri
        val kept = parts.filter { !it.startsWith("package=") && !it.startsWith("component=") }
        return uri.substring(0, at) + "#Intent;" + (kept + "package=$pkg").joinToString(";") + ";end"
    }
}
