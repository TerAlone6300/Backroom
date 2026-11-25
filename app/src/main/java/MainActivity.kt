package com.backrooms.game
// MainActivity.kt - Android client: main menu (Play / Multiplayer / Options + unlocked-levels drawer), saves, pause menu,
// touch controls (look 360° + up/down), HUD
// Touch: left half = floating joystick, right half = drag to look around, RUN button (bottom-right) = toggle walk/run,
// top-centre = pause / options, top-right corner = toggle minimap.
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.provider.OpenableColumns
import android.graphics.*
import android.os.Build
import android.os.Bundle
import android.view.*
import android.widget.EditText
import android.widget.FrameLayout
import java.io.File
import kotlin.math.*

class MainActivity : Activity() {
    private var view: GameView? = null
    private lateinit var updates: UpdateHelper

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        SkinLibrary.init(listOfNotNull(getExternalFilesDir("skins"), File(filesDir, "backrooms/skins"))) // external skin files: .png, .brpack, .mcpack
        Store.init(File(filesDir, "backrooms"))
        updates = UpdateHelper(this); updates.loadVersion()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val frame = GpuFrameView(this)   // OpenGL ES / Vulkan surface; GameView is a transparent overlay on top of it
        val game = GameView(this, frame)
        view = game
        setContentView(FrameLayout(this).apply { addView(frame); addView(game) })
        updates.check() // every launch: is there a newer release?
    }

    override fun onResume() { super.onResume(); updates.onResume() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
    }

    override fun onBackPressed() { if (view?.back() != true) super.onBackPressed() }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == GameView.REQ_SKIN && resultCode == RESULT_OK && data != null) view?.importSkins(data)
    }
}

class GameView(private val act: Activity, private val gpu: GpuFrameView) : SurfaceView(act), SurfaceHolder.Callback, Runnable {
    companion object { const val REQ_SKIN = 4711 } // file picker request code (skin import)
    private var r = Renderer(Settings.frameW, Settings.frameH)  // replaced (on the render thread) when the frame size changes
    private var px = IntArray(r.W * r.H)
    private var bmp = Bitmap.createBitmap(r.W, r.H, Bitmap.Config.ARGB_8888)
    private val scene = SceneData()   // what the GPU renderer draws from, rebuilt every frame
    private val bmpPaint = Paint().apply { isFilterBitmap = true }
    private val g = Game(); private val menuG = Game()
    private var net: Net? = null
    @Volatile private var scr = Scr.MAIN
    @Volatile private var playing = false // a game session exists (running or paused)
    private var optBack = Scr.MAIN
    private var optPage = 0 // 0 = Options, 1 = its Graphics page
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
    @Volatile private var fovR: RectF? = null; @Volatile private var fogR: RectF? = null; @Volatile private var scrollR: RectF? = null
    private var nextFov: RectF? = null; private var nextFog: RectF? = null; private var nextScroll: RectF? = null
    private var menuDown = false; private var slideId = 0 /* 0 none, 1 FOV, 2 fog */; private var dragged = false
    private var hw = Build.VERSION.SDK_INT >= 23 // draw on a hardware canvas: the GPU scales the frame instead of the CPU
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

