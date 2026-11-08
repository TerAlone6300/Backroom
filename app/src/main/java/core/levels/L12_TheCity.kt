package com.backrooms.game
// L12_TheCity.kt - Level 12: The City That Never Sleeps. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Internal id 12 = Backrooms Level 11, The City That Never Sleeps. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level12TheCity : LevelSpec {
    override val def = LevelDef(12, "LEVEL 11", "THE CITY THAT NEVER SLEEPS", "Endless streets, lit windows. Hostile things keep their distance.", "SMILERS · HOUNDS · FACELINGS", 4, 8, 8, 0.27, 0.15, 0.0012, intArrayOf(240, 238, 256), 0xFFC857,
        calm = 0.45, cast = intArrayOf(4, 1, 4, 2, 1, 4, 1, 2), safe = true, lamp0 = 0.5) // 3 Facelings, 3 Hounds, 2 Smilers (scaled by difficulty)
    override val noclip = intArrayOf(1, 4, 6, 10)
    override val flicker = doubleArrayOf(0.02, 0.05, 0.06)
    private val SPEC = TexSpec(intArrayOf(104, 100, 114), intArrayOf(58, 58, 64), intArrayOf(16, 20, 40), intArrayOf(255, 236, 170), 0, 0)

    override fun wall(x: Int, y: Int): Boolean = City.wall(x, y)
    override fun keepsMaze(x: Int, y: Int) = true // the city has its own long streets

    override fun textures(t: TexSet) = t.fromSpec(12, SPEC)
}

// ---------- Level 11: the city ----------
/** An open night-time city, no ceiling. 16x16 blocks, 6-wide streets (sidewalk, four lanes, sidewalk) with open crossings.
 *  Lots hold towers of 2..10 floors (a third solid, the rest hollow shells with a door), parks with trees, squares with a monument.
 *  Above: a star sky with a moon, clouds lit from below and a far skyline that goes on for ever. */
object City {
    private fun vn(x: Double, y: Double, s: Int, wrap: Int): Double { // smooth value noise, tiles every `wrap` cells along x
        val xi = floor(x).toInt(); val yi = floor(y).toInt(); val fx = x - xi; val fy = y - yi
        val x0 = Math.floorMod(xi, wrap); val x1 = Math.floorMod(xi + 1, wrap)
        val a = h(x0, yi, s); val b = h(x1, yi, s); val c = h(x0, yi + 1, s); val d = h(x1, yi + 1, s)
        val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
        return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
    }

    fun wall(x: Int, y: Int): Boolean {
        val lx = x and 15; val ly = y and 15
        if (lx < 6 || ly < 6) return false                 // streets and crossings
        val bx = x shr 4; val by = y shr 4; val k = h(bx, by, 150)
        val px = lx - 6; val py = ly - 6                   // 0..9 inside the lot
        if (k < 0.07) return h(x, y, 157) < 0.045 && px in 1..8 && py in 1..8 // park: scattered trees
        if (k < 0.14) return px == 4 && py == 4            // square: a monument in the middle
        val sx = 6 + (h(bx, by, 151) * 5).toInt(); val sy = 6 + (h(bx, by, 152) * 5).toInt() // footprint 6..10
        val ix = px - ((10 - sx) * h(bx, by, 153)).toInt(); val iy = py - ((10 - sy) * h(bx, by, 154)).toInt()
        if (ix < 0 || iy < 0 || ix >= sx || iy >= sy) return false
        if (k < 0.50) return true                          // about a third of the buildings are solid: they cannot be entered
        if (ix != 0 && iy != 0 && ix != sx - 1 && iy != sy - 1) return ix == sx / 2 && iy == sy / 2 && k > 0.8 // hollow, open to the sky
        val side = (h(bx, by, 155) * 4).toInt(); val dp = 1 + (h(bx, by, 156) * ((if (side < 2) sy else sx) - 3)).toInt()
        return when (side) { // 2-wide door on one side
            0 -> !(ix == 0 && iy in dp..dp + 1); 1 -> !(ix == sx - 1 && iy in dp..dp + 1)
            2 -> !(iy == 0 && ix in dp..dp + 1); else -> !(iy == sy - 1 && ix in dp..dp + 1)
        }
    }

