package com.backrooms.game
// L139_WarriorsTrial.kt - Level 139: The Warrior's Trial. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Level 139: The Warrior's Trial. A small tropical island in an endless ocean with an extinct volcano at its centre: a survival training
 *  ground run by The Warrior, where you face your fears to prove yourself. Three trials lie between the south beach and the crater lake:
 *   1. The Dark Cave: a dark, cramped cave full of tarantulas (arachnophobia, claustrophobia, nyctophobia).
 *   2. The Waterfall: climb a tall waterfall by a slippery ledge; the camera rises with you (acrophobia).
 *   3. The Volcanic Tunnel: wind through volcanic tunnels with lava on the floor to reach the summit (claustrophobia).
 *  At the summit, step into the crater lake to accept the trial and leave. Refuse (walk into the sea) or die and you are sent back to the
 *  dangerous situation you came from. A fall from the ledge or a step in the lava only sends you back to the start of that trial. No noclip walls. */
object Level139WarriorsTrial : LevelSpec {
    override val def = LevelDef(139, "LEVEL 139", "THE WARRIOR'S TRIAL", "A tropical island, a silent volcano, and a Warrior who tests you.", "THE WARRIOR, TARANTULAS", 0, 0, 8, 1.10, 0.0, 0.0004,
        intArrayOf(255, 252, 244), 0xFF8A3D, danger = 2)
    override val noclip = intArrayOf()
    override val flicker = doubleArrayOf(0.0, 0.0, 1.0) // every lamp dead: daylight only
    override val world: OpenWorld? = Island
    override val spawn = Island.SPAWN
    override fun wall(x: Int, y: Int) = Island.wall(x, y)
    override fun textures(t: TexSet) = t.fromSpec(139, TexSpec(intArrayOf(120, 110, 100), intArrayOf(128, 128, 128), intArrayOf(128, 128, 128), intArrayOf(255, 255, 255), 0, 0))
}

const val LV_WARRIOR = 139

object Island : OpenWorld {
    /** Distance from the island's centre; south (+y) is the beach where you arrive. */
    fun rad(x: Int, y: Int) = hypot(x + 0.5, y + 0.5)
    const val SEA = 50.0        // beyond this you have turned your back on the trial
    const val LAKE = 2.6        // inside this you have stepped into the crater lake: trial accepted
    const val CRATER = 6.0      // the summit: ash around the lake
    val SPAWN = doubleArrayOf(0.5, 38.5)
    val LEDGE_START = doubleArrayOf(7.2, 15.7)     // where a fall puts you back
    val TUNNEL_START = doubleArrayOf(4.5, -16.7)   // where the lava puts you back

    // the three routes through the volcano
    private fun p(x: Double, y: Double) = doubleArrayOf(x, y)
    private val CAVE = arrayOf(p(0.0, 25.0), p(0.0, 21.0), p(8.0, 21.0), p(8.0, 17.5), p(7.2, 15.7)) // trial 1: 2-3 cells wide, roofed, nearly dark
    private val VOLC = arrayOf(p(4.5, -16.7), p(3.0, -13.0), p(-5.0, -13.0), p(-5.0, -9.0), p(3.0, -9.0), p(3.0, -6.5), p(2.5, -4.5)) // trial 3: roofed, lava glow
    private const val LEDGE_R = 17.3                                                                    // trial 2: a ledge round the east flank, from 66 down to -75 degrees
    private const val A0 = 66.0; private const val A1 = -75.0
    const val CAVE_LENGTH = 15.5

