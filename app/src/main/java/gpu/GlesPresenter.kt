package com.backrooms.game
// GlesPresenter.kt - OpenGL ES 3.0 backend. Android only.
// One shader (assets/shaders/scene.glsl, shared with Vulkan) draws either the 3D view from the scene data (presentScene) or a
// frame the CPU drew (present). The GL calls are the ones tools/scene-test/gles-scene-test.cpp makes, which checks them
// against the CPU renderer. Create, present and release must all run on the same thread (an EGL context is bound to it).

import android.content.res.AssetManager
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.opengl.GLES30
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder

class GlesPresenter(private val surface: Surface, private val w: Int, private val h: Int, private val assets: AssetManager) : FramePresenter {
    override var name = "OpenGL ES"; private set
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private var program = 0
    private val tex = IntArray(3)   // 0 scene data, 1 level textures, 2 the CPU's frame
    private var texSerial = -1      // the level textures in the GPU are those of this SceneData.texSerial
    private val dataBuf = direct(Scene.DATA_W * Scene.DATA_H * 16)
    private val texBuf = direct(Scene.TEX_LAYERS * Scene.TEX_SIZE * Scene.TEX_SIZE * 4)
    private val frameBuf = direct(w * h * 4)
    private val viewBuf = direct(16)
    private val dataF = dataBuf.asFloatBuffer(); private val texI = texBuf.asIntBuffer()   // views: no new objects per frame
    private val frameI = frameBuf.asIntBuffer(); private val viewF = viewBuf.asFloatBuffer()

    init {
        try { setup() } catch (e: Throwable) { release(); throw e }
    }

    private fun setup() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
        val ver = IntArray(2)
        check(EGL14.eglInitialize(display, ver, 0, ver, 1)) { "eglInitialize failed" }

