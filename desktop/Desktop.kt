package com.backrooms.game
// Desktop.kt - Swing client: main menu (Play / Multiplayer / Options + unlocked-levels drawer), saves, pause menu, mouse look (360° + up/down), HUD
// Server: java -jar backrooms.jar server [port]   Client: java -jar backrooms.jar [host[:port]]
import java.awt.*
import java.awt.event.*
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import kotlin.math.*

private fun txt(g2: Graphics2D, s: String, x0: Int, y: Int, f: Font, col: Color, center: Boolean = true) {
    g2.font = f
    val x = if (center) x0 - g2.fontMetrics.stringWidth(s) / 2 else x0
    g2.color = Color(0, 0, 0, 170); g2.drawString(s, x + 2, y + 2)
    g2.color = col; g2.drawString(s, x, y)
}

private fun spaced(f: Font, tracking: Float) = f.deriveFont(mapOf(TextAttribute.TRACKING to tracking))

private fun wrap(fm: FontMetrics, s: String, maxW: Int): List<String> {
    val out = ArrayList<String>(); var line = ""
    for (w in s.split(" ")) {
        val t = if (line.isEmpty()) w else "$line $w"
        if (fm.stringWidth(t) > maxW && line.isNotEmpty()) { out.add(line); line = w } else line = t
    }
    if (line.isNotEmpty()) out.add(line)
    return out
}

@Volatile private var scr = Scr.MAIN
@Volatile private var playing = false // a game session exists (running or paused)