    private fun segDist(px: Double, py: Double, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy
        val t = (((px - a[0]) * dx + (py - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (a[0] + t * dx), py - (a[1] + t * dy))
    }
    private fun near(path: Array<DoubleArray>, px: Double, py: Double): Double { var m = 1e9; for (i in 0 until path.size - 1) m = min(m, segDist(px, py, path[i], path[i + 1])); return m }
    /** Point `s` cells along the cave from its mouth (for placing the tarantulas). */
    fun tunnelPoint(s: Double): DoubleArray {
        var left = s
        for (i in 0 until CAVE.size - 1) { val a = CAVE[i]; val b = CAVE[i + 1]; val l = hypot(b[0] - a[0], b[1] - a[1])
            if (left <= l) return doubleArrayOf(a[0] + (b[0] - a[0]) * left / l, a[1] + (b[1] - a[1]) * left / l); left -= l }
        return CAVE.last()
    }

    // cell classes inside the volcano (bit set), worked out once per 32x32 chunk
    const val C_CAVE = 1; const val C_VOLC = 2; const val C_LEDGE = 4; const val C_DROP = 8; const val C_LAVA = 16; const val C_FALLS = 32; const val C_SOLID = 64
    private fun compute(x: Int, y: Int): Int {
        val px = x + 0.5; val py = y + 0.5; val r = hypot(px, py)
        if (r < CRATER || r >= 24.0) return 0
        var c = 0; val ang = Math.toDegrees(atan2(py, px))
        if (r in 17.0..26.0 && near(CAVE, px, py) <= 1.1) c = c or C_CAVE
        if (r in 5.5..17.8 && near(VOLC, px, py) <= 1.7) { c = c or C_VOLC; if (h(x, y, 650) < 0.5 && near(VOLC, px, py) > 0.75) c = c or C_LAVA } // 3-4 wide; lava pools at the edges, a clear line down the middle
        if (abs(r - LEDGE_R) < 0.75 && ang <= A0 && ang >= A1) c = c or C_LEDGE
        if (c == 0 && r >= 15.7 && r < 16.55 && ang <= A0 + 3 && ang >= A1 - 3 && hypot(px - TUNNEL_START[0], py - TUNNEL_START[1]) > 2.2) c = C_DROP // the gap below the ledge
        if (c == 0) c = C_SOLID or (if (r >= 18.05 && r < 20.5 && ang <= A0 && ang >= A1) C_FALLS else 0) // rock; the cliff beside the ledge is the waterfall
        return c
    }
    private class CC(val cx: Int, val cy: Int, val v: ByteArray)
    private val cache = java.util.concurrent.atomic.AtomicReferenceArray<CC?>(256)
    /** Class bits of the cell (0 outside the volcano slopes and in the crater). */
    fun cell(x: Int, y: Int): Int {
        val cx = x shr 5; val cy = y shr 5; val slot = ((cx * 73856093) xor (cy * 19349663)) and 255
        var c = cache.get(slot)
        if (c == null || c.cx != cx || c.cy != cy) { val v = ByteArray(1024); for (j in 0 until 32) for (k in 0 until 32) v[j * 32 + k] = compute((cx shl 5) + k, (cy shl 5) + j).toByte(); c = CC(cx, cy, v); cache.set(slot, c) }
        return c.v[((y and 31) shl 5) or (x and 31)].toInt()
    }
    /** How far along the ledge you are, 0..1 (the camera rises with it). */
    fun ledgeProgress(x: Double, y: Double) = ((A0 - Math.toDegrees(atan2(y, x))) / (A0 - A1)).coerceIn(0.0, 1.0)

    fun wall(x: Int, y: Int): Boolean {
        val r = rad(x, y)
        if (r < CRATER) return false                                    // the summit: ash and the lake
        if (r < 24.0) return (cell(x, y) and C_SOLID) != 0              // the volcano is solid rock except the three routes
        if (r < 34.0) return if (y > 0 && abs(x) <= 2) false else h(x, y, 600) < 0.10 // jungle, with a clear trail south
        if (r < 41.0) return h(x, y, 601) < 0.025                       // a few palms on the beach
        return false                                                    // shallows and open sea
    }

    override val haze = pack(200, 226, 244)
    override val hazeK = 0.0009
    override val roofColor = pack(34, 28, 24)
    override fun height(x: Int, y: Int) = if (rad(x, y) < 24.0) (if ((cell(x, y) and C_FALLS) != 0) 14 else 6 + (h(x / 3, y / 3, 610) * 3).toInt()) else 2 + (h(x, y, 611) * 2).toInt()
    override fun style(x: Int, y: Int) = if (rad(x, y) < 24.0) (if ((cell(x, y) and C_FALLS) != 0) 3 else if (h(x / 2, y / 2, 612) < 0.5) 0 else 1) else 2
    override fun texIndex(style: Int, unit: Int, ht: Int) = if (style == 3) 4 else if (style < 2) style else if (unit >= ht - 1) 3 else 2
    override fun roofed(x: Int, y: Int) = (cell(x, y) and (C_CAVE or C_VOLC)) != 0
    override fun dim(x: Int, y: Int): Double { val c = cell(x, y); return if ((c and C_CAVE) != 0) 0.2 else if ((c and C_VOLC) != 0) 0.55 else 1.0 }

    private fun col(r: Int, g: Int, b: Int, n: Double, f: Double = 1.0) = pack((r * n * f).toInt(), (g * n * f).toInt(), (b * n * f).toInt())
    override fun ground(wx: Double, wy: Double, ix: Int, iy: Int, base: Int): Int {
        val r = hypot(wx, wy); val n = 0.85 + 0.3 * ((base and 255) / 255.0)
        if (r < 24.0 && r >= CRATER) { val c = cell(ix, iy)
            if ((c and C_LAVA) != 0) return col(255, 100, 25, n, 0.85 + 0.2 * sin(wx * 3.0 + wy * 2.0)) // lava
            if ((c and C_VOLC) != 0) return col(70, 52, 46, n)
            if ((c and C_CAVE) != 0) return col(60, 52, 46, n)
            if ((c and C_LEDGE) != 0) return col(96, 104, 110, n, 0.9 + 0.1 * sin(wx * 5.0))          // wet rock
            if ((c and C_DROP) != 0) return col(26, 46, 68, n)                                          // mist far below
            return col(80, 70, 62, n)
        }
        return when {
            r < LAKE + 0.3 -> col(40, 120, 170, n, 0.85 + 0.15 * sin(r * 3.0 + wx))   // the crater lake
            r < CRATER -> col(76, 66, 60, n)                                           // ash around the lake
            r < 34.0 -> if (wy > 0 && abs(wx) <= 2.0) col(150, 120, 76, n) else col(54, 108, 46, n, 0.85 + 0.3 * h(ix, iy, 602)) // trail / jungle floor
            r < 41.0 -> col(218, 194, 142, n)                                          // sand
            r < 47.0 -> { val t = (r - 41.0) / 6.0; col((218 * (1 - t) + 80 * t).toInt(), (194 * (1 - t) + 170 * t).toInt(), (142 * (1 - t) + 190 * t).toInt(), n) } // shallows
            else -> col(24, 88, 140, n, 0.9 + 0.2 * sin(wx * 0.9 + wy * 0.6))          // deep sea
        }
    }

    override val tex: Array<IntArray> by lazy {
        fun rock(v: Int) = IntArray(4096) { j -> val x = j % 64; val y = j / 64; var f = 0.7 + 0.5 * h(x, y, 620 + v)
            if (y % 16 == 0) f *= 0.7; if (h(x / 4, y / 6, 625) < 0.10) f *= 0.6
            pack(((70 + 10 * v) * f).toInt(), ((60 + 8 * v) * f).toInt(), ((54 + 8 * v) * f).toInt()) }
        arrayOf(rock(0), rock(1),
            IntArray(4096) { j -> val x = j % 64; val y = j / 64; var f = 0.6 + 0.8 * h(x, y, 630); if (h(x / 5, y / 5, 631) < 0.3) f *= 1.3; pack((40 * f).toInt(), (120 * f).toInt(), (50 * f).toInt()) },
            IntArray(4096) { j -> val x = j % 64; val y = j / 64; val f = 0.7 + 0.6 * h(x / 2, y / 8, 632); pack((110 * f).toInt(), (78 * f).toInt(), (46 * f).toInt()) },
            IntArray(4096) { j -> val x = j % 64; val y = j / 64; val f = 0.75 + 0.5 * h(x, y / 20, 640); if (x % 7 == 3 || h(x, y / 3, 641) < 0.06) pack(238, 246, 255) else pack((150 * f).toInt(), (200 * f).toInt(), (236 * f).toInt()) }) // waterfall: streaming white water
    }

    /** 2048 x 256 day sky: pale at the horizon, deep blue overhead, clouds and a sun. */
    override val sky: IntArray by lazy {
        val w = 2048; val hh = 256; val s = IntArray(w * hh)
        for (v in 0 until hh) {
            val e = v / 255.0; val r0 = 196 - 120 * e; val g0 = 224 - 90 * e; val b0 = 244 - 36 * e
            for (u in 0 until w) {
                val cl = ((valueNoise(u / 150.0, v / 16.0, 801, 14) - 0.55) / 0.45).coerceIn(0.0, 1.0) * (0.9 - 0.5 * e).coerceAtLeast(0.0)
                s[v * w + u] = pack((r0 + (255 - r0) * cl * 0.85).toInt(), (g0 + (255 - g0) * cl * 0.85).toInt(), (b0 + (255 - b0) * cl * 0.85).toInt())
            }
        }
        val uc = 228; val vc = 142 // the sun (5.69 px per degree across, 2.84 up)
        for (v in vc - 60..vc + 60) for (u in uc - 100..uc + 100) {
            val dd = hypot((u - uc) / 5.69, (v - vc) / 2.84); val o = v * w + u; val c = s[o]
            if (dd < 3.6) s[o] = pack(255, 252, 230)
            else if (dd < 18) { val gl = exp(-(dd - 3.6) / 4.5) * 0.7; s[o] = pack(((c shr 16) and 255) + (60 * gl).toInt(), ((c shr 8) and 255) + (55 * gl).toInt(), (c and 255) + (30 * gl).toInt()) }
        }
        s
    }
}