        val attribs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGLExt.EGL_OPENGL_ES3_BIT_KHR, EGL14.EGL_NONE
        )
        val configs = arrayOfNulls<EGLConfig>(1)
        val num = IntArray(1)
        EGL14.eglChooseConfig(display, attribs, 0, configs, 0, 1, num, 0)
        val config = configs[0] ?: error("No EGLConfig for OpenGL ES 3.0")

        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE), 0)
        check(context != EGL14.EGL_NO_CONTEXT) { "OpenGL ES 3.0 is not available" }
        eglSurface = EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        check(eglSurface != EGL14.EGL_NO_SURFACE) { "eglCreateWindowSurface failed" }
        check(EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context)) { "eglMakeCurrent failed" }
        // VSync: 1 = sync to display, 0 = uncapped (driver may still pace). Applied at create; toggle forces a new presenter.
        EGL14.eglSwapInterval(display, if (Settings.vsync) 1 else 0)

        val scene = assets.open("shaders/scene.glsl").bufferedReader().use { it.readText() }
        program = link(VERT, FRAG_PREFIX + scene)
        GLES20.glUseProgram(program)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uData"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTex"), 1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uFrame"), 2)

        GLES20.glGenTextures(3, tex, 0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA32F, Scene.DATA_W, Scene.DATA_H, 0, GLES20.GL_RGBA, GLES20.GL_FLOAT, null)
        params(GLES20.GL_TEXTURE_2D, GLES20.GL_NEAREST, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex[1])
        GLES30.glTexImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, GLES30.GL_RGBA8, Scene.TEX_SIZE, Scene.TEX_SIZE, Scene.TEX_LAYERS, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        params(GLES30.GL_TEXTURE_2D_ARRAY, GLES20.GL_NEAREST, GLES20.GL_REPEAT)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[2])
        GLES30.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        params(GLES20.GL_TEXTURE_2D, GLES20.GL_LINEAR, GLES20.GL_CLAMP_TO_EDGE)   // a CPU frame is scaled smoothly
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 4)
        val mode = if (Settings.vsync) "VSync" else "uncapped"
        name = "OpenGL ES (${GLES20.glGetString(GLES20.GL_RENDERER) ?: "?"}, $mode)"
    }

    override fun presentScene(scene: SceneData, viewW: Int, viewH: Int): Boolean {
        if (display == EGL14.EGL_NO_DISPLAY) return false
        scene.put(Scene.VIEW, viewW.toDouble(), viewH.toDouble(), 0.0, 0.0)   // view size, mode 0 = scene
        dataF.clear(); dataF.put(scene.f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, Scene.DATA_W, Scene.DATA_H, GLES20.GL_RGBA, GLES20.GL_FLOAT, dataBuf)
        if (scene.texSerial != texSerial) {
            texI.clear(); texI.put(scene.tex)
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1); GLES20.glBindTexture(GLES30.GL_TEXTURE_2D_ARRAY, tex[1])
            GLES30.glTexSubImage3D(GLES30.GL_TEXTURE_2D_ARRAY, 0, 0, 0, 0, Scene.TEX_SIZE, Scene.TEX_SIZE, Scene.TEX_LAYERS, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, texBuf)
            texSerial = scene.texSerial
        }
        return draw(viewW, viewH)
    }

    override fun present(px: IntArray, viewW: Int, viewH: Int): Boolean {
        if (display == EGL14.EGL_NO_DISPLAY) return false
        // 0xAARRGGBB ints are B,G,R,A in memory (little endian); the shader swaps R and B back, so no per-pixel work here.
        frameI.clear(); frameI.put(px, 0, w * h)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE2); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[2])
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, w, h, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, frameBuf)
        // mode 1: of the scene data, the shader only reads the texel with the view size and the mode
        viewF.clear(); viewF.put(viewW.toFloat()); viewF.put(viewH.toFloat()); viewF.put(1f); viewF.put(0f)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0])
        GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, Scene.VIEW, 0, 1, 1, GLES20.GL_RGBA, GLES20.GL_FLOAT, viewBuf)
        return draw(viewW, viewH)
    }

    private fun draw(viewW: Int, viewH: Int): Boolean {
        GLES20.glViewport(0, 0, viewW, viewH)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 3)   // one triangle covers the screen; the shader does the rest
        return EGL14.eglSwapBuffers(display, eglSurface)
    }

    override fun release() {
        if (display == EGL14.EGL_NO_DISPLAY) return
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
        if (eglSurface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, eglSurface)
        if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
        EGL14.eglTerminate(display)
        display = EGL14.EGL_NO_DISPLAY
        eglSurface = EGL14.EGL_NO_SURFACE
        context = EGL14.EGL_NO_CONTEXT
    }

    private fun link(vs: String, fs: String): Int {
        val v = shader(GLES20.GL_VERTEX_SHADER, vs)
        val f = shader(GLES20.GL_FRAGMENT_SHADER, fs)
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, v)
        GLES20.glAttachShader(p, f)
        GLES20.glLinkProgram(p)
        val ok = IntArray(1)
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        check(ok[0] != 0) { "Program link failed: ${GLES20.glGetProgramInfoLog(p)}" }
        return p
    }

    private fun shader(type: Int, src: String): Int {
        val s = GLES20.glCreateShader(type)
        GLES20.glShaderSource(s, src)
        GLES20.glCompileShader(s)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
        check(ok[0] != 0) { "Shader compile failed: ${GLES20.glGetShaderInfoLog(s)}" }
        return s
    }

    private fun params(target: Int, filter: Int, wrap: Int) {
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, filter); GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, filter)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, wrap); GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, wrap)
    }

    private companion object {
        fun direct(bytes: Int): ByteBuffer = ByteBuffer.allocateDirect(bytes).order(ByteOrder.nativeOrder())

        const val VERT = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""
        const val FRAG_PREFIX = "#version 300 es\nprecision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler2DArray;\n#define GLES 1\n"
    }
}
