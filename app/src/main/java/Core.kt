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
/** eType: 0 none, 1 Hound (chases on sight), 2 Smiler (moves when unseen), 3 Skin-Stealer (looks like a player, closes in),
 *  4 Faceling (ignores you unless crowded), 5 Lucky Crane (harmless, shy), 6 Living Pizza (harmless, runs away).
 *  lantern: personal light that follows you. isolated: players cannot see each other on this level. */
class LevelDef(val id: Int, val label: String, val name: String, val blurb: String, val entities: String, val eType: Int, val eCount: Int,
               val period: Int, val ambient: Double, val falloff: Double, val fogK: Double, val tint: IntArray, val accent: Int,
               val lantern: Double = 0.0, val isolated: Boolean = false,
               val calm: Double = 1.0, val cast: IntArray = intArrayOf(),
               val safe: Boolean = false) { // safe: rated Safe & Secure (Explore noclips favour these); calm: Level 11 Effect (hostiles notice you less); cast: entity types spawned (empty = eCount x eType)
    val ip = 1.0 / period
}

val LEVELS = listOf(
    LevelDef(0, "LEVEL 0", "THE LOBBY", "Endless yellow rooms. Humming lights. Nobody else can see you.", "NONE", 0, 0, 3, 0.18, 0.40, 0.015, intArrayOf(256, 256, 256), 0xF4E482, isolated = true),
    LevelDef(1, "LEVEL 1", "HABITABLE ZONE", "Cold concrete halls. Something is breathing in the dark.", "HOUNDS", 1, 3, 5, 0.17, 0.30, 0.018, intArrayOf(232, 250, 244), 0x9FD4C8),
    LevelDef(2, "LEVEL 2", "PIPE DREAMS", "Hot, rusted tunnels. Don't look away.", "SMILERS", 2, 2, 4, 0.07, 0.45, 0.030, intArrayOf(256, 205, 150), 0xF0A050),
    LevelDef(3, "LEVEL 3", "ELECTRICAL STATION", "Humming transformers in a grid of machines. Hunters follow the sparks.", "HOUNDS", 1, 4, 4, 0.06, 0.50, 0.028, intArrayOf(230, 240, 256), 0x6FA8FF),
    LevelDef(4, "LEVEL 4", "ABANDONED OFFICE", "Dead cubicles, stained ceilings. Someone is still typing.", "SKIN-STEALERS", 3, 2, 4, 0.18, 0.34, 0.018, intArrayOf(256, 252, 240), 0xD8CFA8),
    LevelDef(5, "LEVEL 5", "TERROR HOTEL", "Red carpet, endless corridors, a door for every secret.", "SMILERS", 2, 3, 4, 0.08, 0.45, 0.026, intArrayOf(256, 225, 190), 0xD04A4A),
    LevelDef(6, "LEVEL 37", "THE POOLROOMS", "Warm tiled halls and still water. Finally, peace.", "NONE", 0, 0, 5, 0.55, 0.12, 0.006, intArrayOf(236, 250, 256), 0x6FD8E8, safe = true),
    LevelDef(7, "LEVEL 6", "LIGHTS OUT", "Pitch-dark halls. Your lantern is all you have.", "HOUNDS", 1, 2, 3, 0.015, 0.50, 0.030, intArrayOf(256, 246, 214), 0xE0C060, lantern = 1.1),
    LevelDef(8, "LEVEL 75", "LIMINALITY", "Sand drifts through quiet corridors. You have been here before.", "NONE", 0, 0, 5, 0.42, 0.12, 0.010, intArrayOf(256, 244, 215), 0xE6C98A, safe = true),
    LevelDef(9, "LEVEL 139", "GRAYSCALE HALLS", "Colourless concrete. The paintings look back.", "NONE", 0, 0, 4, 0.14, 0.38, 0.016, intArrayOf(236, 236, 236), 0xBEBEBE, safe = true),
    LevelDef(10, "LEVEL 182", "CITY OF DUSK", "A sunset that never sets over empty concrete rooms.", "NONE", 0, 0, 4, 0.22, 0.18, 0.014, intArrayOf(256, 200, 150), 0xFF9A5A, safe = true),
    LevelDef(11, "LEVEL 201", "THE THRESHOLD", "A transit between depths. No one else can reach you here.", "NONE", 0, 0, 4, 0.25, 0.30, 0.016, intArrayOf(236, 242, 256), 0xC8D0E8, isolated = true, safe = true),
    LevelDef(12, "LEVEL 11", "THE CITY THAT NEVER SLEEPS", "Endless streets, lit windows. Hostile things keep their distance.", "FACELINGS · HOUNDS · CRANES", 4, 8, 6, 0.30, 0.10, 0.009, intArrayOf(240, 238, 256), 0xFFC857,
        calm = 0.45, cast = intArrayOf(4, 5, 1, 4, 6, 5, 1, 4), safe = true) // 3 Facelings, 2 Lucky Cranes, 2 Hounds, 1 Living Pizza (scaled by difficulty)
)

/** The Manila Room: the one and only room of Level 0 where isolation does not apply. */
object Manila {
    const val CX = 1313; const val CY = -2024 // 9x9 interior starting here, door on the south side; unique in the endless level
    fun inside(x: Double, y: Double) = x >= CX && x < CX + 9 && y >= CY && y < CY + 9
    fun inBox(ix: Int, iy: Int) = ix in CX - 1..CX + 9 && iy in CY - 1..CY + 9
    fun inClear(ix: Int, iy: Int) = ix in CX - 4..CX + 12 && iy in CY - 4..CY + 12 // room plus a free moat around it
    fun wall(ix: Int, iy: Int): Boolean {
        if (!inBox(ix, iy)) return false
        val edge = ix == CX - 1 || ix == CX + 9 || iy == CY - 1 || iy == CY + 9
        return edge && !(iy == CY + 9 && ix in CX + 3..CX + 5)
    }
}

