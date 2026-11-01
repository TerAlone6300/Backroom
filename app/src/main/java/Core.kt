package com.backrooms.game
// Core.kt - shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports)
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

const val SEED = 1337
const val PORT = 7777

// ---------- World: deterministic, so every device sees the same maze ----------
fun h(x: Int, y: Int, s: Int): Double {
    var n = x * 374761393 + y * 668265263 + s * 1274126177 + SEED
    n = (n xor (n ushr 13)) * 1274126177
    n = n xor (n ushr 16)
    return (n and 0xFFFF) / 65536.0
}

fun isWall(x: Int, y: Int): Boolean {
    val bx = Math.floorDiv(x, 6); val by = Math.floorDiv(y, 6)
    val lx = Math.floorMod(x, 6); val ly = Math.floorMod(y, 6)
    if (lx == 0 && ly == 0) return true // pillars
    val dv = 1 + (h(bx, by, 2) * 3).toInt()
    val dh = 1 + (h(bx, by, 4) * 3).toInt()
    if (lx == 0 && h(bx, by, 1) < 0.7 && ly !in dv..dv + 1) return true
    if (ly == 0 && h(bx, by, 3) < 0.7 && lx !in dh..dh + 1) return true
    return false
}

fun free(nx: Double, ny: Double, r: Double = 0.2) =
    !isWall(floor(nx - r).toInt(), floor(ny - r).toInt()) && !isWall(floor(nx + r).toInt(), floor(ny - r).toInt()) &&
    !isWall(floor(nx - r).toInt(), floor(ny + r).toInt()) && !isWall(floor(nx + r).toInt(), floor(ny + r).toInt())

/** Moves with wall sliding. Returns new (x, y). */
fun move(x: Double, y: Double, mx: Double, my: Double, sp: Double): Pair<Double, Double> {
    var nx = x; var ny = y
    if (free(x + mx * sp, y)) nx += mx * sp
    if (free(nx, y + my * sp)) ny += my * sp
    return nx to ny
}

// ---------- Server: relays positions between all clients ----------
fun server(port: Int = PORT) {
    val clients = ConcurrentHashMap<Int, PrintWriter>()
    var next = 1
    val ss = ServerSocket(port)
    println("Backrooms server listening on $port")
    while (true) {
        val s = ss.accept(); val id = next++
        Thread {
            val out = PrintWriter(s.getOutputStream(), true)
            clients[id] = out; out.println("ID $id")
            try {
                s.getInputStream().bufferedReader().forEachLine { l ->
                    clients.forEach { (i, w) -> if (i != id) w.println(l) }
                }
            } catch (_: IOException) {}
            clients.remove(id)
            clients.values.forEach { it.println("Q $id") }
        }.start()
    }
}

// ---------- Client networking (connects on a background thread: Android-safe) ----------
class Net(private val host: String, private val port: Int = PORT) {
    val others = ConcurrentHashMap<Int, DoubleArray>()
    @Volatile var id = 0
    @Volatile private var out: PrintWriter? = null

    fun start() {
        Thread {
            try {
                val s = Socket(host, port)
                out = PrintWriter(s.getOutputStream(), true)
                s.getInputStream().bufferedReader().forEachLine { l ->
                    val p = l.split(" ")
                    when (p[0]) {
                        "ID" -> id = p[1].toInt()
                        "P" -> others[p[1].toInt()] = doubleArrayOf(p[2].toDouble(), p[3].toDouble())
                        "Q" -> others.remove(p[1].toInt())
                    }
                }
            } catch (_: IOException) {}
        }.apply { isDaemon = true; start() }
    }

    fun send(x: Double, y: Double) { if (id != 0) out?.println("P $id $x $y") }
}

// ---------- Renderer: fills an ARGB int array ----------
fun rgb(r: Double, g: Double, b: Double, f: Double) =
    (0xFF shl 24) or (min(255, (r * f).toInt()) shl 16) or (min(255, (g * f).toInt()) shl 8) or min(255, (b * f).toInt())