fun main(args: Array<String>) {
    if (args.firstOrNull() == "server") return server(args.getOrNull(1)?.toInt() ?: PORT)
    Store.init(File(System.getProperty("user.home"), ".backrooms"))

    val r = Renderer()
    val img = BufferedImage(r.W, r.H, BufferedImage.TYPE_INT_RGB)
    val px = (img.raster.dataBuffer as DataBufferInt).data
    val keys = ConcurrentHashMap.newKeySet<Int>()
    val g = Game(); val menuG = Game()
    var net: Net? = null; var fps = 60.0; var playT = 0.0
    val ip = StringBuilder(args.firstOrNull() ?: "")
    var slot: String? = null; var optBack = Scr.MAIN; var saves: List<SaveData> = emptyList()
    var levelsOpen = false; var levelsAnim = 0.0; var scrollY = 0
    var fovR: Rectangle? = null; var fogR: Rectangle? = null; var slideId = 0 /* 0 none, 1 FOV, 2 fog */; var mpos = Point(-1, -1)
    val hits = ArrayList<Pair<Rectangle, () -> Unit>>() // rebuilt on every paint; the newest (topmost) one containing the click wins
    val robot = try { Robot() } catch (_: Exception) { null }
    val blank = Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank")
    var recenter = false
    lateinit var panel: JPanel

    // ---- navigation ----
    fun setCursor(hidden: Boolean) { if (robot != null) panel.cursor = if (hidden) blank else Cursor.getDefaultCursor() }
    // starting a game asks for its difficulty first: the difficulty belongs to the game (and its save), not to the profile
    var pendLv = 0; var pendAddr = ""; var pickBack = Scr.MAIN
    fun openPicker(lv: Int, addr: String, from: Scr) { pendLv = lv; pendAddr = addr; pickBack = from; levelsOpen = false; scr = Scr.DIFFICULTY }
    fun startGame(addr: String = "", lv: Int = 0, load: SaveData? = null) {
        net?.close()
        val a = addr.trim()
        net = if (a.isEmpty()) null else a.split(":").let { p -> Net(p[0], p.getOrNull(1)?.toIntOrNull() ?: PORT).also { it.start() } }
        if (load != null) g.restore(load) else g.newGame(lv)
        slot = load?.id; playT = 0.0; levelsOpen = false; recenter = true
        playing = true; scr = Scr.GAME; setCursor(true)
    }
    fun pickDifficulty(i: Int) { if (i in DIFFS.indices) { Settings.putDiff(i); startGame(pendAddr, pendLv) } }
    fun openSaves() { saves = Store.list(); scrollY = 0; levelsOpen = false; scr = Scr.SAVES }
    fun openOptions(from: Scr) { optBack = from; levelsOpen = false; scr = Scr.OPTIONS }
    fun closeOptions() { Store.saveProfile(); scr = optBack }
    fun pause() { scr = Scr.PAUSE; setCursor(false) }
    fun resume() { recenter = true; scr = Scr.GAME; setCursor(true) }
    fun saveAndQuit() {
        Store.write(g.snapshot(slot ?: System.currentTimeMillis().toString()))
        net?.close(); net = null; slot = null; playing = false; scr = Scr.MAIN; setCursor(false)
    }
    fun confirmDelete(s: SaveData) {
        val d = levelDef(s.level)
        if (JOptionPane.showConfirmDialog(panel, "Delete this save?\n${d.name}  ·  ${s.info()}", "Backrooms", JOptionPane.YES_NO_OPTION) == JOptionPane.YES_OPTION) {
            Store.delete(s.id); saves = Store.list()
        }
    }
    fun back() {
        when (scr) {
            Scr.MAIN -> if (levelsOpen) levelsOpen = false else System.exit(0)
            Scr.SAVES, Scr.MULTI -> scr = Scr.MAIN
            Scr.DIFFICULTY -> scr = pickBack
            Scr.OPTIONS -> closeOptions()
            Scr.PAUSE -> resume()
            Scr.GAME -> pause()
        }
    }
    fun joinServer() { if (ip.isNotBlank()) openPicker(0, ip.toString(), Scr.MULTI) }

    panel = object : JPanel() {
        override fun paintComponent(gr: Graphics) {
            val g2 = gr as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.drawImage(img, 0, 0, width, height, null)
            hits.clear(); fovR = null; fogR = null
            when (scr) {
                Scr.GAME -> drawHud(g2)
                Scr.PAUSE -> drawPause(g2)
                Scr.SAVES -> drawSaves(g2)
                Scr.MULTI -> drawMulti(g2)
                Scr.OPTIONS -> drawOptions(g2)
                Scr.DIFFICULTY -> drawPicker(g2)
                Scr.MAIN -> drawMain(g2)
            }
        }

        fun hit(rc: Rectangle, f: () -> Unit) { hits.add(rc to f) }
        fun hitClip(rc: Rectangle, area: Rectangle, f: () -> Unit) { val v = rc.intersection(area); if (!v.isEmpty) hit(v, f) }

        fun btn(g2: Graphics2D, rc: Rectangle, label: String, primary: Boolean, col: Color, f: () -> Unit) {
            if (primary) { g2.paint = GradientPaint(0f, rc.y.toFloat(), col, 0f, (rc.y + rc.height).toFloat(), col.darker()); g2.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14) }
            else {
                g2.color = Color(0, 0, 0, 170); g2.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14)
                g2.color = Color(col.red, col.green, col.blue, 190); g2.stroke = BasicStroke(2f); g2.drawRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14)
            }
            if (rc.contains(mpos)) { g2.color = Color(255, 255, 255, 36); g2.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14) }
            g2.font = spaced(Font("SansSerif", Font.BOLD, rc.height / 2), 0.2f)
            g2.color = if (primary) Color(30, 24, 6) else col
            g2.drawString(label, rc.x + (rc.width - g2.fontMetrics.stringWidth(label)) / 2, rc.y + rc.height * 2 / 3)
            hit(rc, f)
        }

        fun dim(g2: Graphics2D, a1: Int, a2: Int) { g2.paint = GradientPaint(0f, 0f, Color(0, 0, 0, a1), 0f, height.toFloat(), Color(0, 0, 0, a2)); g2.fillRect(0, 0, width, height) }

        fun heading(g2: Graphics2D, s: String, col: Color, y: Double) = txt(g2, s, width / 2, (height * y).toInt(), Font("Serif", Font.BOLD, height / 9), col)

        /** One level / save card: accent border, label, name and a second line. */
        fun card(g2: Graphics2D, rc: Rectangle, d: LevelDef, line2: String, line2Col: Color) {
            val c = Color(d.accent)
            g2.color = Color(0, 0, 0, 175); g2.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14)
            g2.paint = GradientPaint(0f, rc.y.toFloat(), Color(c.red, c.green, c.blue, 70), 0f, (rc.y + rc.height).toFloat(), Color(c.red, c.green, c.blue, 0))
            g2.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14)
            g2.stroke = BasicStroke(if (rc.contains(mpos)) 3f else 2f); g2.color = c; g2.drawRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14)
            val x0 = rc.x + 14
            txt(g2, d.label, x0, rc.y + rc.height * 26 / 100, spaced(Font("SansSerif", Font.BOLD, height / 64), 0.25f), c, false)
            txt(g2, d.name, x0, rc.y + rc.height * 56 / 100, Font("Serif", Font.BOLD, height / 34), Color.WHITE, false)
            txt(g2, line2, x0, rc.y + rc.height - 9, spaced(Font("SansSerif", Font.BOLD, height / 70), 0.12f), line2Col, false)
        }

        fun drawMain(g2: Graphics2D) {
            val w = width; val hh = height; val cx = w / 2; val ac = Color(levelDef(0).accent)
            dim(g2, 210, 170)
            heading(g2, "THE BACKROOMS", ac, 0.17)
            txt(g2, "POCKET EDITION  ·  MULTIPLAYER", cx, (hh * 0.23).toInt(), spaced(Font("SansSerif", Font.PLAIN, hh / 40), 0.3f), Color(220, 212, 185))
            val bw = (w * 0.28).toInt(); val bh = hh / 12; val gap = hh / 50; var y = (hh * 0.32).toInt()
            btn(g2, Rectangle(cx - bw / 2, y, bw, bh), "PLAY", true, ac) { openSaves() }; y += bh + gap
            btn(g2, Rectangle(cx - bw / 2, y, bw, bh), "MULTIPLAYER", false, ac) { levelsOpen = false; scr = Scr.MULTI }; y += bh + gap
            btn(g2, Rectangle(cx - bw / 2, y, bw, bh), "OPTIONS", false, ac) { openOptions(Scr.MAIN) }
            txt(g2, "Level 0 is endless and empty. Some walls aren't solid — push into one to noclip out.", cx, (hh * 0.76).toInt(),
                Font("SansSerif", Font.ITALIC, hh / 48), Color(210, 200, 170))
            txt(g2, "ENTER  play   ·   ARROW on the left edge  unlocked levels   ·   ESC  quit", cx, hh - hh / 24, Font("SansSerif", Font.PLAIN, hh / 56), Color(200, 195, 160))
            drawLevels(g2)
        }

        /** Side drawer with the unlocked levels; the arrow tab on the left edge opens it. */
        fun drawLevels(g2: Graphics2D) {
            val w = width; val hh = height; val ac = Color(levelDef(0).accent)
            val pw = max(260, w / 4); val left = (-pw + pw * levelsAnim).toInt()
            if (levelsAnim > 0.01) {
                g2.color = Color(0, 0, 0, (130 * levelsAnim).toInt()); g2.fillRect(0, 0, w, hh)
                hit(Rectangle(0, 0, w, hh)) { levelsOpen = false }
                val body = Rectangle(left, 0, pw, hh)
                g2.color = Color(14, 13, 10, 240); g2.fill(body)
                g2.color = Color(244, 228, 130, 120); g2.fillRect(left + pw - 2, 0, 2, hh)
                hit(body) {}
                val ul = LEVELS.filter { Progress.has(it.id) }
                txt(g2, "UNLOCKED LEVELS", left + 16, 32, spaced(Font("SansSerif", Font.BOLD, max(12, hh / 44)), 0.25f), ac, false)
                txt(g2, "${ul.size} / ${LEVELS.size}  ·  click to play", left + 16, 52, Font("SansSerif", Font.PLAIN, max(10, hh / 60)), Color(160, 155, 130), false)
                val lt = 66; val list = Rectangle(left, lt, pw - 2, hh - lt - 8); val rh = 64; val gp = 8
                scrollY = scrollY.coerceIn(0, max(0, ul.size * (rh + gp) - list.height))
                val oldClip = g2.clip; g2.clip = list
                ul.forEachIndexed { k, d ->
                    val rc = Rectangle(left + 12, lt + k * (rh + gp) - scrollY, pw - 28, rh)
                    if (rc.y + rc.height < list.y || rc.y > list.y + list.height) return@forEachIndexed
                    card(g2, rc, d, if (d.entities == "NONE") "SAFE" else d.entities, if (d.entities == "NONE") Color(150, 200, 150) else Color(235, 110, 90))
                    g2.color = Color(d.accent) // play triangle
                    g2.fillPolygon(intArrayOf(rc.x + rc.width - 26, rc.x + rc.width - 26, rc.x + rc.width - 12), intArrayOf(rc.y + rh / 2 - 9, rc.y + rh / 2 + 9, rc.y + rh / 2), 3)
                    hitClip(rc, list) { openPicker(d.id, "", Scr.MAIN) }
                }
                g2.clip = oldClip
            }
            val tab = Rectangle(left + pw - 8, hh / 2 - 40, 38, 80)
            g2.color = Color(14, 13, 10, 215); g2.fillRoundRect(tab.x, tab.y, tab.width, tab.height, 12, 12)
            g2.stroke = BasicStroke(1.5f); g2.color = Color(ac.red, ac.green, ac.blue, 150); g2.drawRoundRect(tab.x, tab.y, tab.width, tab.height, 12, 12)
            val dir = 1 - 2 * levelsAnim; val cx = tab.x + tab.width - 15; val cy = tab.y + tab.height / 2
            g2.stroke = BasicStroke(3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND); g2.color = ac
            g2.drawPolyline(intArrayOf((cx - 4 * dir).toInt(), (cx + 4 * dir).toInt(), (cx - 4 * dir).toInt()), intArrayOf(cy - 10, cy, cy + 10), 3)
            hit(Rectangle(left + pw - 4, hh / 2 - 52, 48, 104)) { levelsOpen = !levelsOpen }
        }

        fun drawSaves(g2: Graphics2D) {
            val w = width; val hh = height; val cx = w / 2; val ac = Color(levelDef(0).accent)
            dim(g2, 225, 200)
            heading(g2, "SELECT SAVE", ac, 0.15)
            val lw = min((w * 0.7).toInt(), 700); val lx = (w - lw) / 2; val top = (hh * 0.22).toInt()
            val list = Rectangle(lx, top, lw, hh - top - hh / 8)
            val rhNew = hh / 12; val rh = hh / 9; val gp = hh / 60
            scrollY = scrollY.coerceIn(0, max(0, rhNew + gp + (if (saves.isEmpty()) hh / 14 else saves.size * (rh + gp)) - list.height))
            val oldClip = g2.clip; g2.clip = list
            var y = top - scrollY
            btn(g2, Rectangle(lx, y, lw, rhNew), "NEW GAME", true, ac) {}; hits.removeAt(hits.size - 1)
            hitClip(Rectangle(lx, y, lw, rhNew), list) { openPicker(0, "", Scr.SAVES) }
            y += rhNew + gp
            if (saves.isEmpty()) txt(g2, "No saves yet  ·  use  Save & Quit  in the pause menu (ESC)", cx, y + hh / 24, Font("SansSerif", Font.ITALIC, hh / 44), Color(170, 165, 140))
            for (s in saves) {
                val d = levelDef(s.level); val rc = Rectangle(lx, y, lw, rh)
                if (rc.y + rc.height >= list.y && rc.y <= list.y + list.height) {
                    card(g2, rc, d, s.info(), Color(200, 195, 165))
                    hitClip(rc, list) { startGame(load = s) }
                    val x0 = Rectangle(rc.x + rc.width - 56, rc.y + rh / 2 - 20, 40, 40) // delete
                    g2.stroke = BasicStroke(if (x0.contains(mpos)) 2.5f else 1.5f); g2.color = Color(235, 110, 90, if (x0.contains(mpos)) 230 else 160)
                    g2.drawRoundRect(x0.x, x0.y, x0.width, x0.height, 10, 10)
                    g2.stroke = BasicStroke(2.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
                    g2.drawLine(x0.centerX.toInt() - 7, x0.centerY.toInt() - 7, x0.centerX.toInt() + 7, x0.centerY.toInt() + 7)
                    g2.drawLine(x0.centerX.toInt() + 7, x0.centerY.toInt() - 7, x0.centerX.toInt() - 7, x0.centerY.toInt() + 7)
                    hitClip(x0, list) { confirmDelete(s) }
                }
                y += rh + gp
            }
            g2.clip = oldClip
            val bh = hh / 14
            btn(g2, Rectangle(cx - 90, hh - bh - hh / 40, 180, bh), "BACK", false, ac) { scr = Scr.MAIN }
        }

        fun drawMulti(g2: Graphics2D) {
            val w = width; val hh = height; val cx = w / 2; val ac = Color(levelDef(0).accent)
            dim(g2, 225, 200)
            heading(g2, "MULTIPLAYER", ac, 0.17)
            txt(g2, "Type the server address  (host  or  host:port)", cx, (hh * 0.33).toInt(), Font("SansSerif", Font.PLAIN, hh / 40), Color(220, 212, 185))
            val bh = hh / 13; val iw = (w * 0.30).toInt(); val bw = (w * 0.16).toInt(); val by = (hh * 0.40).toInt()
            val bx = (w - (iw + 16 + bw)) / 2
            g2.color = Color(0, 0, 0, 170); g2.fillRoundRect(bx, by, iw, bh, 14, 14)
            g2.color = Color(ac.red, ac.green, ac.blue, 190); g2.stroke = BasicStroke(2f); g2.drawRoundRect(bx, by, iw, bh, 14, 14)
            val f = Font("Monospaced", Font.PLAIN, bh / 2)
            if (ip.isEmpty()) txt(g2, "Server IP", bx + 16, by + bh * 2 / 3, f, Color(150, 145, 120), false)
            else txt(g2, ip.toString() + if ((System.currentTimeMillis() / 500) % 2 == 0L) "_" else "", bx + 16, by + bh * 2 / 3, f, Color.WHITE, false)
            btn(g2, Rectangle(bx + iw + 16, by, bw, bh), "JOIN", true, ac) { joinServer() }
            txt(g2, "Host your own:   java -jar backrooms.jar server", cx, (hh * 0.62).toInt(), Font("Monospaced", Font.PLAIN, hh / 46), Color(170, 165, 140))
            val bb = hh / 14
            btn(g2, Rectangle(cx - 90, hh - bb - hh / 40, 180, bb), "BACK", false, ac) { scr = Scr.MAIN }
        }

        /** Labelled slider; returns its touch rectangle. ticks = (position 0..1, label, highlighted). */
        fun slider(g2: Graphics2D, title: String, value: String, tl: Int, tr: Int, yTitle: Int, yTrack: Int, frac: Double, ticks: List<Triple<Double, String, Boolean>>): Rectangle {
            val hh = height; val ac = Color(levelDef(0).accent)
            txt(g2, title, tl, yTitle, spaced(Font("SansSerif", Font.BOLD, hh / 44), 0.25f), Color(220, 212, 185), false)
            val vf = Font("SansSerif", Font.BOLD, hh / 36)
            txt(g2, value, tr - g2.getFontMetrics(vf).stringWidth(value), yTitle, vf, Color.WHITE, false)
            g2.color = Color(255, 255, 255, 110); g2.fillRoundRect(tl, yTrack - 2, tr - tl, 4, 4, 4)
            val kx = tl + ((tr - tl) * frac).toInt()
            g2.color = ac; g2.fillRoundRect(tl, yTrack - 2, kx - tl, 4, 4, 4)
            for ((f, nm, on) in ticks) {
                val x = tl + ((tr - tl) * f).toInt()
                g2.color = Color(255, 255, 255, 160); g2.fillRect(x - 1, yTrack - 8, 2, 16)
                txt(g2, nm, x, yTrack + hh / 18, Font("SansSerif", if (on) Font.BOLD else Font.PLAIN, hh / 50), if (on) ac else Color(190, 185, 160))
            }
            g2.color = Color.WHITE; g2.fillOval(kx - 11, yTrack - 11, 22, 22)
            g2.stroke = BasicStroke(3f); g2.color = ac; g2.drawOval(kx - 11, yTrack - 11, 22, 22)
            return Rectangle(tl, yTrack - 20, tr - tl, 40)
        }

        fun drawPicker(g2: Graphics2D) {
            val w = width; val hh = height; val cx = w / 2; val ac = Color(levelDef(0).accent)
            dim(g2, 225, 200)
            heading(g2, "DIFFICULTY", ac, 0.15)
            txt(g2, "Chosen for this game and kept in its save.   Click, or press 1 - 6.", cx, (hh * 0.215).toInt(), Font("SansSerif", Font.ITALIC, hh / 46), Color(200, 195, 160))
            val cols = arrayOf(Color(150, 200, 150), ac, Color(240, 160, 80), Color(235, 70, 55), Color(110, 210, 200), Color(200, 30, 30))
            val cw = (w * 0.38).toInt(); val ch = hh / 7; val gx = (w * 0.04).toInt(); val x0 = cx - cw - gx / 2
            for (i in DIFFS.indices) {
                val rc = Rectangle(x0 + (i % 2) * (cw + gx), (hh * 0.27).toInt() + (i / 2) * (ch + hh / 60), cw, ch)
                g2.color = Color(0, 0, 0, 185); g2.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 16, 16)
                g2.stroke = BasicStroke(if (rc.contains(mpos)) 3.5f else 2f); g2.color = cols[i]; g2.drawRoundRect(rc.x, rc.y, rc.width, rc.height, 16, 16)
                txt(g2, "${i + 1}   ${DIFFS[i].name.uppercase()}", rc.x + 16, rc.y + ch * 40 / 100, spaced(Font("SansSerif", Font.BOLD, hh / 28), 0.12f), cols[i], false)
                txt(g2, DIFFS[i].blurb, rc.x + 16, rc.y + ch * 78 / 100, Font("SansSerif", Font.PLAIN, hh / 50), Color(215, 208, 180), false)
                hit(rc) { pickDifficulty(i) }
            }
            val bb = hh / 14
            btn(g2, Rectangle(cx - 90, hh - bb - hh / 40, 180, bb), "BACK", false, ac) { scr = pickBack }
        }

        fun drawOptions(g2: Graphics2D) {
            val w = width; val hh = height; val cx = w / 2; val ac = Color(levelDef(0).accent)
            dim(g2, 220, 200)
            heading(g2, "OPTIONS", ac, 0.15)
            val tl = (w * 0.10).toInt(); val tr = (w * 0.55).toInt()
            fovR = slider(g2, "FIELD OF VIEW", Settings.name(), tl, tr, (hh * 0.30).toInt(), (hh * 0.38).toInt(), Settings.frac(),
                Settings.PRESETS.map { (f, nm) -> Triple(Settings.frac(f), "$nm ($f)", Settings.fov == f) })
            fogR = slider(g2, "FOG", Settings.fogName(), tl, tr, (hh * 0.56).toInt(), (hh * 0.64).toInt(), Settings.fog / 100.0,
                Settings.FOG_PRESETS.map { (f, nm) -> Triple(f / 100.0, nm, Settings.fog == f) })
            if (playing) { // the difficulty belongs to the game being played (chosen when it was started)
            // difficulty
            val dl = (w * 0.64).toInt(); val dw = (w * 0.28).toInt(); val bh = hh / 16; val gp = hh / 100; var y = (hh * 0.34).toInt()
            txt(g2, "DIFFICULTY  ·  THIS GAME", dl, (hh * 0.30).toInt(), spaced(Font("SansSerif", Font.BOLD, hh / 44), 0.25f), Color(220, 212, 185), false)
            val cols = arrayOf(Color(150, 200, 150), ac, Color(240, 160, 80), Color(235, 70, 55), Color(110, 210, 200), Color(200, 30, 30))
            for (i in DIFFS.indices) {
                val lock = playing && (g.hardcoreRun || DIFFS[i].hardcore) // a run in progress keeps its difficulty; Hardcore cannot be switched on mid-run
                if (lock) { btn(g2, Rectangle(dl, y, dw, bh), DIFFS[i].name.uppercase(), Settings.diff == i, Color(100, 100, 100)) {}; hits.removeAt(hits.size - 1) } else btn(g2, Rectangle(dl, y, dw, bh), DIFFS[i].name.uppercase(), Settings.diff == i, cols[i]) { Settings.putDiff(i); Store.saveProfile() }
                y += bh + gp
            }
            txt(g2, if (playing && g.hardcoreRun) "Hardcore run: difficulty is locked." else DIFFS[Settings.diff].blurb, dl, y + hh / 30, Font("SansSerif", Font.ITALIC, hh / 50), Color(200, 195, 160), false)
            }
            txt(g2, "drag the sliders  ·  LEFT / RIGHT  fine tune FOV", cx, hh - hh / 7, Font("SansSerif", Font.ITALIC, hh / 56), Color(170, 165, 140))
            val bb = hh / 14
            btn(g2, Rectangle(cx - 90, hh - bb - hh / 40, 180, bb), "BACK", false, ac) { closeOptions() }
        }

        fun drawPause(g2: Graphics2D) {
            val w = width; val hh = height; val cx = w / 2; val d = levelDef(g.level); val ac = Color(d.accent)
            dim(g2, 190, 190)
            heading(g2, "PAUSED", ac, 0.21)
            txt(g2, "${d.label}  ·  ${d.name}", cx, (hh * 0.28).toInt(), spaced(Font("SansSerif", Font.BOLD, hh / 40), 0.2f), Color(220, 212, 185))
            val bw = (w * 0.28).toInt(); val bh = hh / 12; val gap = hh / 50; var y = (hh * 0.35).toInt()
            btn(g2, Rectangle(cx - bw / 2, y, bw, bh), "RESUME", true, ac) { resume() }; y += bh + gap
            btn(g2, Rectangle(cx - bw / 2, y, bw, bh), "OPTIONS", false, ac) { openOptions(Scr.PAUSE) }; y += bh + gap
            btn(g2, Rectangle(cx - bw / 2, y, bw, bh), "SAVE & QUIT", false, Color(235, 110, 90)) { saveAndQuit() }
        }

        fun drawHud(g2: Graphics2D) {
            val w = width; val hh = height; val d = levelDef(g.level); val ac = Color(d.accent)
            val sz = max(12, hh / 44)
            val n = net
            val status = when {
                n == null -> "OFFLINE · SOLO"; n.state == 0 -> "CONNECTING…"
                n.state == 1 -> "ONLINE · ${n.others.size + 1} PLAYERS${if (g.isolated()) " · ISOLATED" else ""}"; else -> "CONNECTION LOST"
            }
            g2.color = Color(0, 0, 0, 120); g2.fillRoundRect(14, 14, sz * 19, sz * 4 + 18, 12, 12)
            g2.color = ac; g2.fillRoundRect(14, 14, 5, sz * 4 + 18, 4, 4)
            txt(g2, "${d.label}  ·  ${d.name}", 30, 14 + sz + 6, spaced(Font("SansSerif", Font.BOLD, sz), 0.2f), ac, false)
            txt(g2, status, 30, 14 + 2 * sz + 10, Font("SansSerif", Font.PLAIN, sz - 1), Color(225, 215, 180), false)
            txt(g2, "${fps.toInt()} FPS" + Settings.modeTag(), 30, 14 + 3 * sz + 14, Font("SansSerif", Font.PLAIN, sz - 3), Color(160, 155, 130), false)

            // food bar (blinks red when low) and supplies
            if (!Settings.explore) { // Explore has no hunger
            val fy = 14 + sz * 4 + 26; val low = g.hunger < 0.2
            g2.color = Color(0, 0, 0, 120); g2.fillRoundRect(14, fy, sz * 19, sz * 2 + 12, 10, 10)
            txt(g2, "FOOD", 26, fy + sz + 1, spaced(Font("SansSerif", Font.BOLD, max(9, sz - 3)), 0.2f), Color(225, 215, 180), false)
            val bx0 = 26 + sz * 4; val bw0 = sz * 19 - (bx0 - 14) - 12
            g2.color = Color(255, 255, 255, 110); g2.fillRect(bx0, fy + sz / 2 + 1, bw0, 7)
            g2.color = if (low && (System.currentTimeMillis() / 300) % 2 == 0L) Color(235, 70, 55) else if (low) Color(210, 90, 60) else Color(240, 170, 70)
            g2.fillRect(bx0, fy + sz / 2 + 1, (bw0 * g.hunger).toInt(), 7)
            txt(g2, "E  RATION ×${g.rations}     Q  ALMOND WATER ×${g.water}", 26, fy + sz * 2 + 4, Font("SansSerif", Font.PLAIN, max(9, sz - 3)), Color(240, 200, 120), false)
            }
            if (g.noticeT > 0) txt(g2, g.notice, w / 2, (hh * 0.74).toInt(), spaced(Font("SansSerif", Font.BOLD, hh / 38), 0.08f), Color(255, 236, 180, (min(1.0, g.noticeT) * 255).toInt()))

            g2.color = Color(255, 255, 255, 120); g2.fillOval(w / 2 - 2, hh / 2 - 2, 4, 4) // crosshair dot
            txt(g2, "ESC  ≡  OPTIONS", w / 2, 14 + sz, spaced(Font("SansSerif", Font.BOLD, max(9, sz - 4)), 0.2f), Color(255, 255, 255, 110)) // small pause hint, top-centre

            if (g.levelT < 4.5) { // level title card
                val al = min(1.0, min(g.levelT / 0.8, (4.5 - g.levelT) / 1.0)).coerceIn(0.0, 1.0)
                val c1 = Color(ac.red, ac.green, ac.blue, (al * 255).toInt())
                txt(g2, g.arrival, w / 2, (hh * 0.30).toInt(), spaced(Font("SansSerif", Font.PLAIN, hh / 42), 0.3f), Color(220, 215, 195, (al * 255).toInt()))
                txt(g2, "${d.label}", w / 2, (hh * 0.40).toInt(), spaced(Font("Serif", Font.BOLD, hh / 12), 0.3f), c1)
                g2.color = Color(ac.red, ac.green, ac.blue, (al * 160).toInt()); g2.fillRect(w / 2 - w / 12, (hh * 0.43).toInt(), w / 6, 2)
                txt(g2, d.name, w / 2, (hh * 0.50).toInt(), spaced(Font("SansSerif", Font.PLAIN, hh / 26), 0.4f), Color(240, 235, 215, (al * 255).toInt()))
                txt(g2, d.blurb, w / 2, (hh * 0.57).toInt(), Font("SansSerif", Font.ITALIC, hh / 40), Color(215, 208, 185, (al * 255).toInt()))
            }
            if (g.manilaT in 0.01..4.5) {
                val al = min(1.0, min(g.manilaT / 0.6, (4.5 - g.manilaT) / 1.0)).coerceIn(0.0, 1.0)
                txt(g2, "THE MANILA ROOM", w / 2, (hh * 0.24).toInt(), spaced(Font("Serif", Font.BOLD, hh / 20), 0.3f), Color(232, 206, 150, (al * 255).toInt()))
                txt(g2, "ISOLATION LIFTED  ·  you are not alone here", w / 2, (hh * 0.29).toInt(), Font("SansSerif", Font.PLAIN, hh / 40), Color(235, 225, 200, (al * 255).toInt()))
            }
            if (g.manilaNear > 0.05) txt(g2, "…the air smells of old paper", w / 2, hh - 70, Font("SansSerif", Font.ITALIC, hh / 36), Color(232, 206, 150, (g.manilaNear * 220).toInt()))
            if (g.noclip > 0.03) {
                val bw0 = w / 6
                g2.color = Color(0, 0, 0, 140); g2.fillRect(w / 2 - bw0 / 2, hh / 2 + 28, bw0, 6)
                g2.color = Color(120, 230, 255, 230); g2.fillRect(w / 2 - bw0 / 2, hh / 2 + 28, (bw0 * g.noclip).toInt(), 6)
                txt(g2, if ((System.currentTimeMillis() / 90) % 3 == 0L) "N0CL1PP1NG…" else "NOCLIPPING…", w / 2, hh / 2 + 60,
                    spaced(Font("SansSerif", Font.BOLD, hh / 36), 0.3f), Color(150, 235, 255))
            }
            if (g.dead) {
                g2.color = Color(120, 0, 0, min(170, (g.deadT * 130).toInt())); g2.fillRect(0, 0, w, hh)
                if (g.deadT > 0.6) {
                    txt(g2, g.deathMsg, w / 2, (hh * 0.48).toInt(), Font("Serif", Font.BOLD, hh / 10), Color(235, 70, 55))
                    txt(g2, "respawning…", w / 2, (hh * 0.57).toInt(), Font("SansSerif", Font.PLAIN, hh / 32), Color(230, 200, 190))
                }
            }
            if (playT in 0.0..9.0 && g.levelT > 4.5) {
                val al = ((9 - playT) / 2).coerceIn(0.0, 1.0)
                txt(g2, "MOUSE look  ·  WASD move  ·  shimmering walls aren't solid — push into one", w / 2, hh - 36, Font("SansSerif", Font.PLAIN, sz), Color(235, 225, 180, (al * 255).toInt()))
            }
        }
    }
    panel.preferredSize = Dimension(960, 540); panel.isFocusable = true

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            when (e.keyCode) {
                KeyEvent.VK_ESCAPE -> back()
                KeyEvent.VK_ENTER -> when (scr) { Scr.MAIN -> openSaves(); Scr.SAVES -> openPicker(0, "", Scr.SAVES); Scr.MULTI -> joinServer(); else -> {} }
                KeyEvent.VK_BACK_SPACE -> if (scr == Scr.MULTI && ip.isNotEmpty()) ip.setLength(ip.length - 1)
                KeyEvent.VK_M -> if (scr == Scr.GAME) g.showMap = !g.showMap
                in KeyEvent.VK_1..KeyEvent.VK_6 -> if (scr == Scr.DIFFICULTY) pickDifficulty(e.keyCode - KeyEvent.VK_1)
                KeyEvent.VK_E -> if (scr == Scr.GAME) g.eatReq = true
                KeyEvent.VK_Q -> if (scr == Scr.GAME) g.drinkReq = true
                KeyEvent.VK_LEFT -> if (scr == Scr.OPTIONS) { Settings.set(Settings.fov - 1); Store.saveProfile() }
                KeyEvent.VK_RIGHT -> if (scr == Scr.OPTIONS) { Settings.set(Settings.fov + 1); Store.saveProfile() }
            }
            keys.add(e.keyCode)
        }
        override fun keyReleased(e: KeyEvent) { keys.remove(e.keyCode) }
        override fun keyTyped(e: KeyEvent) {
            if (scr == Scr.MULTI && (e.keyChar.isLetterOrDigit() || e.keyChar in ".:-_") && ip.length < 40) ip.append(e.keyChar)
        }
    })
    fun setSlider(id: Int, x: Int) {
        val sr = (if (id == 1) fovR else fogR) ?: return
        val t = (x - sr.x).toDouble() / sr.width
        if (id == 1) Settings.set(Settings.fromFrac(t)) else Settings.putFog(Settings.fogFromFrac(t))
    }
    val mouse = object : MouseAdapter() {
        override fun mousePressed(e: MouseEvent) {
            if (scr == Scr.GAME || e.button != MouseEvent.BUTTON1) return
            fun near(sr: Rectangle?) = sr != null && Rectangle(sr.x - 16, sr.y, sr.width + 32, sr.height).contains(e.point)
            slideId = if (scr != Scr.OPTIONS) 0 else if (near(fovR)) 1 else if (near(fogR)) 2 else 0
            if (slideId != 0) setSlider(slideId, e.x)
            else hits.lastOrNull { it.first.contains(e.point) }?.second?.invoke()
        }
        override fun mouseDragged(e: MouseEvent) { mpos = e.point; if (slideId != 0) setSlider(slideId, e.x) }
        override fun mouseMoved(e: MouseEvent) { mpos = e.point }
        override fun mouseReleased(e: MouseEvent) { if (slideId != 0) { slideId = 0; Store.saveProfile() } }
        override fun mouseWheelMoved(e: MouseWheelEvent) { scrollY += e.wheelRotation * 40 }
    }
    panel.addMouseListener(mouse); panel.addMouseMotionListener(mouse); panel.addMouseWheelListener(mouse)
    val frame = JFrame("Backrooms").apply {
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE; add(panel); pack(); setLocationRelativeTo(null); isVisible = true
    }
    panel.requestFocusInWindow()

    var last = System.nanoTime(); var t = 0.0; var lastSend = 0L
    while (true) {
        val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now; t += dt
        if (dt > 0) fps = fps * 0.9 + (1 / dt) * 0.1
        levelsAnim += ((if (levelsOpen && scr == Scr.MAIN) 1.0 else 0.0) - levelsAnim) * min(1.0, dt * 14)
        if (!playing) {
            menuG.a += dt * 0.12; menuG.bob += dt * 2.0; menuG.bobAmp = 0.5
            r.render(px, menuG, t, emptyList(), false)
        } else {
            val n = net
            if (scr == Scr.GAME) { // pausing freezes the game; the last frame stays on screen
                playT += dt
                var f = 0.0; var s = 0.0; var turn = 0.0
                if (KeyEvent.VK_W in keys) f += 1; if (KeyEvent.VK_S in keys) f -= 1
                if (KeyEvent.VK_D in keys) s += 1; if (KeyEvent.VK_A in keys) s -= 1
                if (KeyEvent.VK_RIGHT in keys) turn += 2.2; if (KeyEvent.VK_LEFT in keys) turn -= 2.2
                if (KeyEvent.VK_UP in keys) g.pitch += 1.4 * dt; if (KeyEvent.VK_DOWN in keys) g.pitch -= 1.4 * dt
                // mouse look: recentre the pointer every frame and turn the delta into yaw / pitch
                if (robot != null && frame.isActive && panel.isShowing) {
                    val c = panel.locationOnScreen.let { Point(it.x + panel.width / 2, it.y + panel.height / 2) }
                    if (!recenter) {
                        val m = MouseInfo.getPointerInfo().location
                        g.a += (m.x - c.x) * 0.003; g.pitch = (g.pitch - (m.y - c.y) * 0.003).coerceIn(-1.0, 1.0)
                    }
                    recenter = false; robot.mouseMove(c.x, c.y)
                }
                g.update(dt, f, s, turn, KeyEvent.VK_SHIFT in keys)
                if (n != null && now / 1_000_000 - lastSend > 50) { n.send(g.x, g.y, g.level); lastSend = now / 1_000_000 }
            }
            r.render(px, g, t, n?.others?.values ?: emptyList(), scr == Scr.GAME)
        }
        panel.repaint()
        Thread.sleep(8)
    }
}
