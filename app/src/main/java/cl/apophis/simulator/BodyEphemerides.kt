package cl.apophis.simulator
import android.content.Context
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.LocalDate
import kotlin.math.floor

/**
 * Verified, persistent per-body ephemeris cache. Each body/day is committed
 * atomically only after all 25 state vectors pass validation. A failed body
 * cannot invalidate other downloaded bodies.
 */
class BodyEphemerides(context:Context,private val horizons:HorizonsDistance) {
    data class State(val r:V3,val v:V3)
    data class Day(val date:LocalDate,val states:Map<String,List<State>>,val sources:Map<String,String>,
        val errors:Map<String,String>) {
        fun sample(body:String,phase:Double):V3? {
            val samples=states[body] ?: return null
            if(samples.size!=25 || !phase.isFinite()) return null
            val hour=phase.coerceIn(0.0,1.0)*24.0
            val i=floor(hour).toInt().coerceIn(0,23)
            val u=(hour-i).coerceIn(0.0,1.0)
            val a=samples[i];val b=samples[i+1]
            val u2=u*u;val u3=u2*u
            return a.r*(2*u3-3*u2+1)+a.v*((u3-2*u2+u)/24.0)+
                b.r*(-2*u3+3*u2)+b.v*((u3-u2)/24.0)
        }
        fun summary():String {
            val stored=sources.values.count {it=="CACHE"}
            val downloaded=sources.values.count {it=="JPL"}
            val missing=ids.size-states.size
            val detail=errors.entries.take(2).joinToString("; ") {"${it.key}: ${it.value}"}
            return "Efemérides JPL: $downloaded descargadas, $stored caché, $missing sin datos"+
                if(detail.isEmpty()) "" else "\nFallo: $detail"
        }
    }
    companion object {
        val ids=linkedMapOf(
            "Mercurio" to "199","Venus" to "299","Tierra" to "399",
            "Luna" to "301","Marte" to "499","Júpiter" to "599",
            "Saturno" to "699","Urano" to "799","Neptuno" to "899")
    }
    private val folder=File(context.filesDir,"ephemerides/jpl-v1").also {it.mkdirs()}
    private fun file(date:LocalDate,name:String)=File(folder,"${date}_${ids.getValue(name)}.json")
    private fun valid(rows:List<State>):Boolean {
        if(rows.size!=25) return false
        return rows.all {row ->
            listOf(row.r.x,row.r.y,row.r.z,row.v.x,row.v.y,row.v.z).all {it.isFinite()} &&
                row.r.norm()<100.0 && row.v.norm()<10.0
        }
    }
    private fun read(date:LocalDate,name:String):List<State>? {
        val path=file(date,name)
        if(!path.isFile) return null
        return runCatching {
            val json=JSONObject(path.readText())
            require(json.getInt("schema")==1 && json.getString("date")==date.toString() &&
                json.getString("id")==ids.getValue(name) &&
                json.getString("frame")=="HELIOCENTRIC_ECLIPTIC_J2000_AU_AUD")
            val values=json.getJSONArray("states")
            require(values.length()==25)
            val rows=(0 until values.length()).map {i ->
                val row=values.getJSONArray(i)
                require(row.length()==6)
                State(V3(row.getDouble(0),row.getDouble(1),row.getDouble(2)),
                    V3(row.getDouble(3),row.getDouble(4),row.getDouble(5)))
            }
            require(valid(rows))
            rows
        }.getOrNull()
    }
    private fun persist(date:LocalDate,name:String,rows:List<State>) {
        require(valid(rows))
        val json=JSONObject()
            .put("schema",1).put("date",date.toString()).put("id",ids.getValue(name))
            .put("frame","HELIOCENTRIC_ECLIPTIC_J2000_AU_AUD")
        val array=JSONArray()
        rows.forEach {row ->
            array.put(JSONArray().put(row.r.x).put(row.r.y).put(row.r.z)
                .put(row.v.x).put(row.v.y).put(row.v.z))
        }
        json.put("states",array)
        val atomic=AtomicFile(file(date,name))
        val output=atomic.startWrite()
        try {
            output.write(json.toString().toByteArray(Charsets.UTF_8))
            atomic.finishWrite(output)
        } catch(e:Exception) {
            atomic.failWrite(output)
            throw e
        }
    }
    fun cached(date:LocalDate):Day {
        val states=linkedMapOf<String,List<State>>()
        val sources=linkedMapOf<String,String>()
        for(name in ids.keys) {
            read(date,name)?.let {states[name]=it;sources[name]="CACHE"}
        }
        return Day(date,states,sources,emptyMap())
    }
    /**
     * No silent retry loops. The caller invokes fetch only on a user-selected
     * date or explicit retry. Existing valid files are never re-downloaded.
     */
    fun fetch(date:LocalDate):Day {
        val initial=cached(date)
        val states=initial.states.toMutableMap()
        val sources=initial.sources.toMutableMap()
        val errors=linkedMapOf<String,String>()
        for((name,id) in ids) {
            if(states.containsKey(name)) continue
            try {
                val rows=horizons.hourlyStates(id,date).map {State(it.first,it.second)}
                require(valid(rows)) {"25 vectores inválidos o incompletos"}
                persist(date,name,rows)
                states[name]=rows
                sources[name]="JPL"
            } catch(e:Exception) {
                // IMCCE bundled data only covers Apophis in 2029; it is not a
                // valid alternate source for these nine bodies.
                errors[name]=(e.message ?: e.javaClass.simpleName).take(120)
            }
        }
        return Day(date,states,sources,errors)
    }
}
