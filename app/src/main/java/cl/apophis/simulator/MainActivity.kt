package cl.apophis.simulator

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sqrt

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(36, 36, 36, 36)
            setBackgroundColor(Color.rgb(10, 19, 37))
        }
        fun line(value: String, size: Float) {
            layout.addView(TextView(this).apply {
                text = value
                textSize = size
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, 12, 0, 12)
            })
        }
        val massKg = 2.0e10
        val approachMps = 5860.0
        val earthMu = 3.986004418e14
        val earthRadiusM = 6371000.0
        val impactMps = sqrt(approachMps * approachMps + 2.0 * earthMu / earthRadiusM)
        val energyTJ = 0.5 * massKg * impactMps * impactMps / 1e12
        line("APOPHIS SIMULATOR", 27f)
        line("Análisis hipotético de impacto", 18f)
        line("Velocidad: %.2f km/s".format(java.util.Locale.US, impactMps / 1000), 21f)
        line("Energía: %,.0f TJ".format(java.util.Locale.US, energyTJ), 21f)
        line("Modelo preliminar de dos cuerpos. No predice una colisión en 2029. Pendiente: motor N-cuerpos, efemérides JPL y visualización 3D.", 14f)
        setContentView(layout)
    }
}