    /** Floors (wall units) of the wall cell: trees 2, monuments 3, buildings 2..3 low-rise, 4..6 mid, 7..10 towers. */
    fun height(x: Int, y: Int): Int {
        val bx = x shr 4; val by = y shr 4; val k = h(bx, by, 150)
        if (k < 0.07) return 2
        if (k < 0.14) return 3
        val r = h(bx, by, 160); val q = h(bx, by, 162)
        return if (r < 0.35) 2 + (q * 2).toInt() else if (r < 0.75) 4 + (q * 3).toInt() else 7 + (q * 4).toInt()
    }

    /** 0 apartment brick, 1 glass office tower, 2 stone, 3 tree, 4 monument. */
    fun kind(x: Int, y: Int): Int {
        val bx = x shr 4; val by = y shr 4; val k = h(bx, by, 150)
        if (k < 0.07) return 3
        if (k < 0.14) return 4
        return if (height(x, y) >= 7) 1 else if (h(bx, by, 161) < 0.5) 0 else 2
    }

    /** Per-block facts, cached: footprint of the hollow building [x0, y0, x1, y1] (or null), whether it is stocked, whether it is dark. */
    private class BlockInfo(val bx: Int, val by: Int, val foot: IntArray?, val stocked: Boolean, val dark: Boolean)
    private val blocks = java.util.concurrent.atomic.AtomicReferenceArray<BlockInfo?>(512)
    private fun info(bx: Int, by: Int): BlockInfo {
        val i = ((bx * 73856093) xor (by * 19349663)) and 511
        val c = blocks.get(i); if (c != null && c.bx == bx && c.by == by) return c
        var f: IntArray? = null
        if (h(bx, by, 150) >= 0.50) {
            val sx = 6 + (h(bx, by, 151) * 5).toInt(); val sy = 6 + (h(bx, by, 152) * 5).toInt()
            val x0 = bx * 16 + 6 + ((10 - sx) * h(bx, by, 153)).toInt(); val y0 = by * 16 + 6 + ((10 - sy) * h(bx, by, 154)).toInt()
            f = intArrayOf(x0, y0, x0 + sx - 1, y0 + sy - 1)
        }
        val st = f != null && h(bx, by, 190) < 0.35
        val n = BlockInfo(bx, by, f, st, f != null && !st && h(bx, by, 195) < 0.6) // dark: hollow, not stocked, about 60% of those
        blocks.set(i, n); return n
    }
    private fun foot(bx: Int, by: Int): IntArray? = info(bx, by).foot
    /** The footprint of the hollow building whose open floor contains cell (x, y), else null. */
    fun hollowAt(x: Int, y: Int): IntArray? {
        val f = foot(x shr 4, y shr 4) ?: return null
        return if (x in f[0] + 1..f[2] - 1 && y in f[1] + 1..f[3] - 1 && !wall(x, y)) f else null
    }
    /** Some hollow buildings are stocked: supplies all over the floor. This is where Hounds lead you. */
    fun stocked(bx: Int, by: Int) = info(bx, by).stocked
    /** Dark buildings: no lamps inside, the Smilers' homes. */
    fun dark(bx: Int, by: Int) = info(bx, by).dark
    /** True for an open floor cell inside a dark building (what the renderer switches the lights off for). */
    fun darkCell(x: Int, y: Int): Boolean {
        if ((x and 15) < 6 || (y and 15) < 6) return false
        val i = info(x shr 4, y shr 4); val f = i.foot ?: return false
        return i.dark && x in f[0] + 1..f[2] - 1 && y in f[1] + 1..f[3] - 1
    }
    fun cacheCell(x: Int, y: Int) = hollowAt(x, y) != null && stocked(x shr 4, y shr 4)
    /** An open floor cell inside the nearest stocked building within 3 blocks of (wx, wy), or null. */
    fun stockedNear(wx: Double, wy: Double): IntArray? {
        val b0x = floor(wx).toInt() shr 4; val b0y = floor(wy).toInt() shr 4
        var best: IntArray? = null; var bd = 1e9
        for (by in b0y - 3..b0y + 3) for (bx in b0x - 3..b0x + 3) {
            if (!stocked(bx, by)) continue
            val f = foot(bx, by)!!; val cx = (f[0] + f[2]) / 2; val cy = (f[1] + f[3]) / 2
            var cell: IntArray? = null
            for (dy in -2..2) for (dx in -2..2) if (cell == null && hollowAt(cx + dx, cy + dy) != null) cell = intArrayOf(cx + dx, cy + dy)
            if (cell != null) { val d = hypot(cell[0] + 0.5 - wx, cell[1] + 0.5 - wy); if (d < bd) { bd = d; best = cell } }
        }
        return best
    }

