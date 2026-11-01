package com.backrooms.game
// Desktop.kt - Swing client with animated main menu + HUD
// Server: java -jar backrooms.jar server [port]   Client: java -jar backrooms.jar [host[:port]]
import java.awt.*
import java.awt.event.*
import java.awt.font.TextAttribute
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import kotlin.math.*

private val GOLD = Color(244, 228, 130)

private fun txt(g2: Graphics2D, s: String, x0: Int, y: Int, f: Font, col: Color, center: Boolean = true) {
    g2.font = f
    val x = if (center) x0 - g2.fontMetrics.stringWidth(s) / 2 else x0
    g2.color = Color(0, 0, 0, 170); g2.drawString(s, x + 2, y + 2)
    g2.color = col; g2.drawString(s, x, y)
}

private fun spaced(f: Font, tracking: Float) = f.deriveFont(mapOf(TextAttribute.TRACKING to tracking))

fun main(args: Array<String>) {
    if (args.firstOrNull() == "server") return server(args.getOrNull(1)?.toInt() ?: PORT)

    val r = Renderer()
    val img = BufferedImage(r.W, r.H, BufferedImage.TYPE_INT_RGB)
    val px = (img.raster.dataBuffer as DataBufferInt).data
    val keys = ConcurrentHashMap.newKeySet<Int>()
    val g = Game(); val menuG = Game().also { it.x = 3.5; it.y = 3.5 }
    var net: Net? = null; var inMenu = true; var fps = 60.0; var playT = 0.0
    val ip = StringBuilder(args.firstOrNull() ?: "")
    var btn = Rectangle()

    fun start() {
        net?.close()
        val s = ip.toString().trim()
        net = if (s.isEmpty()) null else s.split(":").let { p -> Net(p[0], p.getOrNull(1)?.toIntOrNull() ?: PORT).also { it.start() } }
        g.reset(); playT = 0.0; inMenu = false
    }
    fun back() { net?.close(); net = null; inMenu = true }

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
            g2.color = Color(0, 0, 0, 120); g2.fillRect(0, 0, width, height)
            val cx = width / 2
            txt(g2, "THE BACKROOMS", cx, (height * 0.30).toInt(), Font("Serif", Font.BOLD, height / 8), GOLD)
            txt(g2, "LEVEL 0  ·  THE LOBBY", cx, (height * 0.37).toInt(),
                spaced(Font("SansSerif", Font.PLAIN, height / 34), 0.3f), Color(230, 220, 190))

            val bw = (width * 0.36).toInt(); val bh = height / 13; val bx = cx - bw / 2; val by = (height * 0.50).toInt()
            g2.color = Color(0, 0, 0, 160); g2.fillRoundRect(bx, by, bw, bh, 14, 14)
            g2.color = Color(244, 228, 130, 190); g2.stroke = BasicStroke(2f); g2.drawRoundRect(bx, by, bw, bh, 14, 14)
            val f = Font("Monospaced", Font.PLAIN, bh / 2)
            if (ip.isEmpty()) txt(g2, "Server IP   (empty = solo)", bx + 16, by + bh * 2 / 3, f, Color(150, 145, 120), false)
            else txt(g2, ip.toString() + if ((System.currentTimeMillis() / 500) % 2 == 0L) "_" else "", bx + 16, by + bh * 2 / 3, f, Color.WHITE, false)

            btn = Rectangle(bx, by + bh + 14, bw, bh)
            g2.color = GOLD; g2.fillRoundRect(btn.x, btn.y, btn.width, btn.height, 14, 14)
            g2.font = spaced(Font("SansSerif", Font.BOLD, bh / 2), 0.2f)
            val label = if (ip.isEmpty()) "PLAY SOLO" else "JOIN SERVER"
            g2.color = Color(40, 32, 8); g2.drawString(label, cx - g2.fontMetrics.stringWidth(label) / 2, btn.y + btn.height * 2 / 3)

            txt(g2, "W A S D  move   ·   SHIFT  run   ·   ← →  turn   ·   M  map   ·   ESC  menu",
                cx, height - height / 22, Font("SansSerif", Font.PLAIN, height / 46), Color(200, 195, 160))
        }

        fun drawHud(g2: Graphics2D) {
            val sz = max(12, height / 42)
            val f = spaced(Font("SansSerif", Font.BOLD, sz), 0.25f)
            txt(g2, "LEVEL 0", 18, 30, f, GOLD, false)
            val n = net
            val status = when {
                n == null -> "OFFLINE · SOLO"
                n.state == 0 -> "CONNECTING…"
                n.state == 1 -> "ONLINE · ${n.others.size + 1} PLAYERS"
                else -> "CONNECTION LOST"
            }
            txt(g2, status, 18, 30 + sz + 6, f, Color(225, 215, 180), false)
            txt(g2, "${fps.toInt()} FPS", 18, 30 + 2 * (sz + 6), Font("SansSerif", Font.PLAIN, sz - 2), Color(160, 155, 130), false)
            if (playT < 9) {
                val al = ((9 - playT) / 2).coerceIn(0.0, 1.0)
                txt(g2, "WASD move  ·  SHIFT run  ·  ← → turn  ·  ESC menu", width / 2, height - 36,
                    Font("SansSerif", Font.PLAIN, sz), Color(235, 225, 180, (al * 255).toInt()))
            }
        }
    }
    panel.preferredSize = Dimension(960, 540); panel.isFocusable = true
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
        override fun mouseClicked(e: MouseEvent) { if (inMenu && btn.contains(e.point)) start() }
    })
    JFrame("Backrooms").apply {
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
            g.update(dt, f, s, turn, KeyEvent.VK_SHIFT in keys)
            val n = net
            if (n != null && now / 1_000_000 - lastSend > 50) { n.send(g.x, g.y); lastSend = now / 1_000_000 }
            r.render(px, g, t, n?.others?.values ?: emptyList())
        }
        panel.repaint()
        Thread.sleep(8)
    }
}
