package cl.apophis.simulator

import java.time.LocalDate
import kotlin.math.*

data class V3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(v: V3) = V3(x + v.x, y + v.y, z + v.z)
    operator fun minus(v: V3) = V3(x - v.x, y - v.y, z - v.z)
    operator fun times(k: Double) = V3(x * k, y * k, z * k)
    fun norm() = sqrt(x * x + y * y + z * z)
}
data class Elements(
    val a: Double, val e: Double, val i: Double,
    val node: Double, val peri: Double, val mean: Double, val epoch: Double
)
data class Planet(val name: String, val radius: Double, val period: Double, val massRatio: Double, val phase: Double, val color: Int)
data class Snapshot(val date: LocalDate, val asteroid: V3, val earth: V3, val planets: List<Pair<Planet,V3>>, val earthDistanceAu: Double)

object OrbitEngine {
    private const val MU = 0.0002959122082855911 // AU^3/day^2
    private const val J2000 = 2451545.0
    private const val RAD = PI / 180.0
    val planets = listOf(
        Planet("Mercurio", .3871, 87.969, 1.660e-7, 1.2, 0xffaaaaaa.toInt()),
        Planet("Venus", .7233, 224.701, 2.447e-6, 2.4, 0xffffd080.toInt()),
        Planet("Tierra", 1.0, 365.256, 3.003e-6, 1.75, 0xff4e9cff.toInt()),
        Planet("Marte", 1.5237, 686.98, 3.227e-7, .35, 0xffff7655.toInt()),
        Planet("Júpiter", 5.2028, 4332.59, .0009543, .65, 0xffffba75.toInt()),
        Planet("Saturno", 9.5388, 10759.2, .0002857, 1.4, 0xffddc991.toInt()),
        Planet("Urano", 19.19, 30688.5, .00004365, 2.7, 0xff86dce3.toInt()),
        Planet("Neptuno", 30.07, 60182.0, .00005149, 3.9, 0xff537cf2.toInt())
    )
    // Elementos ilustrativos: NO son una solución JPL ni permiten predecir encuentros cercanos.
    val fallback = Elements(.9224, .1911, 3.34, 204.4, 126.4, 0.0, 2460000.5)

    fun julian(date: LocalDate): Double = date.toEpochDay() + 2440587.5
    private fun planetAt(p: Planet, jd: Double): V3 {
        val angle = p.phase + 2.0 * PI * (jd - J2000) / p.period
        return V3(p.radius * cos(angle), p.radius * sin(angle), 0.0)
    }
    private fun fromElements(el: Elements): Pair<V3,V3> {
        require(el.a > 0 && el.e >= 0 && el.e < 1)
        val m = el.mean * RAD
        var anomaly = m
        repeat(20) { anomaly -= (anomaly - el.e * sin(anomaly) - m) / (1.0 - el.e * cos(anomaly)) }
        val q = sqrt(1 - el.e * el.e)
        val px = el.a * (cos(anomaly) - el.e)
        val py = el.a * q * sin(anomaly)
        val scale = sqrt(MU * el.a) / (el.a * (1 - el.e * cos(anomaly)))
        val vx = -scale * sin(anomaly)
        val vy = scale * q * cos(anomaly)
        val node = el.node * RAD
        val peri = el.peri * RAD
        val inc = el.i * RAD
        val a = cos(node) * cos(peri) - sin(node) * sin(peri) * cos(inc)
        val b = -cos(node) * sin(peri) - sin(node) * cos(peri) * cos(inc)
        val c = sin(node) * cos(peri) + cos(node) * sin(peri) * cos(inc)
        val d = -sin(node) * sin(peri) + cos(node) * cos(peri) * cos(inc)
        val e = sin(peri) * sin(inc)
        val f = cos(peri) * sin(inc)
        return Pair(V3(a*px+b*py,c*px+d*py,e*px+f*py), V3(a*vx+b*vy,c*vx+d*vy,e*vx+f*vy))
    }
    private fun acceleration(pos: V3, jd: Double): V3 {
        fun field(delta: V3, mu: Double): V3 {
            val r = max(delta.norm(), 1e-7)
            return delta * (mu / (r*r*r))
        }
        var result = field(pos * -1.0, MU)
        for (p in planets) result += field(planetAt(p,jd) - pos, MU*p.massRatio)
        return result
    }
    fun compute(el: Elements, date: LocalDate): Snapshot {
        val target = julian(date)
        var (r, v) = fromElements(el)
        var jd = el.epoch
        val total = target - jd
        val steps = ceil(abs(total) / .5).toInt()
        require(steps <= 120000) { "Fecha fuera del rango permitido" }
        if (steps > 0) {
            val h = total / steps
            repeat(steps) {
                val a0 = acceleration(r, jd)
                val next = r + v*h + a0*(.5*h*h)
                val a1 = acceleration(next, jd+h)
                v += (a0+a1)*(.5*h)
                r = next
                jd += h
            }
        }
        val bodies = planets.map { it to planetAt(it, target) }
        val earth = bodies.first { it.first.name == "Tierra" }.second
        return Snapshot(date,r,earth,bodies,(r-earth).norm())
    }
}
