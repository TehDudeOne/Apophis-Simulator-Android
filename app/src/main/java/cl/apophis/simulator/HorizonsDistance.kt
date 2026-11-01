package cl.apophis.simulator

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import kotlin.math.sqrt

/**
 * Geometric Earth–Apophis separation at 00:00 UTC using JPL Horizons vectors.
 * The result is independent from the approximate orbit rendered on screen.
 */
class HorizonsDistance(context: Context) {
    private val prefs = context.getSharedPreferences("horizons_distances", Context.MODE_PRIVATE)
    private val auKm = 149597870.7

    fun cached(date: LocalDate): Double? {
        val value = prefs.getFloat(date.toString(), Float.NaN)
        return if (value.isNaN()) null else value.toDouble()
    }

    fun query(date: LocalDate): Double {
        val asteroid = vector("99942", date)
        val earth = vector("399", date)
        val dx = asteroid.x - earth.x
        val dy = asteroid.y - earth.y
        val dz = asteroid.z - earth.z
        val distance = sqrt(dx * dx + dy * dy + dz * dz) * auKm
        require(distance.isFinite() && distance >= 0.0)
        prefs.edit().putFloat(date.toString(), distance.toFloat()).apply()
        return distance
    }

    fun vector(command: String, date: LocalDate): V3 = vectors(command,date,"1 d").first()

    fun hourlyVectors(date: LocalDate): List<Pair<V3,V3>> {
        val asteroid=vectors("99942",date,"1 h")
        val earth=vectors("399",date,"1 h")
        check(asteroid.size==25 && earth.size==25) { "Horizons no devolvió 25 muestras horarias" }
        return asteroid.zip(earth)
    }

    private fun vectors(command: String, date: LocalDate, step: String): List<V3> {
        val params = linkedMapOf(
            "format" to "json",
            "COMMAND" to "'$command'",
            "OBJ_DATA" to "'NO'",
            "MAKE_EPHEM" to "'YES'",
            "EPHEM_TYPE" to "'VECTORS'",
            "CENTER" to "'500@10'",
            "START_TIME" to "'$date 00:00'",
            "STOP_TIME" to "'${date.plusDays(1)} 00:00'",
            "STEP_SIZE" to "'$step'",
            "OUT_UNITS" to "'AU-D'",
            "CSV_FORMAT" to "'YES'"
        )
        val queryString = params.entries.joinToString("&") { (key, value) ->
            URLEncoder.encode(key, "UTF-8") + "=" + URLEncoder.encode(value, "UTF-8")
        }
        val connection = URL("https://ssd.jpl.nasa.gov/api/horizons.api?$queryString")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 15000
        connection.readTimeout = 15000
        try {
            check(connection.responseCode == 200) { "Horizons HTTP ${connection.responseCode}" }
            val response = connection.inputStream.bufferedReader().use { it.readText() }
            val result = JSONObject(response).getString("result")
            val marker = 36.toChar().toString().repeat(2)
            val start = result.indexOf(marker + "SOE")
            val end = result.indexOf(marker + "EOE")
            check(start >= 0 && end > start) { "Horizons sin datos para la fecha" }
            val lines = result.substring(start + 5, end).lineSequence()
                .map { it.trim() }.filter { it.isNotEmpty() }.toList()
            check(lines.isNotEmpty()) { "Horizons no devolvió vectores" }
            return lines.map { line ->
                val fields=line.split(",").map { it.trim() }
                require(fields.size >= 5) { "Formato de vectores inesperado" }
                V3(fields[2].toDouble(),fields[3].toDouble(),fields[4].toDouble())
            }
        } finally {
            connection.disconnect()
        }
    }
}
