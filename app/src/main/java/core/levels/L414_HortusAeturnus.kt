package com.backrooms.game
// L414_HortusAeturnus.kt - Level 414: Hortus Aeturnus. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Level 414: Hortus Aeturnus. Survival difficulty Class 0, safe. Entrance: glowing vines in some levels lead to an arch covered in vines and flowers (Garden).
 *  Exit: wish to leave (stand still) and a path of flagstones opens, flowers along both sides; the kind of flower says which level it leads to.
 *  An endless garden under an eternal spring sky: forests, valleys of hydrangeas and bougainvillea, small towns, glasshouses, a castle on the far crag.
 *  Its people, the Ivlania, look like plants walking as people. They are intelligent, friendly, and live in communities. */
object Level414HortusAeturnus : LevelSpec {
    override val def = LevelDef(LV_HORTUS, "LEVEL 414", "HORTUS AETURNUS", "A garden that does not end. It is always spring here, and its people are kind.", "IVLANIA", E_IVLANIA, 9, 8, 1.1, 0.0, 0.0002,
        intArrayOf(255, 252, 238), 0xF2A6C4, safe = true, danger = 0)
    override val noclip = intArrayOf()                      // no unstable walls: you leave by wishing to
    override val flicker = doubleArrayOf(0.0, 0.0, 1.0)     // no lamps: the sun
    override val world: OpenWorld? = Hortus
    override val spawn = doubleArrayOf(0.5, 0.5)
    override fun wall(x: Int, y: Int) = Hortus.wall(x, y)
    override fun textures(t: TexSet) = t.paint { x, y, i ->
        val v = (60 + 150 * h(x, y, 4180)).toInt()          // only the grain of this floor texture is used (see Hortus.ground)
        floor[i] = pack(v, v, v); wall[i] = pack(46, 110, 52); ceil[i] = pack(150, 200, 235); panel[i] = pack(255, 255, 255)
    }
}

/** The way out: a path of flagstones with flowering bushes along it. The cells are the shortest walk (the garden is full of hedges and trees) and the flowers say where it ends. */
class ExitPath(val cells: List<IntArray>, val exit: Garden.Exit, val index: Int) {
    private val ox = cells.minOf { it[0] } - 1; private val oy = cells.minOf { it[1] } - 1
    private val w = cells.maxOf { it[0] } - ox + 2; private val ht = cells.maxOf { it[1] } - oy + 2
    private val grid = ByteArray(w * ht).also { g -> for (c in cells) g[(c[1] - oy) * w + (c[0] - ox)] = 1 }
    val end = cells.last()
    fun has(ix: Int, iy: Int): Boolean { val gx = ix - ox; val gy = iy - oy; return gx in 0 until w && gy in 0 until ht && grid[gy * w + gx].toInt() != 0 }
    fun reached(x: Double, y: Double) = hypot(end[0] + 0.5 - x, end[1] + 0.5 - y) < 1.0
    /** Flowers on both sides of the path, and an arch at its end. */
    fun sprites(x: Double, y: Double, out: MutableList<DoubleArray>) {
        for ((i, c) in cells.withIndex()) {
            if (abs(c[0] + 0.5 - x) > 15 || abs(c[1] + 0.5 - y) > 15) continue
            if (i == cells.size - 1) { out.add(doubleArrayOf(c[0] + 0.5, c[1] + 0.5, S_GATE.toDouble())); continue }
            val n = cells[i + 1]; val dx = n[0] - c[0]; val dy = n[1] - c[1]
            if (i % 2 == 0) for (side in intArrayOf(-1, 1)) {  // a flowering bush on each side of every other step
                val fx = c[0] + 0.5 - dy * side * 1.0 + (h(c[0], c[1], 4200 + side) - 0.5) * 0.2; val fy = c[1] + 0.5 + dx * side * 1.0 + (h(c[0], c[1], 4202 + side) - 0.5) * 0.2
                if (!isWall(LV_HORTUS, floor(fx).toInt(), floor(fy).toInt())) out.add(doubleArrayOf(fx, fy, (S_FLOWER + index).toDouble()))
            }
        }
    }
}

