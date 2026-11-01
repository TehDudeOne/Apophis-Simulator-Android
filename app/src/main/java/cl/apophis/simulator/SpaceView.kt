package cl.apophis.simulator

import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import android.opengl.GLUtils
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
    @Volatile var focus = 0 // 0=Sol, 1=Tierra, 2=Apophis
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
    fun earthRange(km:Double) { focus=1;panX=0.0;panY=0.0;zoom=(5.3*149597870.7/km).coerceIn(.05,1.0e7) }
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
    private var surfaceHandle=0
    private var textureHandle=0
    private val bodyTextures=mutableMapOf<String,Int>()
    private var indexCount=0
    private lateinit var vertexData:java.nio.FloatBuffer
    private lateinit var indices:java.nio.ShortBuffer
    private val projection=FloatArray(16)
    private val camera=FloatArray(16)
    private val model=FloatArray(16)
    private val tmp=FloatArray(16)
    private val mvp=FloatArray(16)
    private var aspect=1f
    private val asteroidTrail=ArrayDeque<V3>()
    private var lastTrailDay:java.time.LocalDate?=null
    private var lastTrailPosition:V3?=null
    private fun moonOffset(date:java.time.LocalDate):V3 {
        val days=date.toEpochDay().toDouble()-java.time.LocalDate.of(2000,1,1).toEpochDay()
        val angle=2.0*PI*days/27.321661
        return V3(0.00257*cos(angle),0.00257*sin(angle),0.00023*sin(angle))
    }
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
            uniform float uSurface;
            uniform sampler2D uTexture;
            varying vec3 vNormal;
            void main() {
                float diffuse=max(dot(normalize(vNormal),normalize(vec3(-0.5,0.8,1.0))),0.0);
                float illumination=mix(0.23+0.77*diffuse,1.0,uLight);
                float latitude=asin(clamp(vNormal.z,-1.0,1.0));
                float longitude=atan(vNormal.y,vNormal.x);
                float detail=sin(longitude*17.0+sin(latitude*11.0))*cos(latitude*29.0);
                float bands=sin(latitude*42.0+detail*0.8);
                vec3 base=uColor.rgb;
                if(uSurface>0.5) {
                    vec2 uv=vec2(fract(0.5+longitude/6.2831853),0.5-latitude/3.14159265);
                    base=texture2D(uTexture,uv).rgb;
                }
                gl_FragColor=vec4(base*illumination,uColor.a);
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
        surfaceHandle=GLES20.glGetUniformLocation(program,"uSurface")
        textureHandle=GLES20.glGetUniformLocation(program,"uTexture")
        val bodies=listOf("Tierra","Mercurio","Venus","Luna","Marte","Júpiter","Saturno","Urano","Neptuno")
        for (name in bodies) {
            val ids=IntArray(1)
            GLES20.glGenTextures(1,ids,0)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,ids[0])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_REPEAT)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
            val bitmap=if(name=="Tierra") {
                view.context.assets.open("earth_map.png").use { BitmapFactory.decodeStream(it) }
            } else PlanetTextures.create(name)
            requireNotNull(bitmap) { "No se pudo crear la textura de $name" }
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D,0,bitmap,0)
            bitmap.recycle()
            bodyTextures[name]=ids[0]
        }
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
        val center=when(view.focus) { 1 -> s.earth; 2 -> s.asteroid; 3 -> s.earth + moonOffset(s.date); in 4..10 -> s.planets.getOrNull(if(view.focus<=5) view.focus-4 else view.focus-3)?.second ?: s.earth; else -> V3(0.0,0.0,0.0) }
        val halfHeight=5.3/view.zoom.coerceIn(0.05,1.0e7)
        Matrix.orthoM(projection,0,(-halfHeight*aspect).toFloat(),(halfHeight*aspect).toFloat(),(-halfHeight).toFloat(),halfHeight.toFloat(),.001f,500f)
        Matrix.setLookAtM(camera,0,0f,0f,40f,0f,0f,0f,0f,1f,0f)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,bodyTextures.getValue("Tierra"))
        GLES20.glUniform1i(textureHandle,0)
        GLES20.glEnableVertexAttribArray(vertices)
        GLES20.glVertexAttribPointer(vertices,3,GLES20.GL_FLOAT,false,12,vertexData)
        fun line(points:List<V3>,color:Int) {
            if(points.size<2) return
            val coords=FloatArray(points.size*3)
            points.forEachIndexed {i,p ->
                val v=p-center
                coords[i*3]=((v.x-v.y)*.70710678+view.panX*halfHeight).toFloat()
                coords[i*3+1]=((v.x+v.y)*.38-v.z*.85+view.panY*halfHeight).toFloat()
                coords[i*3+2]=0f
            }
            val buffer=ByteBuffer.allocateDirect(coords.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(coords).position(0)
            Matrix.multiplyMM(mvp,0,projection,0,camera,0)
            GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
            GLES20.glUniform4f(colorHandle,Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,1f)
            GLES20.glUniform1f(lightHandle,1f)
            GLES20.glUniform1f(surfaceHandle,0f)
            GLES20.glVertexAttribPointer(vertices,3,GLES20.GL_FLOAT,false,12,buffer)
            GLES20.glDrawArrays(GLES20.GL_LINE_STRIP,0,points.size)
            GLES20.glVertexAttribPointer(vertices,3,GLES20.GL_FLOAT,false,12,vertexData)
        }
        // Illustrative heliocentric orbital rings, not JPL ephemerides.
        for((planet,pos) in s.planets) {
            val radius=sqrt(pos.x*pos.x+pos.y*pos.y)
            if(radius>0.0) line((0..128).map {i ->
                val angle=2.0*PI*i/128.0
                V3(radius*cos(angle),radius*sin(angle),0.0)
            },Color.rgb(48,75,105))
        }
        val previous=lastTrailPosition
        if(lastTrailDay!=s.date || previous==null || (s.asteroid-previous).norm()>0.0000002) {
            if(lastTrailDay!=null && kotlin.math.abs(java.time.temporal.ChronoUnit.DAYS.between(lastTrailDay,s.date))>2) asteroidTrail.clear()
            asteroidTrail.addLast(s.asteroid)
            while(asteroidTrail.size>1800) asteroidTrail.removeFirst()
            lastTrailDay=s.date
            lastTrailPosition=s.asteroid
        }
        line(asteroidTrail.toList(),Color.rgb(255,115,65))
        fun draw(p:V3,r:Double,color:Int,emissive:Boolean=false,surface:Float=0f,body:String?=null) {
            val v=p-center
            val sx=(v.x-v.y)*.70710678+view.panX*halfHeight
            val sy=(v.x+v.y)*.38-v.z*.85+view.panY*halfHeight
            if(abs(sx)>halfHeight*aspect*2 || abs(sy)>halfHeight*2) return
            Matrix.setIdentityM(model,0)
            Matrix.translateM(model,0,sx.toFloat(),sy.toFloat(),0f)
            if(body!=null) {
                val days=java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(2000,1,1),s.date).toFloat()
                Matrix.rotateM(model,0,(days*360.9856f)%360f,0f,0f,1f)
                Matrix.rotateM(model,0,23.44f,1f,0f,0f)
            }
            Matrix.scaleM(model,0,r.toFloat(),r.toFloat(),r.toFloat())
            Matrix.multiplyMM(tmp,0,camera,0,model,0)
            Matrix.multiplyMM(mvp,0,projection,0,tmp,0)
            GLES20.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
            GLES20.glUniform4f(colorHandle,Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,1f)
            GLES20.glUniform1f(lightHandle,if(emissive) 1f else 0f)
            GLES20.glUniform1f(surfaceHandle,if(body!=null) 1f else 0f)
            if(body!=null) GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,bodyTextures.getValue(body))
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
            draw(pos,max(physical,3.0*pixelRadius),planet.color,false,1f,planet.name)
        }
        draw(s.earth+moonOffset(s.date),max(1737.4/149597870.7,2.5*pixelRadius),Color.LTGRAY,false,1f,"Luna")
        draw(s.asteroid,max(0.000001,3.5*pixelRadius),Color.rgb(255,80,95))
        GLES20.glDisableVertexAttribArray(vertices)
    }
}
