package com.backrooms.game
// MainActivity.kt - Android client: level select menu, touch controls (look 360° + up/down), HUD
// Touch: left half = floating joystick (push to the edge to run), right half = drag to look around,
// top-right corner = toggle minimap, top-left badge = switch level.
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
    private val g = Game(); private val menuG = Game()
    private var net: Net? = null
    @Volatile private var inMenu = true
    @Volatile private var running = false
    private var thread: Thread? = null
    private var playT = 0.0; private var fps = 60.0
    private val cardRects = Array(3) { RectF() }

    private val dp = resources.displayMetrics.density
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; setShadowLayer(4f, 2f, 2f, Color.argb(190, 0, 0, 0)) }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playRect = RectF(); private val joinRect = RectF()

    // touch state
    @Volatile private var jx = 0f; @Volatile private var jy = 0f
    private var leftId = -1; private var rightId = -1
    private var lx0 = 0f; private var ly0 = 0f; private var rx0 = 0f; private var ry0 = 0f
    private val jr get() = 55 * dp

    init { holder.addCallback(this) }

    private fun acc(d: LevelDef, alpha: Int = 255) = Color.argb(alpha, (d.accent shr 16) and 255, (d.accent shr 8) and 255, d.accent and 255)

    override fun surfaceCreated(h: SurfaceHolder) { running = true; thread = Thread(this).also { it.start() } }
    override fun surfaceChanged(h: SurfaceHolder, f: Int, w: Int, hh: Int) {}
    override fun surfaceDestroyed(h: SurfaceHolder) { running = false; thread?.join() }

    private fun startGame(addr: String) {
        net?.close()
        net = if (addr.isEmpty()) null else addr.split(":").let { p -> Net(p[0], p.getOrNull(1)?.toIntOrNull() ?: PORT).also { it.start() } }
        g.enter(0); playT = 0.0; jx = 0f; jy = 0f; inMenu = false
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
                if (playRect.contains(e.x, e.y)) startGame("")
                else if (joinRect.contains(e.x, e.y)) askIp()
            }
            return true
        }
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                if (x > width * 0.78f && y < height * 0.28f) g.showMap = !g.showMap
                else if (x < width / 2f) { if (leftId < 0) { leftId = id; lx0 = x; ly0 = y } }
                else if (rightId < 0) { rightId = id; rx0 = x; ry0 = y }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until e.pointerCount) {
                val id = e.getPointerId(i)
                if (id == leftId) {
                    var dx = (e.getX(i) - lx0) / jr; var dy = (e.getY(i) - ly0) / jr
                    val m = hypot(dx, dy); if (m > 1f) { dx /= m; dy /= m }
                    jx = dx; jy = dy
                } else if (id == rightId) {
                    g.a += (e.getX(i) - rx0) * 0.006
                    g.pitch = (g.pitch - (e.getY(i) - ry0) * 0.004).coerceIn(-1.0, 1.0)
                    rx0 = e.getX(i); ry0 = e.getY(i)
                }
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

    private fun wrap(s: String, p: Paint, maxW: Float): List<String> {
        val out = ArrayList<String>(); var line = ""
        for (w in s.split(" ")) {
            val t = if (line.isEmpty()) w else "$line $w"
            if (p.measureText(t) > maxW && line.isNotEmpty()) { out.add(line); line = w } else line = t
        }
        if (line.isNotEmpty()) out.add(line)
        return out
    }

    private fun button(c: Canvas, rc: RectF, label: String, filled: Boolean, color: Int) {
        pFill.style = Paint.Style.FILL; pFill.color = if (filled) color else Color.argb(150, 0, 0, 0)
        c.drawRoundRect(rc, 16 * dp, 16 * dp, pFill)
        if (!filled) { pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = color; c.drawRoundRect(rc, 16 * dp, 16 * dp, pFill) }
        pText.textAlign = Paint.Align.CENTER; pText.textSize = 16 * dp; pText.letterSpacing = 0.15f
        pText.color = if (filled) Color.rgb(30, 24, 6) else color
        c.drawText(label, rc.centerX(), rc.centerY() + 6 * dp, pText)
    }

    private fun drawMenu(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val d0 = LEVELS[0]; val a = acc(d0)
        pFill.style = Paint.Style.FILL
        pFill.shader = LinearGradient(0f, 0f, 0f, h, Color.argb(210, 0, 0, 0), Color.argb(170, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, w, h, pFill); pFill.shader = null

        pTitle.color = a; pTitle.textSize = h / 9f; pTitle.setShadowLayer(h / 60f, 0f, 0f, acc(d0, 200))
        c.drawText("THE BACKROOMS", w / 2, h * 0.16f, pTitle)
        pText.textAlign = Paint.Align.CENTER; pText.textSize = 11 * dp; pText.letterSpacing = 0.3f; pText.color = Color.rgb(220, 212, 185)
        c.drawText("POCKET EDITION  ·  MULTIPLAYER", w / 2, h * 0.23f, pText)

        val cw = w * 0.26f; val gap = w * 0.03f; val x0 = (w - (3 * cw + 2 * gap)) / 2; val top = h * 0.29f; val ch = h * 0.40f
        for (i in 0..2) {
            val d = LEVELS[i]; val on = Progress.found[i]; val rc = cardRects[i]
            rc.set(x0 + i * (cw + gap), top, x0 + i * (cw + gap) + cw, top + ch)
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb(if (on) 175 else 130, 0, 0, 0); c.drawRoundRect(rc, 14 * dp, 14 * dp, pFill)
            pFill.shader = LinearGradient(0f, rc.top, 0f, rc.bottom, acc(d, if (on) 70 else 25), acc(d, 0), Shader.TileMode.CLAMP)
            c.drawRoundRect(rc, 14 * dp, 14 * dp, pFill); pFill.shader = null
            pFill.style = Paint.Style.STROKE; pFill.strokeWidth = (if (on) 3 else 1.5f) * dp; pFill.color = acc(d, if (on) 255 else 110)
            c.drawRoundRect(rc, 14 * dp, 14 * dp, pFill)

            val tx = rc.left + 12 * dp
            pText.textAlign = Paint.Align.LEFT; pText.letterSpacing = 0.25f; pText.textSize = 10 * dp; pText.color = acc(d)
            c.drawText("LEVEL ${d.id}", tx, rc.top + 22 * dp, pText)
            pText.letterSpacing = 0.05f; pText.textSize = 15 * dp; pText.color = Color.WHITE
            c.drawText(if (on) d.name else "???", tx, rc.top + 44 * dp, pText)
            pText.typeface = Typeface.DEFAULT; pText.textSize = 10.5f * dp; pText.color = Color.rgb(215, 208, 185)
            var ly = rc.top + 62 * dp
            for (line in wrap(if (on) d.blurb else "Not discovered yet.", pText, rc.width() - 24 * dp)) { c.drawText(line, tx, ly, pText); ly += 13 * dp }
            pText.typeface = Typeface.DEFAULT_BOLD; pText.letterSpacing = 0.15f; pText.textSize = 9.5f * dp
            pText.color = if (d.entities == "NONE") Color.rgb(150, 200, 150) else Color.rgb(235, 110, 90)
            c.drawText("ENTITIES · ${if (on) d.entities else "???"}", tx, rc.bottom - 12 * dp, pText)
        }

        pText.textAlign = Paint.Align.CENTER; pText.typeface = Typeface.DEFAULT; pText.textSize = 11 * dp; pText.letterSpacing = 0.05f
        pText.color = Color.rgb(210, 200, 170)
        c.drawText("Level 0 is endless and empty. Some walls aren't solid — push into one to noclip out.", w / 2, h * 0.755f, pText)
        pText.typeface = Typeface.DEFAULT_BOLD
        val bw = w * 0.22f; val bh = 46 * dp; val by = h * 0.80f
        playRect.set(w / 2 - bw - 8 * dp, by, w / 2 - 8 * dp, by + bh)
        joinRect.set(w / 2 + 8 * dp, by, w / 2 + bw + 8 * dp, by + bh)
        button(c, playRect, "NOCLIP IN", true, a); button(c, joinRect, "JOIN SERVER", false, a)
        pText.textSize = 10 * dp; pText.letterSpacing = 0.05f; pText.color = Color.rgb(200, 195, 160)
        c.drawText("Left: move  ·  Right: look around  ·  Push far to run  ·  Back: menu", w / 2, h - 14 * dp, pText)
    }

    private fun drawHud(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val d = LEVELS[g.level]; val a = acc(d)
        // badge
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(120, 0, 0, 0)
        c.drawRoundRect(RectF(12 * dp, 12 * dp, 230 * dp, 76 * dp), 10 * dp, 10 * dp, pFill)
        pFill.color = a; c.drawRoundRect(RectF(12 * dp, 12 * dp, 16 * dp, 76 * dp), 3 * dp, 3 * dp, pFill)
        pText.textAlign = Paint.Align.LEFT; pText.letterSpacing = 0.15f; pText.typeface = Typeface.DEFAULT_BOLD
        pText.textSize = 12 * dp; pText.color = a; c.drawText("LEVEL ${d.id} · ${d.name}", 26 * dp, 32 * dp, pText)
        val n = net
        val status = when {
            n == null -> "OFFLINE · SOLO"; n.state == 0 -> "CONNECTING…"
            n.state == 1 -> "ONLINE · ${n.others.size + 1} PLAYERS${if (g.level == 0) " · ISOLATED" else ""}"; else -> "CONNECTION LOST"
        }
        pText.textSize = 10.5f * dp; pText.color = Color.rgb(225, 215, 180); c.drawText(status, 26 * dp, 50 * dp, pText)
        pText.textSize = 9.5f * dp; pText.color = Color.rgb(160, 155, 130); c.drawText("${fps.toInt()} FPS", 26 * dp, 66 * dp, pText)

        if (leftId >= 0) { // floating joystick
            pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = Color.argb(110, 255, 255, 255)
            c.drawCircle(lx0, ly0, jr, pFill)
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb(150, 244, 228, 130)
            c.drawCircle(lx0 + jx * jr, ly0 + jy * jr, jr * 0.38f, pFill)
        }
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(120, 255, 255, 255); c.drawCircle(w / 2, h / 2, 2 * dp, pFill) // crosshair dot

        if (g.levelT < 4.5) { // level title card
            val al = min(1.0, min(g.levelT / 0.8, (4.5 - g.levelT) / 1.0)).coerceIn(0.0, 1.0)
            pText.textAlign = Paint.Align.CENTER
            pText.letterSpacing = 0.3f; pText.textSize = 11 * dp; pText.color = Color.argb((al * 255).toInt(), 220, 215, 195)
            c.drawText(g.arrival, w / 2, h * 0.31f, pText)
            pTitle.color = acc(d, (al * 255).toInt()); pTitle.textSize = h / 10f; pTitle.setShadowLayer(8f, 2f, 2f, Color.argb((al * 180).toInt(), 0, 0, 0))
            c.drawText("LEVEL ${d.id}", w / 2, h * 0.42f, pTitle)
            pText.letterSpacing = 0.35f; pText.textSize = 15 * dp; pText.color = Color.argb((al * 255).toInt(), 240, 235, 215)
            c.drawText(d.name, w / 2, h * 0.52f, pText)
        }
        if (g.noclip > 0.03) {
            val bw0 = w / 6; pFill.style = Paint.Style.FILL
            pFill.color = Color.argb(140, 0, 0, 0); c.drawRect(w / 2 - bw0 / 2, h / 2 + 14 * dp, w / 2 + bw0 / 2, h / 2 + 19 * dp, pFill)
            pFill.color = Color.argb(230, 120, 230, 255); c.drawRect(w / 2 - bw0 / 2, h / 2 + 14 * dp, w / 2 - bw0 / 2 + bw0 * g.noclip.toFloat(), h / 2 + 19 * dp, pFill)
            pText.textAlign = Paint.Align.CENTER; pText.letterSpacing = 0.3f; pText.textSize = 14 * dp; pText.color = Color.rgb(150, 235, 255)
            c.drawText(if ((System.currentTimeMillis() / 90) % 3 == 0L) "N0CL1PP1NG…" else "NOCLIPPING…", w / 2, h / 2 + 42 * dp, pText)
        }
        if (g.dead) {
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb(min(170, (g.deadT * 130).toInt()), 120, 0, 0)
            c.drawRect(0f, 0f, w, h, pFill)
            if (g.deadT > 0.6) {
                pTitle.color = Color.rgb(235, 70, 55); pTitle.textSize = h / 8f; pTitle.setShadowLayer(8f, 2f, 2f, Color.BLACK)
                c.drawText("YOU WERE CAUGHT", w / 2, h * 0.50f, pTitle)
                pText.textAlign = Paint.Align.CENTER; pText.letterSpacing = 0.1f; pText.textSize = 13 * dp; pText.color = Color.rgb(230, 200, 190)
                c.drawText("respawning…", w / 2, h * 0.60f, pText)
            }
        }
        if (playT < 9 && g.levelT > 4.5) {
            val al = (((9 - playT) / 2).coerceIn(0.0, 1.0) * 255).toInt()
            pText.textAlign = Paint.Align.CENTER; pText.letterSpacing = 0.05f; pText.textSize = 12 * dp; pText.color = Color.argb(al, 235, 225, 180)
            c.drawText("Shimmering walls aren't solid  ·  push into one to noclip", w / 2, h - 26 * dp, pText)
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
                if (n != null && now / 1_000_000 - lastSend > 50) { n.send(g.x, g.y, g.level); lastSend = now / 1_000_000 }
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
