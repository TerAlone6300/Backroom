package com.backrooms.game
// Core.kt - shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports)
import java.io.*
import java.net.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.*

const val SEED = 1337
const val PORT = 7777

fun h(x: Int, y: Int, s: Int): Double {
    var n = x * 374761393 + y * 668265263 + s * 1274126177 + SEED
    n = (n xor (n ushr 13)) * 1274126177
    n = n xor (n ushr 16)
    return (n and 0xFFFF) / 65536.0
}

// ---------- Levels ----------
/** eType: 0 none, 1 Hound (chases on sight), 2 Smiler (moves when unseen), 3 Skin-Stealer (looks like a player, closes in) */
class LevelDef(val id: Int, val label: String, val name: String, val blurb: String, val entities: String, val eType: Int, val eCount: Int,
               val period: Int, val ambient: Double, val falloff: Double, val fogK: Double, val tint: IntArray, val accent: Int)

val LEVELS = listOf(
    LevelDef(0, "LEVEL 0", "THE LOBBY", "Endless yellow rooms. Humming lights. Damp carpet.", "NONE", 0, 0, 3, 0.18, 0.40, 0.015, intArrayOf(256, 256, 256), 0xF4E482),
    LevelDef(1, "LEVEL 1", "HABITABLE ZONE", "Cold concrete halls. Something is breathing in the dark.", "HOUNDS", 1, 3, 5, 0.17, 0.30, 0.018, intArrayOf(232, 250, 244), 0x9FD4C8),
    LevelDef(2, "LEVEL 2", "PIPE DREAMS", "Hot, rusted tunnels. Don't look away.", "SMILERS", 2, 2, 4, 0.07, 0.45, 0.030, intArrayOf(256, 205, 150), 0xF0A050),
    LevelDef(3, "LEVEL 3", "ELECTRICAL STATION", "Humming transformers in a grid of machines. Hunters follow the sparks.", "HOUNDS", 1, 4, 4, 0.06, 0.50, 0.028, intArrayOf(230, 240, 256), 0x6FA8FF),
    LevelDef(4, "LEVEL 4", "ABANDONED OFFICE", "Dead cubicles, stained ceilings. Someone is still typing.", "SKIN-STEALERS", 3, 2, 4, 0.12, 0.38, 0.020, intArrayOf(256, 252, 240), 0xD8CFA8),
    LevelDef(5, "LEVEL 5", "TERROR HOTEL", "Red carpet, endless corridors, a door for every secret.", "SMILERS", 2, 3, 4, 0.08, 0.45, 0.026, intArrayOf(256, 225, 190), 0xD04A4A),
    LevelDef(6, "LEVEL 37", "THE POOLROOMS", "Warm tiled halls and still water. Finally, peace.", "NONE", 0, 0, 5, 0.55, 0.12, 0.006, intArrayOf(236, 250, 256), 0x6FD8E8)
)

// ---------- Worlds: deterministic per level, so every device sees the same maze ----------
private fun wall0(x: Int, y: Int): Boolean { // The Lobby: 6x6 rooms, 2-wide doors, pillars
    val bx = Math.floorDiv(x, 6); val by = Math.floorDiv(y, 6)
    val lx = Math.floorMod(x, 6); val ly = Math.floorMod(y, 6)
    if (lx == 0 && ly == 0) return true
    val dv = 1 + (h(bx, by, 2) * 3).toInt(); val dh = 1 + (h(bx, by, 4) * 3).toInt()
    if (lx == 0 && h(bx, by, 1) < 0.7 && ly !in dv..dv + 1) return true
    if (ly == 0 && h(bx, by, 3) < 0.7 && lx !in dh..dh + 1) return true
    return false
}

private fun wall1(x: Int, y: Int): Boolean { // Habitable Zone: huge hall, pillar grid, long concrete walls
    if (Math.floorMod(x, 5) == 0 && Math.floorMod(y, 5) == 0) return true
    val bx = Math.floorDiv(x, 10); val by = Math.floorDiv(y, 10)
    val lx = Math.floorMod(x, 10); val ly = Math.floorMod(y, 10)
    val gv = 1 + (h(bx, by, 12) * 5).toInt(); val gh = 1 + (h(bx, by, 14) * 5).toInt()
    if (lx == 0 && h(bx, by, 11) < 0.45 && ly !in gv..gv + 3) return true
    if (ly == 0 && h(bx, by, 13) < 0.45 && lx !in gh..gh + 3) return true
    return false
}

private fun wall2(x: Int, y: Int): Boolean { // Pipe Dreams: tight 2-wide tunnels, 1-cell doors
    val bx = Math.floorDiv(x, 3); val by = Math.floorDiv(y, 3)
    val lx = Math.floorMod(x, 3); val ly = Math.floorMod(y, 3)
    if (lx == 0 && ly == 0) return true
    if (lx == 0 && h(bx, by, 21) < 0.78 && ly != 1 + (h(bx, by, 22) * 2).toInt()) return true
    if (ly == 0 && h(bx, by, 23) < 0.78 && lx != 1 + (h(bx, by, 24) * 2).toInt()) return true
    return false
}