class Renderer(val W: Int = 320, val H: Int = 180) {
    private val zb = DoubleArray(W)

    fun render(px: IntArray, x: Double, y: Double, a: Double, t: Double, others: Collection<DoubleArray>) {
        val fl = if (sin(t * 2.7) * sin(t * 13.1) > 0.9) 0.45 else 1.0 // flickering lights
        val dx = cos(a); val dy = sin(a); val plX = -dy * 0.66; val plY = dx * 0.66

        // floor + ceiling
        for (yy in H / 2 + 1 until H) {
            val rd = 0.5 * H / (yy - H / 2)
            val fog = fl / (1 + rd * rd * 0.05)
            for (xx in 0 until W) {
                val cx = 2.0 * xx / W - 1
                val wx = x + (dx + plX * cx) * rd; val wy = y + (dy + plY * cx) * rd
                val k = (floor(wx * 4).toInt() + floor(wy * 4).toInt()) and 1
                px[yy * W + xx] = if (k == 0) rgb(125.0, 105.0, 55.0, fog) else rgb(112.0, 94.0, 48.0, fog)
                val mxm = ((wx % 3) + 3) % 3; val mym = ((wy % 3) + 3) % 3
                val light = mxm in 1.0..2.0 && mym in 1.0..2.0
                px[(H - 1 - yy) * W + xx] =
                    if (light) rgb(255.0, 255.0, 235.0, max(fl, 0.35)) else rgb(205.0, 190.0, 140.0, fog)
            }
        }

        // walls (DDA)
        for (xx in 0 until W) {
            val cx = 2.0 * xx / W - 1
            val rx = dx + plX * cx; val ry = dy + plY * cx
            var mapX = floor(x).toInt(); var mapY = floor(y).toInt()
            val ddx = abs(1 / rx); val ddy = abs(1 / ry)
            val stepX = if (rx < 0) -1 else 1; val stepY = if (ry < 0) -1 else 1
            var sdx = if (rx < 0) (x - mapX) * ddx else (mapX + 1 - x) * ddx
            var sdy = if (ry < 0) (y - mapY) * ddy else (mapY + 1 - y) * ddy
            var side = 0; var n = 0
            while (n++ < 64) {
                if (sdx < sdy) { sdx += ddx; mapX += stepX; side = 0 } else { sdy += ddy; mapY += stepY; side = 1 }
                if (isWall(mapX, mapY)) break
            }
            val perp = if (side == 0) sdx - ddx else sdy - ddy
            zb[xx] = perp
            val lineH = (H / perp).toInt()
            val wallX = (if (side == 0) y + perp * ry else x + perp * rx).let { it - floor(it) }
            val stripe = if ((wallX * 8).toInt() % 2 == 0) 1.0 else 0.88
            val f = fl / (1 + perp * perp * 0.06) * (if (side == 1) 0.8 else 1.0) * stripe
            val c = rgb(205.0, 180.0, 85.0, f)
            for (yy in max(0, H / 2 - lineH / 2)..min(H - 1, H / 2 + lineH / 2)) px[yy * W + xx] = c
        }

        // other players: dark silhouettes, far to near
        others.sortedByDescending { hypot(it[0] - x, it[1] - y) }.forEach { o ->
            val rx = o[0] - x; val ry = o[1] - y
            val inv = 1.0 / (plX * dy - dx * plY)
            val tx = inv * (dy * rx - dx * ry); val ty = inv * (-plY * rx + plX * ry)
            if (ty > 0.2) {
                val sx = (W / 2 * (1 + tx / ty)).toInt(); val sh = (H / ty).toInt(); val sw = sh / 3
                for (col in max(0, sx - sw / 2)..min(W - 1, sx + sw / 2)) if (ty < zb[col])
                    for (yy in max(0, H / 2 - sh / 2)..min(H - 1, H / 2 + sh / 2))
                        px[yy * W + col] = rgb(25.0, 20.0, 15.0, 1.0)
            }
        }
    }
}
