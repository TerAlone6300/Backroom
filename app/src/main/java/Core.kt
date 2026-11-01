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

// ---------- Player state (shared by both clients) ----------
class Game {
    var x = 1.5; var y = 1.5
    @Volatile var a = 0.0
    var stamina = 1.0; var exhausted = false
    var bob = 0.0; var bobAmp = 0.0
    var showMap = true
    init { reset() }

    fun reset() {
        x = 1.5 + Math.random() * 4; y = 1.5 + Math.random() * 4
        a = 0.0; stamina = 1.0; exhausted = false; bob = 0.0; bobAmp = 0.0
    }

    /** fwd/str in -1..1 (analog), turn in rad/s */
    fun update(dt: Double, fwd: Double, str: Double, turn: Double, sprint: Boolean) {
        a += turn * dt
        var f = fwd; var s = str
        val len = hypot(f, s)
        if (len > 1) { f /= len; s /= len }
        val moving = len > 0.1
        val run = sprint && moving && !exhausted
        if (run) { stamina -= 0.28 * dt; if (stamina <= 0) { stamina = 0.0; exhausted = true } }
        else { stamina = min(1.0, stamina + 0.16 * dt); if (exhausted && stamina > 0.3) exhausted = false }
        val speed = if (run) 4.2 else 2.3
        move(x, y, cos(a) * f - sin(a) * s, sin(a) * f + cos(a) * s, speed * dt).let { x = it.first; y = it.second }
        if (moving) bob += dt * (if (run) 12.0 else 7.5)
        bobAmp += ((if (!moving) 0.0 else if (run) 1.0 else 0.6) - bobAmp) * min(1.0, dt * 8)
    }
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

// ---------- Client networking (background thread: Android-safe) ----------
class Net(private val host: String, private val port: Int = PORT) {
    val others = ConcurrentHashMap<Int, DoubleArray>()
    @Volatile var id = 0
    @Volatile var state = 0 // 0 connecting, 1 online, 2 failed/lost
    @Volatile private var out: PrintWriter? = null
    @Volatile private var sock: Socket? = null

    fun start() {
        Thread {
            try {
                val s = Socket(); s.connect(InetSocketAddress(host, port), 5000)
                sock = s; out = PrintWriter(s.getOutputStream(), true); state = 1
                s.getInputStream().bufferedReader().forEachLine { l ->
                    val p = l.split(" ")
                    when (p[0]) {
                        "ID" -> id = p[1].toInt()
                        "P" -> others[p[1].toInt()] = doubleArrayOf(p[2].toDouble(), p[3].toDouble())
                        "Q" -> others.remove(p[1].toInt())
                    }
                }
            } catch (_: Exception) {}
            state = 2
        }.apply { isDaemon = true; start() }
    }

