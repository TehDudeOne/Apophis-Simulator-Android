package cl.apophis.simulator

import android.content.Context
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

class JplRepository(private val context: Context) {
    private val prefs = context.getSharedPreferences("jpl_orbits",Context.MODE_PRIVATE)
    fun cached(): Elements? = prefs.getString("raw",null)?.let { parse(it) }
    fun refresh(): Elements {
        val connection = URL("https://ssd-api.jpl.nasa.gov/sbdb.api?sstr=99942&full-prec=true").openConnection() as HttpURLConnection
        connection.connectTimeout = 12000
        connection.readTimeout = 12000
        connection.setRequestProperty("Accept","application/json")
        try {
            check(connection.responseCode == 200) { "JPL HTTP ${connection.responseCode}" }
            val raw = connection.inputStream.bufferedReader().use { it.readText() }
            val elements = parse(raw) ?: error("Respuesta JPL incompleta")
            prefs.edit().putString("raw",raw).apply()
            return elements
        } finally { connection.disconnect() }
    }
    private fun parse(raw: String): Elements? = runCatching {
        val orbit = JSONObject(raw).getJSONObject("orbit")
        val values = orbit.getJSONArray("elements")
        val map = HashMap<String,Double>()
        for (i in 0 until values.length()) {
            val element = values.getJSONObject(i)
            map[element.getString("name")] = element.getString("value").toDouble()
        }
        Elements(
            map.getValue("a"),map.getValue("e"),map.getValue("i"),
            map.getValue("om"),map.getValue("w"),map.getValue("ma"),
            orbit.getString("epoch").toDouble()
        )
    }.getOrNull()
}