    init {
        holder.addCallback(this)
        setZOrderMediaOverlay(true); holder.setFormat(PixelFormat.TRANSLUCENT) // transparent: the GPU frame shows through underneath
        gpu.visibility = if (Settings.renderer == R_CANVAS) View.GONE else View.VISIBLE
    }

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
            .setPositiveButton("Join") { _, _ -> openPicker(0, et.text.toString().trim(), Scr.MAIN) }
            .setNegativeButton("Cancel", null).show()
    }

    // starting a game asks for its difficulty first: the difficulty belongs to the game (and its save), not to the profile
    private var pendLv = 0; private var pendAddr = ""; private var pickBack = Scr.MAIN
    private fun openPicker(lv: Int, addr: String, from: Scr) { pendLv = lv; pendAddr = addr; pickBack = from; levelsOpen = false; scr = Scr.DIFFICULTY }

    private fun openSaves() { saves = Store.list(); scrollY = 0f; levelsOpen = false; scr = Scr.SAVES }

    // Dressing Room: pick a skin from the built-in default and the external files (.png, .brpack / .mcpack skin packs)
    private var drBack = Scr.MAIN; @Volatile private var drMsg = ""
    private fun openDressing(from: Scr) { SkinLibrary.reload(); drBack = from; drMsg = ""; scrollY = 0f; levelsOpen = false; scr = Scr.DRESSING }

    private fun pickSkinFiles() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*").putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
        try { act.startActivityForResult(i, REQ_SKIN) } catch (_: Exception) { drMsg = "No file picker found" }
    }

    /** The file picker returned: copy the chosen .png / .brpack / .mcpack files into the skins folder. */
    fun importSkins(data: Intent) {
        val uris = ArrayList<android.net.Uri>()
        data.data?.let { uris.add(it) }
        data.clipData?.let { cd -> for (k in 0 until cd.itemCount) uris.add(cd.getItemAt(k).uri) }
        Thread {
            var last: ImportResult? = null
            for (u in uris) runCatching {
                val name = act.contentResolver.query(u, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cu -> if (cu.moveToFirst()) cu.getString(0) else null } ?: "skin.png"
                act.contentResolver.openInputStream(u)?.use { SkinLibrary.import(name, it)?.let { r -> last = r } }
            }
            val r = last
            drMsg = if (r == null) "Nothing imported: not a valid skin or skin pack" else if (r.kind == "pack") Tf("Imported pack %s (%d skin(s))", r.label, r.skins) else Tf("External skin: %s", r.label)
        }.start()
    }

    private val pvPaint = Paint()
    private var pvBmp: Bitmap? = null; private var pvPx = IntArray(0)
    /** Rotating preview of a skin (default: the selected one) inside [rc]; transparent around the figure. */
    private fun drawSkinPreview(c: Canvas, rc: RectF, e: SkinEntry = SkinLibrary.selected()) {
        val ph = min(rc.height().toInt(), 240).coerceAtLeast(8); val pw = max(8, (rc.width() * ph / rc.height()).toInt())
        if (pvPx.size != pw * ph) pvPx = IntArray(pw * ph)
        var b = pvBmp; if (b == null || b.width != pw || b.height != ph) { b = Bitmap.createBitmap(pw, ph, Bitmap.Config.ARGB_8888); pvBmp = b }
        SkinPreview.render(e.texture, pw, ph, System.nanoTime() / 1e9, pvPx)
        b.setPixels(pvPx, 0, pw, 0, 0, pw, ph)
        c.drawBitmap(b, null, rc, pvPaint)
    }
    private fun openOptions(from: Scr) { optBack = from; optPage = 0; levelsOpen = false; scr = Scr.OPTIONS }
    private fun closeOptions() { Store.saveProfile(); scr = optBack }
    private fun setRenderer(k: Int) { Settings.renderer = k; Store.saveProfile(); gpu.post { gpu.visibility = if (k == R_CANVAS) View.GONE else View.VISIBLE } }
    private fun openPause() { leftId = -1; rightId = -1; jx = 0f; jy = 0f; scr = Scr.PAUSE }

    private fun saveAndQuit() {
        Store.write(g.snapshot(slot ?: System.currentTimeMillis().toString()))
        net?.close(); net = null; slot = null; playing = false; scr = Scr.MAIN
    }

    private fun confirmDelete(s: SaveData) {
        AlertDialog.Builder(act).setTitle("Delete this save?").setMessage("${levelDef(s.level).name}\n${s.info()}")
            .setPositiveButton("Delete") { _, _ -> Store.delete(s.id); saves = Store.list() }
            .setNegativeButton("Cancel", null).show()
    }

    /** Back key: pause / resume / go up one menu level. Returns false when the app should close. */
    fun back(): Boolean {
        when (scr) {
            Scr.GAME -> openPause()
            Scr.PAUSE -> scr = Scr.GAME
            Scr.OPTIONS -> if (optPage == 1) optPage = 0 else closeOptions()
            Scr.SAVES, Scr.MULTI -> scr = Scr.MAIN
            Scr.DRESSING -> scr = drBack
            Scr.DIFFICULTY -> scr = pickBack
            Scr.MAIN -> if (levelsOpen) levelsOpen = false else return false
        }
        return true
    }

    private fun optHit() = RectF(width / 2f - 28 * dp, 0f, width / 2f + 28 * dp, 46 * dp) // small icon, generous tap area
    private fun runHit() = RectF(width - 84 * dp, height - 84 * dp, width.toFloat(), height.toFloat())
    private fun eatHit() = RectF(width - 138 * dp, height - 66 * dp, width - 86 * dp, height - 14 * dp)
    private fun drinkHit() = RectF(width - 194 * dp, height - 66 * dp, width - 142 * dp, height - 14 * dp)

    private fun setSlider(id: Int, x: Float) {
        val sr = (if (id == 1) fovR else fogR) ?: return
        val t = ((x - sr.left) / sr.width()).toDouble()
        if (id == 1) Settings.set(Settings.fromFrac(t)) else Settings.putFog(Settings.fogFromFrac(t))
    }

    private fun menuTouch(e: MotionEvent): Boolean {
        val x = e.x; val y = e.y
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                menuDown = true; dX = x; dY = y; lastY = y; dragged = false
                fun near(sr: RectF?) = sr != null && RectF(sr.left - 16 * dp, sr.top, sr.right + 16 * dp, sr.bottom).contains(x, y)
                slideId = if (scr != Scr.OPTIONS) 0 else if (near(fovR)) 1 else if (near(fogR)) 2 else 0
                if (slideId != 0) setSlider(slideId, x)
            }
            MotionEvent.ACTION_MOVE -> {
                if (slideId != 0) setSlider(slideId, x)
                else {
                    if (hypot(x - dX, y - dY) > 10 * dp) dragged = true
                    if (dragged && scrollR?.contains(dX, dY) == true) scrollY -= y - lastY
                }
                lastY = y
            }
            MotionEvent.ACTION_UP -> {
                if (slideId != 0) { slideId = 0; Store.saveProfile() }
                else if (menuDown && !dragged) hits.lastOrNull { it.first.contains(x, y) }?.second?.invoke()
                menuDown = false
            }
            MotionEvent.ACTION_CANCEL -> { slideId = 0; menuDown = false }
        }
        return true
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (scr != Scr.GAME) return menuTouch(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val i = e.actionIndex; val id = e.getPointerId(i); val x = e.getX(i); val y = e.getY(i)
                if (runHit().contains(x, y)) runOn = !runOn
                else if (!Settings.explore && eatHit().contains(x, y)) g.eatReq = true
                else if (!Settings.explore && drinkHit().contains(x, y)) g.drinkReq = true
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
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(levelDef(0))
        dim(c, 210, 170)
        heading(c, "THE BACKROOMS", a, h * 0.19f)
        small(c, "POCKET EDITION  ·  MULTIPLAYER", w / 2, h * 0.26f, 11f, Color.rgb(220, 212, 185), spacing = 0.3f, bold = true)
        val bw = min(w * 0.34f, 300 * dp); val bh = 38 * dp; val gap = 8 * dp; var y = h * 0.32f
        btn(c, RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh), "PLAY", true, a) { openSaves() }; y += bh + gap
        btn(c, RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh), "MULTIPLAYER", false, a) { levelsOpen = false; askIp() }; y += bh + gap
        btn(c, RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh), "OPTIONS", false, a) { openOptions(Scr.MAIN) }; y += bh + gap
        val dr = RectF(w / 2 - bw / 2, y, w / 2 + bw / 2, y + bh)
        btn(c, dr, "", false, a) { openDressing(Scr.MAIN) }
        small(c, "DRESSING ROOM", dr.left + bh + 14 * dp, dr.top + bh * 0.46f, 10.5f, a, Paint.Align.LEFT, 0.2f, true)
        small(c, SkinLibrary.selected().name, dr.left + bh + 14 * dp, dr.top + bh * 0.82f, 8.5f, Color.rgb(220, 212, 185), Paint.Align.LEFT)
        drawSkinPreview(c, RectF(dr.left + 6 * dp, dr.top - bh / 2, dr.left + 6 * dp + bh * 0.75f + 10 * dp, dr.bottom - 2 * dp)) // shows the skin you wear, popping out above the button
        small(c, "Level 0 is endless and empty. Some walls aren't solid — push into one to noclip out.", w / 2, h * 0.86f, 11f, Color.rgb(210, 200, 170))
        small(c, "Left: move  ·  Right: look around  ·  RUN button: walk / run  ·  Back: pause", w / 2, h - 14 * dp, 10f, Color.rgb(200, 195, 160))
        drawLevels(c)
    }

    /** Side drawer with the unlocked levels; the arrow tab on the left edge opens it. */
    private fun drawLevels(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(levelDef(0))
        val pw = min(w * 0.42f, 260 * dp); val left = -pw + pw * levelsAnim
        if (levelsAnim > 0.01f) {
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb((130 * levelsAnim).toInt(), 0, 0, 0); c.drawRect(0f, 0f, w, h, pFill)
            hit(RectF(0f, 0f, w, h)) { levelsOpen = false }
            val body = RectF(left, 0f, left + pw, h)
            pFill.color = Color.argb(240, 14, 13, 10); c.drawRect(body, pFill)
            pFill.color = Color.argb(120, 244, 228, 130); c.drawRect(left + pw - 2 * dp, 0f, left + pw, h, pFill)
            hit(body) {}
            val ul = LEVELS.filter { Progress.has(it.id) }
            small(c, "UNLOCKED LEVELS", left + 14 * dp, 26 * dp, 10f, a, Paint.Align.LEFT, 0.25f, true)
            small(c, "${ul.size} / ${LEVELS.size}  ·  tap to play", left + 14 * dp, 41 * dp, 8.5f, Color.rgb(160, 155, 130), Paint.Align.LEFT)
            val lt = 52 * dp; val list = RectF(left, lt, left + pw - 2 * dp, h - 6 * dp)
            val rh = 52 * dp; val gp = 6 * dp
            scrollY = scrollY.coerceIn(0f, max(0f, ul.size * (rh + gp) - list.height()))
            nextScroll = list
            c.save(); c.clipRect(list)
            ul.forEachIndexed { k, d ->
                val top = lt + k * (rh + gp) - scrollY
                val rc = RectF(left + 10 * dp, top, left + pw - 14 * dp, top + rh)
                if (rc.bottom < list.top || rc.top > list.bottom) return@forEachIndexed
                card(c, rc, d, if (d.entities == "NONE") "SAFE" else d.entities, if (d.entities == "NONE") Color.rgb(150, 200, 150) else Color.rgb(235, 110, 90))
                pFill.style = Paint.Style.FILL; pFill.color = acc(d) // play triangle
                val tx = rc.right - 20 * dp; val ty = rc.centerY()
                c.drawPath(Path().apply { moveTo(tx, ty - 8 * dp); lineTo(tx + 12 * dp, ty); lineTo(tx, ty + 8 * dp); close() }, pFill)
                hitClip(rc, list) { openPicker(d.id, "", Scr.MAIN) }
            }
            c.restore()
        }
        val tab = RectF(left + pw - 8 * dp, h / 2 - 36 * dp, left + pw + 26 * dp, h / 2 + 36 * dp)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(215, 14, 13, 10); c.drawRoundRect(tab, 10 * dp, 10 * dp, pFill)
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 1.5f * dp; pFill.color = acc(levelDef(0), 150); c.drawRoundRect(tab, 10 * dp, 10 * dp, pFill)
        val dir = 1f - 2f * levelsAnim; val cx = tab.right - 13 * dp; val cy = tab.centerY()
        pFill.strokeWidth = 2.5f * dp; pFill.strokeCap = Paint.Cap.ROUND; pFill.strokeJoin = Paint.Join.ROUND; pFill.color = a
        c.drawPath(Path().apply { moveTo(cx - 3 * dp * dir, cy - 8 * dp); lineTo(cx + 3 * dp * dir, cy); lineTo(cx - 3 * dp * dir, cy + 8 * dp) }, pFill)
        pFill.strokeCap = Paint.Cap.BUTT; pFill.strokeJoin = Paint.Join.MITER
        hit(RectF(left + pw - 4 * dp, h / 2 - 48 * dp, left + pw + 36 * dp, h / 2 + 48 * dp)) { levelsOpen = !levelsOpen }
    }

    private fun drawSaves(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(levelDef(0))
        dim(c, 225, 200)
        heading(c, "SELECT SAVE", a, h * 0.15f)
        val lw = min(w * 0.7f, 520 * dp); val lx = (w - lw) / 2; val top = h * 0.22f
        val list = RectF(lx, top, lx + lw, h - 62 * dp)
        val rhNew = 46 * dp; val rh = 56 * dp; val gp = 8 * dp
        scrollY = scrollY.coerceIn(0f, max(0f, rhNew + gp + (if (saves.isEmpty()) 30 * dp else saves.size * (rh + gp)) - list.height()))
        nextScroll = list
        c.save(); c.clipRect(list)
        var y = top - scrollY
        val nr = RectF(lx, y, lx + lw, y + rhNew); button(c, nr, "NEW GAME", true, a); hitClip(nr, list) { openPicker(0, "", Scr.SAVES) }
        y += rhNew + gp
        if (saves.isEmpty()) small(c, "No saves yet  ·  use  Save & Quit  in the pause menu", w / 2, y + 16 * dp, 10.5f, Color.rgb(170, 165, 140))
        for (s in saves) {
            val d = levelDef(s.level); val rc = RectF(lx, y, lx + lw, y + rh)
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

    /** Labelled slider; returns its touch rectangle. ticks = (position 0..1, label, highlighted). */
    private fun drawSlider(c: Canvas, title: String, value: String, tl: Float, tr: Float, yTitle: Float, yTrack: Float, frac: Float, ticks: List<Triple<Float, String, Boolean>>): RectF {
        val a = acc(levelDef(0))
        small(c, title, tl, yTitle, 10.5f, Color.rgb(220, 212, 185), Paint.Align.LEFT, 0.25f, true)
        small(c, value, tr, yTitle, 12f, Color.WHITE, Paint.Align.RIGHT, 0.05f, true)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(110, 255, 255, 255); c.drawRoundRect(RectF(tl, yTrack - 2 * dp, tr, yTrack + 2 * dp), 2 * dp, 2 * dp, pFill)
        val kx = tl + (tr - tl) * frac
        pFill.color = a; c.drawRoundRect(RectF(tl, yTrack - 2 * dp, kx, yTrack + 2 * dp), 2 * dp, 2 * dp, pFill)
        for ((f, nm, on) in ticks) {
            val x = tl + (tr - tl) * f
            pFill.color = Color.argb(160, 255, 255, 255); c.drawRect(x - 1 * dp, yTrack - 7 * dp, x + 1 * dp, yTrack + 7 * dp, pFill)
            small(c, nm, x, yTrack + 24 * dp, 9.5f, if (on) a else Color.rgb(190, 185, 160), bold = on)
        }
        pFill.color = Color.WHITE; c.drawCircle(kx, yTrack, 10 * dp, pFill)
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 3 * dp; pFill.color = a; c.drawCircle(kx, yTrack, 10 * dp, pFill)
        return RectF(tl, yTrack - 18 * dp, tr, yTrack + 18 * dp)
    }

    private fun drawPicker(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(levelDef(0))
        dim(c, 225, 200)
        heading(c, "DIFFICULTY", a, h * 0.15f)
        small(c, "Chosen for this game and kept in its save.", w / 2, h * 0.215f, 10f, Color.rgb(200, 195, 160))
        val cols = intArrayOf(Color.rgb(150, 200, 150), a, Color.rgb(240, 160, 80), Color.rgb(235, 70, 55), Color.rgb(110, 210, 200), Color.rgb(200, 30, 30))
        val cw = w * 0.38f; val ch = 54 * dp; val gx = w * 0.04f; val x0 = w / 2 - cw - gx / 2
        for (i in DIFFS.indices) {
            val rc = RectF(x0 + (i % 2) * (cw + gx), h * 0.27f + (i / 2) * (ch + 8 * dp), x0 + (i % 2) * (cw + gx) + cw, h * 0.27f + (i / 2) * (ch + 8 * dp) + ch)
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb(185, 0, 0, 0); c.drawRoundRect(rc, 10 * dp, 10 * dp, pFill)
            pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = cols[i]; c.drawRoundRect(rc, 10 * dp, 10 * dp, pFill)
            small(c, DIFFS[i].name.uppercase(), rc.left + 14 * dp, rc.top + 22 * dp, 14f, cols[i], Paint.Align.LEFT, 0.12f, true)
            small(c, DIFFS[i].blurb, rc.left + 14 * dp, rc.top + 42 * dp, 9f, Color.rgb(215, 208, 180), Paint.Align.LEFT)
            hit(rc) { Settings.putDiff(i); startGame(pendAddr, pendLv) }
        }
        btn(c, RectF(w / 2 - 70 * dp, h - 52 * dp, w / 2 + 70 * dp, h - 12 * dp), "BACK", false, a) { scr = pickBack }
    }

    private fun drawOptions(c: Canvas) {
        if (optPage == 1) return drawGraphics(c)
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(levelDef(0))
        dim(c, 220, 200)
        heading(c, "OPTIONS", a, h * 0.15f)
        val tl = w * 0.10f; val tr = w * 0.55f
        nextFov = drawSlider(c, "FIELD OF VIEW", Settings.name(), tl, tr, h * 0.30f, h * 0.38f, Settings.frac().toFloat(),
            Settings.PRESETS.map { (f, nm) -> Triple(Settings.frac(f).toFloat(), "$nm ($f)", Settings.fov == f) })
        nextFog = drawSlider(c, "FOG", Settings.fogName(), tl, tr, h * 0.56f, h * 0.64f, Settings.fog / 100f,
            Settings.FOG_PRESETS.map { (f, nm) -> Triple(f / 100f, nm, Settings.fog == f) })
        if (playing) { // the difficulty belongs to the game being played (chosen when it was started)
        // difficulty
        val dl = w * 0.64f; val dr = w * 0.92f; val bh = 22 * dp; val gp = 4 * dp; var y = h * 0.34f
        small(c, "DIFFICULTY  ·  THIS GAME", dl, h * 0.30f, 10.5f, Color.rgb(220, 212, 185), Paint.Align.LEFT, 0.25f, true)
        val cols = intArrayOf(Color.rgb(150, 200, 150), a, Color.rgb(240, 160, 80), Color.rgb(235, 70, 55), Color.rgb(110, 210, 200), Color.rgb(200, 30, 30))
        for (i in DIFFS.indices) {
            val rc = RectF(dl, y, dr, y + bh); val lock = playing && (g.hardcoreRun || DIFFS[i].hardcore) // a run in progress keeps its difficulty; Hardcore cannot be switched on mid-run
            if (lock) button(c, rc, DIFFS[i].name.uppercase(), Settings.diff == i, Color.rgb(100, 100, 100)) else btn(c, rc, DIFFS[i].name.uppercase(), Settings.diff == i, cols[i]) { Settings.putDiff(i); Store.saveProfile() }
            y += bh + gp
        }
        small(c, if (playing && g.hardcoreRun) "Hardcore run: difficulty is locked." else DIFFS[Settings.diff].blurb, dl, y + 8 * dp, 9.5f, Color.rgb(200, 195, 160), Paint.Align.LEFT)
        }
        btn(c, RectF(w / 2 - 150 * dp, h - 52 * dp, w / 2 - 10 * dp, h - 12 * dp), "BACK", false, a) { closeOptions() }
        btn(c, RectF(w / 2 + 10 * dp, h - 52 * dp, w / 2 + 150 * dp, h - 12 * dp), "GRAPHICS", false, a) { optPage = 1 }
    }

    /** Renderer, frame size, FPS limit and VSync. */
    private fun drawGraphics(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(levelDef(0))
        dim(c, 220, 200)
        heading(c, "GRAPHICS", a, h * 0.10f)
        val tl = w * 0.08f; val tr = w * 0.92f; val label = Color.rgb(220, 212, 185); val note = Color.rgb(200, 195, 160)
        // Renderer
        small(c, "RENDERER", tl, h * 0.20f, 10.5f, label, Paint.Align.LEFT, 0.25f, true)
        val rw = (tr - tl - 2 * 6 * dp) / 3f; val ry = h * 0.225f; var rx = tl
        for (i in RENDERER_NAMES.indices) {
            btn(c, RectF(rx, ry, rx + rw, ry + 24 * dp), RENDERER_NAMES[i].uppercase(), Settings.renderer == i, a) { setRenderer(i) }
            rx += rw + 6 * dp
        }
        small(c, gpu.statusLine(Settings.renderer), tl, ry + 36 * dp, 9f, note, Paint.Align.LEFT)
        // Frame size
        small(c, "FRAME SIZE  ·  ${Settings.frameH}p", tl, h * 0.34f, 10.5f, label, Paint.Align.LEFT, 0.25f, true)
        val n = Settings.FRAME_HEIGHTS.size; val fw = (tr - tl - (n - 1) * 5 * dp) / n; val fy = h * 0.365f; var fx = tl
        for (f in Settings.FRAME_HEIGHTS) {
            btn(c, RectF(fx, fy, fx + fw, fy + 22 * dp), "${f}p", Settings.frameH == f, a) { Settings.putFrame(f); Store.saveProfile() }
            fx += fw + 5 * dp
        }
        // Quality (Sodium-style cost knobs)
        small(c, "QUALITY  ·  ${Settings.QUALITY_NAMES[Settings.quality].uppercase()}", tl, h * 0.46f, 10.5f, label, Paint.Align.LEFT, 0.25f, true)
        val qn = Settings.QUALITY_NAMES.size; val qw = (tr - tl - (qn - 1) * 6 * dp) / qn; val qy = h * 0.485f; var qx = tl
        for (qi in 0 until qn) {
            btn(c, RectF(qx, qy, qx + qw, qy + 22 * dp), Settings.QUALITY_NAMES[qi].uppercase(), Settings.quality == qi, a) {
                Settings.putQuality(qi); Store.saveProfile()
            }
            qx += qw + 6 * dp
        }
        small(c, "Low = fewer rays/sprites, no grain. High = full fidelity.", tl, qy + 32 * dp, 9f, note, Paint.Align.LEFT)
        // VSync
        small(c, "VSYNC", tl, h * 0.58f, 10.5f, label, Paint.Align.LEFT, 0.25f, true)
        val vy = h * 0.605f; val vw = (tr - tl - 6 * dp) / 2f
        btn(c, RectF(tl, vy, tl + vw, vy + 22 * dp), "ON", Settings.vsync, a) {
            Settings.putVsync(true); Store.saveProfile()
        }
        btn(c, RectF(tl + vw + 6 * dp, vy, tr, vy + 22 * dp), "OFF", !Settings.vsync, a) {
            Settings.putVsync(false); Store.saveProfile()
        }
        // FPS limit
        small(c, "FPS LIMIT  ·  ${Settings.fpsLimitName()}", tl, h * 0.70f, 10.5f, label, Paint.Align.LEFT, 0.25f, true)
        val caps = listOf(30, 60, 90, 120, 144, 240, 0)  // 0 = Ultimate
        val cn = caps.size; val cw = (tr - tl - (cn - 1) * 4 * dp) / cn; val cy = h * 0.725f; var cx = tl
        for (cap in caps) {
            val lab = if (cap == 0) "ULT" else "$cap"
            btn(c, RectF(cx, cy, cx + cw, cy + 22 * dp), lab, Settings.fpsLimit == cap, a) {
                Settings.putFpsLimit(cap); Store.saveProfile()
            }
            cx += cw + 4 * dp
        }
        btn(c, RectF(w / 2 - 70 * dp, h - 48 * dp, w / 2 + 70 * dp, h - 12 * dp), "BACK", false, a) { optPage = 0 }
    }

    private fun drawPause(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val d = levelDef(g.level); val a = acc(d)
        dim(c, 190, 190)
        heading(c, "PAUSED", a, h * 0.21f)
        small(c, "${d.label}  ·  ${d.name}", w / 2, h * 0.28f, 11f, Color.rgb(220, 212, 185), spacing = 0.2f, bold = true)
        val bw = min(w * 0.34f, 300 * dp); val bh = 42 * dp; val gap = 10 * dp; val top = h * 0.35f
        val lx = max(w * 0.08f, 16 * dp); val rx = w - lx - bw   // buttons on the left, skin preview + DR on the right
        var y = top
        btn(c, RectF(lx, y, lx + bw, y + bh), "RESUME", true, a) { scr = Scr.GAME }; y += bh + gap
        btn(c, RectF(lx, y, lx + bw, y + bh), "OPTIONS", false, a) { openOptions(Scr.PAUSE) }; y += bh + gap
        btn(c, RectF(lx, y, lx + bw, y + bh), "SAVE & QUIT", false, Color.rgb(235, 110, 90)) { saveAndQuit() }
        val pv = RectF(rx, top, rx + bw, top + 2 * bh + gap)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(150, 0, 0, 0); c.drawRoundRect(pv, 16 * dp, 16 * dp, pFill)
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = acc(d, 120); c.drawRoundRect(pv, 16 * dp, 16 * dp, pFill)
        val ph = pv.height() * 1.35f - 18 * dp   // the figure pops up above the panel
        drawSkinPreview(c, RectF(pv.centerX() - ph * 0.3f, pv.top - pv.height() * 0.45f, pv.centerX() + ph * 0.3f, pv.top - pv.height() * 0.45f + ph))
        small(c, SkinLibrary.selected().name, pv.centerX(), pv.bottom - 7 * dp, 9f, Color.rgb(220, 212, 185), spacing = 0.12f, bold = true)
        btn(c, RectF(rx, top + 2 * (bh + gap), rx + bw, top + 2 * (bh + gap) + bh), "DRESSING ROOM", false, a) { openDressing(Scr.PAUSE) }
    }

    private fun drawDressing(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(levelDef(0))
        dim(c, 210, 190)
        heading(c, "DRESSING ROOM", a, h * 0.17f)
        val list = RectF(w * 0.05f, h * 0.26f, w * 0.55f, h - 26 * dp)
        val rh = 46 * dp; val gp = 6 * dp; val hdr = 22 * dp; val rows = SkinLibrary.rows(); val sel = SkinLibrary.selectedId
        scrollY = scrollY.coerceIn(0f, max(0f, rows.sumOf { ((if (it.entry == null) hdr else rh) + gp).toDouble() }.toFloat() - list.height()))
        nextScroll = list
        c.save(); c.clipRect(list)
        var yy = list.top - scrollY
        rows.forEach { row ->
            val e = row.entry
            if (e == null) { // title of a skin pack
                small(c, row.header.orEmpty().uppercase() + "  ·  " + Tf("%d skin(s)", row.count), list.left + 4 * dp, yy + hdr - 5 * dp, 9f, a, Paint.Align.LEFT, 0.2f, true)
                yy += hdr + gp; return@forEach
            }
            val rc = RectF(list.left, yy, list.right, yy + rh); yy += rh + gp
            if (rc.bottom < list.top || rc.top > list.bottom) return@forEach
            val on = e.id == sel
            pFill.style = Paint.Style.FILL; pFill.color = Color.argb(175, 0, 0, 0); c.drawRoundRect(rc, 10 * dp, 10 * dp, pFill)
            if (on) { pFill.color = acc(levelDef(0), 60); c.drawRoundRect(rc, 10 * dp, 10 * dp, pFill) }
            pFill.style = Paint.Style.STROKE; pFill.strokeWidth = (if (on) 3f else 2f) * dp; pFill.color = if (on) a else acc(levelDef(0), 150); c.drawRoundRect(rc, 10 * dp, 10 * dp, pFill)
            small(c, e.name, rc.left + 12 * dp, rc.top + 20 * dp, 12f, Color.WHITE, Paint.Align.LEFT, 0.02f, true)
            small(c, e.source, rc.left + 12 * dp, rc.bottom - 9 * dp, 8.5f, Color.rgb(190, 185, 160), Paint.Align.LEFT, 0.06f, true)
            if (on) small(c, "WEARING", rc.right - 12 * dp, rc.top + 20 * dp, 9f, a, Paint.Align.RIGHT, 0.2f, true)
            hitClip(rc, list) { SkinLibrary.select(e) }
        }
        c.restore()
        val pv = RectF(w * 0.60f, h * 0.26f, w * 0.95f, h * 0.70f)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(150, 0, 0, 0); c.drawRoundRect(pv, 16 * dp, 16 * dp, pFill)
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = acc(levelDef(0), 120); c.drawRoundRect(pv, 16 * dp, 16 * dp, pFill)
        drawSkinPreview(c, RectF(pv.left + pv.width() * 0.25f, pv.top + 6 * dp, pv.right - pv.width() * 0.25f, pv.bottom - 24 * dp))
        small(c, SkinLibrary.selected().name, pv.centerX(), pv.bottom - 9 * dp, 10f, Color.rgb(220, 212, 185), spacing = 0.15f, bold = true)
        val by = pv.bottom + 10 * dp; val bh = 38 * dp; val half = (pv.width() - 8 * dp) / 2
        btn(c, RectF(pv.left, by, pv.left + half, by + bh), "IMPORT", false, a) { pickSkinFiles() }
        btn(c, RectF(pv.left + half + 8 * dp, by, pv.right, by + bh), "BACK", true, a) { scr = drBack }
        val dir = SkinLibrary.dirs.firstOrNull()?.path ?: ""
        small(c, if (drMsg.isNotEmpty()) drMsg else "Packs (.brpack / .mcpack) are saved in $dir  ·  IMPORT a .png to wear it once", w / 2, h - 10 * dp, 9f, Color.rgb(200, 195, 160))
    }

    /** In-game buttons: small options icon (top-centre) and the walk / run toggle (bottom-right). */
    private fun drawControls(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val a = acc(levelDef(g.level))
        val ob = RectF(w / 2 - 15 * dp, 8 * dp, w / 2 + 15 * dp, 28 * dp)
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(80, 0, 0, 0); c.drawRoundRect(ob, 7 * dp, 7 * dp, pFill)
        pFill.color = Color.argb(190, 255, 255, 255)
        for (k in -1..1) c.drawRect(ob.centerX() - 7 * dp, ob.centerY() + k * 5 * dp - 1 * dp, ob.centerX() + 7 * dp, ob.centerY() + k * 5 * dp + 1 * dp, pFill)
        val cx = w - 20 * dp - 28 * dp; val cy = h - 20 * dp - 28 * dp
        pFill.color = if (runOn) Color.argb(200, Color.red(a), Color.green(a), Color.blue(a)) else Color.argb(110, 0, 0, 0)
        c.drawCircle(cx, cy, 28 * dp, pFill)
        pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = Color.argb(180, 255, 255, 255); c.drawCircle(cx, cy, 28 * dp, pFill)
        small(c, if (runOn) "RUN" else "WALK", cx, cy + 4 * dp, 11f, if (runOn) Color.rgb(30, 24, 6) else Color.WHITE, spacing = 0.1f, bold = true)
        if (!Settings.explore) for ((bx, label, cnt) in listOf(Triple(w - 112 * dp, "EAT", g.rations), Triple(w - 168 * dp, "DRINK", g.water))) { // supplies
            val by = h - 40 * dp; val has = cnt > 0
            pFill.style = Paint.Style.FILL; pFill.color = if (has) Color.argb(150, 40, 30, 10) else Color.argb(70, 0, 0, 0); c.drawCircle(bx, by, 22 * dp, pFill)
            pFill.style = Paint.Style.STROKE; pFill.strokeWidth = 2 * dp; pFill.color = if (has) Color.argb(230, 240, 170, 70) else Color.argb(90, 255, 255, 255); c.drawCircle(bx, by, 22 * dp, pFill)
            small(c, label, bx, by - 3 * dp, 8.5f, if (has) Color.WHITE else Color.rgb(150, 150, 150), spacing = 0.1f, bold = true)
            small(c, "×$cnt", bx, by + 11 * dp, 11f, if (has) Color.rgb(240, 200, 120) else Color.rgb(150, 150, 150), bold = true)
        }
    }

    private fun drawHud(c: Canvas) {
        val w = c.width.toFloat(); val h = c.height.toFloat(); val d = levelDef(g.level); val a = acc(d)
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
        pText.textSize = 9.5f * dp; pText.color = Color.rgb(160, 155, 130); c.drawText("${fps.toInt()} FPS" + Settings.modeTag(), 26 * dp, 66 * dp, pText)

        if (!Settings.explore) { // food bar (blinks red when low); Explore has no hunger
        val low = g.hunger < 0.2
        pFill.style = Paint.Style.FILL; pFill.color = Color.argb(120, 0, 0, 0); c.drawRoundRect(RectF(12 * dp, 82 * dp, 230 * dp, 98 * dp), 6 * dp, 6 * dp, pFill)
        small(c, "FOOD", 20 * dp, 93.5f * dp, 8.5f, Color.rgb(225, 215, 180), Paint.Align.LEFT, 0.2f, true)
        pFill.color = Color.argb(110, 255, 255, 255); c.drawRect(58 * dp, 87 * dp, 222 * dp, 93 * dp, pFill)
        pFill.color = if (low && (System.currentTimeMillis() / 300) % 2 == 0L) Color.rgb(235, 70, 55) else if (low) Color.rgb(210, 90, 60) else Color.rgb(240, 170, 70)
        c.drawRect(58 * dp, 87 * dp, 58 * dp + 164 * dp * g.hunger.toFloat(), 93 * dp, pFill)
        }
        if (g.noticeT > 0) small(c, g.notice, w / 2, h * 0.74f, 12f, Color.argb((min(1.0, g.noticeT) * 255).toInt(), 255, 236, 180), spacing = 0.08f, bold = true)

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
                c.drawText(g.deathMsg, w / 2, h * 0.50f, pTitle)
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

    /** Hardware canvas when available (API 23+), falling back to the normal software canvas if the device refuses. */
    private fun lock(): Canvas? {
        if (hw) try { return holder.surface.lockHardwareCanvas() } catch (_: Exception) { hw = false }
        return holder.lockCanvas()
    }
    private fun unlock(c: Canvas) { if (hw) holder.surface.unlockCanvasAndPost(c) else holder.unlockCanvasAndPost(c) }

    /**
     * Draws the 3D view. With OpenGL ES or Vulkan the GPU draws it from the scene data and the CPU does not render at all;
     * open-world levels (city, island, graveyard) are still drawn by the CPU and the frame is uploaded. Canvas, or a GPU
     * backend that does not work, draws on the CPU into the bitmap. true = the picture is on the GPU surface under this one.
     */
    private fun drawFrame(g: Game, t: Double, others: Collection<DoubleArray>, hud: Boolean): Boolean {
        val kind = Settings.renderer
        if (gpu.usable(kind)) {
            if (r.buildScene(g, t, others, hud, scene)) { if (gpu.presentScene(scene, r.W, r.H, kind)) return true }
            else { r.render(px, g, t, others, hud, net?.playerSkins ?: emptyMap()); if (gpu.present(px, r.W, r.H, kind)) return true }
        }
        r.render(px, g, t, others, hud, net?.playerSkins ?: emptyMap())
        bmp.setPixels(px, 0, r.W, 0, 0, r.W, r.H)
        return false
    }

    override fun run() {
        var last = System.nanoTime(); var t = 0.0; var lastSend = 0L
        while (running) {
            if (r.H != Settings.frameH) { // GRAPHICS changed the frame size
                val oldR = r; val oldBmp = bmp
                r = Renderer(Settings.frameW, Settings.frameH); px = IntArray(r.W * r.H); bmp = Bitmap.createBitmap(r.W, r.H, Bitmap.Config.ARGB_8888)
                oldR.close(); oldBmp.recycle()
            }
            val frameStart = System.nanoTime()
            val now = frameStart; val dt = (now - last) / 1e9; last = now; t += dt
            if (dt > 0) fps = fps * 0.9 + (1 / dt) * 0.1
            levelsAnim += ((if (levelsOpen && scr == Scr.MAIN) 1f else 0f) - levelsAnim) * min(1f, dt.toFloat() * 14f)
            val fg: Game; val fo: Collection<DoubleArray>; val fh: Boolean   // what to draw this frame
            if (!playing) {
                menuG.a += dt * 0.12; menuG.bob += dt * 2.0; menuG.bobAmp = 0.5
                fg = menuG; fo = emptyList(); fh = false
            } else {
                val n = net
                n?.takeSeed()?.let { g.adoptSeed(it) } // multiplayer: the server decides the world
                if (scr == Scr.GAME) { // pausing freezes the game; the last frame stays on screen
                    playT += dt
                    if (g.exhausted) runOn = false
                    g.update(dt, -jy.toDouble(), jx.toDouble(), 0.0, runOn)
                    if (n != null && now / 1_000_000 - lastSend > 50) { n.send(g.x, g.y, g.level); lastSend = now / 1_000_000 }
                }
                fg = g; fo = n?.others?.values ?: emptyList(); fh = scr == Scr.GAME
            }
            val onGpu = drawFrame(fg, t, fo, fh) // true: the 3D view is on the GPU surface under this one
            val c = lock() ?: continue
            try {
                if (onGpu) c.drawColor(0, PorterDuff.Mode.CLEAR) else c.drawBitmap(bmp, null, Rect(0, 0, c.width, c.height), bmpPaint)
                cur = ArrayList(); nextFov = null; nextFog = null; nextScroll = null
                when (scr) {
                    Scr.GAME -> drawHud(c)
                    Scr.PAUSE -> drawPause(c)
                    Scr.SAVES -> drawSaves(c)
                    Scr.OPTIONS -> drawOptions(c)
                    Scr.DIFFICULTY -> drawPicker(c)
                    Scr.MAIN -> drawMain(c)
                    Scr.DRESSING -> drawDressing(c)
                    else -> {}
                }
                hits = cur; fovR = nextFov; fogR = nextFog; scrollR = nextScroll
            } finally { unlock(c) }
            // FPS limit (software pace). 0 = Ultimate. Works with or without VSync.
            val limit = Settings.fpsLimit
            if (limit > 0) {
                val targetNs = 1_000_000_000L / limit
                val spent = System.nanoTime() - frameStart
                val sleepNs = targetNs - spent
                if (sleepNs > 1_000_000L) { // only sleep if > 1 ms remains
                    try { Thread.sleep(sleepNs / 1_000_000L, (sleepNs % 1_000_000L).toInt()) } catch (_: InterruptedException) {}
                }
            }
        }
    }
}
