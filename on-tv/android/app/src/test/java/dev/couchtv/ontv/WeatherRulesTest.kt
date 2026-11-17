package dev.couchtv.ontv

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The weather's reading rules, checked against answers recorded from api.weather.gov on
 * 2026-11-17 (app/src/test/resources/weather: trimmed to the keys the app reads, values as sent,
 * public city-centre points only). The Open-Meteo answer below is written by hand in the shape
 * the app has always read: that service did not answer from the computer these were recorded on.
 */
class WeatherRulesTest {
    private fun load(name: String): JSONObject =
        JSONObject(javaClass.getResourceAsStream("/weather/$name")!!.bufferedReader().readText())
    private fun at(iso: String): Long = ScoreFeeds.parseIso(iso)!!

    private val laSunrise = at("2026-11-16T06:26:48-08:00")
    private val laSunset = at("2026-11-16T16:48:47-08:00")

    // ---------------------------------------------------------------- Open-Meteo

    @Test fun openMeteoReadingKeepsItsShapeAndSaysWhereItCameFrom() {
        val j = JSONObject("""{"latitude":34.06,"longitude":-118.24,"timezone":"America/Los_Angeles",
            "current":{"time":"2026-11-16T19:45","interval":900,"temperature_2m":18.6,"weather_code":61,"is_day":0},
            "daily":{"time":["2026-11-16"],"temperature_2m_max":[21.4],"temperature_2m_min":[12.5]}}""")
        val r = WeatherRules.openMeteo("Los Angeles", j)
        assertEquals("Los Angeles", r.getString("place"))
        assertEquals(19, r.getInt("temp"))
        assertEquals(61, r.getInt("code"))
        assertEquals("Light rain", r.getString("text"))
        assertFalse(r.getBoolean("is_day"))
        assertEquals(21, r.getInt("high"))
        assertEquals(13, r.getInt("low"))
        assertEquals("open-meteo", r.getString("source"))
        assertEquals(setOf("place", "temp", "code", "text", "is_day", "high", "low", "source"), r.keySet())
    }

    @Test fun openMeteoWithoutTheDayStillReadsNow() {
        val r = WeatherRules.openMeteo("X", JSONObject("""{"current":{"temperature_2m":-0.4,"weather_code":3,"is_day":1}}"""))
        assertEquals(0, r.getInt("temp")); assertEquals("Cloudy", r.getString("text")); assertTrue(r.getBoolean("is_day"))
        assertTrue(r.isNull("high")); assertTrue(r.isNull("low"))
    }

    // ---------------------------------------------------------------- the weather service's words

    @Test fun theServicesWordsBecomeTheHomesCodes() {
        val want = listOf(
            // every wording in the recorded forecasts and observations
            "Sunny" to 0, "Clear" to 0, "Mostly Sunny" to 1, "Mostly Clear" to 1, "Partly Cloudy" to 2, "Mostly Cloudy" to 3, "Cloudy" to 3,
            "Light Rain" to 61, "Rain" to 63, "Scattered Rain Showers" to 80, "Showers And Thunderstorms" to 95,
            "Scattered Showers And Thunderstorms" to 95, "Rain And Snow" to 66,
            "Slight Chance Light Rain" to 2, "Slight Chance Light Snow" to 2, "Chance Light Rain" to 3, "Chance Rain Showers" to 3,
            "Chance Showers And Thunderstorms" to 3, "Chance Rain And Snow" to 3,
            // others the service uses
            "Fair" to 0, "A Few Clouds" to 1, "Partly Sunny" to 2, "Overcast" to 3, "Increasing Clouds" to 2, "Gradual Clearing" to 2,
            "Patchy Fog" to 45, "Fog/Mist" to 45, "Haze" to 2, "Patchy Drizzle" to 53, "Light Drizzle" to 51, "Freezing Drizzle" to 56,
            "Heavy Rain" to 65, "Rain Likely" to 63, "Showers Likely" to 80, "Rain Showers" to 80, "Isolated Showers" to 2,
            "Light Snow" to 71, "Snow" to 73, "Snow Likely" to 73, "Heavy Snow" to 75, "Snow Showers" to 85, "Flurries" to 71,
            "Wintry Mix" to 66, "Freezing Rain" to 66, "Sleet" to 66, "Thunderstorms" to 95, "T-storms Likely" to 95,
            "Thunderstorm in Vicinity" to 3, "Isolated Thunderstorms" to 2, "Light Rain and Breezy" to 61, "Sunny and Breezy" to 0,
            "  mostly   CLOUDY " to 3)
        for ((words, code) in want) assertEquals(words, code, WeatherRules.nwsCode(words))
    }