// ---------- Worlds: deterministic per level, so every device sees the same maze ----------
private fun wall0(x: Int, y: Int): Boolean { // The Lobby: 6x6 rooms, 2-wide doors, pillars
    if (Manila.inClear(x, y)) return Manila.wall(x, y)
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

/** Generic room grid: blocks of b cells, walls with probability p, doors dw cells wide. */
private fun grid(x: Int, y: Int, b: Int, s: Int, p: Double, dw: Int): Boolean {
    val bx = Math.floorDiv(x, b); val by = Math.floorDiv(y, b)
    val lx = Math.floorMod(x, b); val ly = Math.floorMod(y, b)
    if (lx == 0 && ly == 0) return true
    val span = b - dw - 1
    val gv = 1 + (h(bx, by, s + 1) * span).toInt(); val gh = 1 + (h(bx, by, s + 3) * span).toInt()
    if (lx == 0 && h(bx, by, s) < p && ly !in gv until gv + dw) return true
    if (ly == 0 && h(bx, by, s + 2) < p && lx !in gh until gh + dw) return true
    return false
}

private fun wallCity(x: Int, y: Int): Boolean { // The City: 12x12 blocks, 4-wide streets, a lamp post in every crossing
    val bx = Math.floorDiv(x, 12); val by = Math.floorDiv(y, 12)
    val lx = Math.floorMod(x, 12); val ly = Math.floorMod(y, 12)
    if (lx == 2 && ly == 2) return true                  // lamp post / traffic light
    if (lx < 4 || ly < 4) return false                   // street
    val k = h(bx, by, 150)
    if (k < 0.15) return false                           // plaza / park
    val sx = 5 + (h(bx, by, 151) * 4).toInt(); val sy = 5 + (h(bx, by, 152) * 4).toInt() // building footprint 5..8
    val ix = lx - 4 - ((8 - sx) * h(bx, by, 153)).toInt(); val iy = ly - 4 - ((8 - sy) * h(bx, by, 154)).toInt()
    if (ix < 0 || iy < 0 || ix >= sx || iy >= sy) return false
    if (k < 0.52) return true                            // about a third of the buildings are solid: they cannot be entered
    if (ix != 0 && iy != 0 && ix != sx - 1 && iy != sy - 1) return ix == sx / 2 && iy == sy / 2 && k > 0.8 // hollow, some with a pillar
    val side = (h(bx, by, 155) * 4).toInt(); val dp = 1 + (h(bx, by, 156) * ((if (side < 2) sy else sx) - 3)).toInt()
    return when (side) { // 2-wide door on one side
        0 -> !(ix == 0 && iy in dp..dp + 1); 1 -> !(ix == sx - 1 && iy in dp..dp + 1)
        2 -> !(iy == 0 && ix in dp..dp + 1); else -> !(iy == sy - 1 && ix in dp..dp + 1)
    }
}

private fun baseWall(lv: Int, x: Int, y: Int) = when (lv) {
    0 -> wall0(x, y); 1 -> wall1(x, y); 2 -> wall2(x, y); 3 -> wall3(x, y); 4 -> wall4(x, y); 5 -> wall5(x, y); 6 -> wall6(x, y)
    7 -> grid(x, y, 5, 110, 0.8, 2); 8 -> grid(x, y, 8, 120, 0.6, 3); 9 -> grid(x, y, 7, 130, 0.7, 2)
    10 -> wall5(x + 37, y + 11); 12 -> wallCity(x, y); else -> grid(x, y, 4, 140, 0.9, 1)
}

// Every so often a long straight hallway (3 wide, 36-60 cells) is cut through the maze. One candidate per 96x96 sector.
private const val SECT = 96
/** 0 = maze as usual, 1 = hallway side wall, 2 = hallway floor (including a 3-cell opening at each end so it joins the maze). */
private fun hallway(lv: Int, x: Int, y: Int): Int {
    val sx = Math.floorDiv(x, SECT); val sy = Math.floorDiv(y, SECT); val q = lv * 7
    if (h(sx, sy, 500 + q) > 0.38) return 0
    val horiz = h(sx, sy, 501 + q) < 0.5
    val len = 36 + (h(sx, sy, 502 + q) * 25).toInt()
    val a0 = 6 + (h(sx, sy, 503 + q) * (SECT - len - 12)).toInt() // start along the hallway
    val p0 = 6 + (h(sx, sy, 504 + q) * (SECT - 18)).toInt()       // first side wall across it
    val lx = Math.floorMod(x, SECT); val ly = Math.floorMod(y, SECT)
    val u = if (horiz) lx else ly; val v = if (horiz) ly else lx
    if (v < p0 || v > p0 + 4 || u < a0 - 3 || u >= a0 + len + 3) return 0
    if (v == p0 || v == p0 + 4) return if (u in a0 until a0 + len) 1 else 0
    return 2
}

private fun rawWall(lv: Int, x: Int, y: Int): Boolean {
    if (lv == 12 || (lv == 0 && Manila.inClear(x, y))) return baseWall(lv, x, y) // the city has its own long streets; the Manila Room stays untouched
    return when (hallway(lv, x, y)) { 1 -> true; 2 -> false; else -> baseWall(lv, x, y) }
}

// The maze is a pure function of (level, x, y) but costs several hashes per cell, so it is cached in 32x32 chunks.
// Chunks are immutable once published, so any thread may read them (renderer workers, game thread, UI thread).
private class Chunk(val lv: Int, val cx: Int, val cy: Int, val w: BooleanArray)
private val chunks = java.util.concurrent.atomic.AtomicReferenceArray<Chunk?>(1024)
@Volatile private var chunkMiss = 0

private fun chunk(lv: Int, cx: Int, cy: Int): Chunk {
    val i = ((cx * 73856093) xor (cy * 19349663) xor (lv * 83492791)) and 1023
    var free = -1
    for (k in 0..3) { // 4-way probing so two busy chunks never evict each other
        val j = (i + k) and 1023; val c = chunks.get(j)
        if (c == null) { if (free < 0) free = j } else if (c.cx == cx && c.cy == cy && c.lv == lv) return c
    }
    val a = BooleanArray(1024); val x0 = cx shl 5; val y0 = cy shl 5
    for (j in 0 until 32) for (k in 0 until 32) a[j * 32 + k] = rawWall(lv, x0 + k, y0 + j)
    val n = Chunk(lv, cx, cy, a)
    chunks.set(if (free >= 0) free else (i + (chunkMiss++ and 3)) and 1023, n)
    return n
}

fun isWall(lv: Int, x: Int, y: Int): Boolean = chunk(lv, x shr 5, y shr 5).w[((y and 31) shl 5) or (x and 31)]

/** Each level has exactly 4 not-quite-solid walls at fixed spots; each leads to a fixed level. */
val NOCLIP_TARGETS = listOf(
    intArrayOf(1, 7, 11, 5), intArrayOf(2, 0, 9, 12), intArrayOf(3, 1, 10, 4), intArrayOf(4, 2, 11, 12), intArrayOf(5, 3, 0, 12),
    intArrayOf(6, 4, 10, 2), intArrayOf(8, 5, 0, 12), intArrayOf(0, 1, 9, 12), intArrayOf(6, 0, 10, 4), intArrayOf(0, 2, 11, 12),
    intArrayOf(2, 5, 8, 12), intArrayOf(3, 5, 8, 12), intArrayOf(1, 4, 6, 10))

val NOCLIPS: List<List<IntArray>> by lazy {
    LEVELS.indices.map { lv ->
        // Level 0: scattered around the Manila Room. Other levels: around the spawn area.
        val ax = if (lv == 0) Manila.CX + 4 else 4; val ay = if (lv == 0) Manila.CY + 4 else 4
        val out = ArrayList<IntArray>()
        val dists = if (lv == 0) doubleArrayOf(70.0, 115.0, 160.0, 92.0) else doubleArrayOf(18.0, 35.0, 52.0, 27.0)
        for (k in 0..3) {
            val ang = lv * 1.3 + k * 2.094 + (if (k == 3) 1.047 else 0.0); val dist = dists[k]
            val sx = (ax + cos(ang) * dist).roundToInt(); val sy = (ay + sin(ang) * dist).roundToInt()
            var best: IntArray? = null
            search@ for (r in 0..40) for (dy in -r..r) for (dx in -r..r) {
                if (max(abs(dx), abs(dy)) != r) continue
                val cx = sx + dx; val cy = sy + dy
                if (!isWall(lv, cx, cy) || (lv == 0 && Manila.inClear(cx, cy))) continue
                val exposed = !(isWall(lv, cx + 1, cy) && isWall(lv, cx - 1, cy) && isWall(lv, cx, cy + 1) && isWall(lv, cx, cy - 1))
                if (exposed && out.none { it[0] == cx && it[1] == cy }) { best = intArrayOf(cx, cy); break@search }
            }
            out.add(best ?: intArrayOf(sx, sy))
        }
        out
    }
}

fun noclipIndex(lv: Int, x: Int, y: Int): Int {
    val l = NOCLIPS[lv]
    for (k in l.indices) if (l[k][0] == x && l[k][1] == y) return k
    return -1
}
fun unstable(lv: Int, x: Int, y: Int) = noclipIndex(lv, x, y) >= 0

object Progress {
    val found = BooleanArray(LEVELS.size).also { it[0] = true } // levels discovered (persisted by Store)
    fun unlock(lv: Int) { if (!found[lv]) { found[lv] = true; Store.saveProfile() } }
}

// ---------- Menu screens (shared by both clients), settings, saves ----------
enum class Scr { MAIN, SAVES, MULTI, OPTIONS, PAUSE, GAME }

object Settings {
    const val FOV_MIN = 30; const val FOV_MAX = 110
    @Volatile var fov = 60
    @Volatile var fog = 100  // % of each level's own fog density (0 = no fog)
    @Volatile var diff = 1   // index into DIFFS
    val FOG_PRESETS = listOf(0 to "Off", 50 to "Light", 100 to "Default")
    val fogScale get() = fog / 100.0
    fun putFog(f: Int) { fog = f.coerceIn(0, 100) }
    fun fogName(f: Int = fog) = FOG_PRESETS.firstOrNull { it.first == f }?.let { "${it.second} ($f%)" } ?: "Custom ($f%)"
    fun fogFromFrac(t: Double): Int {
        val f = (t.coerceIn(0.0, 1.0) * 100).roundToInt()
        return FOG_PRESETS.firstOrNull { abs(it.first - f) <= 3 }?.first ?: f
    }
    val explore get() = DIFFS[diff].explore
    fun putDiff(i: Int) { diff = i.coerceIn(0, DIFFS.size - 1) }
    val PRESETS = listOf(30 to "Narrow", 60 to "Normal", 110 to "Quake Pro")
    fun set(f: Int) { fov = f.coerceIn(FOV_MIN, FOV_MAX) }
    fun name(f: Int = fov) = PRESETS.firstOrNull { it.first == f }?.let { "${it.second} ($f)" } ?: "Custom ($f)"
    fun frac(f: Int = fov) = (f - FOV_MIN).toDouble() / (FOV_MAX - FOV_MIN)
    /** Slider position 0..1 -> FOV; sticks to a preset when within 2 degrees of it. */
    fun fromFrac(t: Double): Int {
        val f = (FOV_MIN + t.coerceIn(0.0, 1.0) * (FOV_MAX - FOV_MIN)).roundToInt()
        return PRESETS.firstOrNull { abs(it.first - f) <= 2 }?.first ?: f
    }
}

/** Difficulty multipliers (Normal = the original game): entity count, entity speed, how far entities notice you,
 *  sprint drain / refill, spawn distance, catch distance, hunger rate, starting rations / almond water. */
class Diff(val name: String, val blurb: String, val count: Double, val speed: Double, val sight: Double, val drain: Double,
           val regen: Double, val spawn: Double, val catchR: Double, val hunger: Double, val kitRations: Int = 2, val kitWater: Int = 1, val explore: Boolean = false)
/** Explore mode knobs: everything to tune after player feedback is in this one place. */
object ExploreRules {
    /** false = levels you pass through are NOT remembered (they never reach the unlocked-levels drawer). true = remembered like in the other modes. */
    const val SAVE_VISITED_LEVELS = false
    /** Chance (0..1) that a noclip drops you on a Safe & Secure level (LevelDef.safe) instead of the wall's usual destination. */
    const val SAFE_NOCLIP_CHANCE = 0.8
    // Monsters are friendly in Explore: they wander and tag along at a polite distance, never chase or hurt (see Game.update).
}
val DIFFS = listOf(
    Diff("Easy", "Fewer, slower entities. Longer sprint.", 0.5, 0.8, 0.8, 0.7, 1.25, 1.4, 0.5, 0.6, 3, 2),
    Diff("Normal", "The intended experience.", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.6, 1.0, 2, 1),
    Diff("Hard", "More, faster entities. Shorter sprint.", 1.5, 1.15, 1.2, 1.3, 0.85, 0.85, 0.65, 1.25, 1, 1),
    Diff("Nightmare", "A horde, faster than your sprint.", 2.0, 1.3, 1.5, 1.6, 0.7, 0.7, 0.7, 1.6, 1, 0),
    Diff("Explore", "Friendly monsters. Safe levels.", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.6, 0.6, 0, 0, explore = true))

class SaveData(val id: String, val stamp: Long, val level: Int, val x: Double, val y: Double, val a: Double,
               val pitch: Double, val stamina: Double, val playSec: Double,
               val hunger: Double = 1.0, val rations: Int = 0, val water: Int = 0, val lobbyT: Double = 0.0) {
    fun info(): String {
        val m = (playSec / 60).toInt()
        val t = if (m < 1) "<1 min" else if (m < 60) "$m min" else "${m / 60}h ${"%02d".format(m % 60)}m"
        return "${java.text.SimpleDateFormat("MMM d, HH:mm", java.util.Locale.getDefault()).format(java.util.Date(stamp))}  ·  $t played"
    }
}

/** Plain .properties files in a folder each platform provides: profile.properties (FOV + unlocked levels) and saves/<id>.properties. */
object Store {
    private var dir: File? = null
    private fun load(f: File) = java.util.Properties().also { p -> f.inputStream().use { p.load(it) } }
    private fun put(f: File, p: java.util.Properties) {
        val tmp = File(f.path + ".tmp"); tmp.outputStream().use { p.store(it, null) }
        if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
    }

    fun init(d: File) {
        dir = d; File(d, "saves").mkdirs()
        runCatching {
            val f = File(d, "profile.properties")
            if (f.exists()) {
                val p = load(f)
                p.getProperty("fov")?.toIntOrNull()?.let { Settings.set(it) }
                p.getProperty("fog")?.toIntOrNull()?.let { Settings.putFog(it) }
                p.getProperty("diff")?.toIntOrNull()?.let { Settings.putDiff(it) }
                p.getProperty("found")?.split(",")?.forEach { s -> s.trim().toIntOrNull()?.let { if (it in Progress.found.indices) Progress.found[it] = true } }
            }
        }
    }

    fun saveProfile() {
        val d = dir ?: return
        runCatching {
            put(File(d, "profile.properties"), java.util.Properties().apply {
                setProperty("fov", Settings.fov.toString()); setProperty("fog", Settings.fog.toString()); setProperty("diff", Settings.diff.toString())
                setProperty("found", Progress.found.indices.filter { Progress.found[it] }.joinToString(","))
            })
        }
    }

    fun list(): List<SaveData> {
        val d = dir ?: return emptyList()
        return (File(d, "saves").listFiles { f -> f.name.endsWith(".properties") } ?: emptyArray()).mapNotNull { f ->
            runCatching {
                val p = load(f)
                SaveData(f.name.removeSuffix(".properties"), p.getProperty("stamp").toLong(), p.getProperty("level").toInt(),
                    p.getProperty("x").toDouble(), p.getProperty("y").toDouble(), p.getProperty("a").toDouble(),
                    p.getProperty("pitch").toDouble(), p.getProperty("stamina").toDouble(), p.getProperty("playSec").toDouble(),
                    p.getProperty("hunger")?.toDouble() ?: 1.0, p.getProperty("rations")?.toInt() ?: 0, p.getProperty("water")?.toInt() ?: 0, p.getProperty("lobbyT")?.toDouble() ?: 0.0)
            }.getOrNull()
        }.sortedByDescending { it.stamp }
    }

    fun write(s: SaveData) {
        val d = dir ?: return
        runCatching {
            put(File(File(d, "saves"), "${s.id}.properties"), java.util.Properties().apply {
                setProperty("stamp", s.stamp.toString()); setProperty("level", s.level.toString())
                setProperty("x", s.x.toString()); setProperty("y", s.y.toString()); setProperty("a", s.a.toString())
                setProperty("pitch", s.pitch.toString()); setProperty("stamina", s.stamina.toString()); setProperty("playSec", s.playSec.toString())
                setProperty("hunger", s.hunger.toString()); setProperty("rations", s.rations.toString()); setProperty("water", s.water.toString()); setProperty("lobbyT", s.lobbyT.toString())
            })
        }
    }

    fun delete(id: String) { val d = dir ?: return; File(File(d, "saves"), "$id.properties").delete() }
}

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
    var dir = Math.random() * 6.283; var chase = false; var gift = false
}