    /** A street lamp stands on lattice cell (ix, iy) if that is open ground in the street grid. */
    fun lampOk(ix: Int, iy: Int) = ((ix and 15) < 6 || (iy and 15) < 6) && !isWall(12, ix, iy)

    /** Ground colour at (wx, wy): asphalt with lane lines and zebra crossings, sidewalks, park grass, plaza tiles, lot pavement. `base` = asphalt grain. */
    fun ground(wx: Double, wy: Double, ix: Int, iy: Int, base: Int): Int {
        val lx = ix and 15; val ly = iy and 15; val fx = wx - ix; val fy = wy - iy
        val n = 0.85 + 0.3 * ((base and 255) / 255.0)
        val inV = lx < 6; val inH = ly < 6
        if (!inV && !inH) {
            val k = h(ix shr 4, iy shr 4, 150)
            if (k < 0.07) { val v = 0.85 + 0.3 * h(ix, iy, 171); return pack((54 * n * v).toInt(), (98 * n * v).toInt(), (48 * n * v).toInt()) } // park grass
            val t = if (fx < 0.04 || fy < 0.04) 0.8 else 1.0
            return if (k < 0.14) pack((132 * n * t).toInt(), (126 * n * t).toInt(), (118 * n * t).toInt())   // square
                   else pack((92 * n).toInt(), (90 * n).toInt(), (88 * n).toInt())                           // lot pavement
        }
        var side = false; var white = false; var yellow = false
        if (inV && inH) { // crossing: corner sidewalks, zebra crossings on the four arms
            if ((lx == 0 || lx == 5) && (ly == 0 || ly == 5)) side = true
            else if (lx in 1..4 && (ly == 0 || ly == 5)) white = ((wx * 3).toInt() and 1) == 0
            else if (ly in 1..4 && (lx == 0 || lx == 5)) white = ((wy * 3).toInt() and 1) == 0
        } else if (inV) {
            if (lx == 0 || lx == 5) side = true
            else if (abs(wx - (ix - lx) - 3.0) < 0.07 && ((wy * 0.5).toInt() and 1) == 0) yellow = true
        } else {
            if (ly == 0 || ly == 5) side = true
            else if (abs(wy - (iy - ly) - 3.0) < 0.07 && ((wx * 0.5).toInt() and 1) == 0) yellow = true
        }
        return if (side) { val t = if (fx < 0.04 || fy < 0.04) 0.8 else 1.0; pack((150 * n * t).toInt(), (148 * n * t).toInt(), (142 * n * t).toInt()) }
        else if (white) pack(222, 222, 216) else if (yellow) pack(212, 180, 62)
        else pack((62 * n).toInt(), (62 * n).toInt(), (68 * n).toInt())
    }

    // --- facade textures: 0..11 = 3 kinds x 4 variants (lit windows are marked with alpha 0xFE so they glow), 12 canopy, 13 trunk, 14..17 shopfronts ---
    private fun mk(r: Int, g: Int, b: Int, lit: Boolean = false) = if (lit) (pack(r, g, b) and 0xFFFFFF) or (0xFE shl 24) else pack(r, g, b)
    private fun nz(x: Int, y: Int, sd: Int, amp: Double) = 1.0 + amp * (h(x, y, sd) - 0.5)

