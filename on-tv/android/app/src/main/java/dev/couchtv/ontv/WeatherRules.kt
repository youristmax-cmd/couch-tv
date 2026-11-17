package dev.couchtv.ontv

import org.json.JSONObject

/**
 * The reading half of the weather (Weather.kt does the asking): a weather service's answer in, the
 * home's reading out. No network and no Android here, so it is checked on a computer against
 * recorded answers (app/src/test).
 *
 * A reading is {place, temp (°C, rounded), code, text, is_day, high, low, source}. code is the WMO
 * weather code Open-Meteo uses, because the home's pictures and words go by it.
 *
 * Two services:
 *  - Open-Meteo ("open-meteo"): the code comes as it is.
 *  - the US National Weather Service, api.weather.gov ("nws"), the backup, United States only. It
 *    speaks in words ("Chance Light Rain then Partly Cloudy"), which nwsCode turns into a code.
 *    Now is put together from two answers: the nearest station's latest observation (temperature,
 *    and its words when it has any: many stations report no sky), and the 12-hour forecast (the
 *    words otherwise, and the day's high and low).
 */
object WeatherRules {
    /** The words for a WMO code, as the home has always been given them (it has its own, by code, in other languages). */
    private val WMO = mapOf(0 to "Clear", 1 to "Mostly clear", 2 to "Partly cloudy", 3 to "Cloudy", 45 to "Fog", 48 to "Fog",
        51 to "Drizzle", 53 to "Drizzle", 55 to "Drizzle", 56 to "Drizzle", 57 to "Drizzle", 61 to "Light rain", 63 to "Rain",
        65 to "Heavy rain", 66 to "Rain", 67 to "Rain", 71 to "Light snow", 73 to "Snow", 75 to "Heavy snow", 77 to "Snow",
        80 to "Showers", 81 to "Showers", 82 to "Heavy showers", 85 to "Snow showers", 86 to "Snow showers",
        95 to "Storms", 96 to "Storms", 99 to "Storms")

    fun text(code: Int): String = WMO[code] ?: ""
    fun fToC(f: Double): Double = (f - 32.0) * 5.0 / 9.0

    private fun str(o: JSONObject?, k: String): String = if (o == null || o.isNull(k)) "" else o.optString(k).trim()

    // ---------------------------------------------------------------- Open-Meteo

