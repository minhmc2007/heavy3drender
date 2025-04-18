package com.example.heavy3drender

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.MotionEvent
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.nio.ShortBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

class TorusView(context: Context) : GLSurfaceView(context) {
    private val renderer: TorusRenderer

    init {
        setEGLContextClientVersion(3)
        renderer = TorusRenderer()
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    private var previousX: Float = 0f
    private var previousY: Float = 0f
    private val touchScaleFactor = 0.01f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x
        val y = event.y
        when (event.action) {
            MotionEvent.ACTION_MOVE -> {
                val dx = x - previousX
                val dy = y - previousY
                renderer.theta += dx * touchScaleFactor
                renderer.phi += dy * touchScaleFactor
            }
        }
        previousX = x
        previousY = y
        return true
    }
}

class TorusRenderer : GLSurfaceView.Renderer {
    var theta = 0f
    var phi = 0f
    private var program = 0
    private lateinit var vertexBuffers: Array<FloatBuffer>
    private lateinit var indexBuffers: Array<ShortBuffer>
    private val torusCount = 2
    private val vertexCounts = IntArray(torusCount)
    private val indexCounts = IntArray(torusCount)

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        GLES30.glEnable(GLES30.GL_DEPTH_TEST)

        // Shaders
        val vertexShaderCode = """
            #version 300 es
            uniform mat4 uMVPMatrix;
            in vec3 aPosition;
            out vec3 vPosition;
            void main() {
                gl_Position = uMVPMatrix * vec4(aPosition, 1.0);
                vPosition = aPosition;
            }
        """.trimIndent()

        val fragmentShaderCode = """
            #version 300 es
            precision highp float;
            uniform vec3 uLightDir;
            uniform float uTime;
            in vec3 vPosition;
            out vec4 fragColor;

            float fractalNoise(vec3 p, float scale) {
                float n = 0.0;
                float freq = 4.0 * scale;
                float amp = 0.12;
                for (int i = 0; i < 4; i++) {
                    n += amp * sin(freq * p.x + uTime) * cos(freq * p.y) * sin(freq * p.z);
                    freq *= 2.1;
                    amp *= 0.45;
                }
                return n;
            }

            float particleField(vec3 p) {
                float n = fractalNoise(p * 2.0, 1.0);
                return 0.02 / (0.01 + abs(n));
            }

            void main() {
                vec3 pos = vPosition;
                float scale = length(pos) < 2.0 ? 1.0 : 0.8; // Different scales for tori
                vec3 normal = normalize(pos + fractalNoise(pos, scale) * 0.25);
                float diffuse = max(0.0, dot(normal, uLightDir));
                vec3 viewDir = normalize(-pos);
                vec3 halfVector = normalize(uLightDir + viewDir);
                float specular = pow(max(0.0, dot(normal, halfVector)), 96.0);
                float ambient = 0.1;
                float brightness = ambient + diffuse * 0.5 + specular * 0.4;

                // Particle effect
                float particle = particleField(pos);
                brightness += particle * 0.3;

                fragColor = vec4(0.2 * brightness, brightness, 0.7 * brightness, 1.0);
            }
        """.trimIndent()

        // Compile shaders
        val vertexShader = compileShader(GLES30.GL_VERTEX_SHADER, vertexShaderCode)
        val fragmentShader = compileShader(GLES30.GL_FRAGMENT_SHADER, fragmentShaderCode)
        program = GLES30.glCreateProgram()
        GLES30.glAttachShader(program, vertexShader)
        GLES30.glAttachShader(program, fragmentShader)
        GLES30.glLinkProgram(program)

        // Generate two tori
        vertexBuffers = Array(torusCount) { FloatBuffer.allocate(0) }
        indexBuffers = Array(torusCount) { ShortBuffer.allocate(0) }
        val n = 1000
        val m = 1000
        val radii = arrayOf(
            floatArrayOf(2f, 0.5f), // Large torus
            floatArrayOf(1.5f, 0.3f) // Smaller torus
        )

