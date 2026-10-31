package cl.apophis.simulator

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.util.Locale
import kotlin.math.sqrt

/**
 * Earth-Apophis geometric separation, queried from JPL Horizons vector tables.
 * The result is a UTC midnight snapshot, not an impact prediction.
 */
class HorizonsDistance(private val context:Context) {
    private val prefs=context.getSharedPreferences("horizons_distances",Context.MODE_PRIVATE)
    fun cached(date:LocalDate):Double? {
        val value=prefs.getFloat(date.toString(),Float.NaN)
        return if(value.isNaN()) null else value.toDouble()
    }
    fun query(date:LocalDate):Double {
        val asteroid=vector("99942",date)
        val earth=vector("399",date)
        val dx=asteroid.x-earth.x
        val dy=asteroid.y-earth.y
        val dz=asteroid.z-earth.z
        val distance=sqrt(dx*dx+dy*dy+dz*dz)*149597870.7
        require(distance.isFinite() && distance>=0)
        prefs.edit().putFloat(date.toString(),distance.toFloat()).apply()
        return distance
    }
    private fun vector(command:String,date:LocalDate):V3 {
        val params=linkedMapOf(
            "format" to "json",
            "COMMAND" to "'$command'",
            "OBJ_DATA" to "'NO'",
            "MAKE_EPHEM" to "'YES'",
            "EPHEM_TYPE" to "'VECTORS'",
            "CENTER" to "'500@10'",
            "START_TIME" to "'${date} 00:00'",
            "STOP_TIME" to "'${date.plusDays(1)} 00:00'",
            "STEP_SIZE" to "'1 d'",
            "OUT_UNITS" to "'AU-D'",
            "CSV_FORMAT" to "'YES'"
        )
        val query=params.entries.joinToString("&") { (k,v) ->
            URLEncoder.encode(k,"UTF-8")+"="+URLEncoder.encode(v,"UTF-8")
        }
        val connection=URL("https://ssd.jpl.nasa.gov/api/horizons.api?$query").openConnection() as HttpURLConnection
        connection.connectTimeout=15000
        connection.readTimeout=15000
        try {
            check(connection.responseCode==200) { "Horizons HTTP ${connection.responseCode}" }
            val raw=connection.inputStream.bufferedReader().use { it.readText() }
            val result=JSONObject(raw).getString("result")
            val start=result.indexOf("$$SOE")
            val end=result.indexOf("$$EOE")
            check(start>=0 && end>start) { "Horizons sin datos para la fecha" }
            val lines=result.substring(start+5,end).lines().filter { it.isNotBlank() }
            // CSV vector records: JD, calendar date, X, Y, Z, VX, VY, VZ...
            val fields=lines.first().split(",").map { it.trim() }
            require(fields.size>=5) { "Formato de vectores inesperado" }
            return V3(fields[2].toDouble(),fields[3].toDouble(),fields[4].toDouble())
        } finally { connection.disconnect() }
    }
}
