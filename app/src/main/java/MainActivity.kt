package com.backrooms.game
// MainActivity.kt - Android client: main menu (Play / Multiplayer / Options + unlocked-levels drawer), saves, pause menu,
// touch controls (look 360° + up/down), HUD
// Touch: left half = floating joystick, right half = drag to look around, RUN button (bottom-right) = toggle walk/run,
// top-centre = pause / options, top-right corner = toggle minimap.
import android.app.Activity
import android.app.AlertDialog
import android.graphics.*
import android.os.Bundle
import android.view.*
import android.widget.EditText
import java.io.File
import kotlin.math.*

class MainActivity : Activity() {
    private var view: GameView? = null

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        Store.init(File(filesDir, "backrooms"))
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
    @Volatile private var scr = Scr.MAIN
    @Volatile private var playing = false // a game session exists (running or paused)
    private var optBack = Scr.MAIN
    private var slot: String? = null      // save slot of the running game (null = not saved yet)
    @Volatile private var running = false
    private var thread: Thread? = null
    private var playT = 0.0; private var fps = 60.0
    @Volatile private var runOn = false   // RUN button: toggles walk <-> run

    // menu state. Buttons register tap rectangles while drawing; the newest (topmost) one that contains the tap wins.
    private var levelsOpen = false; private var levelsAnim = 0f
    @Volatile private var saves: List<SaveData> = emptyList()
    @Volatile private var scrollY = 0f
    @Volatile private var hits: List<Pair<RectF, () -> Unit>> = emptyList()
    private var cur = ArrayList<Pair<RectF, () -> Unit>>()
    @Volatile private var sliderR: RectF? = null; @Volatile private var scrollR: RectF? = null
    private var nextSlider: RectF? = null; private var nextScroll: RectF? = null
    private var menuDown = false; private var sliding = false; private var dragged = false
    private var dX = 0f; private var dY = 0f; private var lastY = 0f

