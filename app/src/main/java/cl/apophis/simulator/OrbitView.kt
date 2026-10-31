package cl.apophis.simulator

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import kotlin.math.*

class OrbitView(context: Context) : View(context) {
    var snapshot: Snapshot? = null
        set(value) { field = value; invalidate() }
    var zoom = 1.0
        set(value) { field = value.coerceIn(.15, 12.0); invalidate() }
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.rgb(6, 13, 29))
        val cx = width / 2f
        val cy = height / 2f
        val scale = min(width,height) * .40f * zoom.toFloat() / 5.3f
        fun point(v: V3): Pair<Float,Float> {
            // Proyección ortográfica inclinada; la coordenada z afecta la elevación.
            return Pair(cx + ((v.x-v.y)*.70710678*scale).toFloat(),
                cy + ((v.x+v.y)*.38*scale - v.z*.85*scale).toFloat())
        }
        fun circle(pos: V3, radius: Float, color: Int) {
            val (x,y) = point(pos)
            paint.color = color
            paint.style = Paint.Style.FILL
            canvas.drawCircle(x,y,radius,paint)
        }
        paint.style = Paint.Style.STROKE
        for (p in OrbitEngine.planets) {
            if (p.radius > 10 && zoom >= 1.0) continue
            paint.color = Color.argb(75,140,155,180)
            paint.strokeWidth = 1f
            val path = android.graphics.Path()
            for (i in 0..180) {
                val a = i*2.0*PI/180.0
                val (x,y) = point(V3(p.radius*cos(a),p.radius*sin(a),0.0))
                if (i==0) path.moveTo(x,y) else path.lineTo(x,y)
            }
            canvas.drawPath(path,paint)
        }
        circle(V3(0.0,0.0,0.0), 10f, Color.YELLOW)
        snapshot?.let { s ->
            for ((p,pos) in s.planets) {
                if (p.radius > 10 && zoom >= 1.0) continue
                circle(pos,if(p.name=="Tierra") 7f else 4f,p.color)
            }
            circle(s.asteroid, 8f, Color.rgb(255,75,95))
            val (x,y)=point(s.asteroid)
            paint.color=Color.WHITE
            paint.textSize=30f
            paint.style=Paint.Style.FILL
            canvas.drawText("Apophis",x+12f,y-10f,paint)
        }
        paint.color=Color.LTGRAY
        paint.textSize=25f
        canvas.drawText("Vista 3D proyectada · escala AU",18f,36f,paint)
    }
}
