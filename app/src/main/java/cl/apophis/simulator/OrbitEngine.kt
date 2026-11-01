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
    // JPL approximate Kepler elements and century rates, 1800–2050.
    // Heliocentric mean ecliptic and equinox J2000; not Horizons vectors.
    private val secular=arrayOf(
        doubleArrayOf(0.38709927,3.7e-7,0.20563593,0.00001906,7.00497902,-0.00594749,252.2503235,149472.67411175,77.45779628,0.16047689,48.33076593,-0.12534081),
        doubleArrayOf(0.72333566,0.0000039,0.00677672,-0.00004107,3.39467605,-0.0007889,181.9790995,58517.81538729,131.60246718,0.00268329,76.67984255,-0.27769418),
        doubleArrayOf(1.00000261,0.00000562,0.01671123,-0.00004392,-0.00001531,-0.01294668,100.46457166,35999.37244981,102.93768193,0.32327364,0.0,0.0),
        doubleArrayOf(1.52371034,0.00001847,0.0933941,0.00007882,1.84969142,-0.00813131,-4.55343205,19140.30268499,-23.94362959,0.44441088,49.55953891,-0.29257343),
        doubleArrayOf(5.202887,-0.00011607,0.04838624,-0.00013253,1.30439695,-0.00183714,34.39644051,3034.74612775,14.72847983,0.21252668,100.47390909,0.20469106),
        doubleArrayOf(9.53667594,-0.0012506,0.05386179,-0.00050991,2.48599187,0.00193609,49.95424423,1222.49362201,92.59887831,-0.41897216,113.66242448,-0.28867794),
        doubleArrayOf(19.18916464,-0.00196176,0.04725744,-0.00004397,0.77263783,-0.00242939,313.23810451,428.48202785,170.9542763,0.40805281,74.01692503,0.04240589),
        doubleArrayOf(30.06992276,0.00026291,0.00859048,0.00005105,1.77004347,0.00035372,-55.12002969,218.45945325,44.96476227,-0.32241464,131.78422574,-0.00508664)
    )
    private fun planetAt(p:Planet,jd:Double):V3 {
        val v=secular[planets.indexOf(p)]
        val t=(jd-J2000)/36525.0
        val a=v[0]+v[1]*t
        val e=v[2]+v[3]*t
        val inc=(v[4]+v[5]*t)*RAD
        val longitude=(v[6]+v[7]*t)*RAD
        val peri=(v[8]+v[9]*t)*RAD
        val node=(v[10]+v[11]*t)*RAD
        val m=atan2(sin(longitude-peri),cos(longitude-peri))
        var eccentric=m
        repeat(12) {eccentric-=(eccentric-e*sin(eccentric)-m)/(1.0-e*cos(eccentric))}
        val px=a*(cos(eccentric)-e)
        val py=a*sqrt(1-e*e)*sin(eccentric)
        val arg=peri-node
        val x=px*cos(arg)-py*sin(arg)
        val y=px*sin(arg)+py*cos(arg)
        return V3(x*cos(node)-y*cos(inc)*sin(node),
            x*sin(node)+y*cos(inc)*cos(node),y*sin(inc))
    }
    /** Continuous positions in a common heliocentric J2000 ecliptic frame. */
    fun planetPositions(date:LocalDate,phase:Double):List<Pair<Planet,V3>> {
        require(phase.isFinite() && phase in 0.0..1.0)
        val jd=julian(date)+phase
        return planets.map {it to planetAt(it,jd)}
    }
    /** Approximate eccentric, inclined lunar orbit in J2000 ecliptic axes. */
    fun moonOffset(date:LocalDate,phase:Double):V3 {
        val d=julian(date)+phase-J2000
        val m=atan2(sin((134.9634+13.06499295*d)*RAD),cos((134.9634+13.06499295*d)*RAD))
        val node=(125.04452-.0529538083*d)*RAD
        val arg=(318.0634+.1643573223*d)*RAD
        val e=.0549
        var eccentric=m
        repeat(10) {eccentric-=(eccentric-e*sin(eccentric)-m)/(1.0-e*cos(eccentric))}
        val a=384400.0/149597870.7
        val px=a*(cos(eccentric)-e)
        val py=a*sqrt(1-e*e)*sin(eccentric)
        val x=px*cos(arg)-py*sin(arg)
        val y=px*sin(arg)+py*cos(arg)
        val inc=5.145*RAD
        return V3(x*cos(node)-y*cos(inc)*sin(node),
            x*sin(node)+y*cos(inc)*cos(node),y*sin(inc))
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