    private fun facade(kind: Int, v: Int): IntArray {
        val t = IntArray(4096); val sd = 400 + kind * 10 + v
        for (y in 0 until 64) for (x in 0 until 64) t[y * 64 + x] = when (kind) {
            0 -> { // apartment: brick, a window and a sill per 16x16 cell, about half lit
                val wx = x % 16; val wy = y % 16
                if (wx in 4..11 && wy in 3..12) {
                    if (wx == 4 || wx == 11 || wy == 3 || wy == 12) mk(60, 40, 34)
                    else if (h(x / 16, y / 16, sd) < 0.5) { val f = 1.0 - 0.25 * (wy - 4) / 8.0; mk((255 * f).toInt(), (206 * f).toInt(), (120 * f).toInt(), true) }
                    else mk(26, 34, 52)
                } else if (wy == 13) mk(168, 150, 132)
                else {
                    val joint = (y % 4 == 3) || (if ((y / 4) % 2 == 0) x % 8 == 0 else (x + 4) % 8 == 0)
                    val f = nz(x, y, sd + 1, 0.25)
                    if (joint) mk((96 * f).toInt(), (72 * f).toInt(), (64 * f).toInt()) else mk((136 * f).toInt(), (80 * f).toInt(), (64 * f).toInt())
                }
            }
            1 -> { // glass tower: dark curtain wall, 8x8 panes, about a third lit (warm or cool)
                val cx = x % 8; val cy = y % 8
                if (cx == 0 || cy == 0) mk(70, 82, 104)
                else if (h(0, y / 8, sd + 7) < 0.65 && h(x / 8, y / 8, sd) < 0.55) { if (h(x / 8, y / 8, sd + 3) < 0.4) mk(255, 224, 160, true) else mk(205, 228, 255, true) }
                else { val sh = 1.0 - 0.3 * ((x + y) % 24) / 24.0; mk((30 * sh + 10).toInt(), (48 * sh + 14).toInt(), (84 * sh + 20).toInt()) }
            }
            2 -> { // stone: pale limestone, tall narrow windows, cornice lines
                val wx = x % 16; val wy = y % 16
                if (wx in 5..10 && wy in 2..12) {
                    if (wx == 5 || wx == 10 || wy == 2 || wy == 12) mk(100, 92, 80) else if (h(x / 16, y / 16, sd) < 0.45) mk(255, 214, 130, true) else mk(32, 38, 56)
                } else if (wy == 0) mk(120, 114, 102)
                else { val f = nz(x, y, sd + 1, 0.2); mk((184 * f).toInt(), (176 * f).toInt(), (160 * f).toInt()) }
            }
            else -> mk(0, 0, 0)
        }
        return t
    }

    val tex: Array<IntArray> by lazy {
        Array(19) { i ->
            when {
                i < 12 -> facade(i / 4, i % 4)
                i == 18 -> IntArray(4096) { j -> val x = j % 64; val y = j / 64; val f = 0.9 + 0.2 * h(x, y, 413); if (y % 16 == 0) mk((120 * f).toInt(), (116 * f).toInt(), (108 * f).toInt()) else mk((196 * f).toInt(), (192 * f).toInt(), (182 * f).toInt()) } // monument: plain carved stone
                i == 12 -> IntArray(4096) { j -> val x = j % 64; val y = j / 64; var f = 0.6 + 0.8 * h(x, y, 410); if (h(x / 3, y / 3, 411) < 0.3) f *= 1.3; mk((34 * f).toInt(), (92 * f).toInt(), (40 * f).toInt()) }
                i == 13 -> IntArray(4096) { j -> val x = j % 64; val y = j / 64; val f = 0.7 + 0.6 * h(x, y / 8, 412); mk((84 * f).toInt(), (58 * f).toInt(), (36 * f).toInt()) }
                else -> { // shopfronts: lit display windows and a door under a neon sign
                    val sign = arrayOf(intArrayOf(255, 120, 90), intArrayOf(120, 220, 255), intArrayOf(255, 140, 200), intArrayOf(150, 255, 160))[i - 14]
                    val glass = arrayOf(intArrayOf(255, 224, 168), intArrayOf(255, 200, 120), intArrayOf(214, 236, 255), intArrayOf(255, 232, 190))[i - 14]
                    IntArray(4096) { j -> val x = j % 64; val y = j / 64
                        if (y < 5) mk(sign[0], sign[1], sign[2], true)
                        else if (y < 8) mk(40, 38, 46)
                        else if (x in 27..36 && y > 14) mk(30, 28, 36)               // door
                        else if (x in 4..59 && y in 10..56) { if (x == 26 || x == 37) mk(52, 50, 58) else mk(glass[0], glass[1], glass[2], true) }
                        else mk(46, 44, 52) }
                }
            }
        }
    }