    private val dp = resources.displayMetrics.density
    private val pText = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.DEFAULT_BOLD; setShadowLayer(4f, 2f, 2f, Color.argb(190, 0, 0, 0)) }
    private val pTitle = Paint(Paint.ANTI_ALIAS_FLAG).apply { typeface = Typeface.create(Typeface.SERIF, Typeface.BOLD); textAlign = Paint.Align.CENTER }
    private val pFill = Paint(Paint.ANTI_ALIAS_FLAG)

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

    private fun startGame(addr: String = "", lv: Int = 0, load: SaveData? = null) {
        net?.close()
        net = if (addr.isEmpty()) null else addr.split(":").let { p -> Net(p[0], p.getOrNull(1)?.toIntOrNull() ?: PORT).also { it.start() } }
        if (load != null) g.restore(load) else g.newGame(lv)
        slot = load?.id
        playT = 0.0; jx = 0f; jy = 0f; leftId = -1; rightId = -1; runOn = false; levelsOpen = false
        playing = true; scr = Scr.GAME
    }

    private fun askIp() {
        val et = EditText(act).apply { hint = "192.168.1.5   or   host:port"; setSingleLine() }
        AlertDialog.Builder(act).setTitle("Join server").setView(et)
            .setPositiveButton("Join") { _, _ -> startGame(et.text.toString().trim()) }
            .setNegativeButton("Cancel", null).show()
    }

    private fun openSaves() { saves = Store.list(); scrollY = 0f; levelsOpen = false; scr = Scr.SAVES }
    private fun openOptions(from: Scr) { optBack = from; levelsOpen = false; scr = Scr.OPTIONS }
    private fun closeOptions() { Store.saveProfile(); scr = optBack }
    private fun openPause() { leftId = -1; rightId = -1; jx = 0f; jy = 0f; scr = Scr.PAUSE }

    private fun saveAndQuit() {
        Store.write(g.snapshot(slot ?: System.currentTimeMillis().toString()))
        net?.close(); net = null; slot = null; playing = false; scr = Scr.MAIN
    }

    private fun confirmDelete(s: SaveData) {
        AlertDialog.Builder(act).setTitle("Delete this save?").setMessage("${LEVELS[s.level.coerceIn(0, LEVELS.size - 1)].name}\n${s.info()}")
            .setPositiveButton("Delete") { _, _ -> Store.delete(s.id); saves = Store.list() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Back key: pause / resume / go up one menu level. Returns false when the app should close. */
    fun back(): Boolean {
        when (scr) {
            Scr.GAME -> openPause()
            Scr.PAUSE -> scr = Scr.GAME
            Scr.OPTIONS -> closeOptions()
            Scr.SAVES, Scr.MULTI -> scr = Scr.MAIN
            Scr.MAIN -> if (levelsOpen) levelsOpen = false else return false
        }
        return true
    }

    private fun optHit() = RectF(width / 2f - 28 * dp, 0f, width / 2f + 28 * dp, 46 * dp) // small icon, generous tap area
    private fun runHit() = RectF(width - 84 * dp, height - 84 * dp, width.toFloat(), height.toFloat())

    private fun setFov(x: Float) {
        val sr = sliderR ?: return
        Settings.set(Settings.fromFrac(((x - sr.left) / sr.width()).toDouble()))
    }

    private fun menuTouch(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                menuDown = true; dX = x; dY = y; lastY = y; dragged = false
                val sr = sliderR
                sliding = scr == Scr.OPTIONS && sr != null && RectF(sr.left - 16 * dp, sr.top, sr.right + 16 * dp, sr.bottom).contains(x, y)
                if (sliding) setFov(x)
            }
            MotionEvent.ACTION_MOVE -> {
                if (sliding) setFov(x)
                else {
                    if (hypot(x - dX, y - dY) > 10 * dp) dragged = true
                    if (dragged && scrollR?.contains(dX, dY) == true) scrollY -= y - lastY
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                if (sliding) { sliding = false; Store.saveProfile() }
                else if (menuDown && !dragged) hits.lastOrNull { it.first.contains(x, y) }?.second?.invoke()
                menuDown = false
            }
            MotionEvent.ACTION_CANCEL -> { sliding = false; menuDown = false }
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (scr != Scr.GAME) return menuTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                if (runHit().contains(x, y)) runOn = !runOn
                else if (optHit().contains(x, y)) openPause()
                else if (x > width * 0.78f && y < height * 0.28f) g.showMap = !g.showMap
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
        pText.textAlign = Paint.Align.CENTER; pText.typeface = Typeface.DEFAULT_BOLD; pText.textSize = 15 * dp; pText.letterSpacing = 0.15f
        pText.color = if (filled) Color.rgb(30, 24, 6) else color
        c.drawText(label, rc.centerX(), rc.centerY() + 5 * dp, pText)
    }

    private fun btn(c: Canvas, rc: RectF, label: String, filled: Boolean, color: Int, f: () -> Unit) { button(c, rc, label, filled, color); hit(rc, f) }
    private fun hit(rc: RectF, f: () -> Unit) { cur.add(rc to f) }
    private fun hitClip(rc: RectF, area: RectF, f: () -> Unit) { val v = RectF(rc); if (v.intersect(area)) hit(v, f) }

    private fun dim(c: Canvas, a1: Int, a2: Int) {
        pFill.style = Paint.Style.FILL
        pFill.shader = LinearGradient(0f, 0f, 0f, c.height.toFloat(), Color.argb(a1, 0, 0, 0), Color.argb(a2, 0, 0, 0), Shader.TileMode.CLAMP)
        c.drawRect(0f, 0f, c.width.toFloat(), c.height.toFloat(), pFill); pFill.shader = null
    }

    private fun heading(c: Canvas, s: String, col: Int, y: Float) {
        pTitle.color = col; pTitle.textSize = c.height / 9f; pTitle.setShadowLayer(c.height / 60f, 0f, 0f, Color.argb(200, Color.red(col), Color.green(col), Color.blue(col)))
        c.drawText(s, c.width / 2f, y, pTitle)
    }

    private fun small(c: Canvas, s: String, x: Float, y: Float, size: Float, col: Int, align: Paint.Align = Paint.Align.CENTER, spacing: Float = 0.05f, bold: Boolean = false) {
        pText.textAlign = align; pText.typeface = if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
        pText.textSize = size * dp; pText.letterSpacing = spacing; pText.color = col
        c.drawText(s, x, y, pText)
    }

    /** One level / save card: accent border, label, name and a second line. */
    private fun card(c: Canvas, rc: RectF, d: LevelDef, line2: String, line2Col: Int) {
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(175, 0, 0, 0); c.drawRoundRect(rc, 10 * dp, 10 * dp, pFill)
        pFill.shader = LinearGradient(0f, rc.top, 0f, rc.bottom, acc(d, 70), acc(d, 0), Shader.TileMode.CLAMP)
        c.drawRoundRect(rc, 10 * dp, 10 * dp, pFill); pFill.shader = null
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = acc(d); c.drawRoundRect(rc, 10 * dp, 10 * dp, pFill)
        val tx = rc.left + 12 * dp
        small(c, d.label, tx, rc.top + 15 * dp, 8.5f, acc(d), Paint.Align.LEFT, 0.2f, true)
        small(c, d.name, tx, rc.top + 31 * dp, 12f, Color.WHITE, Paint.Align.LEFT, 0.02f, true)
        small(c, line2, tx, rc.bottom - 8 * dp, 8.5f, line2Col, Paint.Align.LEFT, 0.06f, true)
    }

    private fun drawMain(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(LEVELS[0])
        dim(c, 210, 170)
        heading(c, "THE BACKROOMS", a, h * 0.19f)
        small(c, "POCKET EDITION  ·  MULTIPLAYER", w / 2, h * 0.26f, 11f, Color.rgb(220, 212, 185), spacing = 0.3f, bold = true)
        val bw = min(w * 0.34f, 300 * dp); val bh = 42 * dp; val gap = 10 * dp; var y = h * 0.33f
        btn(c, RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh), "PLAY", true, a) { openSaves() }; y += bh + gap
        btn(c, RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh), "MULTIPLAYER", false, a) { levelsOpen = false; askIp() }; y += bh + gap
        btn(c, RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh), "OPTIONS", false, a) { openOptions(Scr.MAIN) }
        small(c, "Level 0 is endless and empty. Some walls aren't solid — push into one to noclip out.", w / 2, h * 0.86f, 11f, Color.rgb(210, 200, 170))
        small(c, "Left: move  ·  Right: look around  ·  RUN button: walk / run  ·  Back: pause", w / 2, h - 14 * dp, 10f, Color.rgb(200, 195, 160))
        drawLevels(c)
    }

    /** Side drawer with the unlocked levels; the arrow tab on the left edge opens it. */
    private fun drawLevels(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(LEVELS[0])
        val pw = min(w * 0.42f, 260 * dp); val left = -pw + pw * levelsAnim
        if (levelsAnim > 0.01f) {
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb((130 * levelsAnim).toInt(), 0, 0, 0); c.drawRect(0f, 0f, w, h, pFill)
            hit(RectF(0f, 0f, w, h)) { levelsOpen = false }
            val body = RectF(left, 0f, left + pw, h)
            pFill.color = Color.argb(240, 14, 13, 10); c.drawRect(body, pFill)
            pFill.color = Color.argb(120, 244, 228, 130); c.drawRect(left + pw - 2 * dp, 0f, left + pw, h, pFill)
            hit(body) {}
            val ul = LEVELS.indices.filter { Progress.found[it] }
            small(c, "UNLOCKED LEVELS", left + 14 * dp, 26 * dp, 10f, a, Paint.Align.LEFT, 0.25f, true)
            small(c, "${ul.size} / ${LEVELS.size}  ·  tap to play", left + 14 * dp, 41 * dp, 8.5f, Color.rgb(160, 155, 130), Paint.Align.LEFT)
            val lt = 52 * dp; val list = RectF(left, lt, left + pw - 2 * dp, h - 6 * dp)
            val rh = 52 * dp; val gp = 6 * dp
            scrollY = scrollY.coerceIn(0f, max(0f, ul.size * (rh + gp) - list.height()))
            nextScroll = list
            c.save(); c.clipRect(list)
            ul.forEachIndexed { k, i ->
                val d = LEVELS[i]; val top = lt + k * (rh + gp) - scrollY
                val rc = RectF(left + 10 * dp, top, left + pw - 14 * dp, top + rh)
                if (rc.bottom < list.top || rc.top > list.bottom) return@forEachIndexed
                card(c, rc, d, if (d.entities == "NONE") "SAFE" else d.entities, if (d.entities == "NONE") Color.rgb(150, 200, 150) else Color.rgb(235, 110, 90))
                pFill.style = Paint.Style.FILL; pFill.color = acc(d) // play triangle
                val tx = rc.right - 20 * dp; val ty = rc.centerY()
                c.drawPath(Path().apply { moveTo(tx, ty - 8 * dp); lineTo(tx + 12 * dp, ty); lineTo(tx, ty + 8 * dp); close() }, pFill)
                hitClip(rc, list) { startGame(lv = i) }
            }
            c.restore()
        }
        val tab = RectF(left + pw - 8 * dp, h / 2 - 36 * dp, left + pw + 26 * dp, h / 2 + 36 * dp)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(215, 14, 13, 10); c.drawRoundRect(tab, 10 * dp, 10 * dp, pFill)
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 1.5f * dp; pFill.color = acc(LEVELS[0], 150); c.drawRoundRect(tab, 10 * dp, 10 * dp, pFill)
        val dir = 1f - 2f * levelsAnim; val cx = tab.right - 13 * dp; val cy = tab.centerY()
        pFill.strokeWidth = 2.5f * dp; pFill.strokeCap = Paint.Cap.ROUND; pFill.strokeJoin = Paint.Join.ROUND; pFill.color = a
        c.drawPath(Path().apply { moveTo(cx - 3 * dp * dir, cy - 8 * dp); lineTo(cx + 3 * dp * dir, cy); lineTo(cx - 3 * dp * dir, cy + 8 * dp) }, pFill)
        pFill.strokeCap = Paint.Cap.BUTT; pFill.strokeJoin = Paint.Join.MITER
        hit(RectF(left + pw - 4 * dp, h / 2 - 48 * dp, left + pw + 36 * dp, h / 2 + 48 * dp)) { levelsOpen = !levelsOpen }
    }

    private fun drawSaves(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(LEVELS[0])
        dim(c, 225, 200)
        heading(c, "SELECT SAVE", a, h * 0.15f)
        val lw = min(w * 0.7f, 520 * dp); val lx = (w - lw) / 2; val top = h * 0.22f
        val list = RectF(lx, top, lx + lw, h - 62 * dp)
        val rhNew = 46 * dp; val rh = 56 * dp; val gp = 8 * dp
        scrollY = scrollY.coerceIn(0f, max(0f, rhNew + gp + (if (saves.isEmpty()) 30 * dp else saves.size * (rh + gp)) - list.height()))
        nextScroll = list
        c.save(); c.clipRect(list)
        var y = top - scrollY
        val nr = RectF(lx, y, lx + lw, y + rhNew); button(c, nr, "NEW GAME", true, a); hitClip(nr, list) { startGame() }
        y += rhNew + gp
        if (saves.isEmpty()) small(c, "No saves yet  ·  use  Save & Quit  in the pause menu", w / 2, y + 16 * dp, 10.5f, Color.rgb(170, 165, 140))
        for (s in saves) {
            val d = LEVELS[s.level.coerceIn(0, LEVELS.size - 1)]; val rc = RectF(lx, y, lx + lw, y + rh)
            if (rc.bottom >= list.top && rc.top <= list.bottom) {
                card(c, rc, d, s.info(), Color.rgb(200, 195, 165))
                hitClip(rc, list) { startGame(load = s) }
                val x0 = RectF(rc.right - 46 * dp, rc.centerY() - 18 * dp, rc.right - 10 * dp, rc.centerY() + 18 * dp) // delete
                pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 1.5f * dp; pFill.color = Color.argb(160, 235, 110, 90); c.drawRoundRect(x0, 8 * dp, 8 * dp, pFill)
                pFill.strokeWidth = 2 * dp; pFill.strokeCap = Paint.Cap.ROUND
                c.drawLine(x0.centerX() - 6 * dp, x0.centerY() - 6 * dp, x0.centerX() + 6 * dp, x0.centerY() + 6 * dp, pFill)
                c.drawLine(x0.centerX() + 6 * dp, x0.centerY() - 6 * dp, x0.centerX() - 6 * dp, x0.centerY() + 6 * dp, pFill)
                pFill.strokeCap = Paint.Cap.BUTT
                hitClip(x0, list) { confirmDelete(s) }
            }
            y += rh + gp
        }
        c.restore()
        btn(c, RectF(w / 2 - 70 * dp, h - 52 * dp, w / 2 + 70 * dp, h - 12 * dp), "BACK", false, a) { scr = Scr.MAIN }
    }

    private fun drawOptions(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(LEVELS[0])
        dim(c, 220, 200)
        heading(c, "OPTIONS", a, h * 0.18f)
        small(c, "FIELD OF VIEW", w / 2, h * 0.36f, 11f, Color.rgb(220, 212, 185), spacing = 0.3f, bold = true)
        small(c, Settings.name(), w / 2, h * 0.36f + 28 * dp, 18f, Color.WHITE, spacing = 0.05f, bold = true)
        val tl = w * 0.2f; val tr = w * 0.8f; val ty = h * 0.60f
        nextSlider = RectF(tl, ty - 18 * dp, tr, ty + 18 * dp)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(110, 255, 255, 255); c.drawRoundRect(RectF(tl, ty - 2 * dp, tr, ty + 2 * dp), 2 * dp, 2 * dp, pFill)
        val kx = tl + (tr - tl) * Settings.frac().toFloat()
        pFill.color = a; c.drawRoundRect(RectF(tl, ty - 2 * dp, kx, ty + 2 * dp), 2 * dp, 2 * dp, pFill)
        for ((f, nm) in Settings.PRESETS) {
            val x = tl + (tr - tl) * Settings.frac(f).toFloat()
            pFill.color = Color.argb(160, 255, 255, 255); c.drawRect(x - 1 * dp, ty - 8 * dp, x + 1 * dp, ty + 8 * dp, pFill)
            small(c, "$nm ($f)", x, ty + 28 * dp, 10f, if (Settings.fov == f) a else Color.rgb(190, 185, 160), bold = Settings.fov == f)
        }
        pFill.color = Color.WHITE; c.drawCircle(kx, ty, 11 * dp, pFill)
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 3 * dp; pFill.color = a; c.drawCircle(kx, ty, 11 * dp, pFill)
        btn(c, RectF(w / 2 - 70 * dp, h - 52 * dp, w / 2 + 70 * dp, h - 12 * dp), "BACK", false, a) { closeOptions() }
    }

    private fun drawPause(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val d = LEVELS[g.level]; val a = acc(d)
        dim(c, 190, 190)
        heading(c, "PAUSED", a, h * 0.21f)
        small(c, "${d.label}  ·  ${d.name}", w / 2, h * 0.28f, 11f, Color.rgb(220, 212, 185), spacing = 0.2f, bold = true)
        val bw = min(w * 0.34f, 300 * dp); val bh = 42 * dp; val gap = 10 * dp; var y = h * 0.35f
        btn(c, RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh), "RESUME", true, a) { scr = Scr.GAME }; y += bh + gap
        btn(c, RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh), "OPTIONS", false, a) { openOptions(Scr.PAUSE) }; y += bh + gap
        btn(c, RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh), "SAVE & QUIT", false, Color.rgb(235, 110, 90)) { saveAndQuit() }
    }

    /** In-game buttons: small options icon (top-centre) and the walk / run toggle (bottom-right). */
    private fun drawControls(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(LEVELS[g.level])
        val ob = RectF(w / 2 - 15 * dp, 8 * dp, w / 2 + 15 * dp, 28 * dp)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(80, 0, 0, 0); c.drawRoundRect(ob, 7 * dp, 7 * dp, pFill)
        pFill.color = Color.argb(190, 255, 255, 255)
        for (k in -1..1) c.drawRect(ob.centerX() - 7 * dp, ob.centerY() + k * 5 * dp - 1 * dp, ob.centerX() + 7 * dp, ob.centerY() + k * 5 * dp + 1 * dp, pFill)
        val cx = w - 20 * dp - 28 * dp; val cy = h - 20 * dp - 28 * dp
        pFill.color = if (runOn) Color.argb(200, Color.red(a), Color.green(a), Color.blue(a)) else Color.argb(110, 0, 0, 0)
        c.drawCircle(cx, cy, 28 * dp, pFill)
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = Color.argb(180, 255, 255, 255); c.drawCircle(cx, cy, 28 * dp, pFill)
        small(c, if (runOn) "RUN" else "WALK", cx, cy + 4 * dp, 11f, if (runOn) Color.rgb(30, 24, 6) else Color.WHITE, spacing = 0.1f, bold = true)
    }

    private fun drawHud(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val d = LEVELS[g.level]; val a = acc(d)
        // badge
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(120, 0, 0, 0)
        c.drawRoundRect(RectF(12 * dp, 12 * dp, 230 * dp, 76 * dp), 10 * dp, 10 * dp, pFill)
        pFill.color = a; c.drawRoundRect(RectF(12 * dp, 12 * dp, 16 * dp, 76 * dp), 3 * dp, 3 * dp, pFill)
        pText.textAlign = Paint.Align.LEFT; pText.letterSpacing = 0.15f; pText.typeface = Typeface.DEFAULT_BOLD
        pText.textSize = 12 * dp; pText.color = a; c.drawText("${d.label} · ${d.name}", 26 * dp, 32 * dp, pText)
        val n = net
        val status = when {
            n == null -> "OFFLINE · SOLO"; n.state == 0 -> "CONNECTING…"
            n.state == 1 -> "ONLINE · ${n.others.size + 1} PLAYERS${if (g.isolated()) " · ISOLATED" else ""}"; else -> "CONNECTION LOST"
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
            c.drawText("${d.label}", w / 2, h * 0.42f, pTitle)
            pText.letterSpacing = 0.35f; pText.textSize = 15 * dp; pText.color = Color.argb((al * 255).toInt(), 240, 235, 215)
            c.drawText(d.name, w / 2, h * 0.52f, pText)
            pText.letterSpacing = 0.05f; pText.textSize = 11 * dp; pText.color = Color.argb((al * 255).toInt(), 215, 208, 185)
            c.drawText(d.blurb, w / 2, h * 0.59f, pText)
        }
        if (g.manilaT in 0.01..4.5) {
            val al = min(1.0, min(g.manilaT / 0.6, (4.5 - g.manilaT) / 1.0)).coerceIn(0.0, 1.0)
            pTitle.color = Color.argb((al * 255).toInt(), 232, 206, 150); pTitle.textSize = h / 14f; pTitle.setShadowLayer(8f, 2f, 2f, Color.argb((al * 180).toInt(), 0, 0, 0))
            c.drawText("THE MANILA ROOM", w / 2, h * 0.26f, pTitle)
            pText.textAlign = Paint.Align.CENTER; pText.letterSpacing = 0.1f; pText.textSize = 12 * dp; pText.color = Color.argb((al * 255).toInt(), 235, 225, 200)
            c.drawText("ISOLATION LIFTED · you are not alone here", w / 2, h * 0.33f, pText)
        }
        if (g.manilaNear > 0.05) {
            pText.textAlign = Paint.Align.CENTER; pText.letterSpacing = 0.05f; pText.textSize = 12 * dp; pText.color = Color.argb((g.manilaNear * 220).toInt(), 232, 206, 150)
            c.drawText("…the air smells of old paper", w / 2, h - 50 * dp, pText)
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
        drawControls(c)
    }

    override fun run() {
        var last = System.nanoTime(); var t = 0.0; var lastSend = 0L
        while (running) {
            val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now; t += dt
            if (dt > 0) fps = fps * 0.9 + (1 / dt) * 0.1
            levelsAnim += ((if (levelsOpen && scr == Scr.MAIN) 1f else 0f) - levelsAnim) * min(1f, dt.toFloat() * 14f)
            if (!playing) {
                menuG.a += dt * 0.12; menuG.bob += dt * 2.0; menuG.bobAmp = 0.5
                r.render(px, menuG, t, emptyList(), false)
            } else {
                val n = net
                if (scr == Scr.GAME) { // pausing freezes the game; the last frame stays on screen
                    playT += dt
                    if (g.exhausted) runOn = false
                    g.update(dt, -jy.toDouble(), jx.toDouble(), 0.0, runOn)
                    if (n != null && now / 1_000_000 - lastSend > 50) { n.send(g.x, g.y, g.level); lastSend = now / 1_000_000 }
                }
                r.render(px, g, t, n?.others?.values ?: emptyList(), scr == Scr.GAME)
            }
            bmp.setPixels(px, 0, r.W, 0, 0, r.W, r.H)
            val c = holder.lockCanvas() ?: continue
            try {
                c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), bmpPaint)
                cur = ArrayList(); nextSlider = null; nextScroll = null
                when (scr) {
                    Scr.GAME -> drawHud(c)
                    Scr.PAUSE -> drawPause(c)
                    Scr.SAVES -> drawSaves(c)
                    Scr.OPTIONS -> drawOptions(c)
                    Scr.MAIN -> drawMain(c)
                    else -> {}
                }
                hits = cur; sliderR = nextSlider; scrollR = nextScroll
            } finally { holder.unlockCanvasAndPost(c) }
        }
    }
}
