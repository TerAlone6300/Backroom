package com.backrooms.game
// Unnumbered_TheGrave.kt - Unnumbered Level: The Grave. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

const val LV_GRAVE = -1 // unnumbered: the only way in is death in a Hardcore run

/** Unnumbered Level: The Grave. Survival difficulty Class 0, safe, secure, devoid of entities. Entrance: death (Hardcore). Exit: none.
 *  An earthen landscape of hills, mountains and rivers under a night sky lit by a geometric array of moons, covered in monuments to the dead:
 *  fields of headstones, a city-sized complex of mortuary shelves, a colossal sword in a flooded crater, and, far off on the horizon, a machine
 *  like hundreds of cathedrals on tank treads and a statue of an eight-armed being cradling a singularity. */
object LevelUnnumberedGrave : LevelSpec {
    override val def = LevelDef(LV_GRAVE, "UNNUMBERED", "THE GRAVE", "No one who arrives here has ever been seen to leave.", "NONE", 0, 0, 8, 0.85, 0.0, 0.0006,
        intArrayOf(228, 236, 255), 0xB8C4E8, safe = true, unlisted = true, danger = 0)
    override val noclip = intArrayOf()                      // no exit
    override val flicker = doubleArrayOf(0.0, 0.0, 1.0)     // no lamps: moonlight only
    override val world: OpenWorld? = Graveyard
    override val spawn = doubleArrayOf(0.5, 0.5)
    override fun wall(x: Int, y: Int) = Graveyard.wall(x, y)
    override fun textures(t: TexSet) = t.fromSpec(-1, TexSpec(intArrayOf(120, 120, 120), intArrayOf(128, 128, 128), intArrayOf(128, 128, 128), intArrayOf(255, 255, 255), 0, 0))
}

object Graveyard : OpenWorld {
    // landmarks (far enough from the arrival point, which is at the origin)
    private const val CX = 170; private const val CY = -100; private const val CR = 38.0   // the flooded crater with the sword at its centre
    private const val OX = -150; private const val OY = 70                                  // centre of the mortuary complex (100 x 100)

    private fun crater(x: Int, y: Int) = hypot(x - CX + 0.5, y - CY + 0.5) < CR
    private fun river(x: Int, y: Int) = abs(y - (60 * sin(x * 0.013) + 20 * sin(x * 0.041))) < 3.0
    private fun complex(x: Int, y: Int) = abs(x - OX) < 50 && abs(y - OY) < 50
    private fun cemetery(x: Int, y: Int) = h(Math.floorDiv(x, 40), Math.floorDiv(y, 40), 700) < 0.55
    private fun hill(x: Int, y: Int) = valueNoise(x * 0.05, y * 0.05, 720)

    /** 0 open, 1 headstone, 2 cross, 3 mortuary building, 4 mound / mountain, 5 boulder, 6 the sword. */
    private fun kindAt(x: Int, y: Int): Int {
        if (x == CX && y == CY) return 6
        if (crater(x, y)) return 0
        if (complex(x, y)) { val lx = Math.floorMod(x - OX + 50, 13); val ly = Math.floorMod(y - OY + 50, 13)
            return if (lx < 3 || ly < 3 || h((x - OX + 50) / 13, (y - OY + 50) / 13, 710) < 0.08) 0 else 3 }
        if (river(x, y)) return 0
        if (hypot(x + 0.5, y + 0.5) > 12 && hill(x, y) > 0.74) return 4
        if (cemetery(x, y) && Math.floorMod(x, 3) == 1 && Math.floorMod(y, 4) == 1 && h(x, y, 730) < 0.85) return if (h(x, y, 731) < 0.25) 2 else 1
        if (h(x, y, 740) < 0.004 && hypot(x + 0.5, y + 0.5) > 6) return 5
        return 0
    }
    fun wall(x: Int, y: Int) = kindAt(x, y) != 0

