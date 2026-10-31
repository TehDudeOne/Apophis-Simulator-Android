package cl.apophis.simulator

import android.content.Context
import android.graphics.Color
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.*

class SpaceView(context: Context): GLSurfaceView(context) {
    @Volatile var snapshot: Snapshot? = null
    @Volatile var earthCentered = false
    @Volatile var zoom = 1.0
    @Volatile var panX = 0.0
    @Volatile var panY = 0.0
    private val renderer3d = SpaceRenderer(this)
    private val pinch = ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(d:ScaleGestureDetector):Boolean {
            zoom=(zoom*d.scaleFactor).coerceIn(0.05,1.0e7)
            return true
        }
    })
    private var x=0f
    private var y=0f
    init { setEGLContextClientVersion(2);setRenderer(renderer3d);renderMode=RENDERMODE_CONTINUOUSLY }
    fun earthRange(km:Double) { earthCentered=true;panX=0.0;panY=0.0;zoom=(5.3*149597870.7/km).coerceIn(.05,1.0e7) }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        pinch.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {x=event.x;y=event.y;return true}
            MotionEvent.ACTION_MOVE -> {
                if(event.pointerCount==1 && !pinch.isInProgress) {
                    panX += (event.x-x)/width.toDouble()*2.0
                    panY -= (event.y-y)/height.toDouble()*2.0
                }
                x=event.x;y=event.y;return true
            }
            MotionEvent.ACTION_UP -> {performClick();return true}
        }
        return true
    }
    override fun performClick():Boolean {super.performClick();return true}
}
private class SpaceRenderer(private val view:SpaceView):GLSurfaceView.Renderer {
    private var program=0
    private var vertices=0
    private var mvpHandle=0
    private var colorHandle=0
    private var lightHandle=0
    private var indexCount=0
    private lateinit var vertexData:java.nio.FloatBuffer
    private lateinit var indices:java.nio.ShortBuffer
    private val projection=FloatArray(16)
    private val camera=FloatArray(16)
    private val model=FloatArray(16)
    private val tmp=FloatArray(16)
    private val mvp=FloatArray(16)
    private var aspect=1f
    private fun shader(type:Int,code:String):Int {
        val id=GLES20.glCreateShader(type)
        GLES20.glShaderSource(id,code)
        GLES20.glCompileShader(id)
        val ok=IntArray(1)
        GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,ok,0)
        check(ok[0]!=0) { GLES20.glGetShaderInfoLog(id) }
        return id
    }
    override fun onSurfaceCreated(gl:GL10?, config:EGLConfig?) {
        GLES20.glClearColor(.012f,.025f,.065f,1f)
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        val vs=shader(GLES20.GL_VERTEX_SHADER,"""
            uniform mat4 uMvp;
            attribute vec3 aPosition;
            varying vec3 vNormal;
            void main() {
                vNormal=normalize(aPosition);
                gl_Position=uMvp*vec4(aPosition,1.0);
            }
        """.trimIndent())
        val fs=shader(GLES20.GL_FRAGMENT_SHADER,"""
            precision mediump float;
            uniform vec4 uColor;
            uniform float uLight;
            varying vec3 vNormal;
            void main() {
                float diffuse=max(dot(normalize(vNormal),normalize(vec3(-0.5,0.8,1.0))),0.0);
                float illumination=mix(0.23+0.77*diffuse,1.0,uLight);
                gl_FragColor=vec4(uColor.rgb*illumination,uColor.a);
            }
        """.trimIndent())
        program=GLES20.glCreateProgram()
        GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs)
        GLES20.glLinkProgram(program)
        val linked=IntArray(1)
        GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0)
        check(linked[0]!=0) { GLES20.glGetProgramInfoLog(program) }
        GLES20.glDeleteShader(vs);GLES20.glDeleteShader(fs)
        vertices=GLES20.glGetAttribLocation(program,"aPosition")
        mvpHandle=GLES20.glGetUniformLocation(program,"uMvp")
        colorHandle=GLES20.glGetUniformLocation(program,"uColor")
        lightHandle=GLES20.glGetUniformLocation(program,"uLight")
        val positions=ArrayList<Float>()
        val ids=ArrayList<Short>()
        val lat=20;val lon=32
        for(i in 0..lat) {
            val phi=PI*i/lat
            for(j in 0..lon) {
                val theta=2*PI*j/lon
                positions.add((sin(phi)*cos(theta)).toFloat())
                positions.add((sin(phi)*sin(theta)).toFloat())
                positions.add(cos(phi).toFloat())
            }
        }
        for(i in 0 until lat) for(j in 0 until lon) {
            val a=(i*(lon+1)+j).toShort()
            val b=((i+1)*(lon+1)+j).toShort()
            ids.add(a);ids.add(b);ids.add((a+1).toShort())
            ids.add((a+1).toShort());ids.add(b);ids.add((b+1).toShort())
        }
        indexCount=ids.size
        vertexData=ByteBuffer.allocateDirect(positions.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vertexData.put(positions.toFloatArray()).position(0)
        indices=ByteBuffer.allocateDirect(ids.size*2).order(ByteOrder.nativeOrder()).asShortBuffer()
        indices.put(ids.toShortArray()).position(0)
    }
    override fun onSurfaceChanged(gl:GL10?,width:Int,height:Int) {
        GLES20.glViewport(0,0,width,height)
        aspect=width.toFloat()/max(height,1)
    }
    override fun onDrawFrame(gl:GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
        val s=view.snapshot ?: return
        val center=if(view.earthCentered) s.earth else V3(0.0,0.0,0.0)
        val halfHeight=5.3/view.zoom
        Matrix.orthoM(projection,0,(-halfHeight*aspect).toFloat(),(halfHeight*aspect).toFloat(),(-halfHeight).toFloat(),halfHeight.toFloat(),.001f,500f)
        Matrix.setLookAtM(camera,0,0f,0f,40f,0f,0f,0f,0f,1f,0f)
        GLES20.glUseProgram(program)
        GLES20.glEnableVertexAttribArray(vertices)
        GLES20.glVertexAttribPointer(vertices,3,GLES20.GL_FLOAT,false,12,vertexData)
        fun draw(p:V3,r:Double,color:Int,emissive:Boolean=false) {
            val v=p-center
            val sx=(v.x-v.y)*.70710678+view.panX*halfHeight
            val sy=(v.x+v.y)*.38-v.z*.85+view.panY*halfHeight
            if(abs(sx)>halfHeight*aspect*2 || abs(sy)>halfHeight*2) return
            Matrix.setIdentityM(model,0)
            Matrix.translateM(model,0,sx.toFloat(),sy.toFloat(),0f)
            Matrix.scaleM(model,0,r.toFloat(),r.toFloat(),r.toFloat())
            Matrix.multiplyMM(tmp,0,camera,0,model,0)
            Matrix.multiplyMM(mvp,0,projection,0,tmp,0)
            GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
            GLES20.glUniform4f(colorHandle,Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,1f)
            GLES20.glUniform1f(lightHandle,if(emissive) 1f else 0f)
            indices.position(0)
            GLES20.glDrawElements(GLES20.GL_TRIANGLES,indexCount,GLES20.GL_UNSIGNED_SHORT,indices)
        }
        val pixelRadius=halfHeight/max(view.height,1)*2.0
        draw(V3(0.0,0.0,0.0),max(.045,2.5*pixelRadius),Color.YELLOW,true)
        for((planet,pos) in s.planets) {
            val physical=when(planet.name) {
                "Mercurio"->2439.7;"Venus"->6051.8;"Tierra"->6371.0;"Marte"->3389.5
                "Júpiter"->69911.0;"Saturno"->58232.0;"Urano"->25362.0;else->24622.0
            }/149597870.7
            draw(pos,max(physical,3.0*pixelRadius),planet.color)
        }
        draw(s.asteroid,max(0.000001,3.5*pixelRadius),Color.rgb(255,80,95))
        GLES20.glDisableVertexAttribArray(vertices)
    }
}