object Hortus : OpenWorld {
    // ---- landmarks (far enough from the arrival glade at the origin) ----
    private const val DX = 150; private const val DY = 110; private const val DR = 15.5   // the glass dome with the waterfall
    private const val CAX = 0; private const val CAY = -62                                 // Everspring Atrium, the castle in the centre of the land (52 x 36), its gate towards the glade
    private const val TX = 90; private const val TY = -170                                // the iron-ribbed glasshouse (45 x 9) with the glowing cube
    const val CUBE_X = TX + 0.5; const val CUBE_Y = TY + 0.5

    private const val K_OPEN = 0; private const val K_TREE = 1; private const val K_HEDGE = 2; private const val K_PINK = 3; private const val K_HYDRANGEA = 4
    private const val K_HOUSE = 5; private const val K_GLASS = 6; private const val K_CASTLE = 7; private const val K_ROCK = 8; private const val K_FALL = 9
    private const val K_DOME = 10; private const val K_PALM = 11

    /** The way out, once you have wished for it (or null). */
    @Volatile var path: ExitPath? = null
    /** Sprite codes IVLANIA..IVLANIA + 7: an Ivlania with one of eight flowers for a head. */
    const val IVLANIA = 50

    private fun forestN(x: Int, y: Int) = valueNoise(x * 0.018, y * 0.018, 4101)
    private fun valleyN(x: Int, y: Int) = valueNoise(x * 0.011 + 40, y * 0.011, 4102)
    private fun gardenN(x: Int, y: Int) = valueNoise(x * 0.025, y * 0.025 - 30, 4103)

    // ---- towns: a street grid of 13-cell blocks in a 52 x 52 square, in about one stretch of 96 in five ----
    private const val TOWN = 96
    private fun townRect(sx: Int, sy: Int): IntArray? {
        if ((sx == 0 && sy == 0) || h(sx, sy, 4104) >= 0.22) return null
        val cx = sx * TOWN + 26 + (h(sx, sy, 4105) * 44).toInt(); val cy = sy * TOWN + 26 + (h(sx, sy, 4106) * 44).toInt()
        return intArrayOf(cx - 26, cy - 26, cx + 26, cy + 26)
    }
    private fun inTown(x: Int, y: Int): IntArray? { val r = townRect(Math.floorDiv(x, TOWN), Math.floorDiv(y, TOWN)) ?: return null; return if (x in r[0]..r[2] && y in r[1]..r[3]) r else null }
    private fun townBlock(x: Int, y: Int, r: IntArray): Int { // 0 street, 1 house, 2 square or park
        val lx = x - r[0]; val ly = y - r[1]
        if (lx % 13 < 3 || ly % 13 < 3) return 0
        val bx = lx / 13; val by = ly / 13
        return if ((bx == 2 && by == 2) || h(bx + r[0], by + r[1], 4107) < 0.12) 2 else 1
    }

    // ---- glasshouses: one in about three of the 48-cell lattice cells, 15 x 9 ----
    private fun greenRect(x: Int, y: Int): IntArray? {
        val gx = Math.floorDiv(x, 48); val gy = Math.floorDiv(y, 48)
        if (h(gx, gy, 4108) >= 0.30) return null
        val cx = gx * 48 + 12 + (h(gx, gy, 4109) * 24).toInt(); val cy = gy * 48 + 12 + (h(gx, gy, 4110) * 24).toInt()
        if (cx * cx + cy * cy < 400 || inTown(cx, cy) != null) return null
        return if (x in cx - 7..cx + 7 && y in cy - 4..cy + 4) intArrayOf(cx - 7, cy - 4, cx + 7, cy + 4) else null
    }