private fun wall3(x: Int, y: Int): Boolean { // Electrical Station: 3x3 machines in a grid of 2-wide streets
    val bx = Math.floorDiv(x, 5); val by = Math.floorDiv(y, 5)
    return Math.floorMod(x, 5) in 1..3 && Math.floorMod(y, 5) in 1..3 && h(bx, by, 31) < 0.85
}

private fun wall4(x: Int, y: Int): Boolean { // Abandoned Office: cubicle partitions with 1-cell openings
    val bx = Math.floorDiv(x, 4); val by = Math.floorDiv(y, 4)
    val lx = Math.floorMod(x, 4); val ly = Math.floorMod(y, 4)
    if (lx == 0 && ly == 0) return true
    if (lx == 0 && h(bx, by, 41) < 0.65 && ly != 3) return true
    if (ly == 0 && h(bx, by, 43) < 0.65 && lx != 3) return true
    return false
}

private fun wall5(x: Int, y: Int): Boolean { // Terror Hotel: long corridors, rooms with a door each
    val bx = Math.floorDiv(x, 8)
    val lx = Math.floorMod(x, 8); val ly = Math.floorMod(y, 7)
    if (Math.floorMod(bx, 3) == 0 && lx < 2) return false // vertical corridor
    if (ly < 2) return false                               // horizontal corridor
    if (ly == 2) return lx !in 4..5                        // front wall with a door
    if (ly == 6) return true                               // back wall
    return lx == 2                                         // partition between rooms
}

private fun wall6(x: Int, y: Int): Boolean { // Poolrooms: bright halls, columns, wide archways
    if (Math.floorMod(x, 7) == 0 && Math.floorMod(y, 7) == 0) return true
    val bx = Math.floorDiv(x, 14); val by = Math.floorDiv(y, 14)
    val lx = Math.floorMod(x, 14); val ly = Math.floorMod(y, 14)
    val gv = 2 + (h(bx, by, 62) * 4).toInt(); val gh = 2 + (h(bx, by, 64) * 4).toInt()
    if (lx == 0 && h(bx, by, 61) < 0.6 && ly !in gv..gv + 5) return true
    if (ly == 0 && h(bx, by, 63) < 0.6 && lx !in gh..gh + 5) return true
    return false
}

fun isWall(lv: Int, x: Int, y: Int) = when (lv) {
    0 -> wall0(x, y); 1 -> wall1(x, y); 2 -> wall2(x, y); 3 -> wall3(x, y); 4 -> wall4(x, y); 5 -> wall5(x, y); else -> wall6(x, y)
}

/** A few walls are "not quite solid": push into one for a while and you noclip to the next level. */
fun unstable(lv: Int, x: Int, y: Int) = isWall(lv, x, y) && h(x, y, 77 + lv) < 0.03

object Progress { val found = BooleanArray(LEVELS.size).also { it[0] = true } } // levels discovered this session

fun free(lv: Int, nx: Double, ny: Double, r: Double = 0.2) =
    !isWall(lv, floor(nx - r).toInt(), floor(ny - r).toInt()) && !isWall(lv, floor(nx + r).toInt(), floor(ny - r).toInt()) &&
    !isWall(lv, floor(nx - r).toInt(), floor(ny + r).toInt()) && !isWall(lv, floor(nx + r).toInt(), floor(ny + r).toInt())

fun move(lv: Int, x: Double, y: Double, mx: Double, my: Double, sp: Double): Pair<Double, Double> {
    var nx = x; var ny = y
    if (free(lv, x + mx * sp, y)) nx += mx * sp
    if (free(lv, nx, y + my * sp)) ny += my * sp
    return nx to ny
}

fun los(lv: Int, x0: Double, y0: Double, x1: Double, y1: Double): Boolean {
    val n = (hypot(x1 - x0, y1 - y0) / 0.25).toInt() + 1
    for (i in 1 until n) {
        val t = i.toDouble() / n
        if (isWall(lv, floor(x0 + (x1 - x0) * t).toInt(), floor(y0 + (y1 - y0) * t).toInt())) return false
    }
    return true
}

// ---------- Entities & player state ----------
/** type 1 = Hound (Level 1), type 2 = Smiler (Level 2) */
class Entity(var x: Double, var y: Double, val type: Int) {
    var dir = Math.random() * 6.283; var chase = false
}

class Game {
    var x = 1.5; var y = 1.5
    @Volatile var a = 0.0
    @Volatile var pitch = 0.0 // -1 (down) .. 1 (up)
    var level = 0; var levelT = 0.0
    var stamina = 1.0; var exhausted = false
    var bob = 0.0; var bobAmp = 0.0
    var showMap = true
    var dead = false; var deadT = 0.0; var danger = 0.0
    var noclip = 0.0; var flash = 0.0; var arrival = "YOU SLIPPED OUT OF REALITY"
    val entities = CopyOnWriteArrayList<Entity>()
    init { enter(0) }

