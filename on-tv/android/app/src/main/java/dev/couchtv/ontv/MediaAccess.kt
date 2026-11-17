package dev.couchtv.ontv

import android.service.notification.NotificationListenerService

/**
 * Reads no notifications and does nothing with any: it overrides nothing that would see one.
 * It exists because Android only lets an app ask for other apps' media sessions (what Netflix or
 * Hulu say they are playing) when the app has a notification listener that the TV's owner has
 * allowed. Keep watching (Watching.kt) is built on those sessions, and on nothing else.
 *
 * Allowed once, from a computer:
 *
 *   adb shell cmd notification allow_listener dev.couchtv.ontv/dev.couchtv.ontv.MediaAccess
 *
 * and taken back with disallow_listener. Without it everything else works as before: Keep watching
 * then only has what was started through Couch TV's own search.
 */
class MediaAccess : NotificationListenerService() {
    // Android binds this service when the access is given and lets go of it when it is taken back:
    // the two moments Keep watching starts and stops listening for media sessions.
    override fun onListenerConnected() { Watching.accessChanged() }
    override fun onListenerDisconnected() { Watching.accessChanged() }
}
