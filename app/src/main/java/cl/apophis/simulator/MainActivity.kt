package cl.apophis.simulator

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import java.time.LocalDate
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity() {
    private var date = LocalDate.of(2029,4,13)
    private var elements = OrbitEngine.fallback
    private lateinit var orbitView: OrbitView
    private lateinit var status: TextView
    private lateinit var dateText: TextView
    private val repository by lazy { JplRepository(this) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6,13,29))
            setPadding(16,20,16,12)
        }
        fun text(size: Float): TextView = TextView(this).apply {
            textSize = size
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
            setPadding(4,8,4,8)
        }
        root.addView(text(24f).apply { this.text = "APOPHIS SIMULATOR" })
        status = text(13f)
        root.addView(status)
        orbitView = OrbitView(this)
        root.addView(orbitView, LinearLayout.LayoutParams(-1,0,1f))
        dateText = text(17f)
        root.addView(dateText)
        fun buttons(vararg actions: Pair<String,()->Unit>) {
            val row = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            for ((label,action) in actions) {
                row.addView(Button(this).apply {
                    text=label
                    setOnClickListener { action() }
                },LinearLayout.LayoutParams(0,-2,1f))
            }
            root.addView(row)
        }
        buttons("-30 días" to { move(-30) }, "-1 día" to { move(-1) },
            "+1 día" to { move(1) }, "+30 días" to { move(30) })
        buttons("Zoom +" to { orbitView.zoom *= 1.5 }, "Zoom -" to { orbitView.zoom /= 1.5 },
            "2029" to { date=LocalDate.of(2029,4,13); render() })
        buttons("Actualizar JPL" to { refreshJpl() })
        root.addView(text(12f).apply {
            this.text="Modelo experimental: planetas en órbitas circulares aproximadas; integración N-cuerpos simplificada. No apto para predicción de encuentros ni impactos."
        })
        setContentView(root)
        val cached = repository.cached()
        if (cached != null) elements=cached
        render()
    }
    private fun move(days: Long) {
        date=date.plusDays(days)
        render()
    }
    private fun render() {
        dateText.text = date.toString()
        try {
            val snapshot=OrbitEngine.compute(elements,date)
            orbitView.snapshot=snapshot
            val km=snapshot.earthDistanceAu*149597870.7
            val source=if(elements==OrbitEngine.fallback) "Elementos ilustrativos" else "Elementos JPL (cache)"
            status.text=String.format(Locale.US,"%s\nDistancia geocéntrica aproximada: %,.0f km",source,km)
        } catch (e: Exception) { status.text="Error de cálculo: ${e.message}" }
    }
    private fun refreshJpl() {
        status.text="Consultando JPL..."
        thread {
            val result=runCatching { repository.refresh() }
            runOnUiThread {
                result.onSuccess {
                    elements=it
                    render()
                }.onFailure { status.text="Sin conexión JPL: ${it.message}. Datos locales disponibles." }
            }
        }
    }
}