    @Test fun wordsThatSayNothingOfTheSkyAreUnknown() {
        for (w in listOf(null, "", "  ", "Breezy", "Windy", "Hot", "Patchy Frost")) assertEquals("$w", -1, WeatherRules.nwsCode(w))
        assertEquals("", WeatherRules.text(-1))
    }

    @Test fun slightIsNotLight() {
        // "light" inside "slight" must not make a drizzle light, and a slight chance is not rain at all
        assertEquals(2, WeatherRules.nwsCode("Slight Chance Drizzle"))
        assertEquals(53, WeatherRules.nwsCode("Drizzle"))
        assertEquals(2, WeatherRules.nwsCode("Slight Chance Rain Showers"))
    }

    @Test fun everyRecordedForecastIsUnderstood() {
        var n = 0
        for (f in listOf("la", "miami", "anchorage", "seattle", "sf")) {
            val periods = load("nws-forecast-$f.json").getJSONObject("properties").getJSONArray("periods")
            for (i in 0 until periods.length()) for (part in periods.getJSONObject(i).getString("shortForecast").split(" then ")) {
                val code = WeatherRules.nwsCode(part)
                assertTrue("$f: $part", code >= 0)
                assertTrue("$f: $part has no words", WeatherRules.text(code).isNotEmpty())
                n++
            }
        }
        assertTrue(n >= 30)
    }

    @Test fun everyCodeTheWordsCanGiveHasWords() {
        for (c in listOf(0, 1, 2, 3, 45, 51, 53, 55, 56, 61, 63, 65, 66, 71, 73, 75, 80, 82, 85, 95)) assertTrue("$c", WeatherRules.text(c).isNotEmpty())
    }

    @Test fun thisThenThatGoesByTheMiddleOfThePeriod() {
        val a = at("2026-11-16T18:00:00-08:00"); val b = at("2026-11-17T06:00:00-08:00")
        val s = "Chance Light Rain then Partly Cloudy"
        assertEquals("Chance Light Rain", WeatherRules.nwsClause(s, a, b, at("2026-11-16T19:55:00-08:00")))
        assertEquals("Chance Light Rain", WeatherRules.nwsClause(s, a, b, at("2026-11-16T23:59:00-08:00")))
        assertEquals("Partly Cloudy", WeatherRules.nwsClause(s, a, b, at("2026-11-17T00:00:00-08:00")))
        assertEquals("Partly Cloudy", WeatherRules.nwsClause(s, a, b, at("2026-11-17T05:00:00-08:00")))
        assertEquals("Sunny", WeatherRules.nwsClause("Sunny", a, b, a))
        // times not known: the first half
        assertEquals("Chance Light Rain", WeatherRules.nwsClause(s, null, null, a))
        assertEquals("C", WeatherRules.nwsClause("A then B then C", a, b, b - 1))
    }

    // ---------------------------------------------------------------- where a place's answers are

    @Test fun pointsSaysWhereTheForecastAndStationsAre() {
        val g = WeatherRules.nwsGrid(load("nws-points-la.json"))!!
        assertEquals("https://api.weather.gov/gridpoints/LOX/155,45/forecast", g.forecast)
        assertEquals("https://api.weather.gov/gridpoints/LOX/155,45/stations", g.stations)
        assertEquals(laSunrise, g.sunrise); assertEquals(laSunset, g.sunset)
    }

    @Test fun aPlaceOutsideTheUnitedStatesHasNoGrid() {
        // the 404 answer for Sydney's city centre
        assertNull(WeatherRules.nwsGrid(load("nws-points-sydney.json")))
        assertNull(WeatherRules.nwsGrid(null))
        assertNull(WeatherRules.nwsGrid(JSONObject()))
    }

    @Test fun onlyTheServicesOwnAddressesAreFollowed() {
        assertEquals("https://api.weather.gov/gridpoints/LOX/155,45/forecast", WeatherRules.nwsUrl(" https://api.weather.gov/gridpoints/LOX/155,45/forecast "))
        for (bad in listOf(null, "", "http://api.weather.gov/gridpoints/LOX/155,45/forecast", "https://api.weather.gov.example.com/x",
            "https://example.com/gridpoints/LOX/155,45/forecast", "https://api.weather.gov/x?y=1", "https://api.weather.gov/x y",
            "https://api.weather.gov:8443/x", "https://api.weather.gov"))
            assertNull("$bad", WeatherRules.nwsUrl(bad))
        // an answer that points somewhere else is not a grid
        val p = load("nws-points-la.json")
        p.getJSONObject("properties").put("forecast", "https://example.com/forecast")
        assertNull(WeatherRules.nwsGrid(p))
    }

