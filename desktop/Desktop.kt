package com.backrooms.game
// Desktop.kt - Swing client.  Build: kotlinc Core.kt Desktop.kt -include-runtime -d backrooms.jar
// Server: java -jar backrooms.jar server [port]   Client: java -jar backrooms.jar [host] [port]
import java.awt.*
import java.awt.event.*
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.util.concurrent.ConcurrentHashMap
import javax.swing.*
import kotlin.math.*

fun main(args: Array<String>) {
    if (args.firstOrNull() == "server") return server(args.getOrNull(1)?.toInt() ?: PORT)
    val net = args.firstOrNull()?.let { Net(it, args.getOrNull(1)?.toInt() ?: PORT).also { n -> n.start() } }

    val r = Renderer()
    val img = BufferedImage(r.W, r.H, BufferedImage.TYPE_INT_RGB)
    val px = (img.raster.dataBuffer as DataBufferInt).data
    val keys = ConcurrentHashMap.newKeySet<Int>()
    var x = 1.5 + Math.random() * 4; var y = 1.5 + Math.random() * 4; var a = 0.0

    val panel = object : JPanel() {
        override fun paintComponent(g: Graphics) { g.drawImage(img, 0, 0, width, height, null) }
    }
    panel.preferredSize = Dimension(960, 540); panel.isFocusable = true
    panel.addKeyListener(object : KeyAdapter() {
        override fun keyPressed(e: KeyEvent) { keys.add(e.keyCode) }
        override fun keyReleased(e: KeyEvent) { keys.remove(e.keyCode) }
    })
    JFrame("Backrooms").apply {
        defaultCloseOperation = JFrame.EXIT_ON_CLOSE; add(panel); pack(); setLocationRelativeTo(null); isVisible = true
    }
    panel.requestFocusInWindow()

    var last = System.nanoTime(); var t = 0.0; var lastSend = 0L
    while (true) {
        val now = System.nanoTime(); val dt = (now - last) / 1e9; last = now; t += dt
        if (KeyEvent.VK_LEFT in keys) a -= 2.2 * dt
        if (KeyEvent.VK_RIGHT in keys) a += 2.2 * dt
        var mx = 0.0; var my = 0.0
        if (KeyEvent.VK_W in keys) { mx += cos(a); my += sin(a) }
        if (KeyEvent.VK_S in keys) { mx -= cos(a); my -= sin(a) }
        if (KeyEvent.VK_A in keys) { mx += sin(a); my -= cos(a) }
        if (KeyEvent.VK_D in keys) { mx -= sin(a); my += cos(a) }
        val sp = (if (KeyEvent.VK_SHIFT in keys) 4.0 else 2.2) * dt
        move(x, y, mx, my, sp).let { x = it.first; y = it.second }

        if (net != null && now / 1_000_000 - lastSend > 50) { net.send(x, y); lastSend = now / 1_000_000 }
        r.render(px, x, y, a, t, net?.others?.values ?: emptyList())
        panel.repaint()
        Thread.sleep(8)
    }
}
