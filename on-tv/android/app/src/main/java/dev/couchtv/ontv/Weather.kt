package dev.couchtv.ontv

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * Weather for the home screen. Open-Meteo: free, no account, no key. At most every 30 minutes.
 * The last good reading is kept in the app's files: when the service does not answer, or the app
 * has just been restarted (every update does that), the home shows that reading while it is under
 * two hours old, rather than no weather at all.
 *
 * The backup, when Open-Meteo does not answer: the US National Weather Service (api.weather.gov;
 * free, no account, no key). It only knows the United States: anywhere else it says so (404) and
 * there is simply no backup. What is asked of it:
 *  - /points/<lat>,<lon> (4 KB), which says where the place's forecast and stations are, and then
 *    its nearest station (/gridpoints/.../stations?limit=1, 4 KB): both once a week for a place;
 *  - for each reading, the 12-hour forecast (13 KB) and that station's latest observation (4 KB).
 *    The hourly forecast would say "now" more exactly, but it is 162 KB a time.
 * So with the backup in use the place's coordinates (four decimals) go to weather.gov as well as
 * to Open-Meteo. What an answer means is in WeatherRules.kt.
 *
 * Who goes first. Open-Meteo, until it fails. From then on the backup is asked first and
 * Open-Meteo is left alone for 10 minutes; after that it is tried again, quietly, behind the
 * backup's answer, and the first time it answers it is first again. (Where there is no backup,
 * Open-Meteo is simply asked each time, as before.)
 *
 * Nobody waits long. The home's page gives up on an answer after 6 seconds, and a service that is
 * down often does not refuse: it just does not answer. So the asking is done on a thread of its
 * own, and whoever asked gets, within 5 seconds, the new reading if it has come, else the last
 * one (under two hours old), else nothing; the asking goes on and its answer is there next time.
 */
object Weather {
    private const val KEPT = "weather-last.json"
    private const val NWS_KEPT = "weather-nws.json"   // {"point", "at", "none", "forecast", "stations", "station", "sunrise", "sunset"}
    private const val NWS = "https://api.weather.gov"
    private const val AGENT = "tv-home-weather/1.0"   // the service asks for a name; this one says nothing about the household
    private const val FRESH_MS = 30 * 60_000L         // a reading this new is not asked for again
    private const val KEEP_MS = 2 * 60 * 60_000L      // an older one is still shown this long when the service is silent
    private const val SKIP_MS = 10 * 60_000L          // after Open-Meteo has failed, it is not asked again for this long
    private const val WEEK_MS = 7 * 24 * 60 * 60_000L
    private const val WAIT_MS = 5000L                 // whoever asks is answered within this (the home's page waits 6 seconds)
    private const val BACKUP_MS = 20_000L             // the backup's whole turn: past this it asks nothing more

