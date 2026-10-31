package cl.apophis.simulator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.*

class OrbitView(context: Context) : View(context) {
    var snapshot: Snapshot? = null
        set(value) { field = value; invalidate() }
    var zoom = 1.0
        set(value) { field = value.coerceIn(0.05, 1.0e7); invalidate() }
    var earthCentered = false
        set(value) { field = value; panX=0f; panY=0f; invalidate() }
    private var panX=0f
    private var panY=0f
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val detector=ScaleGestureDetector(context,object: ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(d: ScaleGestureDetector): Boolean {
            zoom *= d.scaleFactor.toDouble()
            return true
        }
    })
    private var lastX=0f
    private var lastY=0f
    private val auKm=149597870.7
    fun setEarthRange(km: Double) {
        earthCentered=true
        // The viewport half-width is the requested radius in km.
        zoom = (5.3 * auKm / km).coerceIn(.05,1e7)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        detector.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lastX=event.x; lastY=event.y; return true }
            MotionEvent.ACTION_MOVE -> {
                if (!detector.isInProgress && event.pointerCount==1) {
                    panX += event.x-lastX
                    panY += event.y-lastY
                    invalidate()
                }
                lastX=event.x;lastY=event.y
                return true
            }
            MotionEvent.ACTION_UP -> { performClick(); return true }
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(6,13,29))
        val s=snapshot ?: return
        val center=if(earthCentered) s.earth else V3(0.0,0.0,0.0)
        val cx=width/2f+panX
        val cy=height/2f+panY
        val scale=min(width,height)*.40*zoom/5.3
        fun point(v: V3): Pair<Float,Float> {
            val p=v-center
            return Pair((cx+(p.x-p.y)*.70710678*scale).toFloat(),
                (cy+((p.x+p.y)*.38-p.z*.85)*scale).toFloat())
        }
        fun circle(pos: V3, radius: Float, color: Int) {
            val (x,y)=point(pos)
            if(x < -200 || x > width+200 || y < -200 || y > height+200) return
            paint.style=Paint.Style.FILL
            paint.color=color
            canvas.drawCircle(x,y,radius,paint)
        }
        if(!earthCentered && zoom < 100.0) {
            for(p in OrbitEngine.planets) {
                val radiusPx=p.radius*scale
                if(radiusPx>max(width,height)*15 || radiusPx<2) continue
                paint.color=Color.argb(80,130,155,190)
                paint.style=Paint.Style.STROKE
                paint.strokeWidth=1f
                val path=Path()
                for(i in 0..180) {
                    val angle=i*2.0*PI/180.0
                    val (x,y)=point(V3(p.radius*cos(angle),p.radius*sin(angle),0.0))
                    if(i==0) path.moveTo(x,y) else path.lineTo(x,y)
                }
                canvas.drawPath(path,paint)
            }
        }
        circle(V3(0.0,0.0,0.0),10f,Color.YELLOW)
        for((p,pos) in s.planets) circle(pos,if(p.name=="Tierra") 8f else 5f,p.color)
        if(earthCentered) {
            // Earth radius is 6371 km, drawn at true scene scale when visible.
            val earthRadiusPx=(6371.0/auKm*scale).toFloat()
            if(earthRadiusPx in 2f..10000f) circle(s.earth,earthRadiusPx,Color.rgb(60,130,250))
        }
        circle(s.asteroid,7f,Color.rgb(255,75,95))
        val (x,y)=point(s.asteroid)
        paint.color=Color.WHITE
        paint.style=Paint.Style.FILL
        paint.textSize=26f
        if(x in 0f..width.toFloat() && y in 0f..height.toFloat()) canvas.drawText("Apophis",x+10,y-8,paint)
        paint.textSize=23f
        canvas.drawText(if(earthCentered) "Cámara: Tierra · escala aproximada" else "Cámara: Sol · órbitas aproximadas",14f,32f,paint)
        canvas.drawText("Pellizca para zoom · arrastra para mover",14f,65f,paint)
        if(earthCentered) {
            paint.textSize=21f
            val halfWidthKm=5.3*auKm/zoom
            canvas.drawText("Radio de vista: "+String.format(java.util.Locale.US,"%,.0f",halfWidthKm)+" km",14f,96f,paint)
        }
    }
}