    /** The three landmarks, as kinds; null outside them. The ground around each is kept clear of trees. */
    private fun landmark(x: Int, y: Int): Int? {
        val dd = hypot(x - DX + 0.5, y - DY + 0.5)
        if (dd < DR + 4) {
            if (dd > DR + 0.2) return K_OPEN
            if (dd > DR - 1.6) return if (abs(y - DY) <= 1 && abs(x - DX) > DR - 3.5) K_OPEN else K_DOME   // glass wall with a door east and west
            if ((x == DX || x == DX + 1) && (y == DY || y == DY + 1)) return K_FALL                         // the waterfall
            if (dd in 8.0..11.5 && abs(y - DY) > 2) { val sec = (atan2(y - DY + 0.5, x - DX + 0.5) + PI) / (2 * PI) * 10; if (sec - floor(sec) in 0.2..0.8) return K_HOUSE } // the town inside the dome
            return if (h(x, y, 4111) < 0.07 && dd > 6.5 && abs(y - DY) > 1) K_PALM else K_OPEN
        }
        val lx = x - CAX; val ly = y - CAY
        if (abs(lx) < 31 && abs(ly) < 23) {
            if (abs(lx) >= 26 || abs(ly) >= 18) return K_OPEN
            if (ly >= 15 && abs(lx) <= 2) return K_OPEN                                                     // the gate
            return if (castleHeight(lx, ly) > 0) K_CASTLE else K_OPEN
        }
        val tx = x - TX; val ty = y - TY
        if (abs(tx) <= 24 && abs(ty) <= 7) {
            if (abs(tx) > 22 || abs(ty) > 4) return K_OPEN
            if (abs(ty) == 4) return K_GLASS
            if (abs(ty) == 3 && abs(tx) % 4 != 0) return K_HYDRANGEA                                       // planters along the walkway
            return K_OPEN
        }
        return null
    }
    private fun castleHeight(lx: Int, ly: Int): Int = when {
        abs(lx) >= 21 && abs(ly) >= 13 -> 12                       // corner towers
        abs(lx) >= 24 || abs(ly) >= 16 -> 6                        // curtain wall
        abs(lx) < 8 && abs(ly) < 6 -> if (abs(lx) >= 6 && abs(ly) >= 4) 14 else 10   // the keep and its turrets
        else -> 0
    }

    private fun kindAt(x: Int, y: Int): Int {
        if (x * x + y * y < 121) return K_OPEN                    // the glade you arrive in
        landmark(x, y)?.let { return it }
        if (abs(x) <= 5 && y in -39..-12) return K_OPEN                                          // the avenue from the glade to the castle gate
        greenRect(x, y)?.let { r ->
            val lx = x - r[0]; val ly = y - r[1]
            if (lx == 0 || lx == 14 || ly == 0 || ly == 8) return if (ly == 4 && (lx == 0 || lx == 14)) K_OPEN else K_GLASS
            if ((ly == 2 || ly == 6) && lx in 3..11 && lx % 4 != 3) return K_PINK                         // flowering planters
            return if (h(x, y, 4112) < 0.09 && ly != 4 && lx in 2..12) K_PALM else K_OPEN
        }
        inTown(x, y)?.let { r -> return if (townBlock(x, y, r) == 1) K_HOUSE else K_OPEN }
        val f = forestN(x, y)
        if (f > 0.60) return if (h(x, y, 4120) < 0.07 + 0.28 * (f - 0.6) / 0.4) K_TREE else K_OPEN
        if (valleyN(x, y) > 0.58) return if (h(x, y, 4121) < 0.004) K_TREE else if (h(x, y, 4122) < 0.016) K_HYDRANGEA else K_OPEN
        if (gardenN(x, y) > 0.62) { // hedge rooms, 7 cells across, with gaps
            val lx = Math.floorMod(x, 7); val ly = Math.floorMod(y, 7); val bx = Math.floorDiv(x, 7); val by = Math.floorDiv(y, 7)
            if ((lx == 0 && h(bx, y, 4125) > 0.25) || (ly == 0 && h(x, by, 4126) > 0.25)) return if (h(bx, by, 4127) < 0.35) K_PINK else K_HEDGE
            return K_OPEN
        }
        if (h(x, y, 4123) < 0.005) return K_TREE
        if (h(x, y, 4124) < 0.003) return K_ROCK
        return K_OPEN
    }
    fun wall(x: Int, y: Int) = kindAt(x, y) != K_OPEN