        for (t in 0 until torusCount) {
            val R = radii[t][0]
            val r = radii[t][1]
            val vertices = mutableListOf<Float>()
            val indices = mutableListOf<Short>()

            // Vertices
            for (i in 0 until n) {
                val tAngle = i * 2 * Math.PI / n
                for (j in 0 until m) {
                    val p = j * 2 * Math.PI / m
                    val x = (R + r * kotlin.math.cos(p.toFloat())) * kotlin.math.cos(tAngle.toFloat())
                    val y = (R + r * kotlin.math.cos(p.toFloat())) * kotlin.math.sin(tAngle.toFloat())
                    val z = r * kotlin.math.sin(p.toFloat())
                    vertices.add(x)
                    vertices.add(y)
                    vertices.add(z)
                }
            }

            // Indices
            for (i in 0 until n) {
                for (j in 0 until m) {
                    val v0 = (i * m + j).toShort()
                    val v1 = (i * m + (j + 1) % m).toShort()
                    val v2 = (((i + 1) % n) * m + j).toShort()
                    val v3 = (((i + 1) % n) * m + (j + 1) % m).toShort()
                    indices.add(v0); indices.add(v1); indices.add(v2)
                    indices.add(v1); indices.add(v3); indices.add(v2)
                }
            }

            vertexCounts[t] = vertices.size / 3
            indexCounts[t] = indices.size

            vertexBuffers[t] = ByteBuffer.allocateDirect(vertices.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(vertices.toFloatArray())
                    position(0)
                }

            indexBuffers[t] = ByteBuffer.allocateDirect(indices.size * 2)
                .order(ByteOrder.nativeOrder())
                .asShortBuffer()
                .apply {
                    put(indices.toShortArray())
                    position(0)
                }
        }
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)
        GLES30.glUseProgram(program)

        // MVP matrix
        val mMatrix = FloatArray(16)
        val vMatrix = FloatArray(16)
        val pMatrix = FloatArray(16)
        val mvpMatrix = FloatArray(16)
        android.opengl.Matrix.setIdentityM(mMatrix, 0)
        android.opengl.Matrix.rotateM(mMatrix, 0, theta * 180 / Math.PI.toFloat(), 0f, 1f, 0f)
        android.opengl.Matrix.rotateM(mMatrix, 0, phi * 180 / Math.PI.toFloat(), 1f, 0f, 0f)
        android.opengl.Matrix.setLookAtM(vMatrix, 0, 0f, 0f, -6f, 0f, 0f, 0f, 0f, 1f, 0f)
        android.opengl.Matrix.perspectiveM(pMatrix, 0, 45f, width.toFloat() / height.toFloat(), 0.1f, 100f)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, pMatrix, 0, vMatrix, 0)
        android.opengl.Matrix.multiplyMM(mvpMatrix, 0, mvpMatrix, 0, mMatrix, 0)

        val mvpHandle = GLES30.glGetUniformLocation(program, "uMVPMatrix")
        GLES30.glUniformMatrix4fv(mvpHandle, 1, false, mvpMatrix, 0)

        val lightHandle = GLES30.glGetUniformLocation(program, "uLightDir")
        GLES30.glUniform3f(lightHandle, 0.577f, 0.577f, -0.577f)

        val timeHandle = GLES30.glGetUniformLocation(program, "uTime")
        GLES30.glUniform1f(timeHandle, System.currentTimeMillis() / 1000f)

        // Draw tori
        val positionHandle = GLES30.glGetAttribLocation(program, "aPosition")
        GLES30.glEnableVertexAttribArray(positionHandle)
        for (t in 0 until torusCount) {
            GLES30.glVertexAttribPointer(positionHandle, 3, GLES30.GL_FLOAT, false, 12, vertexBuffers[t])
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCounts[t], GLES30.GL_UNSIGNED_SHORT, indexBuffers[t])
        }
        GLES30.glDisableVertexAttribArray(positionHandle)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES30.glViewport(0, 0, width, height)
        this.width = width
        this.height = height
    }

    private fun compileShader(type: Int, code: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, code)
        GLES30.glCompileShader(shader)
        return shader
    }

    private var width = 0
    private var height = 0
}