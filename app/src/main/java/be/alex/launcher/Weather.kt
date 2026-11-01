package be.alex.launcher

import kotlin.math.roundToInt

/** Météo Open-Meteo pour Mons, sans clé. */
object Weather {
    data class Day(val code: Int, val max: Int, val min: Int)
    data class Now(val temp: Int, val code: Int, val isDay: Boolean)
    data class Forecast(val now: Now, val days: List<Day>, val sunriseMin: Int?, val sunsetMin: Int?)

    fun fetch(): Forecast {
        val j = Net.json(
            "https://api.open-meteo.com/v1/forecast?latitude=50.4542&longitude=3.9567" +
                "&current=temperature_2m,weather_code,is_day" +
                "&daily=weather_code,temperature_2m_max,temperature_2m_min,sunrise,sunset" +
                "&timezone=Europe%2FBrussels&forecast_days=2"
        )
        val cur = j.getJSONObject("current")
        val now = Now(
            cur.getDouble("temperature_2m").roundToInt(),
            cur.getInt("weather_code"),
            cur.optInt("is_day", 1) == 1
        )
        val d = j.getJSONObject("daily")
        val codes = d.getJSONArray("weather_code")
        val mx = d.getJSONArray("temperature_2m_max")
        val mn = d.getJSONArray("temperature_2m_min")
        val days = (0 until codes.length()).map {
            Day(codes.getInt(it), mx.getDouble(it).roundToInt(), mn.getDouble(it).roundToInt())
        }
        val rise = d.optJSONArray("sunrise")?.optString(0)?.let { minutes(it) }
        val set = d.optJSONArray("sunset")?.optString(0)?.let { minutes(it) }
        return Forecast(now, days, rise, set)
    }

    /** "2026-10-30T07:58" vers 478. */
    private fun minutes(iso: String): Int? = try {
        val t = iso.substringAfter('T')
        t.substring(0, 2).toInt() * 60 + t.substring(3, 5).toInt()
    } catch (e: Exception) {
        null
    }

    fun icon(code: Int, isDay: Boolean = true): Int = when (code) {
        0, 1 -> if (isDay) R.drawable.ic_sun else R.drawable.ic_moon
        2, 3, 45, 48 -> R.drawable.ic_cloud
        in 71..77, 85, 86 -> R.drawable.ic_snow
        else -> R.drawable.ic_rain
    }

    fun describe(code: Int): String = when (code) {
        0 -> "Ciel dégagé"
        1, 2 -> "Éclaircies"
        3 -> "Couvert"
        45, 48 -> "Brouillard"
        in 51..57 -> "Bruine"
        in 61..67, in 80..82 -> "Pluie"
        in 71..77, 85, 86 -> "Neige"
        in 95..99 -> "Orage"
        else -> ""
    }
}
