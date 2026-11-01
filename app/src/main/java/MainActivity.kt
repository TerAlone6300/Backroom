package com.backrooms.game
// MainActivity.kt - Android client. Add Core.kt to the same module.
// Touch: left half = virtual joystick (move/strafe), right half = drag to look.
import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.EditText
import kotlin.math.*

class MainActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val input = EditText(this).apply { hint = "Server IP (empty = solo)" }
        AlertDialog.Builder(this).setTitle("Backrooms").setView(input).setCancelable(false)
            .setPositiveButton("Play") { _, _ ->
                val host = input.text.toString().trim()
                val net = if (host.isEmpty()) null else Net(host).also { it.start() }
                setContentView(GameView(this, net))
            }.show()
    }
}

class GameView(ctx: Context, private val net: Net?) : SurfaceView(ctx), SurfaceHolder.Callback, Runnable {
    private val r = Renderer()
    private val px = IntArray(r.W * r.H)
    private val bmp = Bitmap.createBitmap(r.W, r.H, Bitmap.Config.ARGB_8888)
    private val paint = Paint().apply { isFilterBitmap = false } // chunky retro pixels
    @Volatile private var running = false
    private var thread: Thread? = null

    private var x = 1.5 + Math.random() * 4; private var y = 1.5 + Math.random() * 4
    @Volatile private var a = 0.0
    @Volatile private var jx = 0f; @Volatile private var jy = 0f
    private var leftId = -1; private var rightId = -1
    private var lx0 = 0f; private var ly0 = 0f; private var rx0 = 0f

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder) { running = true; thread = Thread(this).also { it.start() } }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { running = false; thread?.join() }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i)
                if (e.getX(i) < width / 2f) { if (leftId < 0) { leftId = id; lx0 = e.getX(i); ly0 = e.getY(i) } }
                else if (rightId < 0) { rightId = id; rx0 = e.getX(i) }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id == leftId) {
                    jx = ((e.getX(i) - lx0) / 120f).coerceIn(-1f, 1f)
                    jy = ((e.getY(i) - ly0) / 120f).coerceIn(-1f, 1f)
                } else if (id == rightId) { a += (e.getX(i) - rx0) * 0.006; rx0 = e.getX(i) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val id = e.getPointerId(e.actionIndex)
                if (id == leftId) { leftId = -1; jx = 0f; jy = 0f }
                if (id == rightId) rightId = -1
            }
            MotionEvent.ACTION_CANCEL -> { leftId = -1; rightId = -1; jx = 0f; jy = 0f }
        }
        return true
    }

    override fun run() {
        var last = System.nanoTime(); var t = 0.0; var lastSend = 0L
        while (running) {
            val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now; t += dt
            val fwd = -jy.toDouble(); val str = jx.toDouble()
            val mx = cos(a) * fwd - sin(a) * str; val my = sin(a) * fwd + cos(a) * str
            move(x, y, mx, my, 2.6 * dt).let { x = it.first; y = it.second }

            if (net != null && now / 1_000_000 - lastSend > 50) { net.send(x, y); lastSend = now / 1_000_000 }
            r.render(px, x, y, a, t, net?.others?.values ?: emptyList())
            bmp.setPixels(px, 0, r.W, 0, 0, r.W, r.H)

            val c = holder.lockCanvas() ?: continue
            try { c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), paint) } finally { holder.unlockCanvasAndPost(c) }
        }
    }
}