/** Supplies: 1 = ration (food), 2 = almond water (clears hallucinations, a little food). */
class Supply(val x: Int, val y: Int, val type: Int)
/** A hallucination: looks like an entity, drifts toward you, harmless, gone when you get close. */
class Ghost(var x: Double, var y: Double, val type: Int, var life: Double)

/** Supplies are scattered through every level from 1 up. Level 0 keeps them all inside the Manila Room. */
/** Where noclip wall `k` of level `lv` leads. In Explore mode, most of the time (ExploreRules.SAFE_NOCLIP_CHANCE) a Safe & Secure level instead. */
fun noclipDest(lv: Int, k: Int): Int {
    val fixed = NOCLIP_TARGETS[lv][k]
    if (!Settings.explore || Math.random() >= ExploreRules.SAFE_NOCLIP_CHANCE) return fixed
    val safe = LEVELS.indices.filter { LEVELS[it].safe && it != lv }
    return if (safe.isEmpty()) fixed else safe[(Math.random() * safe.size).toInt()]
}

fun supplyAt(lv: Int, x: Int, y: Int): Int {
    if (Settings.explore) return 0 // no hunger in Explore, so no supplies
    if (lv == 0 && !Manila.inside(x + 0.5, y + 0.5)) return 0
    if (isWall(lv, x, y)) return 0
    val p = if (lv == 0) 0.12 else if (lv == 12) 0.0014 else 0.0010
    if (h(x, y, 880 + lv) >= p) return 0
    return if (h(x, y, 881) < 0.4) 2 else 1
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
    var near = 0.0; var manilaNear = 0.0; var manilaT = 0.0
    var presence = 0.0 // 0..1: how close the nearest entity is; lamps stutter more the closer it gets
    var noclip = 0.0; var flash = 0.0; var arrival = "YOU SLIPPED OUT OF REALITY"
    var playSec = 0.0 // total time actually played (paused time does not count), stored in saves
    val entities = CopyOnWriteArrayList<Entity>()
    // food, supplies, hallucinations
    @Volatile var hunger = 1.0; @Volatile var rations = 0; @Volatile var water = 0; var starveT = 0.0
    @Volatile var eatReq = false; @Volatile var drinkReq = false // set by the UI threads, handled in update()
    var lobbyT = 0.0; @Volatile var halluc = 0.0; val ghosts = CopyOnWriteArrayList<Ghost>(); private var ghostCd = 4.0
    @Volatile var supplies: List<Supply> = emptyList()
    private val taken = HashSet<Long>(); private val extra = ArrayList<Supply>()
    private var scanX = Int.MIN_VALUE; private var scanY = 0
    private var warnHungry = false; private var warnHallu = false
    var notice = ""; @Volatile var noticeT = 0.0; var deathMsg = "YOU WERE CAUGHT"
    fun say(s: String) { notice = s; noticeT = 3.5 }
    init { enter(0) }

    /** Unlock a level for the drawer, unless Explore is set to not save the levels you pass through. */
    private fun remember(lv: Int) { if (!Settings.explore || ExploreRules.SAVE_VISITED_LEVELS) Progress.unlock(lv) }
    private fun cellKey(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xFFFFFFFFL)
    private fun resetLevelState() { taken.clear(); extra.clear(); scanX = Int.MIN_VALUE; supplies = emptyList(); ghosts.clear(); halluc = 0.0; lobbyT = 0.0; ghostCd = 4.0; warnHallu = false }
    /** Supplies within 13 cells (redone whenever you change cell); what the renderer draws and what you can pick up. */
    private fun rescan(cx: Int, cy: Int) {
        scanX = cx; scanY = cy
        val out = ArrayList<Supply>()
        for (dy in -13..13) for (dx in -13..13) { val t = supplyAt(level, cx + dx, cy + dy); if (t != 0 && cellKey(cx + dx, cy + dy) !in taken) out.add(Supply(cx + dx, cy + dy, t)) }
        for (e in extra) if (cellKey(e.x, e.y) !in taken && abs(e.x - cx) <= 13 && abs(e.y - cy) <= 13) out.add(e)
        supplies = out
    }

    /** True when other players cannot see you (Level 0 and Level 201), except inside the Manila Room. */
    fun isolated() = LEVELS[level].isolated && !(level == 0 && Manila.inside(x, y))

    private var ncK = 0
    private fun noclipTarget() = noclipDest(level, ncK)

    private fun findFree(cx: Double, cy: Double): Pair<Double, Double> {
        for (r in 0..40) for (dy in -r..r) for (dx in -r..r) {
            if (max(abs(dx), abs(dy)) != r) continue
            val px = floor(cx).toInt() + dx + 0.5; val py = floor(cy).toInt() + dy + 0.5
            if (free(level, px, py, 0.3)) return px to py
        }
        return cx to cy
    }

    fun enter(lv: Int, arrived: String = "YOU SLIPPED OUT OF REALITY") {
        level = lv; levelT = 0.0; dead = false; deadT = 0.0; danger = 0.0; noclip = 0.0; arrival = arrived; resetLevelState()
        if (lv == 12) say("LEVEL 11 EFFECT  ·  hostile entities are less likely to attack you")
        stamina = 1.0; exhausted = false; pitch = 0.0; a = Math.random() * 6.283
        remember(lv)
        // Level 0: you land somewhere in the endless maze, a few minutes' walk from the (unique) Manila Room.
        // Other levels drop you near the origin so players can regroup.
        val p = if (lv == 0) { val ang0 = Math.random() * 6.283; val d0 = 60 + Math.random() * 200
                               findFree(Manila.CX + 4.5 + cos(ang0) * d0, Manila.CY + 4.5 + sin(ang0) * d0) }
                else findFree(4 + Math.random() * 3, 4 + Math.random() * 3)
        x = p.first; y = p.second
        spawnEntities()
    }

    private fun spawnEntities() {
        entities.clear(); presence = 0.0
        val df = DIFFS[Settings.diff]; val ld = LEVELS[level]; val cast = ld.cast
        val n = if (cast.isEmpty()) ld.eCount else cast.size // entity-free levels stay entity-free
        repeat(if (n == 0) 0 else max(1, (n * df.count).roundToInt())) { i ->
            val ang = Math.random() * 6.283; val d = (11 + Math.random() * 8) * df.spawn
            val q = findFree(x + cos(ang) * d, y + sin(ang) * d)
            entities.add(Entity(q.first, q.second, if (cast.isEmpty()) ld.eType else cast[i % cast.size]))
        }
    }

    fun newGame(lv: Int = 0) { playSec = 0.0; hunger = 1.0; rations = DIFFS[Settings.diff].kitRations; water = DIFFS[Settings.diff].kitWater; starveT = 0.0; enter(lv) }

    fun snapshot(id: String) = SaveData(id, System.currentTimeMillis(), level, x, y, a, pitch, stamina, playSec, hunger, rations, water, lobbyT)

    /** Resume a save: same level and spot; entities start fresh, 11+ cells away. */
    fun restore(s: SaveData) {
        level = s.level.coerceIn(0, LEVELS.size - 1); levelT = 0.0; dead = false; deadT = 0.0; danger = 0.0; noclip = 0.0
        arrival = "YOU CAME BACK"; stamina = s.stamina.coerceIn(0.0, 1.0); exhausted = false
        a = s.a; pitch = s.pitch.coerceIn(-1.0, 1.0); playSec = s.playSec
        resetLevelState(); hunger = s.hunger.coerceIn(0.0, 1.0); rations = s.rations.coerceIn(0, 9); water = s.water.coerceIn(0, 9); lobbyT = s.lobbyT.coerceAtLeast(0.0); starveT = 0.0
        remember(level)
        val p = findFree(s.x, s.y); x = p.first; y = p.second
        spawnEntities()
    }

    /** fwd/str in -1..1 (analog), turn in rad/s */
    fun update(dt: Double, fwd: Double, str: Double, turn: Double, sprint: Boolean) {
        levelT += dt; playSec += dt; flash = max(0.0, flash - dt / 1.2); noticeT = max(0.0, noticeT - dt)
        if (dead) { deadT += dt; if (deadT > 3.5) { hunger = max(hunger, 0.5); starveT = 0.0; enter(level) }; return }
        val dif = DIFFS[Settings.diff]
        if (eatReq) { eatReq = false; if (rations > 0) { rations--; hunger = min(1.0, hunger + 0.35); say("YOU ATE A RATION") } }
        if (drinkReq) { drinkReq = false; if (water > 0) { water--; hunger = min(1.0, hunger + 0.08); lobbyT = 0.0; halluc = 0.0; ghosts.clear(); say("ALMOND WATER  ·  your head clears") } }
        a += turn * dt
        pitch = pitch.coerceIn(-1.0, 1.0)
        var f = fwd; var s = str
        val len = hypot(f, s)
        if (len > 1) { f /= len; s /= len }
        val moving = len > 0.1
        val run = sprint && moving && !exhausted
        // food: full to empty in about 18 minutes of walking; hungry = slower recovery, starving = slower and, eventually, fatal. Explore has no hunger (stamina still applies).
        if (dif.explore) hunger = 1.0 else hunger = max(0.0, hunger - (0.0009 + (if (run) 0.0012 else 0.0)) * dif.hunger * dt)
        starveT = if (hunger <= 0.0) starveT + dt else 0.0
        if (starveT > 75) { dead = true; deadT = 0.0; deathMsg = "YOU STARVED"; return }
        if (hunger < 0.2 && !warnHungry) { warnHungry = true; say("YOU ARE HUNGRY  ·  find food") } else if (hunger > 0.4) warnHungry = false
        if (run) { stamina -= 0.10 * dif.drain * dt; if (stamina <= 0) { stamina = 0.0; exhausted = true } }
        else { stamina = min(1.0, stamina + 0.20 * dif.regen * (if (hunger < 0.2) 0.6 else 1.0) * dt); if (exhausted && stamina > 0.3) exhausted = false }
        val speed = (if (run) 4.2 else 2.3) * (if (hunger <= 0.0) 0.85 else 1.0)
        move(level, x, y, cos(a) * f - sin(a) * s, sin(a) * f + cos(a) * s, speed * dt).let { x = it.first; y = it.second }
        if (moving) bob += dt * (if (run) 12.0 else 7.5)
        bobAmp += ((if (!moving) 0.0 else if (run) 1.0 else 0.6) - bobAmp) * min(1.0, dt * 8)

        // noclip: keep pushing into an unstable wall to slip through to the next level
        var pushing = false
        if (moving) {
            val mxn = cos(a) * f - sin(a) * s; val myn = sin(a) * f + cos(a) * s; val ml = hypot(mxn, myn)
            if (ml > 0.01) {
                val cx = floor(x + mxn / ml * 0.34).toInt(); val cy = floor(y + myn / ml * 0.34).toInt()
                val k = noclipIndex(level, cx, cy)
                pushing = k >= 0
                if (pushing) ncK = k
            }
        }
        noclip = if (pushing) min(1.0, noclip + dt / 2.2) else max(0.0, noclip - dt * 1.2)
        if (noclip >= 1.0) { enter(noclipTarget(), "YOU NOCLIPPED THROUGH THE WALL"); flash = 1.0; return }

        near = NOCLIPS[level].minOf { hypot(it[0] + 0.5 - x, it[1] + 0.5 - y) }.let { (1 - it / 22).coerceIn(0.0, 1.0) }
        val inM = level == 0 && Manila.inside(x, y)
        manilaNear = if (level == 0 && !inM) (1 - hypot(Manila.CX + 4.5 - x, Manila.CY + 4.5 - y) / 45).coerceIn(0.0, 1.0) else 0.0
        manilaT = if (inM) manilaT + dt else 0.0

        // The Lobby gets to you: stay in its open halls too long and you start seeing things. Almond water or the Manila Room clears your head.
        if (dif.explore) lobbyT = 0.0 else if (level == 0) lobbyT = if (inM) max(0.0, lobbyT - dt * 2) else lobbyT + dt
        halluc += (((lobbyT - 240) / 300).coerceIn(0.0, 1.0) - halluc) * min(1.0, dt * 0.5)
        if (halluc > 0.08 && !warnHallu) { warnHallu = true; say("YOUR EYES PLAY TRICKS  ·  almond water or the Manila Room helps") } else if (halluc < 0.02) warnHallu = false
        updateGhosts(dt)

        var dg = 0.0; var pr = 0.0
        val calm = LEVELS[level].calm
        for (e in entities) {
            val ex = x - e.x; val ey = y - e.y; val d = hypot(ex, ey).coerceAtLeast(0.01)
            if (dif.explore && e.type in 1..4) { // Explore: every monster is friendly. It wanders, and tags along at a polite distance
                val follow = d < 12 && d > 3.5
                if (follow) e.dir = atan2(ey, ex) + sin(levelT * 0.7 + e.x) * 0.5 else e.dir += (Math.random() - 0.5) * dt * 2
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (follow) 1.4 else 0.6) * dt)
                if (r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second; e.chase = false
                continue
            }
            if (e.type != 5 && e.type != 6) pr = max(pr, 1 - d / 12)
            val sight = dif.sight * (if (calm < 1.0 && !(run && d < 12)) calm else 1.0) // Level 11 Effect: hostiles mostly ignore you, unless you run past them
            if (e.type == 3) { // Skin-Stealer: walks like a player, patiently closes in
                if (d < 16 * sight && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 22 * sight) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 2
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 2.0 else 0.8) * dif.speed * dt)
                if (r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, (1 - d / 6).coerceAtLeast(0.0))
            } else if (e.type == 1) { // Hound: wanders, hunts you on sight, collides with walls
                if (d < 10 * sight && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 16 * sight) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 3
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 3.4 else 1.0) * dif.speed * dt)
                if (!e.chase && r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, 1 - d / (10 * sight))
            } else if (e.type == 4) { // Faceling: drifts around, ignores you until you crowd it
                if (d < 3.5 && los(level, e.x, e.y, x, y)) e.chase = true else if (d > 14) e.chase = false
                if (e.chase) e.dir = atan2(ey, ex) else e.dir += (Math.random() - 0.5) * dt * 2
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (e.chase) 2.6 else 0.7) * dif.speed * dt)
                if (!e.chase && r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (e.chase) dg = max(dg, (1 - d / 8).coerceAtLeast(0.0))
            } else if (e.type == 5) { // Lucky Crane: harmless and shy; leaves a ration behind if you get close
                if (d < 5) e.dir = atan2(-ey, -ex) + (Math.random() - 0.5) * 0.6 else e.dir += (Math.random() - 0.5) * dt * 2.5
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (d < 5) 2.4 else 0.9) * dt)
                if (r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (d < 2.2 && !e.gift && !dif.explore) { e.gift = true; extra.add(Supply(floor(e.x).toInt(), floor(e.y).toInt(), 1)); scanX = Int.MIN_VALUE; say("A LUCKY CRANE LEFT A RATION") }
            } else if (e.type == 6) { // Living Pizza: runs away; catch it (sprint!) and it is food
                if (d < 6) e.dir = atan2(-ey, -ex) + (Math.random() - 0.5) * 0.8 else e.dir += (Math.random() - 0.5) * dt * 2.5
                val r = move(level, e.x, e.y, cos(e.dir), sin(e.dir), (if (d < 6) 2.8 else 0.8) * dt)
                if (r.first == e.x && r.second == e.y) e.dir += 1.6
                e.x = r.first; e.y = r.second
                if (d < 0.8 && !dif.explore) { entities.remove(e); hunger = min(1.0, hunger + 0.5); say("YOU CAUGHT A LIVING PIZZA  ·  +FOOD"); continue }
            } else { // Smiler: only moves while you are NOT looking at it, drifts through walls
                var df = (atan2(e.y - y, e.x - x) - a) % (2 * PI)
                if (df > PI) df -= 2 * PI; if (df < -PI) df += 2 * PI
                val seen = abs(df) < 0.8 && los(level, x, y, e.x, e.y)
                if (!seen) { e.x += ex / d * 1.9 * dif.speed * dt; e.y += ey / d * 1.9 * dif.speed * dt } else dg = max(dg, 1 - d / 14)
            }
            if (d < dif.catchR && (e.type <= 3 || (e.type == 4 && e.chase))) { dead = true; deadT = 0.0; deathMsg = "YOU WERE CAUGHT" }
        }
        danger = dg.coerceIn(0.0, 1.0)
        presence += (pr - presence) * min(1.0, dt * 3)

        // pick up supplies by walking over them (bag holds 9 of each)
        val cx0 = floor(x).toInt(); val cy0 = floor(y).toInt()
        if (cx0 != scanX || cy0 != scanY) rescan(cx0, cy0)
        for (sp in supplies) if (!dif.explore && hypot(sp.x + 0.5 - x, sp.y + 0.5 - y) < 0.75 && (if (sp.type == 1) rations else water) < 9) {
            taken.add(cellKey(sp.x, sp.y)); if (sp.type == 1) rations++ else water++
            say(if (sp.type == 1) "+1 RATION" else "+1 ALMOND WATER"); scanX = Int.MIN_VALUE
        }
    }

    private fun updateGhosts(dt: Double) {
        for (gh in ghosts) {
            val d = hypot(x - gh.x, y - gh.y).coerceAtLeast(0.01)
            gh.life -= dt
            if (gh.life <= 0 || d < 2.5) { ghosts.remove(gh); if (d < 2.5) flash = max(flash, 0.3); continue } // it was never there
            gh.x += (x - gh.x) / d * 0.9 * dt; gh.y += (y - gh.y) / d * 0.9 * dt
        }
        if (halluc > 0.1 && ghosts.size < 1 + (halluc * 3).toInt()) {
            ghostCd -= dt
            if (ghostCd <= 0) {
                ghostCd = 9 - 7 * halluc + Math.random() * 3
                val ang = a + (Math.random() - 0.5) * 1.4; val dd = 6 + Math.random() * 8
                val q = findFree(x + cos(ang) * dd, y + sin(ang) * dd)
                ghosts.add(Ghost(q.first, q.second, intArrayOf(0, 2, 3)[(Math.random() * 3).toInt()], 4 + Math.random() * 4))
            }
        }
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

/** Colours and patterns for the data-driven levels (7..12). wp: wall pattern, fp: floor pattern. */
class TexSpec(val w: IntArray, val f: IntArray, val c: IntArray, val p: IntArray, val wp: Int, val fp: Int)
val SPECS = listOf(
    TexSpec(intArrayOf(190, 170, 90), intArrayOf(100, 84, 44), intArrayOf(180, 170, 140), intArrayOf(255, 255, 200), 0, 0),   // 7  Lights Out
    TexSpec(intArrayOf(222, 196, 138), intArrayOf(224, 192, 122), intArrayOf(205, 185, 135), intArrayOf(255, 246, 215), 2, 1), // 8  Liminality (sand)
    TexSpec(intArrayOf(150, 150, 150), intArrayOf(95, 95, 95), intArrayOf(135, 135, 135), intArrayOf(235, 235, 235), 8, 3),    // 9  Grayscale Halls
    TexSpec(intArrayOf(130, 126, 120), intArrayOf(88, 84, 80), intArrayOf(100, 96, 92), intArrayOf(255, 160, 90), 3, 0),      // 10 City of Dusk
    TexSpec(intArrayOf(200, 200, 206), intArrayOf(168, 168, 176), intArrayOf(210, 210, 216), intArrayOf(255, 255, 255), 5, 3), // 11 Threshold
    TexSpec(intArrayOf(104, 100, 114), intArrayOf(58, 58, 64), intArrayOf(16, 20, 40), intArrayOf(255, 236, 170), 9, 4),         // 12 The City (Level 11)
)

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
                6 -> { // Poolrooms: glazed tile, blue band, still water, skylights
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
                99 -> { // Manila Room: everything the colour of old folders
                    val f = 0.9 + 0.2 * h(x, y, 301) * (if (h(x, y / 9, 302) < 0.1) 0.9 else 1.0)
                    wall[i] = c(226.0, 204.0, 150.0, f); floor[i] = c(214.0, 192.0, 138.0, f); ceil[i] = c(230.0, 210.0, 158.0, f)
                    panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(200, 180, 130) else pack(255, 240, 200)
                }
                else -> { // data-driven levels
                    val sp = SPECS[lv - 7]
                    var r = sp.w[0].toDouble(); var g = sp.w[1].toDouble(); var b = sp.w[2].toDouble()
                    var f = (0.88 + 0.24 * h(x, y, 201 + lv)) * (0.94 + 0.12 * h(x / 4, y / 4, 202 + lv))
                    when (sp.wp) {
                        2 -> if (x % 32 == 0 || y % 32 == 0) f *= 0.8
                        3 -> { // apartment block: concrete seams and lit windows
                            if (x % 32 == 0) f *= 0.85
                            if (x in 18..46 && y in 10..34) {
                                if (x in 20..44 && y in 12..32) { r = 255.0; g = 170.0; b = 80.0; f = 1.0 + 0.2 * (1 - (y - 12) / 20.0) } else { r = 50.0; g = 46.0; b = 44.0 }
                            }
                        }
                        5 -> { // triangle outlines
                            val tx = x % 32; val ty = y % 32
                            if (ty in 7..25 && abs(abs(tx - 16) - (ty - 6) / 2) < 1) f *= 0.55
                        }
                        9 -> { // city facade: window grid (about half lit) and a lit shopfront at street level
                            val wx = x % 16; val wy = y % 16
                            if (y >= 48) { if (x % 32 in 3..28 && y in 50..61) { r = 255.0; g = 196.0; b = 110.0; f = 1.0 + 0.2 * h(x, y, 208) } else { r = 52.0; g = 50.0; b = 56.0 } }
                            else if (wx in 3..12 && wy in 3..12) {
                                if (h(x / 16, y / 16, 207) < 0.5) { r = 255.0; g = 214.0; b = 130.0; f = 1.0 + 0.2 * h(x, y, 209) } else { r = 34.0; g = 44.0; b = 64.0 }
                            } else if (wy == 0) f *= 0.8
                        }
                        8 -> { // framed pictures
                            if (x in 10..53 && y in 8..42) {
                                if (x in 13..50 && y in 11..39) { val v = 70 + 110 * h(x / 3, y / 3, 206); r = v; g = v; b = v; f = 1.0 } else { r = 40.0; g = 40.0; b = 40.0 }
                            }
                        }
                    }
                    wall[i] = c(r, g, b, f)
                    var ff = 0.85 + 0.3 * h(x, y, 211 + lv)
                    when (sp.fp) {
                        1 -> ff *= 0.88 + 0.16 * sin((y + 3 * sin(x * 0.25)) * 0.7)           // sand ripples
                        3 -> if (x % 16 == 0 || y % 16 == 0) ff *= 0.7                         // tile grout
                    }
                    floor[i] = c(sp.f[0].toDouble(), sp.f[1].toDouble(), sp.f[2].toDouble(), ff)
                    if (sp.fp == 4) { // asphalt: yellow dashes down the middle, pale kerb at the edges
                        if (x in 30..33 && y % 32 < 18) floor[i] = pack(206, 176, 58) else if (x < 2 || x > 61) floor[i] = pack(122, 122, 128)
                    }
                    var tf = 0.9 + 0.15 * h(x, y, 221 + lv); if (x < 2 || y < 2) tf *= 0.75
                    ceil[i] = c(sp.c[0].toDouble(), sp.c[1].toDouble(), sp.c[2].toDouble(), tf)
                    if (sp.wp == 9 && h(x, y, 230) < 0.015) ceil[i] = pack(240, 240, 255) // night sky: stars
                    panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(sp.p[0] * 3 / 4, sp.p[1] * 3 / 4, sp.p[2] * 3 / 4) else pack(sp.p[0], sp.p[1], sp.p[2])
                }
            }
        }
    }
}