    @Test fun aPlaceIsWrittenWithFourDecimalsAtMost() {
        assertEquals("34.0522,-118.2437", WeatherRules.nwsPoint(34.0522, -118.2437))
        assertEquals("34.0522,-118.2437", WeatherRules.nwsPoint(34.05224999, -118.24370001))
        assertEquals("40.7,-74", WeatherRules.nwsPoint(40.7, -74.0))
        assertEquals("-33.8688,151.2093", WeatherRules.nwsPoint(-33.8688, 151.2093))
        assertEquals("0,0", WeatherRules.nwsPoint(0.0, 0.0))
    }

    @Test fun theNearestStationIsTheFirstOne() {
        assertEquals("FHMC1", WeatherRules.nwsStation(load("nws-stations-la-1.json")))
        assertNull(WeatherRules.nwsStation(JSONObject("""{"features":[]}""")))
        assertNull(WeatherRules.nwsStation(JSONObject("""{"features":[{"properties":{"stationIdentifier":"../x"}}]}""")))
        assertNull(WeatherRules.nwsStation(null))
    }

    @Test fun dayOrNightFromTheSunsTimes() {
        assertEquals(false, WeatherRules.nwsDay(laSunrise, laSunset, at("2026-11-16T06:00:00-08:00")))
        assertEquals(true, WeatherRules.nwsDay(laSunrise, laSunset, at("2026-11-16T06:30:00-08:00")))
        assertEquals(true, WeatherRules.nwsDay(laSunrise, laSunset, at("2026-11-16T16:45:00-08:00")))
        assertEquals(false, WeatherRules.nwsDay(laSunrise, laSunset, at("2026-11-16T16:50:00-08:00")))
        // days later, and days before, the same times still serve
        assertEquals(true, WeatherRules.nwsDay(laSunrise, laSunset, at("2026-11-21T12:00:00-08:00")))
        assertEquals(false, WeatherRules.nwsDay(laSunrise, laSunset, at("2026-11-21T23:00:00-08:00")))
        assertEquals(true, WeatherRules.nwsDay(laSunrise, laSunset, at("2026-11-14T12:00:00-08:00")))
        assertNull(WeatherRules.nwsDay(null, laSunset, laSunrise))
        assertNull(WeatherRules.nwsDay(laSunset, laSunrise, laSunrise))
    }

    // ---------------------------------------------------------------- a reading from the service

    @Test fun losAngelesInTheEvening() {
        // the downtown station reports a temperature and no sky: the words are the forecast's
        val r = WeatherRules.nws("Los Angeles", load("nws-forecast-la.json"), load("nws-obs-fhmc1.json"), laSunrise, laSunset, at("2026-11-17T03:55:00Z"))!!
        assertEquals("Los Angeles", r.getString("place"))
        assertEquals(19, r.getInt("temp"))               // 18.79 at the station
        assertEquals(3, r.getInt("code"))                // "Chance Light Rain", early in "... then Partly Cloudy"
        assertEquals("Cloudy", r.getString("text"))
        assertFalse(r.getBoolean("is_day"))              // after sunset
        assertEquals(18, r.getInt("high"))               // Tuesday, 65 F
        assertEquals(11, r.getInt("low"))                // tonight, 52 F
        assertEquals("nws", r.getString("source"))
        assertEquals(setOf("place", "temp", "code", "text", "is_day", "high", "low", "source"), r.keySet())
    }

    @Test fun anOldObservationIsNotNow() {
        // 02:00 the same night: the station's report is six hours old, so everything is the forecast's
        val r = WeatherRules.nws("Los Angeles", load("nws-forecast-la.json"), load("nws-obs-fhmc1.json"), laSunrise, laSunset, at("2026-11-17T10:00:00Z"))!!
        assertEquals(11, r.getInt("temp"))               // 52 F
        assertEquals(2, r.getInt("code"))                // late in the period: "Partly Cloudy"
        assertFalse(r.getBoolean("is_day"))
    }

