package com.backrooms.game
// GpuFrameView.kt - the surface the OpenGL ES / Vulkan frame is drawn on. Android only.
//
// It sits under GameView (a transparent overlay that keeps drawing the menus and HUD with Canvas), because a Surface
// cannot be drawn with Canvas and with EGL/Vulkan at the same time. Everything about the presenter happens on the
// render thread (GameView.run): an EGL context belongs to its thread. The UI thread only hands over the Surface.

import android.content.Context
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.util.concurrent.locks.ReentrantLock

class GpuFrameView(ctx: Context) : SurfaceView(ctx), SurfaceHolder.Callback {
    private val lock = ReentrantLock()
    private val released = lock.newCondition()
    private var surf: Surface? = null
    private var viewW = 0; private var viewH = 0
    private var presenter: FramePresenter? = null
    private var presenterKind = R_CANVAS
    private var presenterSurf: Surface? = null
    private var presenterW = 0; private var presenterH = 0   // frame size the presenter was built for
    private var lastKind = -1
    private val failed = arrayOfNulls<String>(3)   // why a backend was given up on this session (null = not failed)
    @Volatile private var active: String? = null   // name of the running backend, for the Options screen

    init { holder.addCallback(this) }

    /**
     * Render thread, once per frame (present / presentScene). true = the frame is now on this surface; false = the caller must draw it with Canvas
     * (Canvas selected, surface not ready, or the backend failed: the reason is kept for statusLine()).
     */
    fun present(px: IntArray, w: Int, h: Int, kind: Int): Boolean = use(w, h, kind) { it.present(px, viewW, viewH) }

    /** Like present(), but the GPU draws the picture from the scene data. */
    fun presentScene(scene: SceneData, w: Int, h: Int, kind: Int): Boolean = use(w, h, kind) { it.presentScene(scene, viewW, viewH) }

    /** false once a backend has been given up on this session: the caller should not bother building a scene for it. */
    fun usable(kind: Int) = kind != R_CANVAS && failed[kind] == null

    private inline fun use(w: Int, h: Int, kind: Int, draw: (FramePresenter) -> Boolean): Boolean {
        lock.lock()
        try {
            if (kind != lastKind) { lastKind = kind; failed[kind] = null }  // picking a backend again retries it
            val s = surf
            if (kind == R_CANVAS || s == null || !s.isValid || viewW <= 0 || viewH <= 0) { drop(); return false }
            if (presenter != null && (presenterKind != kind || presenterSurf !== s || presenterW != w || presenterH != h)) drop()
            if (failed[kind] != null) return false

            var p = presenter
            if (p == null) {
                p = try { if (kind == R_VULKAN) VulkanPresenter.create(s, w, h) else GlesPresenter(s, w, h, context.assets) }
                    catch (t: Throwable) {   // includes UnsatisfiedLinkError when the native library is missing
                        failed[kind] = t.message ?: t.javaClass.simpleName
                        Log.w(TAG, "${RENDERER_NAMES[kind]} unavailable: ${failed[kind]}")
                        return false
                    }
                presenter = p; presenterKind = kind; presenterSurf = s; presenterW = w; presenterH = h; active = p.name
                Log.i(TAG, "Using ${p.name}")
            }
            if (!draw(p)) {
                failed[kind] = "${p.name} stopped working"
                Log.w(TAG, failed[kind]!!)
                drop()
                return false
            }
            return true
        } finally { lock.unlock() }
    }

    /** One line for the Options screen about the selected backend. UI thread. */
    fun statusLine(kind: Int): String {
        failed[kind]?.let { return "${RENDERER_NAMES[kind]} unavailable: $it. Using Canvas." }
        return when (kind) {
            R_CANVAS -> "CPU frame scaled by Android. Works everywhere."
            else -> active?.let { "Active: $it" } ?: "Starting ${RENDERER_NAMES[kind]}..."
        }
    }

    private fun drop() {  // lock held
        presenter?.release()
        presenter = null; presenterSurf = null; active = null
        released.signalAll()
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        lock.lock(); try { surf = holder.surface } finally { lock.unlock() }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        lock.lock(); try { viewW = width; viewH = height } finally { lock.unlock() }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        lock.lock()
        try {
            surf = null
            // The render thread owns the presenter: its next present() sees surf == null and releases it. Give it a moment.
            var ns = 200_000_000L
            while (presenter != null && ns > 0) ns = released.awaitNanos(ns)
            if (presenter != null) drop()   // render thread stopped or stuck: release from here
        } finally { lock.unlock() }
    }

    private companion object { const val TAG = "Backrooms" }
}