    fun send(x: Double, y: Double) { if (id != 0) out?.println("P $id $x $y") }
    fun close() { try { sock?.close() } catch (_: IOException) {} }
}

// ---------- Procedural textures (64x64) ----------
fun pack(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

object Tex {
    const val S = 64
    val wall = IntArray(S * S); val carpet = IntArray(S * S); val ceil = IntArray(S * S); val panel = IntArray(S * S)
    private fun c(r: Double, g: Double, b: Double, f: Double) = pack((r * f).toInt(), (g * f).toInt(), (b * f).toInt())

    init {
        for (y in 0 until S) for (x in 0 until S) {
            val i = y * S + x
            // yellow wallpaper: stripes, diamond motif, water drips, baseboard
            var r = 214.0; var g = 190.0; var b = 98.0
            var f = 0.92 + 0.16 * h(x, y, 21)
            if (x % 16 < 2) { r *= 0.88; g *= 0.88; b *= 0.8 }
            if (abs(x % 16 - 8) + abs(y % 16 - 8) <= 3) f *= 0.93
            val drip = h(x / 3, 0, 22)
            if (drip < 0.3) f *= 1 - 0.3 * (y / S.toDouble()).pow(1.5) * (1 - drip)
            if (y >= 56) { r = 92.0; g = 62.0; b = 34.0; f = 0.9 + 0.2 * h(x, y, 23); if (y == 56) f = 1.3; if (y >= 62) f *= 0.8 }
            wall[i] = c(r, g, b, f)
            // damp carpet
            var cf = (0.82 + 0.3 * h(x, y, 31)) * (0.9 + 0.2 * h(x / 2, y / 2, 32))
            if (x == 0 || y == 0) cf *= 0.85
            carpet[i] = c(122.0, 98.0, 50.0, cf)
            // drop-ceiling tile
            var tf = 0.95 + 0.1 * h(x, y, 41)
            if (h(x, y, 42) < 0.05) tf *= 0.75
            if (x < 2 || y < 2) tf *= 0.72
            ceil[i] = c(222.0, 214.0, 176.0, tf)
            // fluorescent panel
            panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(196, 196, 186)
                       else if (x % 16 == 0) pack(235, 235, 222) else pack(255, 255, 244)
        }
    }
}

// ---------- Renderer: textured raycaster, pooled lighting, fog, vignette, grain ----------
class Renderer(val W: Int = 480, val H: Int = 270) {
    private val zb = DoubleArray(W)
    private val vig = IntArray(W * H)
    private var grain = 1234567

    init {
        for (y in 0 until H) for (x in 0 until W) {
            val nx = (x - W / 2.0) / (W / 2.0); val ny = (y - H / 2.0) / (H / 2.0)
            vig[y * W + x] = ((1 - 0.38 * (nx * nx + ny * ny)).coerceIn(0.3, 1.0) * 256).toInt()
        }
    }

    private fun mul(c: Int, f: Double): Int {
        val k = (f * 256).toInt().coerceIn(0, 640)
        return pack((((c shr 16) and 255) * k) shr 8, (((c shr 8) and 255) * k) shr 8, ((c and 255) * k) shr 8)
    }

    private fun flick(kx: Int, ky: Int, t: Double): Double {
        val v = h(kx, ky, 9)
        return when {
            v < 0.12 -> if (sin(t * (14 + v * 60) + v * 50) > 0.2) 0.2 else 1.0 // broken, stuttering
            v < 0.2 -> 0.92 + 0.08 * sin(t * 40)                                 // humming
            v < 0.27 -> 0.0                                                      // dead
            else -> 1.0
        }
    }

    /** Light pooled around each ceiling panel (one per 3x3 cells). */
    private fun lightAt(wx: Double, wy: Double, t: Double): Double {
        val kx = floor((wx - 1.5) / 3 + 0.5).toInt(); val ky = floor((wy - 1.5) / 3 + 0.5).toInt()
        val ddx = wx - (kx * 3 + 1.5); val ddy = wy - (ky * 3 + 1.5)
        return 0.18 + flick(kx, ky, t) / (1 + (ddx * ddx + ddy * ddy) * 0.4)
    }

    private fun put(px: IntArray, xx: Int, yy: Int, col: Int, a: Int) {
        if (xx < 0 || yy < 0 || xx >= W || yy >= H) return
        val d = px[yy * W + xx]
        px[yy * W + xx] = pack(
            ((((d shr 16) and 255) * (256 - a)) + (((col shr 16) and 255) * a)) shr 8,
            ((((d shr 8) and 255) * (256 - a)) + (((col shr 8) and 255) * a)) shr 8,
            (((d and 255) * (256 - a)) + ((col and 255) * a)) shr 8)
    }

    private fun rect(px: IntArray, x0: Int, y0: Int, w: Int, hh: Int, col: Int, a: Int) {
        for (yy in y0 until y0 + hh) for (xx in x0 until x0 + w) put(px, xx, yy, col, a)
    }

    fun render(px: IntArray, g: Game, t: Double, others: Collection<DoubleArray>, hud: Boolean = true) {
        val x = g.x; val y = g.y; val a = g.a
        val hz = (H / 2 + sin(g.bob) * g.bobAmp * 3.0).toInt()
        val gl = if (sin(t * 2.7) * sin(t * 13.1) > 0.93) 0.5 else 1.0 // rare global flicker
        val dx = cos(a); val dy = sin(a); val plX = -dy * 0.66; val plY = dx * 0.66

        // floor (carpet) + ceiling (tiles & light panels)
        for (yy in 0 until H) {
            val dr = yy - hz
            if (dr == 0) { for (xx in 0 until W) px[yy * W + xx] = pack(10, 8, 4); continue }
            val rd = 0.5 * H / abs(dr)
            val fog = 1.0 / (1 + rd * rd * 0.015)
            val isFloor = dr > 0
            for (xx in 0 until W) {
                val cx = 2.0 * xx / W - 1
                val wx = x + (dx + plX * cx) * rd; val wy = y + (dy + plY * cx) * rd
                val tx = ((wx - floor(wx)) * 64).toInt() and 63; val ty = ((wy - floor(wy)) * 64).toInt() and 63
                val l = lightAt(wx, wy, t) * gl
                px[yy * W + xx] = if (isFloor) mul(Tex.carpet[ty * 64 + tx], l * fog * 0.85)
                else if (Math.floorMod(floor(wx).toInt(), 3) == 1 && Math.floorMod(floor(wy).toInt(), 3) == 1)
                    mul(Tex.panel[ty * 64 + tx], l * max(fog, 0.6))
                else mul(Tex.ceil[ty * 64 + tx], l * fog)
            }
        }

        // textured walls (DDA)
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
            val perp = (if (side == 0) sdx - ddx else sdy - ddy).coerceAtLeast(0.05)
            zb[xx] = perp
            val hx = x + perp * rx; val hy = y + perp * ry
            var tx = (((if (side == 0) hy else hx).let { it - floor(it) }) * 64).toInt() and 63
            if ((side == 0 && rx > 0) || (side == 1 && ry < 0)) tx = 63 - tx
            val br = lightAt(hx, hy, t) * gl / (1 + perp * perp * 0.015) * (if (side == 1) 0.82 else 1.0)
            val lineH = (H / perp).toInt().coerceAtLeast(1)
            val start = hz - lineH / 2
            for (yy in max(0, start)..min(H - 1, start + lineH)) {
                val ty = ((yy - start).toLong() * 64 / lineH).toInt().coerceIn(0, 63)
                px[yy * W + xx] = mul(Tex.wall[ty * 64 + tx], br)
            }
        }

        // other players: tall dark figures with glowing eyes
        others.sortedByDescending { hypot(it[0] - x, it[1] - y) }.forEach { o ->
            val rx = o[0] - x; val ry = o[1] - y
            val inv = 1.0 / (plX * dy - dx * plY)
            val tx = inv * (dy * rx - dx * ry); val ty = inv * (-plY * rx + plX * ry)
            if (ty > 0.2) {
                val sx = (W / 2 * (1 + tx / ty)).toInt()
                val full = H / ty; val sh = (full * 0.9).toInt(); val sw = (sh * 0.42).toInt().coerceAtLeast(2)
                val bottom = hz + (full / 2).toInt(); val top = bottom - sh
                val body = mul(pack(16, 14, 10), 0.6 + 0.4 / (1 + ty * 0.3))
                for (col in max(0, sx - sw / 2)..min(W - 1, sx + sw / 2)) {
                    if (ty >= zb[col]) continue
                    val u = (col - sx) / (sw / 2.0)
                    for (row in max(0, top)..min(H - 1, bottom)) {
                        val v = (row - top).toDouble() / sh
                        val inside = when {
                            v < 0.17 -> (u / 0.42) * (u / 0.42) + ((v - 0.09) / 0.085) * ((v - 0.09) / 0.085) < 1
                            v < 0.22 -> abs(u) < 0.18
                            v < 0.62 -> abs(u) < 0.8 - (v - 0.22) * 0.5
                            else -> abs(abs(u) - 0.35) < 0.22
                        }
                        if (!inside) continue
                        val eye = v in 0.075..0.10 && abs(abs(u) - 0.17) < 0.07
                        px[row * W + col] = if (eye) pack(255, 255, 225) else body
                    }
                }
            }
        }

        // post: vignette + film grain
        var s = grain
        for (i in px.indices) {
            val c = px[i]; val v = vig[i]
            s = s * 1664525 + 1013904223
            val nz = ((s ushr 28) and 15) - 8
            px[i] = pack((((c shr 16) and 255) * v shr 8) + nz, (((c shr 8) and 255) * v shr 8) + nz, ((c and 255) * v shr 8) + nz)
        }
        grain = s

        if (hud) drawHud(px, g, others)
    }

    // ---------- shape-only HUD (text is drawn by each platform) ----------
    private fun drawHud(px: IntArray, g: Game, others: Collection<DoubleArray>) {
        if (g.showMap) {
            val R = 8; val cs = 3; val size = (2 * R + 1) * cs
            val ox = W - size - 8; val oy = 8
            val fx = floor(g.x).toInt(); val fy = floor(g.y).toInt()
            rect(px, ox - 2, oy - 2, size + 4, size + 4, pack(0, 0, 0), 150)
            for (cy in -R..R) for (cx in -R..R) {
                if (isWall(fx + cx, fy + cy)) rect(px, ox + (cx + R) * cs, oy + (cy + R) * cs, cs, cs, pack(214, 190, 98), 210)
            }
            for (o in others) if (abs(o[0] - g.x) < R && abs(o[1] - g.y) < R)
                rect(px, ox + ((o[0] - fx + R) * cs).toInt() - 1, oy + ((o[1] - fy + R) * cs).toInt() - 1, 3, 3, pack(230, 60, 40), 256)
            val pxx = ox + ((g.x - fx + R) * cs).toInt(); val pyy = oy + ((g.y - fy + R) * cs).toInt()
            for (k in 2..6) rect(px, pxx + (cos(g.a) * k).toInt(), pyy + (sin(g.a) * k).toInt(), 1, 1, pack(255, 255, 255), 200)
            rect(px, pxx - 1, pyy - 1, 3, 3, pack(255, 255, 255), 256)
        }
        if (g.stamina < 0.999) {
            val bw = 90; val bx = (W - bw) / 2; val by = H - 16
            rect(px, bx - 2, by - 2, bw + 4, 8, pack(0, 0, 0), 150)
            rect(px, bx, by, (bw * g.stamina).toInt(), 4,
                if (g.exhausted) pack(210, 60, 40) else pack(240, 225, 140), 240)
        }
    }
}