    private var ncx = 0; private var ncy = 0
    /** Where a given unstable wall leads is fixed (same for every player), usually the next level, sometimes a jump. */
    private fun noclipTarget(): Int {
        val n = LEVELS.size; val r = h(ncx, ncy, 99 + level)
        return if (r < 0.6) (level + 1) % n else (level + 1 + ((r * 1000).toInt() % (n - 1))) % n
    }

    private fun findFree(cx: Double, cy: Double): Pair<Double, Double> {
        for (r in 0..40) for (dy in -r..r) for (dx in -r..r) {
            if (max(abs(dx), abs(dy)) != r) continue
            val px = floor(cx).toInt() + dx + 0.5; val py = floor(cy).toInt() + dy + 0.5
            if (free(level, px, py, 0.3)) return px to py
        }
        return cx to cy
    }

    fun enter(lv: Int, arrived: String = "YOU SLIPPED OUT OF REALITY") {
        level = lv; levelT = 0.0; dead = false; deadT = 0.0; danger = 0.0; noclip = 0.0; arrival = arrived
        stamina = 1.0; exhausted = false; pitch = 0.0; a = Math.random() * 6.283
        Progress.found[lv] = true
        // Level 0 is isolating: everyone lands thousands of cells apart in the endless maze.
        // Other levels drop you near the origin so players can regroup.
        val p = if (lv == 0) findFree((Math.random() - 0.5) * 40000, (Math.random() - 0.5) * 40000)
                else findFree(4 + Math.random() * 3, 4 + Math.random() * 3)
        x = p.first; y = p.second
        entities.clear()
        repeat(LEVELS[lv].eCount) {
            val ang = Math.random() * 6.283; val d = 11 + Math.random() * 8
            val q = findFree(x + cos(ang) * d, y + sin(ang) * d)
            entities.add(Entity(q.first, q.second, LEVELS[lv].eType))
        }
    }

    /** fwd/str in -1..1 (analog), turn in rad/s */
    fun update(dt: Double, fwd: Double, str: Double, turn: Double, sprint: Boolean) {
        levelT += dt; flash = max(0.0, flash - dt / 1.2)
        if (dead) { deadT += dt; if (deadT > 3.5) enter(level); return }
        a += turn * dt
        pitch = pitch.coerceIn(-1.0, 1.0)
        var f = fwd; var s = str
        val len = hypot(f, s)
        if (len > 1) { f /= len; s /= len }
        val moving = len > 0.1
        val run = sprint && moving && !exhausted
        if (run) { stamina -= 0.28 * dt; if (stamina <= 0) { stamina = 0.0; exhausted = true } }
        else { stamina = min(1.0, stamina + 0.16 * dt); if (exhausted && stamina > 0.3) exhausted = false }
        val speed = if (run) 4.2 else 2.3
        move(level, x, y, cos(a) * f - sin(a) * s, sin(a) * f + cos(a) * s, speed * dt).let { x = it.first; y = it.second }
        if (moving) bob += dt * (if (run) 12.0 else 7.5)
        bobAmp += ((if (!moving) 0.0 else if (run) 1.0 else 0.6) - bobAmp) * min(1.0, dt * 8)

        // noclip: keep pushing into an unstable wall to slip through to the next level
        var pushing = false
        if (moving) {
            val mxn = cos(a) * f - sin(a) * s; val myn = sin(a) * f + cos(a) * s; val ml = hypot(mxn, myn)
            if (ml > 0.01) {
                val cx = floor(x + mxn / ml * 0.34).toInt(); val cy = floor(y + myn / ml * 0.34).toInt()
                pushing = unstable(level, cx, cy)
                if (pushing) { ncx = cx; ncy = cy }
            }
        }
        noclip = if (pushing) min(1.0, noclip + dt / 2.2) else max(0.0, noclip - dt * 1.2)
        if (noclip >= 1.0) { enter(noclipTarget(), "YOU NOCLIPPED THROUGH THE WALL"); flash = 1.0; return }

        var dg = 0.0
        for (e in entities) {
            val ex = x - e.x; val ey = y - e.y; val d = hypot(ex, ey).coerceAtLeast(0.01)
            if (e.type == 3) { // Skin-Stealer: walks like a player, patiently closes in
                if (d < 16 && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 22) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 2
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 2.0 else 0.8) * dt)
                if (r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, (1 - d / 6).coerceAtLeast(0.0))
            } else if (e.type == 1) { // Hound: wanders, hunts you on sight, collides with walls
                if (d < 10 && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 16) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 3
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 3.4 else 1.0) * dt)
                if (!e.chase && r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, 1 - d / 10)
            } else { // Smiler: only moves while you are NOT looking at it, drifts through walls
                var df = (atan2(e.y - y, e.x - x) - a) % (2 * PI)
                if (df > PI) df -= 2 * PI; if (df < -PI) df += 2 * PI
                val seen = abs(df) < 0.8 && los(level, x, y, e.x, e.y)
                if (!seen) { e.x += ex / d * 1.9 * dt; e.y += ey / d * 1.9 * dt } else dg = max(dg, 1 - d / 14)
            }
            if (d < 0.6) { dead = true; deadT = 0.0 }
        }
        danger = dg.coerceIn(0.0, 1.0)
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
    val others = ConcurrentHashMap<Int, DoubleArray>() // id -> [x, y, level]
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
                        "P" -> others[p[1].toInt()] = doubleArrayOf(p[2].toDouble(), p[3].toDouble(), p[4].toDouble())
                        "Q" -> others.remove(p[1].toInt())
                    }
                }
            } catch (_: Exception) {}
            state = 2
        }.apply { isDaemon = true; start() }
    }

    fun send(x: Double, y: Double, lv: Int) { if (id != 0) out?.println("P $id $x $y $lv") }
    fun close() { try { sock?.close() } catch (_: IOException) {} }
}

