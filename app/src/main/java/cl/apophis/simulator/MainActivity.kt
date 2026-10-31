package cl.apophis.simulator

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity : Activity() {
    private var date=LocalDate.of(2029,4,13)
    private var elements=OrbitEngine.fallback
    private lateinit var orbitView: OrbitView
    private lateinit var status: TextView
    private lateinit var dateText: TextView
    private lateinit var playButton: Button
    private val repository by lazy { JplRepository(this) }
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private var playing=false
    private var busy=false
    private var generation=0
    private var daysPerFrame=1L
    private val tick=object: Runnable {
        override fun run() {
            if(!playing) return
            if(!busy) { date=date.plusDays(daysPerFrame); render() }
            handler.postDelayed(this,500)
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6,13,29))
            setPadding(10,8,10,8)
        }
        fun label(size:Float):TextView=TextView(this).apply {
            textSize=size;setTextColor(Color.WHITE);gravity=Gravity.CENTER
            setPadding(2,3,2,3)
        }
        root.addView(label(22f).apply { text="APOPHIS SIMULATOR" })
        status=label(12f);root.addView(status)
        orbitView=OrbitView(this)
        root.addView(orbitView,LinearLayout.LayoutParams(-1,0,1f))
        dateText=label(17f);root.addView(dateText)
        val controls=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        fun buttons(vararg actions:Pair<String,()->Unit>):List<Button> {
            val row=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL }
            val result=actions.map { (title,action) ->
                Button(this).apply {
                    text=title
                    textSize=12f
                    setPadding(0,0,0,0)
                    setOnClickListener { action() }
                    row.addView(this,LinearLayout.LayoutParams(0,-2,1f))
                }
            }
            controls.addView(row)
            return result
        }
        playButton=buttons("▶ Play" to { togglePlay() }, "-1 d" to { move(-1) },
            "+1 d" to { move(1) }, "2029" to { date=LocalDate.of(2029,4,13);render() })[0]
        buttons("1 d/paso" to { daysPerFrame=1 }, "7 d/paso" to { daysPerFrame=7 },
            "30 d/paso" to { daysPerFrame=30 })
        buttons("☉ Sol" to { orbitView.earthCentered=false;orbitView.zoom=1.0 },
            "⊕ Tierra" to { orbitView.earthCentered=true;orbitView.zoom=200.0 },
            "35.000 km" to { orbitView.setEarthRange(35000.0) })
        buttons("Zoom +" to { orbitView.zoom*=2.0 },
            "Zoom -" to { orbitView.zoom/=2.0 },
            "Actualizar JPL" to { refreshJpl() })
        val scroll=ScrollView(this)
        scroll.addView(controls)
        root.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        root.addView(label(11f).apply {
            text="AVISO: modelo N-cuerpos simplificado, planetas circulares. Las distancias NO son predicciones fiables del encuentro de 2029."
        })
        setContentView(root)
        repository.cached()?.let { elements=it }
        render()
    }
    private fun togglePlay() {
        playing=!playing
        playButton.text=if(playing) "⏸ Pausa" else "▶ Play"
        handler.removeCallbacks(tick)
        if(playing) handler.post(tick)
    }
    private fun move(days:Long) { date=date.plusDays(days);render() }
    private fun render() {
        val selectedDate=date
        val selectedElements=elements
        val ticket=++generation
        dateText.text=selectedDate.toString()
        if(busy) return
        busy=true
        executor.execute {
            val result=runCatching { OrbitEngine.compute(selectedElements,selectedDate) }
            runOnUiThread {
                busy=false
                if(isFinishing || isDestroyed) return@runOnUiThread
                if(ticket!=generation) { render();return@runOnUiThread }
                result.onSuccess { snapshot ->
                    orbitView.snapshot=snapshot
                    val km=snapshot.earthDistanceAu*149597870.7
                    val source=if(selectedElements==OrbitEngine.fallback) "Órbita ilustrativa" else "Elementos JPL"
                    status.text=String.format(Locale.US,"%s · distancia aproximada: %,.0f km",source,km)
                }.onFailure { status.text="Error: ${it.message}" }
            }
        }
    }
    private fun refreshJpl() {
        status.text="Consultando NASA/JPL..."
        executor.execute {
            val result=runCatching { repository.refresh() }
            runOnUiThread {
                if(isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess { elements=it;render() }
                    .onFailure { status.text="JPL no disponible: ${it.message}" }
            }
        }
    }
    override fun onDestroy() {
        playing=false
        handler.removeCallbacks(tick)
        executor.shutdownNow()
        super.onDestroy()
    }
}
