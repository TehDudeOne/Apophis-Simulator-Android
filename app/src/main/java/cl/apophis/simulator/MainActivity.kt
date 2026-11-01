package cl.apophis.simulator

import android.app.Activity
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.graphics.Color
import android.view.Gravity
import android.view.WindowInsets
import android.widget.Button
import android.widget.EditText
import android.widget.Toast
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.TextView
import java.time.LocalDate
import java.util.Locale
import java.util.concurrent.Executors

class MainActivity:Activity() {
    private var date=LocalDate.of(2029,4,13)
    private var phase=0.0
    private var elements=OrbitEngine.fallback
    private lateinit var space:SpaceView
    private lateinit var status:TextView
    private lateinit var dateLabel:TextView
    private lateinit var playButton:Button
    private lateinit var dateInput:EditText
    private val repository by lazy { JplRepository(this) }
    private val horizons by lazy { HorizonsDistance(this) }
    private val executor=Executors.newSingleThreadExecutor()
    private val handler=Handler(Looper.getMainLooper())
    private var playing=false
    private var daysPerSecond=1.0
    private lateinit var timeSeek:SeekBar
    private var seeking=false
    private var lastFrame=0L
    private var version=0
    private var loading=false
    private var pendingRequest=false
    private var pair:Pair<Snapshot,Snapshot>?=null
    private var pairDate:LocalDate?=null
    private var horizonsKm:Double?=null
    private var horizonsDate:LocalDate?=null
    private val vectorCache=mutableMapOf<LocalDate,Pair<V3,V3>>()
    private val french by lazy { FrenchEphemeris(this) }
    private val hourlyCache=mutableMapOf<LocalDate,List<Pair<V3,V3>>>()
    private val bodyEphemerides by lazy {BodyEphemerides(this,horizons)}
    private val bodyCache=object:LinkedHashMap<LocalDate,BodyEphemerides.Day>(4,0.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<LocalDate,BodyEphemerides.Day>?):Boolean = size>3
    }
    private val asteroidCache=mutableMapOf<LocalDate,BodyEphemerides.Day>()
    private val bodyPending=mutableSetOf<LocalDate>()
    private val bodyAttempted=mutableSetOf<LocalDate>()
    private var bodyStatus="Efemérides: buscando caché interno"
    private var trailDate:LocalDate?=null
    private var trailPhase=Double.NaN
    private fun updateReferenceTrail(day:LocalDate,fraction:Double) {
        // A centered 24-hour window, sampled every 10 minutes. This curve is
        // rebuilt when time changes and never depends on previous render frames.
        if(trailDate==day && kotlin.math.abs(trailPhase-fraction)<(1.0/144.0)) return
        val points=ArrayList<V3>(145)
        val center=day.toEpochDay().toDouble()+fraction
        for(i in -72..72) {
            val instant=center+i/144.0
            val epochDay=kotlin.math.floor(instant).toLong()
            val phaseAt=instant-epochDay
            val state=french.sample(LocalDate.ofEpochDay(epochDay),phaseAt)
            if(state!=null) points.add(state.position)
        }
        space.predictedGeocentricTrail=if(points.size>=2) points else emptyList()
        trailDate=day
        trailPhase=fraction
    }

    private fun requestBodies(day:LocalDate,force:Boolean=false) {
        if(bodyPending.contains(day) || (!force && bodyAttempted.contains(day))) return
        bodyAttempted.add(day)
        bodyPending.add(day)
        executor.execute {
            // Load cached bodies first, before contacting any service.
            val cached=runCatching {bodyEphemerides.cached(day)}.getOrNull()
            if(cached!=null) runOnUiThread {
                if(!isFinishing && !isDestroyed) {
                    bodyCache[day]=cached
                    asteroidCache[day]=cached
                    if(date==day) {bodyStatus=cached.summary();showFrame()}
                }
            }
            val result=runCatching {bodyEphemerides.fetch(day)}
            runOnUiThread {
                bodyPending.remove(day)
                if(isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {
                    bodyCache[day]=it
                    asteroidCache[day]=it
                    if(date==day) bodyStatus=it.summary()
                }.onFailure {
                    if(date==day) bodyStatus="ERROR efemérides: ${it.message ?: it.javaClass.simpleName}"
                }
                if(date==day) showFrame()
            }
        }
    }

    private var hourly:List<Pair<V3,V3>>?=null

    private val frames=object:Runnable {
        override fun run() {
            if(!playing) return
            val now=SystemClock.elapsedRealtime()
            val elapsed=((now-lastFrame).coerceIn(0,150)).toDouble()/1000.0
            lastFrame=now
            if((pairDate==date && pair!=null) || french.sample(date,phase)!=null) {
                phase+=elapsed*daysPerSecond
                if(phase>=1.0 || phase<0.0) {
                    val delta=kotlin.math.floor(phase).toLong()
                    date=date.plusDays(delta)
                    phase-=delta
                    requestPair()
                    requestHorizons()
                }
            }
            showFrame()
            handler.postDelayed(this,33)
        }
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        if(savedInstanceState!=null) {
            date=LocalDate.parse(savedInstanceState.getString("date","2029-04-13"))
            phase=savedInstanceState.getDouble("phase",0.0)
            daysPerSecond=savedInstanceState.getDouble("speed",1.0)
        }
        window.statusBarColor=Color.rgb(6,13,29)
        window.navigationBarColor=Color.rgb(6,13,29)
        val root=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(6,13,29))
            setPadding(10,8,10,8)
            setOnApplyWindowInsetsListener { view, insets ->
                val bars=insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
                view.setPadding(10+bars.left,8+bars.top,10+bars.right,8+bars.bottom)
                insets
            }
        }
        fun label(size:Float)=TextView(this).apply {
            textSize=size
            setTextColor(Color.WHITE)
            gravity=Gravity.CENTER
            setPadding(2,3,2,3)
        }
        root.addView(label(15f).apply {text="APOPHIS SIMULATOR"})
        status=label(12f)
        root.addView(status)
        space=SpaceView(this)
        root.addView(space,LinearLayout.LayoutParams(-1,0,1.6f))
        dateLabel=label(15f)
        root.addView(dateLabel)
        val dateRow=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
        dateInput=EditText(this).apply {
            setSingleLine(true)
            setText(date.toString())
            hint="AAAA-MM-DD"
            textSize=16f
            setTextColor(Color.WHITE)
            setHintTextColor(Color.LTGRAY)
            inputType=android.text.InputType.TYPE_CLASS_DATETIME or android.text.InputType.TYPE_DATETIME_VARIATION_DATE
        }
        dateRow.addView(dateInput,LinearLayout.LayoutParams(0,-2,1f))
        val goDate=Button(this).apply {text="Ir a fecha";isAllCaps=false;setOnClickListener {applyDateInput()} }
        dateRow.addView(goDate)
        root.addView(dateRow)
        val controls=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL}
        fun buttons(vararg actions:Pair<String,()->Unit>):List<Button> {
            val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL}
            val result=actions.map { (title,action) ->
                Button(this).apply {
                    text=title
                    textSize=10f
                    isAllCaps=false
                    minHeight=(48*resources.displayMetrics.density).toInt()
                    minimumHeight=(48*resources.displayMetrics.density).toInt()
                    setPadding(2,2,2,2)
                    setOnClickListener {action()}
                    row.addView(this,LinearLayout.LayoutParams(0,-2,1f))
                }
            }
            controls.addView(row)
            return result
        }
        playButton=buttons("▶ Play" to {togglePlay()}, "-1 d" to {move(-1)},
            "+1 d" to {move(1)}, "2029" to {date=LocalDate.of(2029,4,13);phase=0.0;requestPair();requestHorizons()})[0]
        buttons("⏪ REW" to {setSpeed(-kotlin.math.abs(daysPerSecond))}, "⏹" to {if(playing) togglePlay()},
            "⏩ FFW" to {setSpeed(kotlin.math.abs(daysPerSecond))})
        timeSeek=SeekBar(this).apply {
            max=86400
            progress=(phase*86400).toInt()
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onStartTrackingTouch(bar:SeekBar?) {seeking=true}
                override fun onStopTrackingTouch(bar:SeekBar?) {seeking=false;showFrame()}
                override fun onProgressChanged(bar:SeekBar?,value:Int,fromUser:Boolean) {
                    if(fromUser) {phase=value/86400.0;showFrame()}
                }
            })
        }
        controls.addView(timeSeek)
        buttons("◀ 1 s" to {stepSeconds(-1)}, "◀ 1 min" to {stepSeconds(-60)},
            "1 min ▶" to {stepSeconds(60)}, "1 s ▶" to {stepSeconds(1)})
        buttons("0,1 d/s" to {setSpeed(.1)}, "1 d/s" to {setSpeed(1.0)},
            "7 d/s" to {setSpeed(7.0)})
        buttons("☉ Sol" to {space.focus=0;space.panX=0.0;space.panY=0.0;space.zoom=1.0},
            "⊕ Tierra" to {space.focus=1;space.panX=0.0;space.panY=0.0;space.zoom=200.0},
            "35.000 km" to {space.earthRange(35000.0)})
        buttons("☾ Luna" to {space.focus=3;space.zoom=180.0;space.panX=0.0;space.panY=0.0},
            "Mercurio" to {space.focus=4;space.zoom=3.0}, "Venus" to {space.focus=5;space.zoom=3.0},
            "Marte" to {space.focus=6;space.zoom=3.0})
        buttons("Júpiter" to {space.focus=7;space.zoom=2.0},
            "Saturno" to {space.focus=8;space.zoom=2.0},
            "Urano" to {space.focus=9;space.zoom=2.0},
            "Neptuno" to {space.focus=10;space.zoom=2.0})
        buttons("◎ Apophis" to {space.focus=2;space.panX=0.0;space.panY=0.0;space.zoom=500.0},
            "Recentrar" to {space.panX=0.0;space.panY=0.0})
        buttons("↶ Roll" to {space.rotateRoll(-15.0)},
            "Roll ↷" to {space.rotateRoll(15.0)},
            "Pan/Órbita" to {space.dragToPan=!space.dragToPan;Toast.makeText(this,if(space.dragToPan) "Arrastre: desplazar" else "Arrastre: rotar",Toast.LENGTH_SHORT).show()})
        buttons("Datos ↻" to {requestBodies(date,true)}, "Vista ↺" to {space.resetOrientation()},
            "Tierra ⊕" to {space.focus=1;space.zoom=200.0;space.panX=0.0;space.panY=0.0})
        buttons("Zoom +" to {space.zoom=(space.zoom*2.0).coerceIn(.05,1.0e7)},
            "Zoom -" to {space.zoom=(space.zoom/2.0).coerceIn(.05,1.0e7)},
            "Actualizar JPL" to {refreshJpl()})
        val scroll=ScrollView(this).apply { isFillViewport=false }
        scroll.addView(controls)
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,0.85f))
        setContentView(root)
        repository.cached()?.let {elements=it}
        requestBodies(date)
        requestPair()
        requestHorizons()
        if(savedInstanceState!=null) {
            space.focus=savedInstanceState.getInt("focus",0)
            space.zoom=savedInstanceState.getDouble("zoom",1.0)
            space.panX=savedInstanceState.getDouble("panX",0.0)
            space.panY=savedInstanceState.getDouble("panY",0.0)
            space.yaw=savedInstanceState.getDouble("yaw",0.0)
            space.pitch=savedInstanceState.getDouble("pitch",0.0)
            space.roll=savedInstanceState.getDouble("roll",0.0)
            space.dragToPan=savedInstanceState.getBoolean("dragToPan",false)
            if(savedInstanceState.getBoolean("playing",false)) togglePlay()
        }
    }
    private fun applyDateInput() {
        val value=dateInput.text.toString().trim()
        val selected=runCatching {LocalDate.parse(value)}.getOrNull()
        if(selected==null || selected.year !in 1900..2100) {
            dateInput.error="Fecha válida: AAAA-MM-DD, entre 1900 y 2100"
            return
        }
        if(playing) togglePlay()
        date=selected
        phase=0.0
        requestBodies(date)
        (getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager)
            ?.hideSoftInputFromWindow(dateInput.windowToken,0)
        dateInput.clearFocus()
        requestPair()
        requestHorizons()
        showFrame()
    }
    private fun setSpeed(speed:Double) {
        daysPerSecond=speed
        if(!playing) togglePlay()
        showFrame()
    }
    private fun togglePlay() {
        playing=!playing
        playButton.text=if(playing) "⏸ Pausa" else "▶ Play"
        handler.removeCallbacks(frames)
        if(playing) {
            lastFrame=SystemClock.elapsedRealtime()
            handler.post(frames)
        }
    }
    private fun stepSeconds(seconds:Int) {
        val total=phase*86400.0+seconds
        val delta=kotlin.math.floor(total/86400.0).toLong()
        date=date.plusDays(delta)
        phase=(total-delta*86400.0)/86400.0
        requestPair()
        requestHorizons()
        showFrame()
    }
    private fun move(days:Long) {
        date=date.plusDays(days)
        phase=0.0
        requestPair()
        requestHorizons()
    }
    private fun requestPair() {
        val requested=date
        val selected=elements
        val token=++version
        pair=null
        pairDate=null
        hourly=null
        dateLabel.text=requested.toString()
        if(::dateInput.isInitialized && !dateInput.hasFocus()) dateInput.setText(requested.toString())
        if(loading) { pendingRequest=true; return }
        loading=true
        executor.execute {
            val result=runCatching {
                fun accurate(day:LocalDate):Snapshot {
                    // Preserve offline playback at the end of the French data window.
                    // The following midnight is outside the 432-point series.
                    if (french.sample(requested,0.0)!=null &&
                        french.sample(day,0.0)==null) {
                        return OrbitEngine.compute(selected,day)
                    }
                    val frenchState=french.sample(day,0.0)
                    if (frenchState!=null) {
                        val fallback=OrbitEngine.compute(selected,day)
                        val earth=fallback.earth
                        return Snapshot(day,earth+frenchState.position,earth,fallback.planets,frenchState.position.norm())
                    }
                    val fallback=OrbitEngine.compute(selected,day)
                    val vectors=vectorCache[day] ?: runCatching {
                        val asteroid=horizons.vector("99942",day)
                        val earth=horizons.vector("399",day)
                        (asteroid to earth).also { vectorCache[day]=it }
                    }.getOrNull() ?: return fallback
                    val planets=fallback.planets.map { (planet,pos) ->
                        planet to if(planet.name=="Tierra") vectors.second else pos
                    }
                    return Snapshot(day,vectors.first,vectors.second,planets,
                        (vectors.first-vectors.second).norm())
                }
                val daily=accurate(requested) to accurate(requested.plusDays(1))
                val samples=if(french.sample(requested,0.0)!=null) null
                    else hourlyCache[requested] ?: runCatching { horizons.hourlyVectors(requested) }.getOrNull()?.also { hourlyCache[requested]=it }
                daily to samples
            }
            runOnUiThread {
                loading=false
                if(isFinishing || isDestroyed) return@runOnUiThread
                if(token!=version || pendingRequest) {
                    pendingRequest=false
                    requestPair()
                    return@runOnUiThread
                }
                result.onSuccess {pair=it.first;hourly=it.second;pairDate=requested;showFrame()}
                    .onFailure {status.text="Horizons sin vectores: ${it.message}. Escena no actualizada."}
            }
        }
    }
    private fun interpolate(a:V3,b:V3,t:Double)=a*(1.0-t)+b*t
    private fun showFrame() {
        dateLabel.text=String.format(Locale.US,"%s · %02d:%02d:%02d UTC",date,
            (phase*24).toInt().coerceIn(0,23),((phase*24%1)*60).toInt().coerceIn(0,59),
            ((phase*86400)%60).toInt().coerceIn(0,59))
        if(::timeSeek.isInitialized && !seeking) timeSeek.progress=(phase*86400).toInt().coerceIn(0,86400)
        val t=phase.coerceIn(0.0,1.0)
        if(!bodyAttempted.contains(date)) requestBodies(date)
        val frenchState=french.sample(date,t)
        val jpl=if(frenchState==null) asteroidCache[date]?.sample("Apophis",t) else null
        val pairNow=if(pairDate==date) pair else null
        val fallback=if(frenchState==null && jpl==null && pairNow!=null) {
            val asteroid=hourly?.takeIf {it.size==25}?.let { samples ->
                val h=(t*24.0).coerceIn(0.0,24.0)
                val i=h.toInt().coerceIn(0,23)
                interpolate(samples[i].first,samples[i+1].first,h-i)
            } ?: interpolate(pairNow.first.asteroid,pairNow.second.asteroid,t)
            pairNow.first.copy(asteroid=asteroid)
        } else null
        if(frenchState==null && jpl==null && fallback==null) return
        val resolved=SnapshotResolver.resolve(date,t,bodyCache[date],frenchState,jpl,fallback)
        space.dayFraction=t
        space.moonPosition=resolved.moon
        space.snapshot=resolved.snapshot
        if(frenchState!=null) updateReferenceTrail(date,t)
        else {space.predictedGeocentricTrail=emptyList();trailDate=null}
        val playback=if(playing) String.format(Locale.US," · ▶ %.1f d/s",daysPerSecond) else " · Pausa"
        status.text=String.format(Locale.US,"%s · Distancia: %,.1f km",resolved.asteroidSource,
            resolved.snapshot.earthDistanceAu*149597870.7)+playback+
            "\n"+resolved.provenance+"\n"+bodyStatus+
            if(frenchState!=null) "\nTrayectoria: IMCCE geocéntrica ±12 h" else ""
    }
    private fun requestHorizons() {
        val requested=date
        if(french.sample(requested,phase)!=null) { horizonsDate=null; horizonsKm=null; showFrame(); return }
        horizonsDate=requested
        horizonsKm=horizons.cached(requested)
        showFrame()
        if(horizonsKm!=null) return
        executor.execute {
            val result=runCatching {horizons.query(requested)}
            runOnUiThread {
                if(isFinishing || isDestroyed || requested!=date) return@runOnUiThread
                result.onSuccess {horizonsKm=it;showFrame()}
                    .onFailure {status.text="JPL Horizons no disponible: ${it.message}\nDistancia de escena no validada"}
            }
        }
    }
    private fun refreshJpl() {
        status.text="Consultando elementos NASA/JPL..."
        executor.execute {
            val result=runCatching {repository.refresh()}
            runOnUiThread {
                if(isFinishing || isDestroyed) return@runOnUiThread
                result.onSuccess {elements=it;requestPair();requestHorizons()}
                    .onFailure {status.text="JPL no disponible: ${it.message}"}
            }
        }
    }
    override fun onSaveInstanceState(outState:Bundle) {
        outState.putString("date",date.toString())
        outState.putDouble("phase",phase)
        outState.putDouble("speed",daysPerSecond)
        outState.putDouble("yaw",space.yaw)
        outState.putDouble("pitch",space.pitch)
        outState.putDouble("roll",space.roll)
        outState.putBoolean("dragToPan",space.dragToPan)
        outState.putBoolean("playing",playing)
        if(::space.isInitialized) {
            outState.putInt("focus",space.focus)
            outState.putDouble("zoom",space.zoom)
            outState.putDouble("panX",space.panX)
            outState.putDouble("panY",space.panY)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {super.onResume();if(::space.isInitialized) space.onResume();showFrame()}
    override fun onPause() {
        if(::space.isInitialized) space.onPause()
        playing=false
        handler.removeCallbacks(frames)
        if(::playButton.isInitialized) playButton.text="▶ Play"
        super.onPause()
    }
    override fun onDestroy() {
        handler.removeCallbacks(frames)
        executor.shutdownNow()
        super.onDestroy()
    }
}
