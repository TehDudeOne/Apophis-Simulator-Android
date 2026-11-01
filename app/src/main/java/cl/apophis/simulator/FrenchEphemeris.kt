package cl.apophis.simulator

import android.content.Context
import java.time.LocalDate
import kotlin.math.*

/**
 * IMCCE Miriade: 99942 Apophis, geocentric J2000 equatorial astrometric states.
 * 2029-04-12 00:00 UTC through 2029-04-14 23:50 UTC, 600 s sampling.
 * Source: user-provided ephemcc CSV, ticket 188061910888119708.
 *
 * Reference ephemeris interpolation; NOT an independent N-body integration.
 * Samples are rotated into the common J2000 ecliptic frame before rendering.
 */
class FrenchEphemeris(context: Context) {
    data class State(val position: V3, val velocity: V3)
    private val start = LocalDate.of(2029,4,12).toEpochDay()
    private val au = 149597870.7
    private val secondsPerDay = 86400.0
    private val rows: List<State> = context.assets.open("apophis_miriade_2029.csv").bufferedReader().use { reader ->
        reader.lineSequence().filter { it.isNotBlank() }.map { line ->
            val f = line.split(',')
            require(f.size == 7) { "Invalid French ephemeris row" }
            State(
                V3(f[1].toDouble(),f[2].toDouble(),f[3].toDouble()),
                V3(f[4].toDouble(),f[5].toDouble(),f[6].toDouble())
            )
        }.toList()
    }.also { require(it.size == 432) { "Incomplete Miriade ephemeris" } }

    fun sample(date: LocalDate, phase: Double): State? {
        val seconds = (date.toEpochDay()-start)*secondsPerDay + phase*secondsPerDay
        if (!seconds.isFinite() || seconds < 0.0 || seconds > (rows.size-1)*600.0) return null
        val index = floor(seconds/600.0).toInt().coerceIn(0,rows.lastIndex-1)
        val u = ((seconds-index*600.0)/600.0).coerceIn(0.0,1.0)
        val a=rows[index];val b=rows[index+1]
        val u2=u*u;val u3=u2*u
        val h00=2*u3-3*u2+1
        val h10=u3-2*u2+u
        val h01=-2*u3+3*u2
        val h11=u3-u2
        // Velocity data are in AU/day. Hermite tangents use 600 seconds.
        val p=a.position*h00 + a.velocity*(h10*600.0/secondsPerDay) +
              b.position*h01 + b.velocity*(h11*600.0/secondsPerDay)
        val dh00=6*u2-6*u
        val dh10=3*u2-4*u+1
        val dh01=-6*u2+6*u
        val dh11=3*u2-2*u
        val v=a.position*(dh00*secondsPerDay/600.0) + a.velocity*dh10 +
              b.position*(dh01*secondsPerDay/600.0) + b.velocity*dh11
        return State(equatorialToEcliptic(p),equatorialToEcliptic(v))
    }
    private fun equatorialToEcliptic(v:V3):V3 {
        val angle=Math.toRadians(23.439291111)
        return V3(v.x,v.y*cos(angle)+v.z*sin(angle),-v.y*sin(angle)+v.z*cos(angle))
    }
    fun distanceKm(date:LocalDate,phase:Double):Double? = sample(date,phase)?.position?.norm()?.times(au)
}