// ---------- Procedural textures (64x64), one set per level ----------
fun pack(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

class TexSet(lv: Int) {
    val wall = IntArray(4096); val floor = IntArray(4096); val ceil = IntArray(4096); val panel = IntArray(4096)
    private fun c(r: Double, g: Double, b: Double, f: Double) = pack((r * f).toInt(), (g * f).toInt(), (b * f).toInt())

    init {
        for (y in 0 until 64) for (x in 0 until 64) {
            val i = y * 64 + x
            when (lv) {
                0 -> { // yellow wallpaper, damp carpet, drop ceiling, fluorescent panel
                    var r = 214.0; var g = 190.0; var b = 98.0
                    var f = 0.92 + 0.16 * h(x, y, 21)
                    if (x % 16 < 2) { r *= 0.88; g *= 0.88; b *= 0.8 }
                    if (abs(x % 16 - 8) + abs(y % 16 - 8) <= 3) f *= 0.93
                    val drip = h(x / 3, 0, 22)
                    if (drip < 0.3) f *= 1 - 0.3 * (y / 64.0).pow(1.5) * (1 - drip)
                    if (y >= 56) { r = 92.0; g = 62.0; b = 34.0; f = 0.9 + 0.2 * h(x, y, 23); if (y == 56) f = 1.3; if (y >= 62) f *= 0.8 }
                    wall[i] = c(r, g, b, f)
                    var cf = (0.82 + 0.3 * h(x, y, 31)) * (0.9 + 0.2 * h(x / 2, y / 2, 32))
                    if (x == 0 || y == 0) cf *= 0.85
                    floor[i] = c(122.0, 98.0, 50.0, cf)
                    var tf = 0.95 + 0.1 * h(x, y, 41)
                    if (h(x, y, 42) < 0.05) tf *= 0.75
                    if (x < 2 || y < 2) tf *= 0.72
                    ceil[i] = c(222.0, 214.0, 176.0, tf)
                    panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(196, 196, 186) else if (x % 16 == 0) pack(235, 235, 222) else pack(255, 255, 244)
                }
                1 -> { // concrete, hazard stripe, wet floor with parking lines, tube lights
                    var r = 152.0; var g = 150.0; var b = 142.0
                    var f = (0.85 + 0.3 * h(x, y, 51)) * (0.9 + 0.2 * h(x / 4, y / 4, 52))
                    if (h(x / 2, y / 6, 53) < 0.03) f *= 0.6
                    if (h(x / 2, 0, 54) < 0.25) f *= 1 - 0.35 * (y / 64.0)
                    if (y in 40..45) { if (((x + y) / 6) % 2 == 0) { r = 30.0; g = 30.0; b = 28.0 } else { r = 200.0; g = 176.0; b = 60.0 }; f = 0.95 + 0.1 * h(x, y, 55) }
                    if (y >= 58) { r = 86.0; g = 86.0; b = 84.0 }
                    wall[i] = c(r, g, b, f)
                    var fr = 112.0; var fg = 114.0; var fb = 110.0
                    var ff = (0.8 + 0.35 * h(x, y, 56)) * (0.9 + 0.2 * h(x / 4, y / 4, 57))
                    if (h(x / 10, y / 10, 58) < 0.18) { ff *= 0.7; fb *= 1.2 }
                    if (x < 2) { fr = 200.0; fg = 176.0; fb = 60.0; ff *= 0.85 }
                    floor[i] = c(fr, fg, fb, ff)
                    var cf = 0.85 + 0.25 * h(x, y, 59); if (y < 6 || y > 57) cf *= 0.6
                    ceil[i] = c(98.0, 98.0, 94.0, cf)
                    panel[i] = if (x < 4 || x > 59 || y < 20 || y > 43) pack(120, 126, 120) else pack(228, 252, 242)
                }
                2 -> { // rusty pipes, metal grating, caged amber lamps
                    var r = 82.0; var g = 96.0; var b = 92.0
                    var f = (0.85 + 0.3 * h(x, y, 61)) * (0.92 + 0.16 * h(x / 3, y / 3, 62))
                    if (y in 8..15) { r = 160.0; g = 92.0; b = 52.0; f = (0.55 + 0.65 * sin(PI * (y - 8) / 8.0)) * (0.9 + 0.2 * h(x, y, 63)) }
                    else if (y in 34..40) { r = 128.0; g = 132.0; b = 132.0; f = (0.5 + 0.7 * sin(PI * (y - 34) / 6.0)) * (0.9 + 0.2 * h(x, y, 64)) }
                    else {
                        if (y in 16..32 && h(x / 2, 0, 65) < 0.2) { r = 110.0; g = 62.0; b = 34.0; f *= 0.8 }
                        if (x % 16 == 3 && (y % 16 == 2 || y % 16 == 12)) f *= 1.5
                    }
                    if (y in 6..17 && x % 32 < 3) { r = 90.0; g = 70.0; b = 60.0; f = 0.9 }
                    if (y >= 58) f *= 0.5
                    wall[i] = c(r, g, b, f)
                    floor[i] = if (x % 8 == 0 || y % 8 == 0) c(24.0, 24.0, 24.0, 1.0) else c(46.0, 38.0, 30.0, 0.8 + 0.4 * h(x, y, 66))
                    ceil[i] = if (y in 22..34) c(110.0, 70.0, 45.0, (0.4 + 0.8 * sin(PI * (y - 22) / 12.0)) * (0.9 + 0.2 * h(x, y, 67)))
                              else c(58.0, 62.0, 60.0, 0.8 + 0.3 * h(x, y, 68))
                    panel[i] = if (x % 8 == 0 || y % 8 == 0 || x < 4 || x > 59 || y < 4 || y > 59) pack(120, 70, 24) else pack(255, 176, 72)
                }
                3 -> { // Electrical Station: caution bands, conduit, diamond plate, strobing lamps
                    var r = 98.0; var g = 106.0; var b = 120.0
                    var f = (0.85 + 0.3 * h(x, y, 71)) * (0.92 + 0.16 * h(x / 4, y / 4, 72))
                    if (y in 12..19) { if (((x + y) / 5) % 2 == 0) { r = 28.0; g = 28.0; b = 30.0 } else { r = 230.0; g = 190.0; b = 40.0 }; f = 0.95 + 0.1 * h(x, y, 73) }
                    else if (y in 44..50) { r = 40.0; g = 44.0; b = 52.0; f = 0.55 + 0.9 * sin(PI * (y - 44) / 6.0) }
                    else if (x % 32 == 5 && y % 16 in 2..3) f *= 1.6
                    if (y >= 58) f *= 0.55
                    wall[i] = c(r, g, b, f)
                    var pf = 0.8 + 0.3 * h(x, y, 74)
                    if ((x + y) % 16 < 2 || (x - y + 64) % 16 < 2) pf *= 1.5
                    floor[i] = c(52.0, 56.0, 64.0, pf)
                    ceil[i] = if (x % 16 < 3) c(30.0, 32.0, 36.0, 1.0) else c(60.0, 66.0, 76.0, 0.8 + 0.3 * h(x, y, 75))
                    panel[i] = if (x < 6 || x > 57 || y < 14 || y > 49) pack(40, 46, 60) else pack(205, 228, 255)
                }
                4 -> { // Abandoned Office: stained beige walls, grey carpet, drop ceiling
                    var r = 206.0; var g = 198.0; var b = 172.0
                    var f = 0.9 + 0.2 * h(x, y, 81)
                    if (y >= 34) { r = 170.0; g = 160.0; b = 135.0 }
                    if (y in 32..34) { r = 120.0; g = 110.0; b = 90.0 }
                    if (h(x / 6, y / 6, 82) < 0.1) f *= 0.82
                    if (y >= 58) { r = 70.0; g = 62.0; b = 50.0 }
                    wall[i] = c(r, g, b, f)
                    var cf = 0.85 + 0.3 * h(x, y, 83); if (x == 0 || y == 0) cf *= 0.8
                    floor[i] = c(92.0, 98.0, 108.0, cf)
                    var tf = 0.95 + 0.1 * h(x, y, 84); if (h(x / 8, y / 8, 85) < 0.12) tf *= 0.8; if (x < 2 || y < 2) tf *= 0.75
                    ceil[i] = c(232.0, 230.0, 220.0, tf)
                    panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(190, 190, 182) else pack(250, 252, 246)
                }
                5 -> { // Terror Hotel: damask wallpaper, wood wainscot, red carpet, wall sconces
                    var r = 112.0; var g = 26.0; var b = 34.0
                    var f = 0.9 + 0.2 * h(x, y, 91)
                    if (abs(x % 16 - 8) + abs(y % 16 - 8) <= 4) { r = 150.0; g = 104.0; b = 56.0; f *= 0.85 }
                    if (y >= 42) { r = 84.0; g = 50.0; b = 28.0; f = 0.85 + 0.25 * h(x, y, 92); if (y == 42 || x % 16 == 0) f *= 0.6 }
                    wall[i] = c(r, g, b, f)
                    var fr = 104.0; var fg = 24.0; var fb = 30.0; val ff = 0.8 + 0.3 * h(x, y, 93)
                    if (x < 6 || x > 57) { fr = 170.0; fg = 130.0; fb = 60.0 } else if ((x + y) % 12 < 2) { fr = 150.0; fg = 100.0; fb = 50.0 }
                    floor[i] = c(fr, fg, fb, ff)
                    ceil[i] = c(74.0, 56.0, 44.0, 0.8 + 0.3 * h(x, y, 94))
                    panel[i] = if (x < 12 || x > 51 || y < 12 || y > 51) pack(60, 40, 24) else pack(255, 205, 130)
                }
                else -> { // Poolrooms: glazed tile, blue band, still water, skylights
                    var r = 226.0; var g = 233.0; var b = 236.0
                    var f = 0.95 + 0.1 * h(x, y, 101)
                    if (x % 16 == 0 || y % 16 == 0) { r = 186.0; g = 198.0; b = 204.0 }
                    if (y in 44..52) { r = 64.0; g = 156.0; b = 196.0; f = 0.95 + 0.1 * h(x, y, 102); if (x % 16 == 0 || y % 16 == 0) f *= 0.8 }
                    if (y >= 58) { r = 96.0; g = 150.0; b = 176.0 }
                    wall[i] = c(r, g, b, f)
                    var wr = 70.0; var wg = 170.0; var wb = 205.0
                    if (x % 16 == 0 || y % 16 == 0) { wr = 40.0; wg = 120.0; wb = 150.0 }
                    floor[i] = c(wr, wg, wb, 0.9 + 0.2 * h(x, y, 103))
                    ceil[i] = c(232.0, 238.0, 240.0, (0.95 + 0.1 * h(x, y, 104)) * (if (x % 32 < 2 || y % 32 < 2) 0.85 else 1.0))
                    panel[i] = if (x < 4 || x > 59 || y < 4 || y > 59) pack(200, 210, 214) else pack(246, 253, 255)
                }
            }
        }
    }
}

val TEX = Array(LEVELS.size) { TexSet(it) }
private val FLICKER = arrayOf(doubleArrayOf(0.12, 0.20, 0.27), doubleArrayOf(0.20, 0.28, 0.40), doubleArrayOf(0.15, 0.25, 0.32),
    doubleArrayOf(0.25, 0.35, 0.45), doubleArrayOf(0.15, 0.22, 0.30), doubleArrayOf(0.18, 0.30, 0.38), doubleArrayOf(0.02, 0.05, 0.05))

// ---------- Renderer: textured raycaster, pooled lighting, fog, look up/down, entities ----------
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

    private fun sq(a: Double) = a * a

    private fun mul(c: Int, f: Double, tn: IntArray): Int {
        val k = (f * 256).toInt().coerceIn(0, 640)
        return pack((((c shr 16) and 255) * k * tn[0]) shr 16, (((c shr 8) and 255) * k * tn[1]) shr 16, ((c and 255) * k * tn[2]) shr 16)
    }

    private fun flick(lv: Int, kx: Int, ky: Int, t: Double): Double {
        val v = h(kx, ky, 9 + lv * 13); val th = FLICKER[lv]
        return when {
            v < th[0] -> if (sin(t * (14 + v * 60) + v * 50) > 0.2) 0.2 else 1.0 // broken, stuttering
            v < th[1] -> 0.92 + 0.08 * sin(t * 40)                               // humming
            v < th[2] -> 0.0                                                     // dead
            else -> 1.0
        }
    }

    /** Light pooled around each ceiling panel (one every `period` cells). */
    private fun lightAt(d: LevelDef, wx: Double, wy: Double, t: Double): Double {
        val p = d.period
        val kx = floor((wx - 1.5) / p + 0.5).toInt(); val ky = floor((wy - 1.5) / p + 0.5).toInt()
        val ddx = wx - (kx * p + 1.5); val ddy = wy - (ky * p + 1.5)
        return d.ambient + flick(d.id, kx, ky, t) / (1 + (ddx * ddx + ddy * ddy) * d.falloff)
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

    /** Sprite shapes in (u,v) space; u in -1..1 across, v in 0..1 top to bottom. 0 = transparent. */
    private fun sprite(type: Int, u: Double, v: Double, dim: Double): Int {
        val dark = pack((16 * dim).toInt(), (14 * dim).toInt(), (10 * dim).toInt())
        return when (type) {
            0, 3 -> { // player = tall dark figure with glowing eyes; type 3 (Skin-Stealer) copies it, minus the glow
                val inside = when {
                    v < 0.17 -> sq(u / 0.42) + sq((v - 0.09) / 0.085) < 1
                    v < 0.22 -> abs(u) < 0.18
                    v < 0.62 -> abs(u) < 0.8 - (v - 0.22) * 0.5
                    else -> abs(abs(u) - 0.35) < 0.22
                }
                if (!inside) 0 else if (type == 0 && v in 0.075..0.10 && abs(abs(u) - 0.17) < 0.07) pack(255, 255, 225) else dark
            }
            1 -> { // Hound: low quadruped, red eye
                val body = sq((u - 0.1) / 0.7) + sq((v - 0.55) / 0.32) < 1
                val head = sq((u + 0.72) / 0.26) + sq((v - 0.38) / 0.22) < 1
                val legs = v > 0.78 && (abs(u - 0.65) < 0.09 || abs(u - 0.35) < 0.09 || abs(u + 0.25) < 0.09 || abs(u + 0.5) < 0.09)
                if (sq((u + 0.82) / 0.07) + sq((v - 0.33) / 0.07) < 1) pack(255, 50, 30) else if (body || head || legs) dark else 0
            }
            else -> { // Smiler: floating dark face, glowing eyes and grin
                val yc = 0.47 + 0.06 * (1 - sq(u / 0.62))
                val glow = sq((abs(u) - 0.34) / 0.16) + sq((v - 0.34) / 0.03) < 1 || (abs(u) < 0.62 && abs(v - yc) < 0.022)
                if (glow) pack(255, 250, 215) else if (sq(u / 0.88) + sq((v - 0.4) / 0.22) < 1) pack(26, 22, 20) else 0
            }
        }
    }

    fun render(px: IntArray, g: Game, t: Double, others: Collection<DoubleArray>, hud: Boolean = true) {
        val lv = g.level; val d = LEVELS[lv]; val ts = TEX[lv]; val tn = d.tint
        val x = g.x; val y = g.y; val a = g.a
        val hz = (H / 2 + sin(g.bob) * g.bobAmp * 3.0 + g.pitch * H * 0.5).toInt()
        val gl = if (sin(t * 2.7) * sin(t * 13.1) > 0.93) 0.5 else 1.0 // rare global flicker
        val dx = cos(a); val dy = sin(a); val plX = -dy * 0.66; val plY = dx * 0.66

        // floor + ceiling
        for (yy in 0 until H) {
            val dr = yy - hz
            if (dr == 0) { for (xx in 0 until W) px[yy * W + xx] = pack(8, 6, 4); continue }
            val rd = 0.5 * H / abs(dr)
            val fog = 1.0 / (1 + rd * rd * d.fogK)
            val isFloor = dr > 0
            for (xx in 0 until W) {
                val cx = 2.0 * xx / W - 1
                val wx = x + (dx + plX * cx) * rd; val wy = y + (dy + plY * cx) * rd
                val tx = ((wx - floor(wx)) * 64).toInt() and 63; val ty = ((wy - floor(wy)) * 64).toInt() and 63
                val l = lightAt(d, wx, wy, t) * gl
                px[yy * W + xx] = if (isFloor) mul(ts.floor[ty * 64 + tx], l * fog * 0.85 * (if (lv == 6) 0.85 + 0.2 * sin(wx * 9 + t * 1.5) * sin(wy * 9 + t * 1.1) else 1.0), tn)
                else if (Math.floorMod(floor(wx).toInt(), d.period) == 1 && Math.floorMod(floor(wy).toInt(), d.period) == 1)
                    mul(ts.panel[ty * 64 + tx], l * max(fog, 0.6), tn)
                else mul(ts.ceil[ty * 64 + tx], l * fog, tn)
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
                if (isWall(lv, mapX, mapY)) break
            }
            val unst = unstable(lv, mapX, mapY)
            val perp = (if (side == 0) sdx - ddx else sdy - ddy).coerceAtLeast(0.05)
            zb[xx] = perp
            val hx = x + perp * rx; val hy = y + perp * ry
            var tx = (((if (side == 0) hy else hx).let { it - floor(it) }) * 64).toInt() and 63
            if ((side == 0 && rx > 0) || (side == 1 && ry < 0)) tx = 63 - tx
            val br = lightAt(d, hx, hy, t) * gl / (1 + perp * perp * d.fogK) * (if (side == 1) 0.82 else 1.0)
            val lineH = (H / perp).toInt().coerceAtLeast(1)
            val start = hz - lineH / 2
            for (yy in max(0, start)..min(H - 1, start + lineH)) {
                val ty = ((yy - start).toLong() * 64 / lineH).toInt().coerceIn(0, 63)
                if (unst) { // cold, wobbling texture = not solid
                    val tx2 = (tx + (sin(t * 11 + yy * 0.25) * 4).toInt()) and 63
                    val c0 = mul(ts.wall[ty * 64 + tx2], br * (0.85 + 0.3 * sin(t * 7 + xx * 0.1)), tn)
                    px[yy * W + xx] = pack((((c0 shr 16) and 255) * 3) / 4, ((c0 shr 8) and 255) + 25, (c0 and 255) + 60)
                } else px[yy * W + xx] = mul(ts.wall[ty * 64 + tx], br, tn)
            }
        }

        // sprites: other players on this level + this level's entities, far to near
        val items = ArrayList<DoubleArray>()
        for (o in others) if (o.size > 2 && o[2].toInt() == lv) items.add(doubleArrayOf(o[0], o[1], 0.0))
        for (e in g.entities) items.add(doubleArrayOf(e.x, e.y, e.type.toDouble()))
        items.sortByDescending { hypot(it[0] - x, it[1] - y) }
        for (o in items) {
            val rx = o[0] - x; val ry = o[1] - y; val type = o[2].toInt()
            val inv = 1.0 / (plX * dy - dx * plY)
            val tx = inv * (dy * rx - dx * ry); val ty = inv * (-plY * rx + plX * ry)
            if (ty <= 0.2) continue
            val sx = (W / 2 * (1 + tx / ty)).toInt(); val full = H / ty
            val sh = (full * (if (type == 1) 0.6 else 0.9)).toInt().coerceAtLeast(2)
            val sw = (sh * (if (type == 1) 1.7 else if (type == 2) 0.5 else 0.42)).toInt().coerceAtLeast(2)
            val bottom = hz + (full / 2).toInt(); val top = bottom - sh
            val dim = 0.55 + 0.45 / (1 + ty * 0.3)
            for (col in max(0, sx - sw / 2)..min(W - 1, sx + sw / 2)) {
                if (ty >= zb[col]) continue
                val u = (col - sx) / (sw / 2.0)
                for (row in max(0, top)..min(H - 1, bottom)) {
                    val c = sprite(type, u, (row - top).toDouble() / sh, dim)
                    if (c != 0) px[row * W + col] = c
                }
            }
        }

        // post: vignette, film grain, danger pulse, death fade
        val dgr = g.danger * g.danger * (0.5 + 0.5 * sin(t * (5 + 9 * g.danger)))
        val rk = (dgr * 180).toInt()
        val fadeI = ((if (g.dead) (1 - g.deadT / 1.2).coerceIn(0.0, 1.0) else 1.0) * 256).toInt()
        val wash = ((g.noclip * g.noclip * 150) + g.flash * 255).toInt()
        var s = grain
        for (i in px.indices) {
            val c = px[i]; val v = (vig[i] * fadeI) shr 8
            s = s * 1664525 + 1013904223
            val nz = ((s ushr 28) and 15) - 8
            val red = ((256 - vig[i]) * rk) shr 8
            px[i] = pack((((c shr 16) and 255) * v shr 8) + nz + red + wash, (((c shr 8) and 255) * v shr 8) + nz + wash, ((c and 255) * v shr 8) + nz + wash)
        }
        grain = s
        if (g.noclip > 0.02) { // reality tearing: shifted scanlines
            var q = s
            repeat((g.noclip * 50).toInt() + 1) {
                q = q * 1664525 + 1013904223; val row = ((q ushr 8) and 0xFFFF) % H
                q = q * 1664525 + 1013904223; val sh = ((((q ushr 8) and 0xFF) - 128) * g.noclip * 0.35).toInt()
                if (sh != 0) {
                    val base = row * W; val tmp = px.copyOfRange(base, base + W)
                    for (i in 0 until W) px[base + (((i + sh) % W) + W) % W] = tmp[i]
                }
            }
        }

        if (hud) drawHud(px, g, others)
    }

    // ---------- shape-only HUD (text is drawn by each platform) ----------
    private fun drawHud(px: IntArray, g: Game, others: Collection<DoubleArray>) {
        if (g.showMap && g.level != 0) { // there is no map of Level 0
            val R = 8; val cs = 3; val size = (2 * R + 1) * cs
            val ox = W - size - 8; val oy = 8
            val fx = floor(g.x).toInt(); val fy = floor(g.y).toInt()
            rect(px, ox - 2, oy - 2, size + 4, size + 4, pack(0, 0, 0), 150)
            val ac = LEVELS[g.level].accent
            for (cy in -R..R) for (cx in -R..R)
                if (isWall(g.level, fx + cx, fy + cy)) rect(px, ox + (cx + R) * cs, oy + (cy + R) * cs, cs, cs, pack((ac shr 16) and 255, (ac shr 8) and 255, ac and 255), 210)
            for (o in others) if (o.size > 2 && o[2].toInt() == g.level && abs(o[0] - g.x) < R && abs(o[1] - g.y) < R)
                rect(px, ox + ((o[0] - fx + R) * cs).toInt() - 1, oy + ((o[1] - fy + R) * cs).toInt() - 1, 3, 3, pack(230, 60, 40), 256)
            val pxx = ox + ((g.x - fx + R) * cs).toInt(); val pyy = oy + ((g.y - fy + R) * cs).toInt()
            for (k in 2..6) rect(px, pxx + (cos(g.a) * k).toInt(), pyy + (sin(g.a) * k).toInt(), 1, 1, pack(255, 255, 255), 200)
            rect(px, pxx - 1, pyy - 1, 3, 3, pack(255, 255, 255), 256)
        }
        if (g.stamina < 0.999) {
            val bw = 90; val bx = (W - bw) / 2; val by = H - 16
            rect(px, bx - 2, by - 2, bw + 4, 8, pack(0, 0, 0), 150)
            rect(px, bx, by, (bw * g.stamina).toInt(), 4, if (g.exhausted) pack(210, 60, 40) else pack(240, 225, 140), 240)
        }
    }
}