    override fun height(x: Int, y: Int): Int = when (kindAt(x, y)) {
        K_TREE -> 4 + (h(x, y, 4130) * 3).toInt(); K_PALM -> 5; K_HOUSE -> 2 + (h(x / 13, y / 13, 4131) * 3).toInt()
        K_GLASS -> 2; K_CASTLE -> max(6, castleHeight(x - CAX, y - CAY)); K_ROCK -> 1 + (h(x, y, 4132) * 2).toInt(); K_FALL, K_DOME -> 8
        else -> 1 }
    override fun style(x: Int, y: Int) = kindAt(x, y)
    override fun texIndex(style: Int, unit: Int, ht: Int) = when (style) {
        K_TREE, K_PALM -> if (unit <= 1) 1 else 0                           // a crown of leaves on a trunk
        K_HEDGE -> 2; K_PINK -> 3; K_HYDRANGEA -> 4
        K_HOUSE -> if (unit == 0) 7 else if (unit == ht - 1) 6 else 5        // slate roof, windows, a door at the foot
        K_GLASS -> 8
        K_CASTLE -> if (unit == 0) 7 else if (unit % 2 == 1 && unit < ht - 1) 10 else 9
        K_ROCK -> 11; K_FALL -> 12; else -> 13 }

    override val haze = pack(212, 232, 238)
    override val hazeK = 0.0004

    // ---- the ground ----
    private const val Z_MEADOW = 0; private const val Z_FOREST = 1; private const val Z_VALLEY = 2; private const val Z_GARDEN = 3; private const val Z_STREET = 4
    private const val Z_GREEN = 5; private const val Z_DOME = 6; private const val Z_POOL = 7; private const val Z_CASTLE = 8; private const val Z_ROCKY = 9; private const val Z_TUNNEL = 10

    private fun computeZone(ix: Int, iy: Int): Int {
        val dd = hypot(ix - DX + 0.5, iy - DY + 0.5)
        if (dd < DR) return if (dd < 5.0) Z_POOL else Z_DOME
        val lx = ix - CAX; val ly = iy - CAY
        if (abs(lx) < 26 && abs(ly) < 18) return Z_CASTLE
        if (abs(lx) < 40 && abs(ly) < 30) return Z_ROCKY
        if (abs(ix - TX) <= 22 && abs(iy - TY) <= 4) return Z_TUNNEL
        if (abs(ix) <= 1 && iy in -39..-12) return Z_STREET                                    // the flagstones of the avenue
        greenRect(ix, iy)?.let { return Z_GREEN or ((if (h(ix shr 4, iy shr 4, 4140) < 0.5) 1 else 0) shl 8) } // 1: a floor of petals
        inTown(ix, iy)?.let { r -> return if (townBlock(ix, iy, r) == 0) Z_STREET else Z_GARDEN }
        if (forestN(ix, iy) > 0.60) return Z_FOREST
        val pal = (valueNoise(ix * 0.02, iy * 0.02, 4141) * 4).toInt().coerceIn(0, 3) shl 8
        if (valleyN(ix, iy) > 0.58) return Z_VALLEY or pal
        if (gardenN(ix, iy) > 0.62) return Z_GARDEN or pal
        return Z_MEADOW
    }
    private val zoneCache = LongArray(8192)   // a floor pixel asks about its cell: remember the answers (races only waste a recomputation)
    private fun zoneAt(ix: Int, iy: Int): Int {
        val key = ix * 73856093 xor iy * 19349663 xor worldSeed
        val slot = (key xor (key ushr 13)) and 8191
        val e = zoneCache[slot]
        if (e != 0L && (e ushr 32).toInt() == key) return e.toInt()
        val z = computeZone(ix, iy)
        zoneCache[slot] = (key.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)
        return z
    }