val TEX = Array(LEVELS.size) { TexSet(it) }
val MANILA = TexSet(99)
private val FLICKER = arrayOf(doubleArrayOf(0.12, 0.20, 0.27), doubleArrayOf(0.20, 0.28, 0.40), doubleArrayOf(0.15, 0.25, 0.32),
    doubleArrayOf(0.25, 0.35, 0.45), doubleArrayOf(0.15, 0.22, 0.30), doubleArrayOf(0.18, 0.30, 0.38), doubleArrayOf(0.02, 0.05, 0.05),
    doubleArrayOf(0.02, 0.04, 0.95), doubleArrayOf(0.0, 0.02, 0.03), doubleArrayOf(0.10, 0.20, 0.28), doubleArrayOf(0.0, 0.05, 0.08),
    doubleArrayOf(0.08, 0.16, 0.20), doubleArrayOf(0.02, 0.05, 0.06))

// ---------- Renderer: textured raycaster, pooled lighting, fog, look up/down, entities ----------
private val SINT = DoubleArray(4096) { sin(it * 2 * PI / 4096) }
/** Table sine for cosmetic shimmer (water); accuracy does not matter there, speed does. */
private fun fsin(x: Double) = SINT[(x * (4096 / (2 * PI))).toInt() and 4095]

/** Floor/ceiling rows, wall columns and the post pass are split over up to `threads` threads (the caller works too). */
class Renderer(val W: Int = 480, val H: Int = 270, threads: Int = min(4, Runtime.getRuntime().availableProcessors())) {
    private val zb = DoubleArray(W)
    private val vig = IntArray(W * H)
    private val camX = DoubleArray(W) { 2.0 * it / W - 1 }
    private var grain = 1234567
    private var frame = 0
    private var lpx = 0.0; private var lpy = 0.0; private var lant = 0.0; private var pres = 0.0
    private val nt = max(1, threads)
    private val pool = if (nt > 1) java.util.concurrent.Executors.newFixedThreadPool(nt - 1) { r -> Thread(r).also { it.isDaemon = true } } else null

