package cl.apophis.simulator
import java.time.LocalDate

/** All rendered bodies are resolved for one UTC instant and one reference frame. */
object SnapshotResolver {
    data class Resolved(val snapshot:Snapshot,val moon:V3,val provenance:String,val asteroidSource:String)
    fun resolve(date:LocalDate,phase:Double,ephemeris:BodyEphemerides.Day?,
        french:FrenchEphemeris.State?,jplAsteroid:V3?,fallback:Snapshot?):Resolved {
        val approximations=OrbitEngine.planetPositions(date,phase)
        val planets=approximations.map { (planet,position) ->
            planet to (ephemeris?.sample(planet.name,phase) ?: position)
        }
        val earth=planets.first {it.first.name=="Tierra"}.second
        val moon=ephemeris?.sample("Luna",phase) ?: earth+OrbitEngine.moonOffset(date,phase)
        val asteroid=when {
            french!=null -> earth+french.position
            jplAsteroid!=null -> jplAsteroid
            fallback!=null -> fallback.asteroid
            else -> earth
        }
        val source=when {
            french!=null -> "IMCCE Miriade CSV"
            jplAsteroid!=null -> "JPL Horizons"
            fallback!=null -> "Integración aproximada"
            else -> "SIN EFEMÉRIDE DE APOPHIS"
        }
        val realCount=OrbitEngine.planets.count {ephemeris?.sample(it.name,phase)!=null}
        val provenance="Planetas: $realCount/8 JPL, ${8-realCount}/8 aproximados · "+
            "Luna: ${if(ephemeris?.sample("Luna",phase)!=null) "JPL" else "aproximación"}"
        return Resolved(Snapshot(date,asteroid,earth,planets,(asteroid-earth).norm()),moon,provenance,source)
    }
}