    private fun col(r: Int, g: Int, b: Int, n: Double, f: Double = 1.0) = pack((r * n * f).toInt().coerceIn(0, 255), (g * n * f).toInt().coerceIn(0, 255), (b * n * f).toInt().coerceIn(0, 255))
    private val PALETTES = arrayOf(
        arrayOf(intArrayOf(110, 130, 235), intArrayOf(150, 110, 215), intArrayOf(190, 130, 225), intArrayOf(90, 160, 240)),    // hydrangeas
        arrayOf(intArrayOf(226, 60, 110), intArrayOf(240, 100, 150), intArrayOf(250, 160, 190), intArrayOf(200, 40, 80)),      // bougainvillea
        arrayOf(intArrayOf(250, 222, 70), intArrayOf(255, 255, 240), intArrayOf(246, 170, 60), intArrayOf(250, 240, 160)),     // sunny
        arrayOf(intArrayOf(160, 112, 214), intArrayOf(200, 170, 240), intArrayOf(255, 255, 255), intArrayOf(120, 90, 200)))    // lavender
    private fun grass(wx: Double, wy: Double, n: Double, r: Int = 64, g: Int = 148, b: Int = 58) = col(r, g, b, n, 0.78 + 0.4 * h((wx * 2).toInt(), (wy * 2).toInt(), 4162))
    private fun flowers(wx: Double, wy: Double, n: Double, pal: Int, density: Double, gr: Int = 64, gg: Int = 148, gb: Int = 58): Int {
        val fx = floor(wx * 14).toInt(); val fy = floor(wy * 14).toInt()
        if (h(fx, fy, 4160) < density) {
            val cx = wx * 14 - fx - 0.5; val cy = wy * 14 - fy - 0.5
            if (cx * cx + cy * cy < 0.16) { val c = PALETTES[pal][(h(fx, fy, 4161) * 4).toInt().coerceIn(0, 3)]; return col(c[0], c[1], c[2], n, 0.9 + 0.2 * (1 - 2 * hypot(cx, cy))) }
        }
        return grass(wx, wy, n, gr, gg, gb)
    }
    /** Heads of tiny florets about 0.2 across with greenery between, like hydrangeas and bougainvillea. */
    private fun clusters(wx: Double, wy: Double, n: Double, pal: Int, density: Double, gr: Int = 64, gg: Int = 148, gb: Int = 58): Int {
        val hx = floor(wx * 4).toInt(); val hy = floor(wy * 4).toInt()
        if (h(hx, hy, 4165) < density) {
            val cx = wx * 4 - hx - 0.5 - (h(hx, hy, 4166) - 0.5) * 0.3; val cy = wy * 4 - hy - 0.5 - (h(hx, hy, 4167) - 0.5) * 0.3
            if (cx * cx + cy * cy < 0.17) { val c = PALETTES[pal][(h(hx, hy, 4161) * 4).toInt().coerceIn(0, 3)]
                return col(c[0], c[1], c[2], n, (0.7 + 0.5 * h(floor(wx * 30).toInt(), floor(wy * 30).toInt(), 4168)) * (1.0 - 0.5 * hypot(cx, cy))) }
        }
        return grass(wx, wy, n, gr, gg, gb)
    }
    private fun slabs(wx: Double, wy: Double, n: Double, size: Double, r: Int, g: Int, b: Int): Int {
        val sx = floor(wx / size).toInt(); val sy = floor(wy / size).toInt(); val fx = wx / size - sx; val fy = wy / size - sy
        if (fx < 0.06 || fy < 0.06) return col(r - 50, g - 48, b - 44, n, 0.8)
        return col(r, g, b, n, 0.85 + 0.3 * h(sx, sy, 4163))
    }