    @Test fun noObservationAtAll() {
        // 09:00 the next morning, the forecast alone: the second period is the one for now
        val r = WeatherRules.nws("Los Angeles", load("nws-forecast-la.json"), null, laSunrise, laSunset, at("2026-11-17T17:00:00Z"))!!
        assertEquals(18, r.getInt("temp"))               // 65 F
        assertEquals(0, r.getInt("code")); assertEquals("Clear", r.getString("text"))
        assertTrue(r.getBoolean("is_day"))
        assertEquals(18, r.getInt("high")); assertEquals(11, r.getInt("low"))
    }

    @Test fun aStationWithNoTemperatureLeavesItToTheForecast() {
        val o = load("nws-obs-fhmc1.json")
        o.getJSONObject("properties").getJSONObject("temperature").put("value", JSONObject.NULL)   // as the service sends it when the sensor is out
        val r = WeatherRules.nws("Los Angeles", load("nws-forecast-la.json"), o, laSunrise, laSunset, at("2026-11-17T03:55:00Z"))!!
        assertEquals(11, r.getInt("temp"))
    }

    @Test fun theStationsOwnWordsWin() {
        // Miami: the forecast for now says "Mostly Cloudy", the airport says it is raining
        val r = WeatherRules.nws("Miami", load("nws-forecast-miami.json"), load("nws-obs-kmia.json"), null, null, at("2026-11-17T03:50:00Z"))!!
        assertEquals(22, r.getInt("temp"))
        assertEquals(63, r.getInt("code")); assertEquals("Rain", r.getString("text"))
        assertFalse(r.getBoolean("is_day"))              // the station's picture is a night one
        assertEquals(27, r.getInt("high"))               // 81 F
        assertEquals(24, r.getInt("low"))                // 76 F
    }

    @Test fun belowFreezing() {
        val r = WeatherRules.nws("Anchorage", load("nws-forecast-anchorage.json"), load("nws-obs-panc.json"), null, null, at("2026-11-17T03:55:00Z"))!!
        assertEquals(-4, r.getInt("temp"))
        assertEquals(2, r.getInt("code"))                // "Partly Cloudy" at the airport
        assertEquals(1, r.getInt("high"))                // 33 F
        assertEquals(-2, r.getInt("low"))                // 29 F
    }

    @Test fun withNoSunTimesTheForecastsHalfOfTheDayDecides() {
        val night = WeatherRules.nws("Seattle", load("nws-forecast-seattle.json"), null, null, null, at("2026-11-17T03:55:00Z"))!!
        assertFalse(night.getBoolean("is_day"))
        val day = WeatherRules.nws("Seattle", load("nws-forecast-seattle.json"), null, null, null, at("2026-11-17T20:00:00Z"))!!
        assertTrue(day.getBoolean("is_day"))
        assertEquals(1, day.getInt("code"))              // "Mostly Sunny"
        assertEquals(10, day.getInt("temp"))             // 50 F
    }

    @Test fun aClearNightAtTheStation() {
        val r = WeatherRules.nws("Minneapolis", load("nws-forecast-seattle.json"), load("nws-obs-kmsp.json"), null, null, at("2026-11-17T03:50:00Z"))!!
        assertEquals(8, r.getInt("temp")); assertEquals(0, r.getInt("code")); assertEquals("Clear", r.getString("text"))
    }

    @Test fun aForecastWithNothingInItIsNoReading() {
        assertNull(WeatherRules.nws("X", null, load("nws-obs-kmia.json"), null, null, 0))
        assertNull(WeatherRules.nws("X", JSONObject("""{"properties":{"periods":[]}}"""), load("nws-obs-kmia.json"), null, null, 0))
        // the 404 answer has no forecast in it either
        assertNull(WeatherRules.nws("X", load("nws-points-sydney.json"), null, null, null, 0))
        // a period with no temperature and no station: nothing to show
        assertNull(WeatherRules.nws("X", JSONObject("""{"properties":{"periods":[{"shortForecast":"Sunny","isDaytime":true}]}}"""), null, null, null, 0))
        assertNotNull(WeatherRules.nws("X", JSONObject("""{"properties":{"periods":[{"shortForecast":"Sunny","isDaytime":true,"temperature":20,"temperatureUnit":"C"}]}}"""), null, null, null, 0))
    }

    @Test fun fahrenheitToCelsius() {
        assertEquals(0.0, WeatherRules.fToC(32.0), 1e-9)
        assertEquals(100.0, WeatherRules.fToC(212.0), 1e-9)
        assertEquals(-40.0, WeatherRules.fToC(-40.0), 1e-9)
    }
}