    override fun height(x: Int, y: Int): Int = when (kindAt(x, y)) {
        6 -> 18; 3 -> 4 + (h((x - OX + 50) / 13, (y - OY + 50) / 13, 711) * 3).toInt(); 4 -> (2 + (hill(x, y) - 0.74) / 0.26 * 9).toInt().coerceIn(2, 11)
        2 -> 2; 5 -> 2; else -> 1 }
    override fun style(x: Int, y: Int) = when (kindAt(x, y)) { 1 -> 0; 2 -> 1; 3 -> 2; 4 -> 3; 5 -> 4; else -> 5 }
    override fun texIndex(style: Int, unit: Int, ht: Int) = when (style) { 3 -> if (unit == 0) 3 else 4; 4 -> 5; 5 -> 6; else -> style }

    override val haze = pack(26, 30, 56)
    override val hazeK = 0.0004
    private fun col(r: Int, g: Int, b: Int, n: Double, f: Double = 1.0) = pack((r * n * f).toInt(), (g * n * f).toInt(), (b * n * f).toInt())
    override fun ground(wx: Double, wy: Double, ix: Int, iy: Int, base: Int): Int {
        val n = 0.85 + 0.3 * ((base and 255) / 255.0)
        if (hypot(wx - CX, wy - CY) < CR) return col(46, 70, 110, n, 0.85 + 0.15 * sin(hypot(wx - CX, wy - CY) * 2.2)) // flooded crater
        if (complex(ix, iy)) { val lx = Math.floorMod(ix - OX + 50, 13); val ly = Math.floorMod(iy - OY + 50, 13); val j = if (wx - ix < 0.04 || wy - iy < 0.04) 0.8 else 1.0
            return col(92, 92, 98, n, j) }
        if (river(ix, iy)) return col(38, 56, 92, n, 0.85 + 0.2 * sin(wx * 0.9 + wy * 0.5))
        val p = 0.8 + 0.4 * valueNoise(wx * 0.15, wy * 0.15, 750)
        if (cemetery(ix, iy) && Math.floorMod(ix, 3) != 1 && Math.floorMod(iy, 4) == 2) return col(96, 92, 84, n, 0.9)   // worn paths between the rows
        return col(46, 66, 52, n, p)
    }

    override val tex: Array<IntArray> by lazy {
        fun stone(seed: Int, r: Int, g: Int, b: Int, lines: Boolean) = IntArray(4096) { j -> val x = j % 64; val y = j / 64; var f = 0.8 + 0.4 * h(x, y, seed)
            if (lines && (y % 12 == 0 || x % 21 == 0)) f *= 0.65; pack((r * f).toInt(), (g * f).toInt(), (b * f).toInt()) }
        arrayOf(
            stone(760, 150, 150, 156, false).also { t -> for (y in 14..22) for (x in 22..42) t[y * 64 + x] = pack(90, 90, 96) },   // headstone with a carved name
            stone(761, 160, 160, 164, false).also { t -> for (y in 0 until 64) for (x in 28..35) t[y * 64 + x] = pack(120, 120, 126); for (y in 18..26) for (x in 12..51) t[y * 64 + x] = pack(120, 120, 126) }, // cross
            IntArray(4096) { j -> val x = j % 64; val y = j / 64; val slot = x % 16 in 3..12 && y % 16 in 3..12   // mortuary shelves: stone niches
                if (slot) pack(24, 22, 24) else { val f = 0.8 + 0.4 * h(x, y, 762); pack((130 * f).toInt(), (130 * f).toInt(), (136 * f).toInt()) } },
            IntArray(4096) { j -> val x = j % 64; val y = j / 64; val f = 0.6 + 0.8 * h(x, y, 763); pack((44 * f).toInt(), (84 * f).toInt(), (48 * f).toInt()) },    // grass
            IntArray(4096) { j -> val x = j % 64; val y = j / 64; val f = 0.6 + 0.8 * h(x / 2, y / 3, 764); pack((90 * f).toInt(), (70 * f).toInt(), (54 * f).toInt()) }, // earth
            stone(765, 110, 106, 104, true),                                                                                                                        // mountain rock
            IntArray(4096) { j -> val x = j % 64; val y = j / 64; val f = 0.85 + 0.3 * h(x, y / 8, 766); if (x in 29..34) pack(246, 248, 255) else pack((170 * f).toInt(), (176 * f).toInt(), (190 * f).toInt()) }) // the sword: a long steel blade
    }