    /** Per-thread lamp memo: a lamp's brightness (flicker) is worked out once per frame instead of once per pixel. */
    private inner class Lamps {
        val kx = IntArray(256); val ky = IntArray(256); val fr = IntArray(256); val v = DoubleArray(256)
        var mx = 0; var my = 0; var mv = 1.0; var mf = -1
        fun at(lv: Int, x: Int, y: Int, t: Double): Double {
            if (mf == frame && x == mx && y == my) return mv // neighbouring pixels almost always share the lamp
            val i = ((x * 73856093) xor (y * 19349663)) and 255
            val r = if (fr[i] == frame && kx[i] == x && ky[i] == y) v[i]
                    else (flick(lv, x, y, t) * spook(x, y, t)).also { kx[i] = x; ky[i] = y; fr[i] = frame; v[i] = it }
            mf = frame; mx = x; my = y; mv = r
            return r
        }
    }
    private val bands = Array(nt) { Lamps() }
    private val rowTmp = IntArray(W)

    /** body(band, from, to) over [0, n) in `nt` slices; the last slice runs on the calling thread. */
    private fun par(n: Int, body: (Int, Int, Int) -> Unit) {
        val p = pool
        if (p == null) { body(0, 0, n); return }
        val fs = ArrayList<java.util.concurrent.Future<*>>(nt)
        for (b in 0 until nt - 1) { val from = n * b / nt; val to = n * (b + 1) / nt; fs.add(p.submit(Runnable { body(b, from, to) })) }
        body(nt - 1, n * (nt - 1) / nt, n)
        for (f in fs) f.get()
    }