    override fun ground(wx: Double, wy: Double, ix: Int, iy: Int, base: Int): Int {
        val n = 0.85 + 0.3 * ((base and 255) / 255.0)
        val p = path
        if (p != null && p.has(ix, iy)) return slabs(wx, wy, n, 0.55, 196, 190, 176)             // the way out: flagstones
        val z = zoneAt(ix, iy); val pal = z shr 8
        return when (z and 255) {
            Z_STREET -> slabs(wx, wy, n, 0.25, 150, 138, 120)
            Z_CASTLE -> slabs(wx, wy, n, 0.5, 164, 160, 152)
            Z_ROCKY -> col(112, 112, 98, n, 0.85 + 0.3 * h((wx * 3).toInt(), (wy * 3).toInt(), 4150))
            Z_TUNNEL -> slabs(wx, wy, n, 0.6, 150, 150, 142)
            Z_GREEN -> if (pal == 1) clusters(wx, wy, n, 1, 0.6, 90, 70, 50) else slabs(wx, wy, n, 0.5, 120, 92, 70)
            Z_DOME -> slabs(wx, wy, n, 0.7, 200, 214, 206)
            Z_POOL -> col(70, 150, 200, n, 0.85 + 0.2 * sin(wx * 4 + wy * 3))
            Z_VALLEY -> clusters(wx, wy, n, pal, 0.72)
            Z_GARDEN -> clusters(wx, wy, n, pal, 0.22)
            Z_FOREST -> col(50, 98, 48, n, 0.7 + 0.5 * h((wx * 3).toInt(), (wy * 3).toInt(), 4164))
            else -> flowers(wx, wy, n, 2, 0.025)
        }
    }

    override fun extraSprites(x: Double, y: Double, out: MutableList<DoubleArray>) {
        if (abs(x - 0.5) < 20 && abs(y - 4.5) < 20) out.add(doubleArrayOf(0.5, 4.5, S_GATE.toDouble()))     // the archway you came through
        if (hypot(x - CUBE_X, y - CUBE_Y) < 30) out.add(doubleArrayOf(CUBE_X, CUBE_Y, S_CUBE.toDouble()))
        val cx = floor(x).toInt(); val cy = floor(y).toInt()
        for (iy in cy - 14..cy + 14) for (ix in cx - 14..cx + 14) { // the Ivlania at home: in the streets, and in the glasshouses
            val p = when (zoneAt(ix, iy) and 255) { Z_STREET -> 0.012; Z_GREEN -> 0.006; Z_DOME -> 0.025; Z_VALLEY -> 0.004; else -> 0.0 }
            if (p > 0 && h(ix, iy, 4170) < p) out.add(doubleArrayOf(ix + 0.2 + 0.6 * h(ix, iy, 4171), iy + 0.2 + 0.6 * h(ix, iy, 4172), (IVLANIA + (h(ix, iy, 4174) * 8).toInt().coerceIn(0, 7)).toDouble()))
        }
        path?.sprites(x, y, out)
    }

    /** Wishing to leave: a path of flagstones, about 30 steps, leaves in the direction you face, round the trees and hedges. null if you are boxed in. */
    fun openPath(x: Double, y: Double, facing: Double): ExitPath? {
        val sx = floor(x).toInt(); val sy = floor(y).toInt()
        val fl = Garden.Flood(LV_HORTUS, sx, sy, 48)
        var best: IntArray? = null; var bestScore = -1e9
        for (iy in 0 until fl.n) for (ix in 0 until fl.n) {
            val d = fl.dist[iy * fl.n + ix]
            if (d < 8) continue
            val gx = sx - 48 + ix; val gy = sy - 48 + iy
            val ang = atan2(gy + 0.5 - y, gx + 0.5 - x)
            val score = cos(ang - facing) * 10 + min(d, 30) * 0.5 - max(0, d - 34) * 2   // towards where you look, a decent walk, not a huge detour
            if (score > bestScore) { bestScore = score; best = intArrayOf(gx, gy) }
        }
        val b = best ?: return null
        val cells = fl.pathTo(b[0], b[1]) ?: return null
        val index = (Math.random() * Garden.EXITS.size).toInt().coerceIn(0, Garden.EXITS.size - 1)
        return ExitPath(cells, Garden.EXITS[index], index).also { path = it }
    }