    /** 2048 x 256 night sky: stars, an array of moons, far mountains, and on the horizon the cathedral machine and the eight-armed statue. */
    override val sky: IntArray by lazy {
        val w = 2048; val hh = 256; val s = IntArray(w * hh)
        for (v in 0 until hh) { val e = v / 255.0; val r0 = 20 + 14 * e; val g0 = 24 + 16 * e; val b0 = 52 + 26 * e
            for (u in 0 until w) {
                var r = r0; var g = g0; var b = b0
                if (h(u, v, 770) < 0.0018 + 0.0020 * e) { val sb = 120 + 135 * h(u, v, 771); r = sb; g = sb; b = sb }
                s[v * w + u] = pack(r.toInt(), g.toInt(), b.toInt()) } }
        // the moons: a hexagonal array, each a slightly different near-white
        val tints = arrayOf(intArrayOf(250, 250, 255), intArrayOf(255, 244, 226), intArrayOf(226, 240, 255), intArrayOf(255, 232, 238), intArrayOf(232, 255, 240), intArrayOf(246, 236, 255), intArrayOf(255, 250, 220))
        for (m in 0..6) { val a = (m - 1) * PI / 3; val du = if (m == 0) 0.0 else 7 * cos(a) * 5.69; val dv = if (m == 0) 0.0 else 7 * sin(a) * 2.84
            val uc = 1100 + du.toInt(); val vc = 79 + dv.toInt(); val t = tints[m]  // low enough to see when you look up
            for (v in vc - 40..vc + 40) for (u in uc - 70..uc + 70) { val dd = hypot((u - uc) / 5.69, (v - vc) / 2.84); val o = v * w + u; val c = s[o]
                if (dd < 2.2) s[o] = pack(t[0], t[1], t[2])
                else if (dd < 8) { val gl = exp(-(dd - 2.2) / 2.4) * 0.45; s[o] = pack(((c shr 16) and 255) + (t[0] * gl).toInt(), ((c shr 8) and 255) + (t[1] * gl).toInt(), (c and 255) + (t[2] * gl).toInt()) } } }
        // far mountains
        for (u in 0 until w) { val ht = (6 + 38 * valueNoise(u / 110.0, 0.5, 780, 19)).toInt(); for (v in 0 until ht) s[v * w + u] = pack(12, 14, 26) }
        // the machine: cathedrals on tank treads, drifting across the plain (azimuth 250 degrees)
        val mu = 1422
        for (k in 0 until 7) { val bx = mu - 90 + k * 26; val bh = 22 + (h(k, 0, 790) * 26).toInt()
            for (u in bx until bx + 22) for (v in 5 until bh) { val tip = bh - v < 10 && abs(u - bx - 11) > (bh - v) / 2 + 1; if (!tip) s[v * w + u] = pack(4, 4, 8) }
            for (u in bx + 3 until bx + 20 step 4) for (v in 9 until bh - 8 step 5) if (h(u, v, 791) < 0.4) s[v * w + u] = pack(255, 214, 130) }
        for (u in mu - 96 until mu + 96) for (v in 0 until 6) s[v * w + u] = pack(6, 6, 10)
        // the eight-armed statue holding a singularity (azimuth 20 degrees)
        val su = 114
        for (v in 0 until 72) { val half = if (v < 44) 9 else if (v < 62) 7 else 5; for (u in su - half..su + half) s[v * w + u] = pack(4, 4, 8) }
        for (a in 0 until 8) { val ang = (-70 + a * 20) * PI / 180; for (t in 0 until 34) { val u = su + (sin(ang) * t).toInt(); val v = 52 + (cos(ang) * t * 0.5).toInt(); if (v in 0 until hh) for (k in -1..1) s[v * w + (u + k)] = pack(4, 4, 8) } }
        for (v in 54 until 70) for (u in su - 10..su + 10) { val dd = hypot((u - su).toDouble(), (v - 61).toDouble()); if (dd < 6.5) s[v * w + u] = if (dd < 3.8) pack(0, 0, 0) else pack(255, 248, 225) } // the singularity
        s
    }
}
