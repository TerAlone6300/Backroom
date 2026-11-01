package com.backrooms.game
// Desktop.kt - Swing client: level select menu, mouse look (360° + up/down), HUD
// Server: java -jar backrooms.jar server [port]   Client: java -jar backrooms.jar [host[:port]]
import java.awt.*
import java.awt.event.*
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
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

fun main(args: Array<String>) {
    if (args.firstOrNull() == "server") return server(args.getOrNull(1)?.toInt() ?: PORT)

    val r = Renderer()
    val img = BufferedImage(r.W, r.H, BufferedImage.TYPE_INT_RGB)
    val px = (img.raster.dataBuffer as DataBufferInt).data
    val keys = ConcurrentHashMap.newKeySet<Int>()
    val g = Game(); val menuG = Game()
    var net: Net? = null; var inMenu = true; var fps = 60.0; var playT = 0.0
    val ip = StringBuilder(args.firstOrNull() ?: "")
    var btn = Rectangle(); val cardRects = Array(LEVELS.size) { Rectangle() }
    val robot = try { Robot() } catch (_: Exception) { null }
    val blank = Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "blank")
    var recenter = false


    val panel = object : JPanel() {
        override fun paintComponent(gr: Graphics) {
            val g2 = gr as Graphics2D
            g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            g2.drawImage(img, 0, 0, width, height, null)
            if (inMenu) drawMenu(g2) else drawHud(g2)
        }

        fun drawMenu(g2: Graphics2D) {
            val w = width; val hh = height; val cx = w / 2
            val ac = Color(LEVELS[0].accent)
            g2.paint = GradientPaint(0f, 0f, Color(0, 0, 0, 210), 0f, hh.toFloat(), Color(0, 0, 0, 170)); g2.fillRect(0, 0, w, hh)
            txt(g2, "THE BACKROOMS", cx, (hh * 0.15).toInt(), Font("Serif", Font.BOLD, hh / 9), ac)
            txt(g2, "POCKET EDITION  ·  MULTIPLAYER", cx, (hh * 0.21).toInt(), spaced(Font("SansSerif", Font.PLAIN, hh / 40), 0.3f), Color(220, 212, 185))

            val cols = 4; val gap = (w * 0.012).toInt(); val cw = (w * 0.22).toInt(); val ch = (hh * 0.125).toInt()
            val x0 = (w - (cols * cw + (cols - 1) * gap)) / 2; val cy = (hh * 0.24).toInt()
            for (i in LEVELS.indices) {
                val d = LEVELS[i]; val c = Color(d.accent); val on = Progress.found[i]
                val rc = Rectangle(x0 + (i % cols) * (cw + gap), cy + (i / cols) * (ch + gap), cw, ch); cardRects[i] = rc
                g2.color = Color(0, 0, 0, if (on) 175 else 130); g2.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14)
                g2.paint = GradientPaint(0f, rc.y.toFloat(), Color(c.red, c.green, c.blue, if (on) 70 else 20),
                    0f, (rc.y + rc.height).toFloat(), Color(c.red, c.green, c.blue, 0))
                g2.fillRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14)
                g2.stroke = BasicStroke(if (on) 2.5f else 1.2f); g2.color = Color(c.red, c.green, c.blue, if (on) 255 else 90)
                g2.drawRoundRect(rc.x, rc.y, rc.width, rc.height, 14, 14)
                val px0 = rc.x + 12
                txt(g2, d.label, px0, rc.y + ch * 27 / 100, spaced(Font("SansSerif", Font.BOLD, hh / 60), 0.25f), if (on) c else Color(c.red, c.green, c.blue, 120), false)
                val nm = if (on) d.name else "???"
                var nf = Font("Serif", Font.BOLD, hh / 36); g2.font = nf
                while (g2.fontMetrics.stringWidth(nm) > rc.width - 22 && nf.size > 8) { nf = nf.deriveFont(nf.size2D - 1f); g2.font = nf }
                txt(g2, nm, px0, rc.y + ch * 58 / 100, nf, if (on) Color.WHITE else Color(150, 150, 150), false)
                txt(g2, if (on) d.entities else "???", px0, rc.y + ch - 9, spaced(Font("SansSerif", Font.BOLD, hh / 68), 0.15f),
                    if (!on) Color(130, 130, 130) else if (d.entities == "NONE") Color(150, 200, 150) else Color(235, 110, 90), false)
            }

            txt(g2, "Level 0 is endless and empty. Some walls aren't solid — push into one to noclip out.", cx, (hh * 0.715).toInt(),
                Font("SansSerif", Font.ITALIC, hh / 48), Color(210, 200, 170))
            val bh = hh / 13; val iw = (w * 0.30).toInt(); val bw = (w * 0.16).toInt(); val by = (hh * 0.75).toInt()
            val bx = (w - (iw + 16 + bw)) / 2
            g2.color = Color(0, 0, 0, 170); g2.fillRoundRect(bx, by, iw, bh, 14, 14)
            g2.color = Color(ac.red, ac.green, ac.blue, 190); g2.stroke = BasicStroke(2f); g2.drawRoundRect(bx, by, iw, bh, 14, 14)
            val f = Font("Monospaced", Font.PLAIN, bh / 2)
            if (ip.isEmpty()) txt(g2, "Server IP   (empty = solo)", bx + 16, by + bh * 2 / 3, f, Color(150, 145, 120), false)
            else txt(g2, ip.toString() + if ((System.currentTimeMillis() / 500) % 2 == 0L) "_" else "", bx + 16, by + bh * 2 / 3, f, Color.WHITE, false)
            btn = Rectangle(bx + iw + 16, by, bw, bh)
            g2.paint = GradientPaint(0f, by.toFloat(), ac, 0f, (by + bh).toFloat(), ac.darker()); g2.fillRoundRect(btn.x, btn.y, btn.width, btn.height, 14, 14)
            g2.font = spaced(Font("SansSerif", Font.BOLD, bh / 2), 0.2f)
            val label = if (ip.isEmpty()) "NOCLIP IN" else "JOIN"
            g2.color = Color(30, 24, 6); g2.drawString(label, btn.x + (btn.width - g2.fontMetrics.stringWidth(label)) / 2, btn.y + btn.height * 2 / 3)

            txt(g2, "ENTER  start   ·   MOUSE / ARROWS  look   ·   W A S D  move   ·   SHIFT  run   ·   M  map   ·   ESC  menu",
                cx, hh - hh / 24, Font("SansSerif", Font.PLAIN, hh / 56), Color(200, 195, 160))
        }

        fun drawHud(g2: Graphics2D) {
            val w = width; val hh = height; val d = LEVELS[g.level]; val ac = Color(d.accent)
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
            txt(g2, "${fps.toInt()} FPS", 30, 14 + 3 * sz + 14, Font("SansSerif", Font.PLAIN, sz - 3), Color(160, 155, 130), false)

            g2.color = Color(255, 255, 255, 120); g2.fillOval(w / 2 - 2, hh / 2 - 2, 4, 4) // crosshair dot

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
                    txt(g2, "YOU WERE CAUGHT", w / 2, (hh * 0.48).toInt(), Font("Serif", Font.BOLD, hh / 10), Color(235, 70, 55))
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

    fun start() {
        net?.close()
        val s = ip.toString().trim()
        net = if (s.isEmpty()) null else s.split(":").let { p -> Net(p[0], p.getOrNull(1)?.toIntOrNull() ?: PORT).also { it.start() } }
        g.enter(0); playT = 0.0; inMenu = false; recenter = true
        if (robot != null) panel.cursor = blank
    }
    fun back() { net?.close(); net = null; inMenu = true; panel.cursor = Cursor.getDefaultCursor() }

    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) {
            if (inMenu) when (e.keyCode) {
                KeyEvent.VK_ENTER -> start()
                KeyEvent.VK_BACK_SPACE -> if (ip.isNotEmpty()) ip.setLength(ip.length - 1)
                KeyEvent.VK_ESCAPE -> System.exit(0)
            } else when (e.keyCode) {
                KeyEvent.VK_ESCAPE -> back()
                KeyEvent.VK_M -> g.showMap = !g.showMap
            }
            keys.add(e.keyCode)
        }
        override fun keyReleased(e: KeyEvent) { keys.remove(e.keyCode) }
        override fun keyTyped(e: KeyEvent) {
            if (inMenu && (e.keyChar.isLetterOrDigit() || e.keyChar in ".:-_") && ip.length < 40) ip.append(e.keyChar)
        }
    })
    panel.addMouseListener(object : MouseAdapter() {
        override fun mouseClicked(e: MouseEvent) {
            if (!inMenu) return
            if (btn.contains(e.point)) start()
        }
    })
    val frame = JFrame("Backrooms").apply {
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE; add(panel); pack(); setLocationRelativeTo(null); isVisible = true
    }
    panel.requestFocusInWindow()

    var last = System.nanoTime(); var t = 0.0; var lastSend = 0L
    while (true) {
        val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now; t += dt
        if (dt > 0) fps = fps * 0.9 + (1 / dt) * 0.1
        if (inMenu) {
            menuG.a += dt * 0.12; menuG.bob += dt * 2.0; menuG.bobAmp = 0.5
            r.render(px, menuG, t, emptyList(), false)
        } else {
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
            val n = net
            if (n != null && now / 1_000_000 - lastSend > 50) { n.send(g.x, g.y, g.level); lastSend = now / 1_000_000 }
            r.render(px, g, t, n?.others?.values ?: emptyList())
        }
        panel.repaint()
        Thread.sleep(8)
    }
}