    /** One round of asking the services. answered: a reading has come, or the round is over without one. */
    private class Asking { val answered = CountDownLatch(1); @Volatile var over = false }

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "weather").apply { isDaemon = true } }
    private var asking: Asking? = null
    private var epoch = 0                             // moves on when the place changes: an answer for the old place is dropped
    private var restored = false
    @Volatile private var at = 0L
    @Volatile private var data: JSONObject? = null
    @Volatile private var down = false                // Open-Meteo did not answer the last time it was asked
    @Volatile private var skipUntil = 0L
    @Volatile private var covered = true              // false once the backup has said the place is not one it knows

    /** The place changed (settings): the reading for the old place goes, kept copy included. */
    @Synchronized fun clear() {
        at = 0; data = null; restored = true; down = false; skipUntil = 0; covered = true; epoch++; asking = null
        runCatching { Store.writeJson(KEPT, JSONObject()) }
    }

    fun get(): JSONObject? {
        val a: Asking
        synchronized(this) {
            if (!restored) {
                // once after the app starts: the reading kept from before
                restored = true
                runCatching {
                    val k = Store.readJson(KEPT) as? JSONObject
                    val d = k?.optJSONObject("data")
                    if (d != null && d.has("temp")) { data = d; at = k.optLong("at") }
                }
            }
            data?.takeIf { System.currentTimeMillis() - at < FRESH_MS }?.let { return JSONObject(it.toString()) }
            // one round of asking at a time: whoever else asks meanwhile waits for the same one
            a = asking?.takeIf { !it.over } ?: Asking().also { n ->
                asking = n
                val e = epoch
                worker.execute {
                    try { refresh(n, e) } catch (t: Exception) { android.util.Log.w("couchtv", "weather: ${t.message}") }
                    finally { n.over = true; n.answered.countDown() }
                }
            }
        }
        runCatching { a.answered.await(WAIT_MS, TimeUnit.MILLISECONDS) }
        // the new reading if it has come; else the last one, while it is not too old to be worth showing
        return synchronized(this) { data?.takeIf { System.currentTimeMillis() - at < KEEP_MS }?.let { JSONObject(it.toString()) } }
    }

    private fun refresh(a: Asking, e: Int) {
        val w = Store.weather()
        if (down && covered) {
            // Open-Meteo did not answer last time: the backup first, so nobody waits through Open-Meteo's silence
            backup(w)?.let { publish(it, e, a) }
            // and, no sooner than 10 minutes after it failed, a quiet try at Open-Meteo behind that answer
            if (System.currentTimeMillis() >= skipUntil) first(w)?.let { publish(it, e, a) }
            return
        }
        val r = first(w)
        if (r != null) publish(r, e, a) else backup(w)?.let { publish(it, e, a) }
    }

    private fun publish(reading: JSONObject, e: Int, a: Asking) {
        synchronized(this) {
            if (e != epoch) return       // the place changed while this was being asked
            data = reading; at = System.currentTimeMillis()
            runCatching { Store.writeJson(KEPT, JSONObject().put("at", at).put("data", reading)) }
        }
        a.answered.countDown()
    }

    /** Open-Meteo. Remembers whether it answered, which decides who is asked first next time. */
    private fun first(w: JSONObject): JSONObject? {
        val r = try { openMeteo(w) } catch (e: Exception) { android.util.Log.w("couchtv", "weather: ${e.message}"); null }
        if (r != null) { down = false; skipUntil = 0 } else { down = true; skipUntil = System.currentTimeMillis() + SKIP_MS }
        return r
    }

    private fun backup(w: JSONObject): JSONObject? =
        try { nws(w) } catch (e: Exception) { android.util.Log.w("couchtv", "weather (backup): ${e.message}"); null }

    private fun openMeteo(w: JSONObject): JSONObject? {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${w.getDouble("lat")}&longitude=${w.getDouble("lon")}" +
            "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
            "&timezone=${URLEncoder.encode(w.optString("timezone", "UTC"), "UTF-8")}&forecast_days=1"
        val status = IntArray(1)
        // 3 seconds to connect: it normally takes a fraction of one, and the backup still has to fit in the home's wait
        val body = fetch(url, 3000, 5000, status)
        if (body == null) { android.util.Log.w("couchtv", "weather: Open-Meteo answered ${status[0]}"); return null }
        return WeatherRules.openMeteo(w.optString("place"), JSONObject(body))
    }

    /**
     * The backup. Null when the place is not in the United States, or the service does not answer.
     * The whole of it gets 20 seconds: past that nothing more is asked, and the station's
     * observation is the first thing to go (the forecast alone still makes a reading).
     */
    private fun nws(w: JSONObject): JSONObject? {
        val now = System.currentTimeMillis()
        val late = { System.currentTimeMillis() - now > BACKUP_MS }
        val point = WeatherRules.nwsPoint(w.getDouble("lat"), w.getDouble("lon"))
        var k = (Store.readJson(NWS_KEPT) as? JSONObject)?.takeIf { it.optString("point") == point && now - it.optLong("at") < WEEK_MS }
        if (k == null) {
            // where this place's forecast and stations are: asked once a week
            val status = IntArray(1)
            val body = fetch("$NWS/points/$point", 4000, 5000, status)
            if (status[0] == 404) {
                // not a place the service covers (it is for the United States): no backup here, and no asking again this week
                Store.writeJson(NWS_KEPT, JSONObject().put("point", point).put("at", now).put("none", true))
                covered = false
                return null
            }
            val grid = WeatherRules.nwsGrid(body?.let { JSONObject(it) }) ?: return null
            k = JSONObject().put("point", point).put("at", now).put("forecast", grid.forecast).put("stations", grid.stations)
                .put("sunrise", grid.sunrise ?: JSONObject.NULL).put("sunset", grid.sunset ?: JSONObject.NULL)
            Store.writeJson(NWS_KEPT, k)
        }
        covered = !k.optBoolean("none")
        if (!covered) return null
        if (k.optString("station").isEmpty() && !late()) {
            // the nearest station, for the temperature now (asked for again next time if this did not come)
            val s = runCatching { fetch(k.optString("stations") + "?limit=1", 4000, 5000, null)?.let { WeatherRules.nwsStation(JSONObject(it)) } }.getOrNull()
            if (s != null) { k.put("station", s); Store.writeJson(NWS_KEPT, k) }
        }
        if (late()) return null
        val forecast = fetch(WeatherRules.nwsUrl(k.optString("forecast")) ?: return null, 4000, 5000, null) ?: return null
        val station = k.optString("station")
        val seen = if (station.isEmpty() || late()) null
            else runCatching { fetch("$NWS/stations/$station/observations/latest", 3000, 4000, null)?.let { JSONObject(it) } }.getOrNull()
        val sun = { key: String -> if (k.isNull(key) || !k.has(key)) null else k.optLong(key) }
        return WeatherRules.nws(w.optString("place"), JSONObject(forecast), seen, sun("sunrise"), sun("sunset"), now)
    }

    /** One plain GET. The body when the answer is 200 and under 64 KB, else null; status[0], when asked for, gets the answer's number. */
    private fun fetch(url: String, connectMs: Int, readMs: Int, status: IntArray?): String? {
        val c = URL(url).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = connectMs; c.readTimeout = readMs
            c.setRequestProperty("User-Agent", AGENT)
            c.setRequestProperty("Accept", "application/geo+json, application/json")
            val st = c.responseCode
            status?.set(0, st)
            if (st != 200) return null
            val out = java.io.ByteArrayOutputStream()
            val buf = ByteArray(8192)
            c.inputStream.use { s ->
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    if (out.size() > 64 * 1024) return null
                }
            }
            return out.toString("UTF-8")
        } finally { c.disconnect() }
    }
}