    /** 2048 x 256 panorama: azimuth around, elevation 0..90 degrees upward. */
    val sky: IntArray by lazy {
        val w = 2048; val hh = 256; val s = IntArray(w * hh)
        for (v in 0 until hh) {
            val e = v / 255.0; val glow = max(0.0, 1.0 - e / 0.30).let { it * it } // city light pollution near the horizon
            val r0 = 10 + 6 * e + 90 * glow; val g0 = 12 + 12 * e + 50 * glow; val b0 = 34 + 30 * e + 56 * glow
            for (u in 0 until w) {
                var r = r0; var g = g0; var b = b0
                val cl = ((vn(u / 128.0, v / 14.0, 251, 16) - 0.52) / 0.48).coerceIn(0.0, 1.0) * (0.55 - 0.45 * e).coerceAtLeast(0.0) // clouds, lit from below
                if (cl > 0) { r += (96 - r) * cl * 0.6; g += (66 - g) * cl * 0.6; b += (98 - b) * cl * 0.6 }
                if (cl < 0.25 && e > 0.05 && h(u, v, 240) < 0.0016 + 0.0018 * e) { val sb = 140 + 115 * h(u, v, 241); r = sb; g = sb; b = sb * 0.95 + 10 }
                s[v * w + u] = pack(r.toInt(), g.toInt(), b.toInt())
            }
        }
        val uc = 340; val vc = 118 // the moon (5.69 px per degree across, 2.84 up)
        for (v in vc - 60..vc + 60) for (u in uc - 100..uc + 100) {
            val dd = hypot((u - uc) / 5.69, (v - vc) / 2.84); val o = v * w + u; val c = s[o]
            if (dd < 3.4) { val f = (if (vn(u / 6.0, v / 6.0, 261, 1000) < 0.35) 0.82 else 1.0) * (0.95 + 0.05 * h(u, v, 262)); s[o] = pack((238 * f).toInt(), (238 * f).toInt(), (224 * f).toInt()) }
            else if (dd < 16) { val gl = exp(-(dd - 3.4) / 3.5) * 0.5; s[o] = pack(((c shr 16) and 255) + (70 * gl).toInt(), ((c shr 8) and 255) + (80 * gl).toInt(), (c and 255) + (110 * gl).toInt()) }
        }
        fun layer(seed: Int, hMin: Int, hMax: Int, wMin: Int, wMax: Int, base: Int, win: Int, winP: Double) { // skyline silhouettes with lit windows
            var u = 0
            while (u < w) {
                val bw = wMin + (h(u, 3, seed) * (wMax - wMin)).toInt()
                var ht = hMin + (h(u, 5, seed).let { it * it } * (hMax - hMin)).toInt()
                val tower = h(u, 7, seed) < 0.06
                if (tower) ht += 12 + (h(u, 9, seed) * 14).toInt() // the odd tower
                for (uu in u until min(w, u + bw)) for (v in 0 until min(ht, hh)) {
                    s[v * w + uu] = if (uu % 3 == 1 && v % 4 == 2 && v > 1 && uu > u && uu < u + bw - 1 && h(uu, v, seed + 1) < winP) win else base
                }
                if (tower && ht + 1 < hh) s[ht * w + u + bw / 2] = pack(255, 60, 50) // aircraft warning light
                u += bw + (h(u, 11, seed) * 3).toInt()
            }
        }
        layer(300, 4, 15, 8, 26, pack(46, 36, 64), pack(190, 150, 110), 0.30)  // far, hazy
        layer(320, 3, 22, 14, 40, pack(14, 14, 26), pack(255, 214, 130), 0.28) // near, dark
        s
    }

    fun warm() { tex; sky } // build the textures and the sky now (called when the level starts)
}