    // ---- wall textures (64 x 64) ----
    private fun tex(f: (Int, Int) -> Int) = IntArray(4096) { f(it % 64, it / 64) }
    private fun rgb(r: Double, g: Double, b: Double, f: Double = 1.0) = pack((r * f).toInt().coerceIn(0, 255), (g * f).toInt().coerceIn(0, 255), (b * f).toInt().coerceIn(0, 255))
    private fun blossoms(s: Int, base: (Int, Int) -> Int, p: Array<IntArray>, dens: Double) = tex { x, y -> if (h(x / 2, y / 2, s) < dens) { val c = p[(h(x / 2, y / 2, s + 1) * p.size).toInt().coerceIn(0, p.size - 1)]; rgb(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), 0.85 + 0.3 * h(x, y, s + 2)) } else base(x, y) }
    private fun leaves(s: Int) = { x: Int, y: Int -> val f = 0.55 + 0.9 * h(x, y, s); if (h(x, y, s + 1) > 0.86) rgb(110.0, 178.0, 74.0, f) else rgb(36.0, 104.0, 46.0, f) }
    private fun ashlar(s: Int, w: Boolean) = tex { x, y -> val row = y / 13; val xo = (x + (row and 1) * 16) % 32
        if (y % 13 == 0 || xo == 0) rgb(166.0, 156.0, 136.0) else { val f = 0.88 + 0.2 * h(xo / 32 + row * 7, row, s) * 0.5 + 0.12 * h(x, y, s + 1)
            if (w && x in 28..35 && y in 16..46 && (y > 22 || abs(x - 31.5) < 3.5 + (y - 16) * 0.5)) rgb(42.0, 42.0, 56.0) else rgb(226.0, 216.0, 192.0, f) } }

    override val tex: Array<IntArray> by lazy { arrayOf(
        tex { x, y -> val f = 0.55 + 0.6 * h(x / 2, y / 16, 4300) * (if (x % 9 == 0) 0.6 else 1.0); rgb(96.0, 68.0, 46.0, f) },                                          // 0 bark
        tex { x, y -> val f = 0.5 + 0.9 * h(x / 3, y / 3, 4301); if (h(x, y, 4302) < 0.02) rgb(255.0, 205.0, 225.0) else if (h(x, y, 4303) > 0.88) rgb(120.0, 190.0, 80.0, f) else rgb(44.0, 118.0, 52.0, f) }, // 1 foliage
        tex(leaves(4304)),                                                                                                                                              // 2 hedge
        blossoms(4305, leaves(4306), arrayOf(intArrayOf(226, 60, 110), intArrayOf(240, 100, 150), intArrayOf(250, 160, 190)), 0.28),                                      // 3 bougainvillea
        blossoms(4307, leaves(4308), PALETTES[0], 0.62),                                                                                                                 // 4 hydrangea
        tex { x, y -> if (x in 18..44 && y in 12..44) { if (x in 20..42 && y in 14..42) (if (x == 31 || y == 28) rgb(96.0, 70.0, 50.0) else rgb(118.0, 150.0, 178.0, 0.9 + 0.2 * h(x, y, 4309))) else rgb(96.0, 70.0, 50.0) }
            else if ((x in 8..16 || x in 46..54) && y in 12..44) rgb(86.0, 124.0, 92.0) else rgb(232.0, 218.0, 186.0, 0.92 + 0.16 * h(x, y, 4310)) },                      // 5 plaster, windows
        tex { x, y -> if (x in 22..42 && y >= 22 && (y > 30 || abs(x - 32) < 6 + (y - 22))) rgb(112.0, 78.0, 48.0, 0.9 + 0.2 * h(x, y / 8, 4311)) else if (x in 20..44 && y >= 20 && y <= 63) rgb(150.0, 140.0, 120.0)
            else rgb(232.0, 218.0, 186.0, 0.92 + 0.16 * h(x, y, 4312)) },                                                                                                // 6 plaster, door
        tex { x, y -> val row = y / 8; val xo = (x + (row and 1) * 6) % 12; if (y % 8 == 0 || xo == 0) rgb(40.0, 44.0, 60.0) else rgb(70.0, 78.0, 98.0, 0.85 + 0.3 * h(x / 12 + row * 5, row, 4313)) }, // 7 slate roof
        tex { x, y -> if (x % 16 < 2 || y % 16 < 2) rgb(228.0, 234.0, 228.0) else rgb(172.0, 216.0, 202.0, 0.88 + 0.24 * h(x / 16, y / 16, 4314)) },                    // 8 glasshouse
        ashlar(4315, false), ashlar(4316, true),                                                                                                                         // 9, 10 castle
        tex { x, y -> val m = h(x / 6, y / 6, 4317); if (m > 0.62) rgb(84.0, 130.0, 64.0, 0.8 + 0.4 * h(x, y, 4318)) else rgb(124.0, 120.0, 110.0, 0.75 + 0.5 * h(x, y, 4319)) }, // 11 mossy rock
        tex { x, y -> rgb(196.0, 228.0, 242.0, 0.75 + 0.5 * h(x / 2, y / 20, 4320)) },                                                                                    // 12 waterfall
        tex { x, y -> if (x % 16 < 2 || y % 32 < 2 || (x + y) % 16 < 2) rgb(236.0, 240.0, 236.0) else rgb(150.0, 206.0, 228.0, 0.88 + 0.24 * h(x / 8, y / 8, 4321)) }) }  // 13 glass dome

    /** 2048 x 256 spring sky: azimuth around, 0 at the horizon up to the zenith. Clouds, the sun, far mountains and a forest line. */
    override val sky: IntArray by lazy {
        val w = 2048; val hh = 256; val s = IntArray(w * hh)
        fun lerp(a: Int, b: Int, t: Double) = (a + (b - a) * t).toInt()
        for (v in 0 until hh) { val e = v / 255.0; val k = e.pow(0.55)
            for (u in 0 until w) {
                var r = lerp(200, 62, k); var g = lerp(230, 128, k); var b = lerp(246, 224, k)
                val cn = 0.6 * valueNoise(u / 146.29, v / 26.0, 4190, 14) + 0.4 * valueNoise(u / 60.235, v / 12.0, 4191, 34)
                val ca = ((cn - 0.52) / 0.26).coerceIn(0.0, 1.0) * (0.95 - 0.45 * e)
                if (ca > 0) { val sh = 255 - 30 * (1 - ca); r = lerp(r, sh.toInt(), ca); g = lerp(g, sh.toInt(), ca); b = lerp(b, (sh + 2).toInt().coerceAtMost(255), ca) }
                val dd = hypot((u - 256) / 5.69, (v - 99) / 2.84)   // the sun, 45 degrees round and 35 up
                if (dd < 2.6) { r = 255; g = 252; b = 236 } else { val gl = 0.55 * exp(-dd / 9.0); r = min(255, r + (60 * gl).toInt()); g = min(255, g + (52 * gl).toInt()); b = min(255, b + (30 * gl).toInt()) }
                s[v * w + u] = pack(r, g, b) } }
        for (u in 0 until w) { // far mountains with snow, then the forest line
            val range = valueNoise(u / 146.29, 0.7, 4192, 14); val peak = valueNoise(u / 36.57, 0.5, 4194, 56)
            val mh = (6 + 24 * range + 18 * peak * range).toInt()   // the range rises in jagged peaks
            for (v in 0 until mh) { val t = v / mh.toDouble(); val rock = 0.9 + 0.2 * h(u / 3, v / 2, 4195)
                s[v * w + u] = if (t > 0.8 && peak > 0.5 && mh > 24) pack(236, 240, 250) else pack((lerp(112, 150, t) * rock).toInt(), (lerp(136, 170, t) * rock).toInt(), (lerp(174, 200, t) * rock).toInt()) }
            val fh = (3 + 8 * valueNoise(u / 40.96, 1.3, 4193, 50)).toInt()
            for (v in 0 until fh) s[v * w + u] = pack(lerp(52, 76, v / fh.toDouble()), lerp(104, 138, v / fh.toDouble()), lerp(64, 84, v / fh.toDouble())) }
        s
    }
}
