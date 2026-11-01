package cl.apophis.simulator

import android.content.Context
import android.graphics.Color
import android.graphics.BitmapFactory
import android.opengl.GLUtils
import android.opengl.GLES30
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
    @Volatile var dayFraction = 0.0
    @Volatile var moonPosition:V3?=null
    @Volatile var predictedGeocentricTrail:List<V3> = emptyList()
    @Volatile var focus = 0 // 0=Sol, 1=Tierra, 2=Apophis
    @Volatile var zoom = 1.0
    @Volatile var panX = 0.0
    @Volatile var panY = 0.0
    @Volatile var yaw = 0.0
    @Volatile var pitch = 0.0
    @Volatile var roll = 0.0
    @Volatile var dragToPan = false
    fun resetOrientation() { yaw=0.0; pitch=0.0; roll=0.0 }
    fun rotateRoll(degrees:Double) { roll=(roll+degrees)%360.0 }
    private val renderer3d = SpaceRenderer(this)
    private val pinch = ScaleGestureDetector(context,object:ScaleGestureDetector.SimpleOnScaleGestureListener(){
        override fun onScale(d:ScaleGestureDetector):Boolean {
            zoom=(zoom*d.scaleFactor).coerceIn(0.05,1.0e7)
            return true
        }
    })
    private var x=0f
    private var y=0f
    private var activePointers=0
    private var gestureX=0f
    private var gestureY=0f
    init { setEGLContextClientVersion(3);setPreserveEGLContextOnPause(true);setRenderer(renderer3d);renderMode=RENDERMODE_CONTINUOUSLY }
    fun earthRange(km:Double) { focus=1;panX=0.0;panY=0.0;zoom=(5.3*149597870.7/km).coerceIn(.05,1.0e7) }
    override fun onTouchEvent(event:MotionEvent):Boolean {
        pinch.onTouchEvent(event)
        when(event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                activePointers=event.pointerCount
                gestureX=event.x
                gestureY=event.y
                x=event.x; y=event.y
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val px=event.x
                val py=event.y
                val dx=(px-x)/width.coerceAtLeast(1).toDouble()
                val dy=(py-y)/height.coerceAtLeast(1).toDouble()
                if(event.pointerCount>=2 && !pinch.isInProgress) {
                    panX=(panX+dx*2.0).coerceIn(-2.0,2.0)
                    panY=(panY-dy*2.0).coerceIn(-2.0,2.0)
                } else if(event.pointerCount==1 && !pinch.isInProgress) {
                    if(dragToPan) {
                        panX=(panX+dx*2.0).coerceIn(-2.0,2.0)
                        panY=(panY-dy*2.0).coerceIn(-2.0,2.0)
                    } else {
                        yaw=(yaw+dx*240.0)%360.0
                        pitch=(pitch+dy*240.0)%360.0
                    }
                }
                x=px; y=py
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val remaining=if(event.actionIndex==0) 1 else 0
                if(remaining<event.pointerCount) {
                    x=event.getX(remaining)
                    y=event.getY(remaining)
                }
                activePointers=event.pointerCount-1
                return true
            }
            MotionEvent.ACTION_UP -> {performClick();return true}
            MotionEvent.ACTION_CANCEL -> return true
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
    private var vao=0
    private var sphereVbo=0
    private var sphereIbo=0
    private var trailVbo=0
    private lateinit var vertexData:java.nio.FloatBuffer
    private lateinit var indices:java.nio.ShortBuffer
    private val projection=FloatArray(16)
    private val camera=FloatArray(16)
    private val model=FloatArray(16)
    private val tmp=FloatArray(16)
    private val mvp=FloatArray(16)
    private var aspect=1f
    private fun shader(type:Int,code:String):Int {
        val id=GLES30.glCreateShader(type)
        GLES30.glShaderSource(id,code)
        GLES30.glCompileShader(id)
        val ok=IntArray(1)
        GLES30.glGetShaderiv(id,GLES30.GL_COMPILE_STATUS,ok,0)
        check(ok[0]!=0) { GLES30.glGetShaderInfoLog(id) }
        return id
    }
    override fun onSurfaceCreated(gl:GL10?, config:EGLConfig?) {
        bodyTextures.clear()
        GLES30.glClearColor(.012f,.025f,.065f,1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)
        GLES30.glEnable(GLES30.GL_CULL_FACE)
        GLES30.glCullFace(GLES30.GL_BACK)
        val vs=shader(GLES30.GL_VERTEX_SHADER,"""
            #version 300 es
            uniform mat4 uMvp;
            in vec3 aPosition;
            out vec3 vNormal;
            void main() {
                vNormal=normalize(aPosition);
                gl_Position=uMvp*vec4(aPosition,1.0);
            }
        """.trimIndent())
        val fs=shader(GLES30.GL_FRAGMENT_SHADER,"""
            #version 300 es
            precision mediump float;
            uniform vec4 uColor;
            uniform float uLight;
            uniform float uSurface;
            uniform sampler2D uTexture;
            in vec3 vNormal;
            out vec4 fragColor;
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
                    base=texture(uTexture,uv).rgb;
                }
                fragColor=vec4(base*illumination,uColor.a);
            }
        """.trimIndent())
        program=GLES30.glCreateProgram()
        GLES30.glAttachShader(program,vs);GLES30.glAttachShader(program,fs)
        GLES30.glLinkProgram(program)
        val linked=IntArray(1)
        GLES30.glGetProgramiv(program,GLES30.GL_LINK_STATUS,linked,0)
        check(linked[0]!=0) { GLES30.glGetProgramInfoLog(program) }
        GLES30.glDeleteShader(vs);GLES30.glDeleteShader(fs)
        vertices=GLES30.glGetAttribLocation(program,"aPosition")
        mvpHandle=GLES30.glGetUniformLocation(program,"uMvp")
        colorHandle=GLES30.glGetUniformLocation(program,"uColor")
        lightHandle=GLES30.glGetUniformLocation(program,"uLight")
        surfaceHandle=GLES30.glGetUniformLocation(program,"uSurface")
        textureHandle=GLES30.glGetUniformLocation(program,"uTexture")
        val bodies=listOf("Tierra","Mercurio","Venus","Luna","Marte","Júpiter","Saturno","Urano","Neptuno")
        for (name in bodies) {
            val ids=IntArray(1)
            GLES30.glGenTextures(1,ids,0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,ids[0])
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MIN_FILTER,GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_MAG_FILTER,GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_S,GLES30.GL_REPEAT)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D,GLES30.GL_TEXTURE_WRAP_T,GLES30.GL_CLAMP_TO_EDGE)
            val bitmap=if(name=="Tierra") {
                view.context.assets.open("earth_map.png").use { BitmapFactory.decodeStream(it) }
            } else PlanetTextures.create(name)
            requireNotNull(bitmap) { "No se pudo crear la textura de $name" }
            GLUtils.texImage2D(GLES30.GL_TEXTURE_2D,0,bitmap,0)
            bitmap.recycle()
            bodyTextures[name]=ids[0]
        }
        val positions=ArrayList<Float>()
        val ids=ArrayList<Short>()
        val lat=40;val lon=64
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
        val buffers=IntArray(3)
        GLES30.glGenBuffers(3,buffers,0)
        sphereVbo=buffers[0];sphereIbo=buffers[1];trailVbo=buffers[2]
        val arrays=IntArray(1)
        GLES30.glGenVertexArrays(1,arrays,0)
        vao=arrays[0]
        GLES30.glBindVertexArray(vao)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,sphereVbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,positions.size*4,vertexData,GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER,sphereIbo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER,ids.size*2,indices,GLES30.GL_STATIC_DRAW)
        GLES30.glEnableVertexAttribArray(vertices)
        GLES30.glVertexAttribPointer(vertices,3,GLES30.GL_FLOAT,false,12,0)
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0)
    }
    override fun onSurfaceChanged(gl:GL10?,width:Int,height:Int) {
        GLES30.glViewport(0,0,width,height)
        aspect=width.toFloat()/max(height,1)
    }
    override fun onDrawFrame(gl:GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        val s=view.snapshot ?: return
        val center=when(view.focus) { 1 -> s.earth; 2 -> s.asteroid; 3 -> (view.moonPosition ?: (s.earth + OrbitEngine.moonOffset(s.date,view.dayFraction))); in 4..10 -> s.planets.getOrNull(if(view.focus<=5) view.focus-4 else view.focus-3)?.second ?: s.earth; else -> V3(0.0,0.0,0.0) }
        val halfHeight=5.3/view.zoom.coerceIn(0.05,1.0e7)
        Matrix.orthoM(projection,0,(-halfHeight*aspect).toFloat(),(halfHeight*aspect).toFloat(),(-halfHeight).toFloat(),halfHeight.toFloat(),.001f,500f)
        Matrix.setLookAtM(camera,0,0f,0f,40f,0f,0f,0f,0f,1f,0f)
        Matrix.rotateM(camera,0,view.roll.toFloat(),0f,0f,1f)
        Matrix.rotateM(camera,0,view.pitch.toFloat(),1f,0f,0f)
        Matrix.rotateM(camera,0,view.yaw.toFloat(),0f,0f,1f)
        GLES30.glUseProgram(program)
        GLES30.glBindVertexArray(vao)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,bodyTextures.getValue("Tierra"))
        GLES30.glUniform1i(textureHandle,0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,sphereVbo)
        GLES30.glVertexAttribPointer(vertices,3,GLES30.GL_FLOAT,false,12,0)
        fun line(points:List<V3>,color:Int) {
            if(points.size<2) return
            val coords=FloatArray(points.size*3)
            points.forEachIndexed {i,p ->
                val v=p-center
                coords[i*3]=(v.x+view.panX*halfHeight).toFloat()
                coords[i*3+1]=(v.y+view.panY*halfHeight).toFloat()
                coords[i*3+2]=v.z.toFloat()
            }
            val buffer=ByteBuffer.allocateDirect(coords.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            buffer.put(coords).position(0)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,trailVbo)
            GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER,coords.size*4,buffer,GLES30.GL_STREAM_DRAW)
            Matrix.multiplyMM(mvp,0,projection,0,camera,0)
            GLES30.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
            GLES30.glUniform4f(colorHandle,Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,1f)
            GLES30.glUniform1f(lightHandle,1f)
            GLES30.glUniform1f(surfaceHandle,0f)
            GLES30.glVertexAttribPointer(vertices,3,GLES30.GL_FLOAT,false,12,0)
            GLES30.glDrawArrays(GLES30.GL_LINE_STRIP,0,points.size)
            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,sphereVbo)
            GLES30.glVertexAttribPointer(vertices,3,GLES30.GL_FLOAT,false,12,0)
        }
        // Illustrative heliocentric orbital rings, not JPL ephemerides.
        if(view.zoom<5000.0) for((planet,pos) in s.planets) {
            val radius=sqrt(pos.x*pos.x+pos.y*pos.y)
            if(radius>0.0) line((0..128).map {i ->
                val angle=2.0*PI*i/128.0
                V3(radius*cos(angle),radius*sin(angle),0.0)
            },Color.rgb(48,75,105))
        }
        // Geocentric reference curve: every sample comes from the same
        // IMCCE Miriade series. Earth is held at the current UTC instant,
        // explicitly representing an Earth-centered view (not a heliocentric orbit).
        // Never connect historical rendered positions across ephemeris updates.
        val referenceTrail=view.predictedGeocentricTrail
        if(referenceTrail.size>=2) {
            line(referenceTrail.map {s.earth+it},Color.rgb(255,115,65))
        }
        fun draw(p:V3,r:Double,color:Int,emissive:Boolean=false,surface:Float=0f,body:String?=null) {
            val v=p-center
            val sx=v.x+view.panX*halfHeight
            val sy=v.y+view.panY*halfHeight
            val sz=v.z
            if(max(abs(sx),max(abs(sy),abs(sz)))>halfHeight*aspect*1000.0) return
            Matrix.setIdentityM(model,0)
            Matrix.translateM(model,0,sx.toFloat(),sy.toFloat(),sz.toFloat())
            if(body!=null) {
                val days=(java.time.temporal.ChronoUnit.DAYS.between(java.time.LocalDate.of(2000,1,1),s.date).toDouble()+view.dayFraction).toFloat()
                Matrix.rotateM(model,0,(days*360.9856f)%360f,0f,0f,1f)
                Matrix.rotateM(model,0,23.44f,1f,0f,0f)
            }
            Matrix.scaleM(model,0,r.toFloat(),r.toFloat(),r.toFloat())
            Matrix.multiplyMM(tmp,0,camera,0,model,0)
            Matrix.multiplyMM(mvp,0,projection,0,tmp,0)
            GLES30.glUniformMatrix4fv(mvpHandle,1,false,mvp,0)
            GLES30.glUniform4f(colorHandle,Color.red(color)/255f,Color.green(color)/255f,Color.blue(color)/255f,1f)
            GLES30.glUniform1f(lightHandle,if(emissive) 1f else 0f)
            GLES30.glUniform1f(surfaceHandle,if(body!=null) 1f else 0f)
            if(body!=null) GLES30.glBindTexture(GLES30.GL_TEXTURE_2D,bodyTextures.getValue(body))
            indices.position(0)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES,indexCount,GLES30.GL_UNSIGNED_SHORT,0)
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
        draw((view.moonPosition ?: (s.earth+OrbitEngine.moonOffset(s.date,view.dayFraction))),max(1737.4/149597870.7,2.5*pixelRadius),Color.LTGRAY,false,1f,"Luna")
        draw(s.asteroid,max(0.000001,3.5*pixelRadius),Color.rgb(255,80,95))
        GLES30.glBindVertexArray(0)
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER,0)
    }
}