    /** Open-Meteo's answer (current temperature_2m, weather_code, is_day; daily max and min) as a reading. */
    fun openMeteo(place: String, j: JSONObject): JSONObject {
        val cur = j.getJSONObject("current"); val day = j.optJSONObject("daily")
        val code = cur.optInt("weather_code", -1)
        val first = { k: String -> day?.optJSONArray(k)?.takeIf { it.length() > 0 && !it.isNull(0) }?.optDouble(0)?.takeIf { !it.isNaN() } }
        return JSONObject().put("place", place).put("temp", Math.round(cur.getDouble("temperature_2m")))
            .put("code", code).put("text", text(code)).put("is_day", cur.optInt("is_day") == 1)
            .put("high", first("temperature_2m_max")?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("low", first("temperature_2m_min")?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("source", "open-meteo")
    }

    // ---------------------------------------------------------------- the National Weather Service's words

    /**
     * The weather service's words as a WMO code. The order matters: thunder before showers
     * ("Showers And Thunderstorms"), a mix before snow, "mostly cloudy" before "cloudy".
     *  - thunder 95; freezing rain, sleet, "rain and snow" 66; snow 71 / 73 / 75, snow showers 85;
     *    drizzle 51 / 53 / 55; showers 80 / 82; rain 61 / 63 / 65; fog and mist 45;
     *  - cloudy, mostly cloudy, overcast 3; partly cloudy or sunny 2; mostly sunny or clear 1;
     *    sunny, clear, fair 0. Haze and smoke are taken as 2: the sun is still there.
     *  - rain or snow that is only a possibility is not the sky now: "Slight Chance ..." and
     *    "Isolated ..." read as partly cloudy (2), "Chance ..." and "... in Vicinity" as cloudy
     *    (3). "Likely", "Scattered" and plain words are the thing itself.
     * -1 for words that say nothing of the sky ("Breezy"), as an unknown code is.
     */
    fun nwsCode(words: String?): Int {
        val w = words.orEmpty().lowercase().replace(Regex("""\s+"""), " ").trim()
        if (w.isEmpty()) return -1
        val has = { re: String -> Regex(re).containsMatchIn(w) }
        val wet = when {
            has("thunder|t-storm|tstm") -> 95
            has("freezing rain|sleet|ice pellets|wintry mix|rain and snow|snow and rain|rain/snow") -> 66
            has("freezing drizzle") -> 56
            has("blizzard|heavy snow") -> 75
            has("snow showers") -> 85
            has("""flurr|\blight snow""") -> 71
            has("snow") -> 73
            has("drizzle") -> if (has("""\blight\b""")) 51 else if (has("heavy")) 55 else 53
            has("shower") -> if (has("heavy")) 82 else 80
            has("heavy rain") -> 65
            has("""\blight rain""") -> 61
            has("""\brain""") -> 63
            else -> null
        }
        if (wet != null) return when {
            has("""\bslight chance\b|\bisolated\b""") -> 2
            has("""\bchance\b|vicinity""") && !has("likely") -> 3
            else -> wet
        }
        return when {
            has("""\bfog|\bmist""") -> 45
            has("""\bhaze|\bsmoke|\bdust|\bsand\b|\bash\b""") -> 2
            has("mostly cloudy|considerable cloud|overcast") -> 3
            has("partly|increasing clouds|decreasing clouds|clearing|variable clouds") -> 2
            has("mostly sunny|mostly clear|few clouds|becoming sunny|becoming clear") -> 1
            has("cloud") -> 3
            has("""sunny|\bclear|\bfair""") -> 0
            else -> -1
        }
    }

    /**
     * The half of a "this then that" forecast that is for now: the first before the middle of its
     * 12 hours, the second after. ("Chance Light Rain then Partly Cloudy" at 3 in the morning is
     * partly cloudy.) The service does not say when the change comes; the middle is the fair guess.
     */
    fun nwsClause(short: String, start: Long?, end: Long?, now: Long): String {
        val parts = short.split(Regex("""\s+then\s+""", RegexOption.IGNORE_CASE)).map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 2) return short.trim()
        val late = start != null && end != null && end > start && now >= start + (end - start) / 2
        return if (late) parts.last() else parts.first()
    }

    /** Only an address on api.weather.gov is ever followed, whatever an answer says. */
    fun nwsUrl(u: String?): String? = u?.trim()?.takeIf { Regex("""https://api\.weather\.gov/[A-Za-z0-9/,._-]{1,200}""").matches(it) }

    /** A place for /points: four decimals at most, as the service wants them. */
    fun nwsPoint(lat: Double, lon: Double): String {
        val f = { v: Double -> "%.4f".format(java.util.Locale.US, v).trimEnd('0').trimEnd('.') }
        return f(lat) + "," + f(lon)
    }

    /** What /points says about a place: where its forecast and its stations are, and the day's sunrise and sunset there. */
    class Grid(val forecast: String, val stations: String, val sunrise: Long?, val sunset: Long?)

    fun nwsGrid(points: JSONObject?): Grid? {
        val p = points?.optJSONObject("properties") ?: return null
        val forecast = nwsUrl(str(p, "forecast")) ?: return null
        val stations = nwsUrl(str(p, "observationStations")) ?: return null
        val sun = p.optJSONObject("astronomicalData")
        return Grid(forecast, stations, ScoreFeeds.parseIso(str(sun, "sunrise")), ScoreFeeds.parseIso(str(sun, "sunset")))
    }

    /** The nearest station's name for the service ("KLAX"), from the stations answer. Null when there is none. */
    fun nwsStation(stations: JSONObject?): String? =
        str(stations?.optJSONArray("features")?.optJSONObject(0)?.optJSONObject("properties"), "stationIdentifier")
            .takeIf { Regex("[A-Z0-9]{3,8}").matches(it) }

    /**
     * Is it day at the place? From a sunrise and sunset there on some recent day: the sun's times
     * move by a minute or two a day in most of the country (more in Alaska), so last week's will
     * do for choosing between a sun and a moon. Null when they are not known.
     */
    fun nwsDay(sunrise: Long?, sunset: Long?, now: Long): Boolean? {
        if (sunrise == null || sunset == null || sunset <= sunrise || sunset - sunrise >= DAY) return null
        return Math.floorMod(now - sunrise, DAY) < sunset - sunrise
    }

    private const val DAY = 24 * 60 * 60_000L
    private const val OBS_FRESH_MS = 2 * 60 * 60_000L     // an observation older than this is not "now"

    /**
     * The service's forecast, and its nearest station's latest observation when there is one, as
     * a reading for now.
     *  - temperature: the station's, when it reports one and the report is under 2 hours old;
     *    otherwise the forecast's for this half of the day.
     *  - the sky: the station's words when it has any; otherwise the forecast's for now.
     *  - day or night: the station's picture says ("/day/", "/night/"); otherwise the sun's times
     *    for the place; otherwise the forecast's half of the day (6 to 6).
     *  - high and low: the forecast's next day half and night half. So in the evening the high is
     *    tomorrow's, where Open-Meteo's is the day's that has just been.
     * Null when the forecast has nothing usable.
     */
    fun nws(place: String, forecast: JSONObject?, observation: JSONObject?, sunrise: Long?, sunset: Long?, now: Long): JSONObject? {
        val list = forecast?.optJSONObject("properties")?.optJSONArray("periods") ?: return null
        val periods = (0 until minOf(list.length(), 4)).mapNotNull { list.optJSONObject(it) }
        if (periods.isEmpty()) return null
        val start = { p: JSONObject -> ScoreFeeds.parseIso(str(p, "startTime")) }
        val end = { p: JSONObject -> ScoreFeeds.parseIso(str(p, "endTime")) }
        val cur = periods.firstOrNull { p -> val a = start(p); val b = end(p); a != null && b != null && now >= a && now < b } ?: periods[0]
        val celsius = { p: JSONObject ->
            if (p.isNull("temperature") || !p.has("temperature")) null
            else p.optDouble("temperature").takeIf { !it.isNaN() }?.let { if (str(p, "temperatureUnit").equals("C", true)) it else fToC(it) }
        }
        val o = observation?.optJSONObject("properties")
        val seen = ScoreFeeds.parseIso(str(o, "timestamp"))
        val fresh = o != null && seen != null && now - seen < OBS_FRESH_MS
        val measured = o?.takeIf { fresh }?.optJSONObject("temperature")?.takeIf { !it.isNull("value") && it.has("value") }?.let { t ->
            t.optDouble("value").takeIf { !it.isNaN() }?.let { if (str(t, "unitCode").endsWith("degF")) fToC(it) else it }
        }
        val temp = measured ?: celsius(cur) ?: return null
        val words = (if (fresh) str(o, "textDescription") else "").ifEmpty { nwsClause(str(cur, "shortForecast"), start(cur), end(cur), now) }
        val code = nwsCode(words)
        val icon = if (fresh) str(o, "icon") else ""
        val day = when {
            "/day/" in icon -> true
            "/night/" in icon -> false
            else -> nwsDay(sunrise, sunset, now) ?: cur.optBoolean("isDaytime", true)
        }
        val two = periods.take(2)
        val high = two.firstOrNull { it.optBoolean("isDaytime") }?.let(celsius)
        val low = two.firstOrNull { !it.optBoolean("isDaytime", true) }?.let(celsius)
        return JSONObject().put("place", place).put("temp", Math.round(temp)).put("code", code).put("text", text(code)).put("is_day", day)
            .put("high", high?.let { Math.round(it) } ?: JSONObject.NULL).put("low", low?.let { Math.round(it) } ?: JSONObject.NULL)
            .put("source", "nws")
    }
}
