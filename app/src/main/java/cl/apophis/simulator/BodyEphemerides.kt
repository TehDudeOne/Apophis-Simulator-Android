package cl.apophis.simulator
import java.time.LocalDate
import kotlin.math.floor

/** Geometric heliocentric ecliptic J2000 state vectors, AU and AU/day. */
class BodyEphemerides(private val horizons:HorizonsDistance) {
    data class State(val r:V3,val v:V3)
    data class Day(val date:LocalDate,val states:Map<String,List<State>>) {
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
    }
    companion object {
        val ids=linkedMapOf(
            "Mercurio" to "199","Venus" to "299","Tierra" to "399",
            "Luna" to "301","Marte" to "499","Júpiter" to "599",
            "Saturno" to "699","Urano" to "799","Neptuno" to "899")
    }
    fun fetch(date:LocalDate):Day {
        val result=linkedMapOf<String,List<State>>()
        for((name,id) in ids) {
            val rows=horizons.hourlyStates(id,date)
            require(rows.size==25) {"Incomplete Horizons samples: $name"}
            result[name]=rows.map {State(it.first,it.second)}
        }
        return Day(date,result)
    }
}
