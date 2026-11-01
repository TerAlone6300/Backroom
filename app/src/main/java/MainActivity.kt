package com.backrooms.game
// MainActivity.kt - Android client: animated menu, touch controls, HUD
// Touch: left half = floating joystick (push to the edge to run), right half = drag to look,
// tap the top-right corner to toggle the minimap.
import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.EditText
import kotlin.math.*

class MainActivity : Activity() {
    private var view: GameView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = GameView(this)
        setContentView(view)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onBackPressed() { if (view?.back() != true) super.onBackPressed() }
}

class GameView(private val act: Activity) : SurfaceView(act), SurfaceHolder.Callback, Runnable {
    private val r = Renderer()
    private val px = IntArray(r.W * r.H)
    private val bmp = Bitmap.createBitmap(r.W, r.H, Bitmap.Config.ARGB_8888)
    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    private val g = Game(); private val menuG = Game().also { it.x = 3.5; it.y = 3.5 }
    private var net: Net? = null
    @Volatile private var inMenu = true
    @Volatile private var running = false
    private var thread: Thread? = null
    private var playT = 0.0; private var fps = 60.0

    private val dp = resources.displayMetrics.density
    private val gold = Color.rgb(244, 228, 130)
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; typeface = Typeface.DEFAULT_BOLD; setShadowLayer(4f, 2f, 2f, Color.argb(190, 0, 0, 0)) }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = gold; typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playRect = RectF(); private val joinRect = RectF()

    // touch state
    @Volatile private var jx = 0f; @Volatile private var jy = 0f
    private var leftId = -1; private var rightId = -1
    private var lx0 = 0f; private var ly0 = 0f; private var rx0 = 0f
    private val jr get() = 55 * dp

    init { holder.addCallback(this) }

    override fun surfaceCreated(h: SurfaceHolder) { running = true; thread = Thread(this).also { it.start() } }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { running = false; thread?.join() }

    private fun startGame(addr: String) {
        net?.close()
        net = if (addr.isEmpty()) null else addr.split(":").let { p -> Net(p[0], p.getOrNull(1)?.toIntOrNull() ?: PORT).also { it.start() } }
        g.reset(); playT = 0.0; jx = 0f; jy = 0f; inMenu = false
    }

    private fun askIp() {
        val et = EditText(act).apply { hint = "192.168.1.5   or   host:port"; setSingleLine() }
        AlertDialog.Builder(act).setTitle("Join server").setView(et)
            .setPositiveButton("Join") { _, _ -> startGame(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    /** Returns true if it handled the back press (left the game to the menu). */
    fun back(): Boolean {
        if (inMenu) return false
        net?.close(); net = null; inMenu = true; return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (inMenu) {
            if (e.actionMasked == MotionEvent.ACTION_UP) {
                if (playRect.contains(e.x, e.y)) startGame("") else if (joinRect.contains(e.x, e.y)) askIp()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i)
                if (e.getX(i) > width * 0.78f && e.getY(i) < height * 0.28f) g.showMap = !g.showMap
                else if (e.getX(i) < width / 2f) { if (leftId < 0) { leftId = id; lx0 = e.getX(i); ly0 = e.getY(i) } }
                else if (rightId < 0) { rightId = id; rx0 = e.getX(i) }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id == leftId) {
                    var dx = (e.getX(i) - lx0) / jr; var dy = (e.getY(i) - ly0) / jr
                    val m = hypot(dx, dy); if (m > 1f) { dx /= m; dy /= m }
                    jx = dx; jy = dy
                } else if (id == rightId) { g.a += (e.getX(i) - rx0) * 0.006; rx0 = e.getX(i) }
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

    private fun button(c: Canvas, rc: RectF, label: String, filled: Boolean) {
        pFill.style = Paint.Style.FILL; pFill.color = if (filled) gold else Color.argb(150, 0, 0, 0)
        c.drawRoundRect(rc, 16 * dp, 16 * dp, pFill)
        if (!filled) { pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = gold; c.drawRoundRect(rc, 16 * dp, 16 * dp, pFill) }
        pText.textAlign = Paint.Align.CENTER; pText.textSize = 18 * dp; pText.letterSpacing = 0.15f
        pText.color = if (filled) Color.rgb(40, 32, 8) else gold
        c.drawText(label, rc.centerX(), rc.centerY() + 6 * dp, pText)
        pText.color = gold
    }

    private fun drawMenu(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        c.drawColor(Color.argb(120, 0, 0, 0))
        pTitle.textSize = h / 7f; pTitle.setShadowLayer(h / 50f, 0f, 0f, Color.argb(200, 244, 228, 130))
        c.drawText("THE BACKROOMS", w / 2, h * 0.32f, pTitle)
        pText.textAlign = Paint.Align.CENTER; pText.textSize = 13 * dp; pText.letterSpacing = 0.3f; pText.color = Color.rgb(230, 220, 190)
        c.drawText("LEVEL 0  ·  THE LOBBY", w / 2, h * 0.41f, pText)
        pText.color = gold
        val bw = min(w * 0.4f, 280 * dp); val bh = 48 * dp
        playRect.set(w / 2 - bw / 2, h * 0.52f, w / 2 + bw / 2, h * 0.52f + bh)
        joinRect.set(playRect.left, playRect.bottom + 14 * dp, playRect.right, playRect.bottom + 14 * dp + bh)
        button(c, playRect, "PLAY SOLO", true); button(c, joinRect, "JOIN SERVER", false)
        pText.textSize = 11 * dp; pText.letterSpacing = 0.05f; pText.color = Color.rgb(200, 195, 160)
        c.drawText("Left: move  ·  Right: look  ·  Push far to run  ·  Back: menu", w / 2, h - 18 * dp, pText)
        pText.color = gold
    }

    private fun drawHud(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat()
        pText.textAlign = Paint.Align.LEFT; pText.letterSpacing = 0.2f
        pText.textSize = 13 * dp; c.drawText("LEVEL 0", 16 * dp, 28 * dp, pText)
        val n = net
        val status = when {
            n == null -> "OFFLINE · SOLO"; n.state == 0 -> "CONNECTING…"
            n.state == 1 -> "ONLINE · ${n.others.size + 1} PLAYERS"; else -> "CONNECTION LOST"
        }
        pText.textSize = 11 * dp; pText.color = Color.rgb(225, 215, 180)
        c.drawText(status, 16 * dp, 46 * dp, pText)
        pText.color = Color.rgb(160, 155, 130); c.drawText("${fps.toInt()} FPS", 16 * dp, 62 * dp, pText)
        pText.color = gold

        if (leftId >= 0) { // floating joystick
            pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = Color.argb(110, 255, 255, 255)
            c.drawCircle(lx0, ly0, jr, pFill)
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb(150, 244, 228, 130)
            c.drawCircle(lx0 + jx * jr, ly0 + jy * jr, jr * 0.38f, pFill)
        }
        if (playT < 9) {
            val al = (((9 - playT) / 2).coerceIn(0.0, 1.0) * 255).toInt()
            pText.textAlign = Paint.Align.CENTER; pText.letterSpacing = 0.05f; pText.setShadowLayer(4f, 2f, 2f, Color.argb(al, 0, 0, 0))
            pText.color = Color.argb(al, 235, 225, 180); pText.textSize = 13 * dp
            c.drawText("Left thumb: move   ·   Right thumb: look", w / 2, h - 28 * dp, pText)
            pText.color = gold; pText.setShadowLayer(4f, 2f, 2f, Color.argb(190, 0, 0, 0))
        }
    }

    override fun run() {
        var last = System.nanoTime(); var t = 0.0; var lastSend = 0L
        while (running) {
            val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now; t += dt
            if (dt > 0) fps = fps * 0.9 + (1 / dt) * 0.1
            if (inMenu) {
                menuG.a += dt * 0.12; menuG.bob += dt * 2.0; menuG.bobAmp = 0.5
                r.render(px, menuG, t, emptyList(), false)
            } else {
                playT += dt
                g.update(dt, -jy.toDouble(), jx.toDouble(), 0.0, hypot(jx, jy) > 0.9f)
                val n = net
                if (n != null && now / 1_000_000 - lastSend > 50) { n.send(g.x, g.y); lastSend = now / 1_000_000 }
                r.render(px, g, t, n?.others?.values ?: emptyList())
            }
            bmp.setPixels(px, 0, r.W, 0, 0, r.W, r.H)
            val c = holder.lockCanvas() ?: continue
            try {
                c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), bmpPaint)
                if (inMenu) drawMenu(c) else drawHud(c)
            } finally { holder.unlockCanvasAndPost(c) }
        }
    }
}