    init {
        for (y in 0 until H) for (x in 0 until W) {
            val nx = (x - W / 2.0) / (W / 2.0); val ny = (y - H / 2.0) / (H / 2.0)
            vig[y * W + x] = ((1 - 0.38 * (nx * nx + ny * ny)).coerceIn(0.3, 1.0) * 256).toInt()
        }
    }

    private fun sq(a: Double) = a * a
    private fun sc(r: Int, g: Int, b: Int, dim: Double) = pack((r * dim).toInt(), (g * dim).toInt(), (b * dim).toInt())

    private fun mul(c: Int, f: Double, tn: IntArray): Int {
        val k = (f * 256).toInt().coerceIn(0, 640)
        return pack((((c shr 16) and 255) * k * tn[0]) shr 16, (((c shr 8) and 255) * k * tn[1]) shr 16, ((c and 255) * k * tn[2]) shr 16)
    }

    /** Same as mul() with the per-colour multipliers (k * tint) worked out once per wall column. */
    private fun mul2(c: Int, kr: Int, kg: Int, kb: Int) = pack((((c shr 16) and 255) * kr) shr 16, (((c shr 8) and 255) * kg) shr 16, ((c and 255) * kb) shr 16)

    private fun flick(lv: Int, kx: Int, ky: Int, t: Double): Double {
        val v = h(kx, ky, 9 + lv * 13); val th = FLICKER[lv]
        return when {
            v < th[0] * 0.4 -> if (sin(t * (6 + v * 20) + v * 50) > 0.5) 0.45 else 1.0 // broken: few, slow, shallow stutter
            v < th[1] -> 0.96 + 0.04 * sin(t * 40)                                    // humming (barely visible)
            v < th[2] -> 0.0                                                     // dead
            else -> 1.0
        }
    }

    /** Lamps near an entity stutter: more of them, and for longer, the closer it is. 1.0 = steady. */
    private fun spook(kx: Int, ky: Int, t: Double): Double {
        if (pres < 0.02 || h(kx, ky, 77) > pres * 1.3) return 1.0
        return if (sin(t * (16 + h(kx, ky, 78) * 30) + h(kx, ky, 79) * 20) > 0.7 - pres) 0.1 else 1.0
    }

