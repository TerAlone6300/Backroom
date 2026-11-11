package com.backrooms.game
// L139_WarriorsTrial.kt - Level 139: The Warrior's Trial. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Level 139: The Warrior's Trial. A small tropical island in an endless ocean with an extinct volcano at its centre: a survival training
 *  ground run by The Warrior, where you face your fear to prove yourself. You arrive on the south beach, follow the trail through the jungle
 *  into the cave in the volcano's flank (tarantulas), climb to the crater and step into the lake. Refuse (walk into the sea) or fail
 *  (die) and you are sent back to the dangerous situation you came from. There are no noclip walls. */
object Level139WarriorsTrial : LevelSpec {
    override val def = LevelDef(139, "LEVEL 139", "THE WARRIOR'S TRIAL", "A tropical island, a silent volcano, and a Warrior who tests you.", "THE WARRIOR, TARANTULAS", 0, 0, 8, 1.10, 0.0, 0.0004,
        intArrayOf(255, 252, 244), 0xFF8A3D, danger = 2)
    override val noclip = intArrayOf()
    override val flicker = doubleArrayOf(0.0, 0.0, 1.0) // every lamp dead: daylight only
    override val world: OpenWorld? = Island
    override val spawn = doubleArrayOf(0.5, 36.5)
    override fun wall(x: Int, y: Int) = Island.wall(x, y)
    override fun textures(t: TexSet) = t.fromSpec(139, TexSpec(intArrayOf(120, 110, 100), intArrayOf(128, 128, 128), intArrayOf(128, 128, 128), intArrayOf(255, 255, 255), 0, 0))
}

const val LV_WARRIOR = 139

object Island : OpenWorld {
    /** Distance from the island's centre; south (+y) is the beach where you arrive. */
    fun rad(x: Int, y: Int) = hypot(x + 0.5, y + 0.5)
    const val SEA = 46.0        // beyond this you have turned your back on the trial
    const val LAKE = 3.2        // inside this you have stepped into the crater lake: trial complete

    // the cave: a winding tunnel, 3 cells wide, from the south flank to the crater
    private val TUN = arrayOf(doubleArrayOf(0.0, 19.0), doubleArrayOf(0.0, 15.0), doubleArrayOf(7.0, 15.0), doubleArrayOf(7.0, 11.0), doubleArrayOf(-2.0, 11.0), doubleArrayOf(-2.0, 8.0))
    private fun segDist(px: Double, py: Double, a: DoubleArray, b: DoubleArray): Double {
        val dx = b[0] - a[0]; val dy = b[1] - a[1]; val l2 = dx * dx + dy * dy
        val t = (((px - a[0]) * dx + (py - a[1]) * dy) / l2).coerceIn(0.0, 1.0)
        return hypot(px - (a[0] + t * dx), py - (a[1] + t * dy))
    }
    fun tunnel(x: Int, y: Int): Boolean {
        val r = rad(x, y); if (r > 19.5 || r < 8.0) return false
        for (i in 0 until TUN.size - 1) if (segDist(x + 0.5, y + 0.5, TUN[i], TUN[i + 1]) <= 1.45) return true
        return false
    }
    /** Point `s` cells along the cave from its mouth (for placing the tarantulas). */
    fun tunnelPoint(s: Double): DoubleArray {
        var left = s
        for (i in 0 until TUN.size - 1) { val a = TUN[i]; val b = TUN[i + 1]; val l = hypot(b[0] - a[0], b[1] - a[1])
            if (left <= l) return doubleArrayOf(a[0] + (b[0] - a[0]) * left / l, a[1] + (b[1] - a[1]) * left / l); left -= l }
        return TUN.last()
    }
    const val TUNNEL_LENGTH = 30.0

    fun wall(x: Int, y: Int): Boolean {
        val r = rad(x, y)
        if (r < 9.5) return false                                       // crater floor and lake
        if (r < 18.0) return !tunnel(x, y)                              // the volcano is solid rock except the cave
        if (r < 30.0) return if (y > 0 && abs(x) <= 2) false else h(x, y, 600) < 0.10 // jungle, with a clear trail south
        if (r < 37.0) return h(x, y, 601) < 0.025                       // a few palms on the beach
        return false                                                    // shore, shallows, open sea
    }

    override val haze = pack(200, 226, 244)
    override val hazeK = 0.0009
    override val roofColor = pack(34, 28, 24)
    override fun height(x: Int, y: Int) = if (rad(x, y) < 18.5) 5 + (h(x / 3, y / 3, 610) * 3).toInt() else 2 + (h(x, y, 611) * 2).toInt()
    override fun style(x: Int, y: Int) = if (rad(x, y) < 18.5) (if (h(x / 2, y / 2, 612) < 0.5) 0 else 1) else 2
    override fun texIndex(style: Int, unit: Int, ht: Int) = if (style < 2) style else if (unit >= ht - 1) 3 else 2
    override fun dim(x: Int, y: Int) = if (tunnel(x, y)) 0.45 else 1.0
    override fun roofed(x: Int, y: Int) = tunnel(x, y)

    private fun col(r: Int, g: Int, b: Int, n: Double, f: Double = 1.0) = pack((r * n * f).toInt(), (g * n * f).toInt(), (b * n * f).toInt())
    override fun ground(wx: Double, wy: Double, ix: Int, iy: Int, base: Int): Int {
        val r = hypot(wx, wy); val n = 0.85 + 0.3 * ((base and 255) / 255.0)
        return when {
            r < LAKE + 0.3 -> col(40, 120, 170, n, 0.85 + 0.15 * sin(r * 3.0 + wx))   // the crater lake
            r < 9.5 -> col(76, 66, 60, n)                                              // ash around the lake
            tunnel(ix, iy) || r < 18.0 -> col(84, 74, 64, n)                           // cave floor
            r < 30.0 -> if (wy > 0 && abs(wx) <= 2.0) col(150, 120, 76, n) else col(54, 108, 46, n, 0.85 + 0.3 * h(ix, iy, 602)) // trail / jungle floor
            r < 38.0 -> col(218, 194, 142, n)                                          // sand
            r < 44.0 -> { val t = (r - 38.0) / 6.0; col((218 * (1 - t) + 80 * t).toInt(), (194 * (1 - t) + 170 * t).toInt(), (142 * (1 - t) + 190 * t).toInt(), n) } // shallows
            else -> col(24, 88, 140, n, 0.9 + 0.2 * sin(wx * 0.9 + wy * 0.6))          // deep sea
        }
    }

    override val tex: Array<IntArray> by lazy {
        fun rock(v: Int) = IntArray(4096) { j -> val x = j % 64; val y = j / 64; var f = 0.7 + 0.5 * h(x, y, 620 + v)
            if (y % 16 == 0) f *= 0.7; if (h(x / 4, y / 6, 625) < 0.10) f *= 0.6
            pack(((70 + 10 * v) * f).toInt(), ((60 + 8 * v) * f).toInt(), ((54 + 8 * v) * f).toInt()) }
        arrayOf(rock(0), rock(1),
            IntArray(4096) { j -> val x = j % 64; val y = j / 64; var f = 0.6 + 0.8 * h(x, y, 630); if (h(x / 5, y / 5, 631) < 0.3) f *= 1.3; pack((40 * f).toInt(), (120 * f).toInt(), (50 * f).toInt()) },
            IntArray(4096) { j -> val x = j % 64; val y = j / 64; val f = 0.7 + 0.6 * h(x / 2, y / 8, 632); pack((110 * f).toInt(), (78 * f).toInt(), (46 * f).toInt()) })
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