    /** Light pooled around each ceiling panel (one every `period` cells). */
    private fun lightAt(d: LevelDef, lp: Lamps, wx: Double, wy: Double, t: Double): Double {
        val p = d.period
        val kx = floor((wx - 1.5) * d.ip + 0.5).toInt(); val ky = floor((wy - 1.5) * d.ip + 0.5).toInt()
        val ddx = wx - (kx * p + 1.5); val ddy = wy - (ky * p + 1.5)
        val lamp = d.ambient + lp.at(d.id, kx, ky, t) / (1 + (ddx * ddx + ddy * ddy) * d.falloff)
        return if (lant > 0.0) lamp + lant / (1 + (sq(wx - lpx) + sq(wy - lpy)) * 0.35) else lamp
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
    private fun sprite(type: Int, u: Double, v: Double, dim: Double, fr: Boolean = false): Int { // fr = friendly (Explore): green eyes, soft glow
        val dark = pack((16 * dim).toInt(), (14 * dim).toInt(), (10 * dim).toInt())
        return when (type) {
            0, 3, 4 -> { // player = tall dark figure with glowing eyes; type 3 (Skin-Stealer) copies it, minus the glow; type 4 (Faceling) has a pale head with misplaced features
                val inside = when {
                    v < 0.17 -> sq(u / 0.42) + sq((v - 0.09) / 0.085) < 1
                    v < 0.22 -> abs(u) < 0.18
                    v < 0.62 -> abs(u) < 0.8 - (v - 0.22) * 0.5
                    else -> abs(abs(u) - 0.35) < 0.22
                }
                if (!inside) 0
                else if (type == 4) {
                    if (v >= 0.17) sc(40, 42, 56, dim)
                    else if (sq((u + 0.12) / 0.06) + sq((v - 0.07) / 0.016) < 1 || sq((u - 0.2) / 0.05) + sq((v - 0.105) / 0.02) < 1 || (abs(u + 0.02) < 0.12 && abs(v - 0.145) < 0.006)) dark
                    else sc(208, 192, 178, dim)
                }
                else if (type == 0 && v in 0.075..0.10 && abs(abs(u) - 0.17) < 0.07) pack(255, 255, 225)
                else if (fr && type == 3 && v in 0.075..0.10 && abs(abs(u) - 0.17) < 0.07) pack(130, 255, 160) else dark
            }
            5 -> { // Lucky Crane: white bird, red crown, long neck
                val head = sq((u + 0.45) / 0.12) + sq((v - 0.17) / 0.09) < 1
                val neck = abs(u + 0.42) < 0.07 && v in 0.2..0.55
                val body = sq((u - 0.05) / 0.5) + sq((v - 0.58) / 0.2) < 1
                when {
                    head && v < 0.12 -> sc(210, 40, 40, dim)
                    head || neck || body -> sc(236, 236, 226, dim)
                    v in 0.14..0.2 && u in -0.8..-0.56 -> sc(200, 120, 40, dim)
                    u in 0.45..0.8 && abs(v - (0.52 - (u - 0.45) * 0.4)) < 0.05 -> sc(40, 40, 44, dim)
                    v > 0.76 && (abs(u) < 0.03 || abs(u - 0.2) < 0.03) -> sc(200, 120, 40, dim)
                    else -> 0
                }
            }
            6 -> { // Living Pizza: round, crust, cheese, pepperoni and a worried face
                val r2 = sq(u * 0.5) + sq(v - 0.55)
                if (r2 > 0.2) 0
                else if (sq((abs(u) - 0.35) / 0.06) + sq((v - 0.46) / 0.03) < 1) sc(20, 16, 14, dim)
                else if (sq((abs(u) - 0.35) / 0.17) + sq((v - 0.45) / 0.075) < 1) sc(245, 245, 240, dim)
                else if (abs(u) < 0.3 && abs(v - (0.68 + 0.04 * sq(u / 0.3))) < 0.015) sc(120, 30, 24, dim)
                else if (sq((u + 0.55) / 0.17) + sq((v - 0.65) / 0.08) < 1 || sq((u - 0.5) / 0.17) + sq((v - 0.7) / 0.08) < 1 || sq(u / 0.17) + sq((v - 0.3) / 0.08) < 1) sc(176, 52, 42, dim)
                else if (r2 > 0.15) sc(206, 140, 60, dim)
                else sc(250, 205, 92, dim)
            }
            7 -> { // ration: tin can
                if (abs(u) > 0.7 || v < 0.15) 0
                else if (v < 0.3 || v > 0.9) sc(196, 200, 204, dim)
                else if (v in 0.45..0.75) sc(236, 226, 190, dim) else sc(170, 60, 46, dim)
            }
            8 -> { // almond water: bottle with a cream label
                val neck = abs(u) < 0.28 && v in 0.08..0.3; val body = abs(u) < 0.7 && v in 0.3..1.0
                if (!neck && !body) 0
                else if (v < 0.14) sc(210, 170, 60, dim)
                else if (body && v in 0.52..0.82) (if (abs(u) < 0.2 && v in 0.6..0.74) sc(150, 98, 52, dim) else sc(240, 226, 190, dim))
                else sc(170, 212, 238, dim)
            }
            1 -> { // Hound: low quadruped, red eye
                val body = sq((u - 0.1) / 0.7) + sq((v - 0.55) / 0.32) < 1
                val head = sq((u + 0.72) / 0.26) + sq((v - 0.38) / 0.22) < 1
                val legs = v > 0.78 && (abs(u - 0.65) < 0.09 || abs(u - 0.35) < 0.09 || abs(u + 0.25) < 0.09 || abs(u + 0.5) < 0.09)
                if (sq((u + 0.82) / 0.07) + sq((v - 0.33) / 0.07) < 1) (if (fr) pack(120, 255, 150) else pack(255, 50, 30)) else if (body || head || legs) dark else 0
            }
            else -> { // Smiler: floating dark face, glowing eyes and grin
                val yc = 0.47 + 0.06 * (1 - sq(u / 0.62))
                val glow = sq((abs(u) - 0.34) / 0.16) + sq((v - 0.34) / 0.03) < 1 || (abs(u) < 0.62 && abs(v - yc) < 0.022)
                if (glow) (if (fr) pack(190, 255, 200) else pack(255, 250, 215)) else if (sq(u / 0.88) + sq((v - 0.4) / 0.22) < 1) pack(26, 22, 20) else 0
            }
        }
    }

    fun render(px: IntArray, g: Game, t: Double, others: Collection<DoubleArray>, hud: Boolean = true) {
        val lv = g.level; val d = LEVELS[lv]; val ts = TEX[lv]; val tn = d.tint
        val x = g.x; val y = g.y; val a = g.a
        frame++; lpx = x; lpy = y; lant = d.lantern; pres = g.presence
        val hz = (H / 2 + sin(g.bob) * g.bobAmp * 3.0 + g.pitch * H * 0.5).toInt()
        val gl = if (sin(t * 2.7) * sin(t * 13.1) > 0.985) 0.75 else 1.0 // very rare, mild global flicker
        val dx = cos(a); val dy = sin(a)
        val pl = tan(Math.toRadians(Settings.fov / 2.0)); val vs = 0.66 / pl // camera plane length from FOV; vs keeps heights in proportion
        val plX = -dy * pl; val plY = dx * pl
        val fk = d.fogK * Settings.fogScale // Options > Fog scales every level's own fog

        // floor + ceiling (rows are split between threads)
        par(H) { b, r0, r1 ->
            val lp = bands[b]
            for (yy in r0 until r1) {
                val dr = yy - hz
                if (dr == 0) { for (xx in 0 until W) px[yy * W + xx] = pack(8, 6, 4); continue }
                val rd = 0.5 * H * vs / abs(dr)
                val fog = 1.0 / (1 + rd * rd * fk)
                val isFloor = dr > 0
                val water = lv == 6 && isFloor
                for (xx in 0 until W) {
                    val cx = camX[xx]
                    val wx = x + (dx + plX * cx) * rd; val wy = y + (dy + plY * cx) * rd
                    val tx = ((wx - floor(wx)) * 64).toInt() and 63; val ty = ((wy - floor(wy)) * 64).toInt() and 63
                    val mt = if (lv == 0 && Manila.inside(wx, wy)) MANILA else ts
                    val l = if (mt === MANILA) 1.05 else lightAt(d, lp, wx, wy, t) * gl
                    px[yy * W + xx] = if (isFloor) mul(mt.floor[ty * 64 + tx], l * fog * 0.85 * (if (water) 0.85 + 0.2 * fsin(wx * 9 + t * 1.5) * fsin(wy * 9 + t * 1.1) else 1.0), tn)
                    else if (Math.floorMod(floor(wx).toInt(), d.period) == 1 && Math.floorMod(floor(wy).toInt(), d.period) == 1)
                        mul(mt.panel[ty * 64 + tx], l * max(fog, 0.6), tn)
                    else mul(mt.ceil[ty * 64 + tx], l * fog, tn)
                }
            }
        }

        // textured walls (DDA; columns are split between threads)
        par(W) { b, c0, c1 ->
            val lp = bands[b]
            for (xx in c0 until c1) {
                val cx = camX[xx]
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
                val wt = if (lv == 0 && Manila.inBox(mapX, mapY)) MANILA else ts
                val perp = (if (side == 0) sdx - ddx else sdy - ddy).coerceAtLeast(0.05)
                zb[xx] = perp
                val hx = x + perp * rx; val hy = y + perp * ry
                var tx = (((if (side == 0) hy else hx).let { it - floor(it) }) * 64).toInt() and 63
                if ((side == 0 && rx > 0) || (side == 1 && ry < 0)) tx = 63 - tx
                val br = (if (wt === MANILA) 1.05 else lightAt(d, lp, hx, hy, t) * gl) / (1 + perp * perp * fk) * (if (side == 1) 0.82 else 1.0)
                val lineH = (H * vs / perp).toInt().coerceAtLeast(1)
                val start = hz - lineH / 2
                val y0 = max(0, start); val y1 = min(H - 1, start + lineH)
                val step = 64.0 / lineH
                var tpos = (y0 - start) * step // texture row, stepped instead of divided per pixel
                if (unst) {
                    for (yy in y0..y1) { // cold, wobbling texture = not solid
                        val ty = min(63, tpos.toInt()); tpos += step
                        val tx2 = (tx + (sin(t * 11 + yy * 0.25) * 4).toInt()) and 63
                        val c0v = mul(wt.wall[ty * 64 + tx2], br * (0.85 + 0.3 * sin(t * 7 + xx * 0.1)), tn)
                        px[yy * W + xx] = pack((((c0v shr 16) and 255) * 3) / 4, ((c0v shr 8) and 255) + 25, (c0v and 255) + 60)
                    }
                } else {
                    val k = (br * 256).toInt().coerceIn(0, 640); val kr = k * tn[0]; val kg = k * tn[1]; val kb = k * tn[2]
                    for (yy in y0..y1) { val ty = min(63, tpos.toInt()); tpos += step; px[yy * W + xx] = mul2(wt.wall[ty * 64 + tx], kr, kg, kb) }
                }
            }
        }

        // sprites: other players on this level + this level's entities, far to near
        val items = ArrayList<DoubleArray>()
        for (o in others) if (o.size > 2 && o[2].toInt() == lv) {
            // isolated levels hide other players, even right next to you (except inside the Manila Room)
            if (d.isolated && !(lv == 0 && Manila.inside(x, y) && Manila.inside(o[0], o[1]))) continue
            items.add(doubleArrayOf(o[0], o[1], 0.0))
        }
        val friendly = Settings.explore
        for (e in g.entities) items.add(if (friendly && e.type in 1..4) doubleArrayOf(e.x, e.y, e.type.toDouble(), 2.0) else doubleArrayOf(e.x, e.y, e.type.toDouble()))
        for (sp in g.supplies) items.add(doubleArrayOf(sp.x + 0.5, sp.y + 0.5, if (sp.type == 1) 7.0 else 8.0))
        for (gh in g.ghosts) if (sin(t * 11 + gh.x * 3) > -0.4) items.add(doubleArrayOf(gh.x, gh.y, gh.type.toDouble(), 1.0)) // hallucinations flicker in and out
        items.sortByDescending { sq(it[0] - x) + sq(it[1] - y) }
        for (o in items) {
            val rx = o[0] - x; val ry = o[1] - y; val type = o[2].toInt()
            val inv = 1.0 / (plX * dy - dx * plY)
            val tx = inv * (dy * rx - dx * ry); val ty = inv * (-plY * rx + plX * ry)
            if (ty <= 0.2) continue
            val sx = (W / 2 * (1 + tx / ty)).toInt(); val full = H * vs / ty
            val hk = when (type) { 1 -> 0.6; 5 -> 0.55; 6 -> 0.45; 7 -> 0.2; 8 -> 0.26; else -> 0.9 }
            val wk = when (type) { 1 -> 1.7; 2 -> 0.5; 5 -> 0.9; 6 -> 1.0; 7 -> 0.9; 8 -> 0.55; else -> 0.42 }
            val sh = (full * hk).toInt().coerceAtLeast(2)
            val sw = (sh * wk).toInt().coerceAtLeast(2)
            val bottom = hz + (full / 2).toInt(); val top = bottom - sh
            val dim = (0.55 + 0.45 / (1 + ty * 0.3)).let { if (type >= 7) max(0.85, it) else if (o.size > 3 && o[3] == 1.0) it * 0.6 else it } // supplies stay easy to spot; ghosts are dim
            for (col in max(0, sx - sw / 2)..min(W - 1, sx + sw / 2)) {
                if (ty >= zb[col]) continue
                val u = (col - sx) / (sw / 2.0)
                for (row in max(0, top)..min(H - 1, bottom)) {
                    val c = sprite(type, u, (row - top).toDouble() / sh, dim, o.size > 3 && o[3] == 2.0)
                    if (c != 0) px[row * W + col] = c
                }
            }
        }

        // post: vignette, film grain, danger pulse, death fade (rows are split between threads, each with its own grain stream)
        val dgr = max(g.danger * g.danger * (0.5 + 0.5 * sin(t * (5 + 9 * g.danger))), g.halluc * 0.3 * (0.5 + 0.5 * sin(t * 1.9))) // danger pulse; hallucinations breathe red
        val rk = (dgr * 180).toInt()
        val fadeI = ((if (g.dead) (1 - g.deadT / 1.2).coerceIn(0.0, 1.0) else 1.0) * 256).toInt()
        val wash = ((g.noclip * g.noclip * 150) + g.flash * 255).toInt()
        val amp = 1 + (g.near * 2).toInt()
        val seed = grain
        par(H) { b, r0, r1 ->
            var s = seed xor (b * -1640531535)
            if (rk == 0 && wash == 0) for (i in r0 * W until r1 * W) { // usual case: no red pulse, no flash
                val c = px[i]; val v = (vig[i] * fadeI) shr 8
                s = s * 1664525 + 1013904223
                val nz = (((s ushr 28) and 15) - 8) * amp
                px[i] = pack((((c shr 16) and 255) * v shr 8) + nz, (((c shr 8) and 255) * v shr 8) + nz, ((c and 255) * v shr 8) + nz)
            } else for (i in r0 * W until r1 * W) {
                val c = px[i]; val v = (vig[i] * fadeI) shr 8
                s = s * 1664525 + 1013904223
                val nz = (((s ushr 28) and 15) - 8) * amp
                val red = ((256 - vig[i]) * rk) shr 8
                px[i] = pack((((c shr 16) and 255) * v shr 8) + nz + red + wash, (((c shr 8) and 255) * v shr 8) + nz + wash, ((c and 255) * v shr 8) + nz + wash)
            }
        }
        grain = seed * 1664525 + 1013904223
        val gk = max(g.noclip, g.near * 0.3) // reality tears while noclipping, and faintly when a not-quite-solid wall is near
        if (gk > 0.02) {
            var q = grain
            repeat((gk * 50).toInt() + 1) {
                q = q * 1664525 + 1013904223; val row = ((q ushr 8) and 0xFFFF) % H
                q = q * 1664525 + 1013904223; val sh = ((((q ushr 8) and 0xFF) - 128) * gk * 0.35).toInt()
                if (sh != 0) {
                    val base = row * W; val tmp = px.copyOfRange(base, base + W)
                    for (i in 0 until W) px[base + (((i + sh) % W) + W) % W] = tmp[i]
                }
            }
        }

        if (g.halluc > 0.04) { // hallucination: the picture sways from side to side
            val amp = g.halluc * 5.0
            for (row in 0 until H) {
                val sh = (sin(row * 0.11 + t * 2.3) * amp + sin(row * 0.047 - t * 1.1) * amp * 0.6).toInt()
                if (sh == 0) continue
                val base = row * W; System.arraycopy(px, base, rowTmp, 0, W)
                if (sh > 0) { System.arraycopy(rowTmp, 0, px, base + sh, W - sh); System.arraycopy(rowTmp, W - sh, px, base, sh) }
                else { val k = -sh; System.arraycopy(rowTmp, k, px, base, W - k); System.arraycopy(rowTmp, 0, px, base + W - k, k) }
            }
        }

        if (hud) drawHud(px, g, others)
    }

    // ---------- shape-only HUD (text is drawn by each platform) ----------
    private fun drawHud(px: IntArray, g: Game, others: Collection<DoubleArray>) {
        if (g.showMap && !LEVELS[g.level].isolated) { // no maps on isolated levels
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
