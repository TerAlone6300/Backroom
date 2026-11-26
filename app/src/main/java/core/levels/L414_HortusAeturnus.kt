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
    private const val SX = 0.7071; private const val SY = 0.7071   // world direction towards the sun (azimuth 45 degrees, see [sun])
    private fun fbm(x: Double, y: Double, s: Int) = 0.55 * valueNoise(x, y, s) + 0.30 * valueNoise(x * 2.1 + 7.3, y * 2.1 + 3.1, s + 1) + 0.15 * valueNoise(x * 4.3 + 1.7, y * 4.3 + 9.2, s + 2)
    private fun mulc(c: Int, f: Double) = pack((((c shr 16) and 255) * f).toInt(), (((c shr 8) and 255) * f).toInt(), ((c and 255) * f).toInt())

    /** Grass: big soft patches of fresher and drier green, fine blades that run along y, and blade tips that catch the sun. `far` skips the sub-pixel detail. */
    private fun grass(wx: Double, wy: Double, n: Double, r: Int = 56, g: Int = 126, b: Int = 48, far: Boolean = false): Int {
        val macro = valueNoise(wx * 0.14, wy * 0.14, 4170); val meso = valueNoise(wx * 1.1, wy * 1.1, 4171)
        val blade = if (far) 0.5 else h((wx * 64).toInt(), (wy * 14).toInt(), 4172); val fine = if (far) 0.5 else h((wx * 110).toInt(), (wy * 110).toInt(), 4173)
        val f = 0.66 + 0.24 * macro + 0.15 * meso + 0.15 * blade + 0.10 * fine
        val dry = (1.0 - macro) * 0.5; val tip = if (blade > 0.9) 0.16 else 0.0
        return col((r + 40 * dry + 50 * tip).toInt(), (g + 12 * dry + 44 * tip).toInt(), (b - 12 * dry + 6 * tip).toInt(), n, f)
    }
    /** Flower beds: low-frequency noise gathers the flowers into drifts, with clear meadow between. */
    private fun bed(wx: Double, wy: Double, seed: Int) = ((valueNoise(wx * 0.33, wy * 0.33, seed) - 0.3) / 0.4).coerceIn(0.0, 1.0)
    /** Single flowers with five petals, a yellow eye and a soft shadow on the side away from the sun. */
    private fun flowers(wx: Double, wy: Double, n: Double, pal: Int, density: Double, far: Boolean = false): Int {
        val k = 12.0; val fx = floor(wx * k).toInt(); val fy = floor(wy * k).toInt()
        if (h(fx, fy, 4160) >= density * (0.35 + 1.3 * bed(wx, wy, 4179))) return grass(wx, wy, n, far = far)
        val cx = wx * k - fx - 0.5 - (h(fx, fy, 4174) - 0.5) * 0.3; val cy = wy * k - fy - 0.5 - (h(fx, fy, 4175) - 0.5) * 0.3
        val rr = hypot(cx, cy); val pr = 0.30 + 0.11 * cos(5 * atan2(cy, cx) + h(fx, fy, 4176) * 6.28)
        if (rr < pr) {
            if (rr < 0.085) return col(252, 200, 56, n, 0.9 + 0.2 * h(fx, fy, 4177))
            val c = PALETTES[pal][(h(fx, fy, 4161) * 4).toInt().coerceIn(0, 3)]
            return col(c[0], c[1], c[2], n, 0.80 + 0.18 * (rr / pr) + 0.14 * ((cx * SX + cy * SY) / max(rr, 0.001)))
        }
        val bg = grass(wx, wy, n, far = far)
        return if (rr < pr + 0.13 && cx * SX + cy * SY < 0) mulc(bg, 0.74) else bg
    }
    /** Heads of tiny florets (hydrangea, bougainvillea): irregular domes in drifts, lit from the sun's side, with a leafy fringe and soft gaps between the florets. */
    private fun clusters(wx: Double, wy: Double, n: Double, pal: Int, density: Double, gr: Int = 56, gg: Int = 126, gb: Int = 48, far: Boolean = false): Int {
        val hx = floor(wx * 4).toInt(); val hy = floor(wy * 4).toInt()
        if (h(hx, hy, 4165) >= density * (0.5 + 1.0 * bed(wx, wy, 4177)).coerceAtMost(1.0)) return grass(wx, wy, n, gr, gg, gb, far)
        val size = 0.3 + 0.2 * h(hx, hy, 4169)
        val cx = (wx * 4 - hx - 0.5 - (h(hx, hy, 4166) - 0.5) * 0.3) / size; val cy = (wy * 4 - hy - 0.5 - (h(hx, hy, 4167) - 0.5) * 0.3) / size
        val ang = atan2(cy, cx); val r2 = (cx * cx + cy * cy) * (1.0 + 0.3 * sin(3 * ang + h(hx, hy, 4171) * 6.28) + 0.15 * sin(5 * ang + h(hx, hy, 4172) * 6.28))
        if (r2 >= 1.45) return grass(wx, wy, n, gr, gg, gb, far)
        if (r2 >= 1.0) return col(30, 80, 34, n, 0.8 + 0.4 * h((wx * 40).toInt(), (wy * 40).toInt(), 4173))     // a fringe of leaves round the head
        val dome = sqrt(1.0 - r2); val light = (0.55 + 0.5 * (cx * SX * 0.5 + cy * SY * 0.5 + dome * 0.7)).coerceIn(0.3, 1.25)
        val base = PALETTES[pal][(h(hx, hy, 4161) * 4).toInt().coerceIn(0, 3)]
        if (far) return col(base[0], base[1], base[2], n, light * (0.82 + 0.3 * h((wx * 9).toInt(), (wy * 9).toInt(), 4169)))
        val fx = floor(wx * 24).toInt(); val fy = floor(wy * 24).toInt(); val lx = wx * 24 - fx - 0.5; val ly = wy * 24 - fy - 0.5
        val fr = hypot(lx, ly)
        val alt = PALETTES[pal][(((h(hx, hy, 4161) * 4).toInt() + (if (h(fx, fy, 4178) > 0.7) 1 else 0)) % 4).coerceIn(0, 3)]
        val f = light * (0.72 + 0.42 * h(fx, fy, 4168))
        if (fr > 0.5) return col(alt[0], alt[1], alt[2], n, f * 0.5)                         // the shaded gap between two florets
        if (fr < 0.07) return col(236, 226, 170, n, light)
        return col(alt[0], alt[1], alt[2], n, f * (1.12 - 0.4 * fr))
    }
    /** Paving: moss in the joints, bevelled edges (lit on the sun's side), grain, hairline cracks and a different tone for every stone. */
    private fun slabs(wx: Double, wy: Double, n: Double, size: Double, r: Int, g: Int, b: Int, far: Boolean = false): Int {
        val sx = floor(wx / size).toInt(); val sy = floor(wy / size).toInt(); val fx = wx / size - sx; val fy = wy / size - sy
        val mw = 0.018 / size; val bw = 0.05 / size
        if (fx < mw || fy < mw) { val moss = if (far) 0.4 else fbm(wx * 9, wy * 9, 4182); return if (moss > 0.5) col(58, 92, 40, n, 0.7 + 0.5 * moss) else col(r / 3, g / 3, b / 3, n, 0.8 + 0.4 * (if (far) 0.5 else h((wx * 80).toInt(), (wy * 80).toInt(), 4183))) }
        var f = 0.86 + 0.26 * h(sx, sy, 4163)
        if (!far) f += 0.10 * (fbm(wx * 14, wy * 14, 4184) - 0.5) + 0.07 * (h((wx * 90).toInt(), (wy * 90).toInt(), 4185) - 0.5)
        if (fx > 1 - bw) f += 0.20 else if (fx < mw + bw) f -= 0.16
        if (fy > 1 - bw) f += 0.20 else if (fy < mw + bw) f -= 0.16
        if (!far) {
            val crack = abs(fbm(wx * 5 + sx * 3.1, wy * 5 + sy * 2.3, 4186) - 0.5); if (crack < 0.012) f -= 0.3
            val lichen = fbm(wx * 4, wy * 4, 4187); if (lichen > 0.66) return col((r * 0.8).toInt(), (g * 0.95).toInt(), (b * 0.7).toInt(), n, f * 0.9)
        }
        return col(r, g, b, n, f)
    }
    private fun water(wx: Double, wy: Double, n: Double): Int {
        val rip = sin(wx * 5.2 + sin(wy * 3.1) * 1.3) + 0.8 * sin(wy * 6.3 + wx * 1.7) + 0.4 * sin((wx + wy) * 9.1)
        val spec = max(0.0, rip * 0.3 + 0.55).pow(14.0)
        val deep = 0.5 + 0.5 * valueNoise(wx * 0.6, wy * 0.6, 4188)
        return col((34 + 40 * deep + 190 * spec).toInt(), (104 + 56 * deep + 180 * spec).toInt(), (142 + 50 * deep + 150 * spec).toInt(), n, 0.86 + 0.1 * rip * 0.3)
    }

    override fun ground(wx: Double, wy: Double, ix: Int, iy: Int, base: Int): Int = groundAt(wx, wy, ix, iy, base, false)
    override fun groundFar(wx: Double, wy: Double, ix: Int, iy: Int, base: Int): Int = groundAt(wx, wy, ix, iy, base, true)
    private fun groundAt(wx: Double, wy: Double, ix: Int, iy: Int, base: Int, far: Boolean): Int {
        val n = 0.85 + 0.3 * ((base and 255) / 255.0)
        val p = path
        if (p != null && p.has(ix, iy)) return slabs(wx, wy, n, 0.55, 196, 190, 176, far)         // the way out: flagstones
        val z = zoneAt(ix, iy); val pal = z shr 8
        return when (z and 255) {
            Z_STREET -> slabs(wx, wy, n, 0.25, 150, 138, 120, far)
            Z_CASTLE -> slabs(wx, wy, n, 0.5, 164, 160, 152, far)
            Z_ROCKY -> { val f = if (far) 0.9 else 0.72 + 0.5 * fbm(wx * 6, wy * 6, 4150) + 0.15 * h((wx * 40).toInt(), (wy * 40).toInt(), 4151); if (!far && abs(fbm(wx * 2.5, wy * 2.5, 4152) - 0.5) < 0.02) col(60, 58, 52, n, 1.0) else col(118, 116, 102, n, f) }
            Z_TUNNEL -> slabs(wx, wy, n, 0.6, 150, 150, 142, far)
            Z_GREEN -> if (pal == 1) clusters(wx, wy, n, 1, 0.6, 90, 70, 50, far) else slabs(wx, wy, n, 0.5, 120, 92, 70, far)
            Z_DOME -> slabs(wx, wy, n, 0.7, 200, 214, 206, far)
            Z_POOL -> water(wx, wy, n)
            Z_VALLEY -> clusters(wx, wy, n, pal, 0.8, far = far)
            Z_GARDEN -> clusters(wx, wy, n, pal, 0.5, far = far)
            Z_FOREST -> { val dap = valueNoise(wx * 1.6, wy * 1.6, 4180); val lit = if (dap > 0.6) 0.55 + 1.4 * (dap - 0.6) else 0.0   // dappled sun through the canopy
                col((46 + 70 * lit).toInt(), (92 + 80 * lit).toInt(), (44 + 30 * lit).toInt(), n, 0.62 + 0.55 * (if (far) 0.5 else fbm(wx * 5, wy * 5, 4164)) + 0.2 * (if (far) 0.5 else h((wx * 30).toInt(), (wy * 30).toInt(), 4189))) }
            else -> flowers(wx, wy, n, (h(ix shr 3, iy shr 3, 4190) * 4).toInt().coerceIn(0, 3), 0.045, far)
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
    /** Flowers on a hedge: every 8x8 cell may hold one round bloom (a lit centre, shaded rim); the rest is leaf. Tiles seamlessly. */
    private fun blossoms(s: Int, base: (Int, Int) -> Int, p: Array<IntArray>, dens: Double) = tex { x, y ->
        var best = 99.0; var bid = 0; var bdx = 0.0; var bdy = 0.0
        val cx0 = x / 8; val cy0 = y / 8
        for (j in -1..1) for (i in -1..1) {
            val ci = Math.floorMod(cx0 + i, 8); val cj = Math.floorMod(cy0 + j, 8)
            if (h(ci, cj, s) >= dens) continue
            val lx = (cx0 + i) * 8 + 2.5 + 3.0 * h(ci, cj, s + 1); val ly = (cy0 + j) * 8 + 2.5 + 3.0 * h(ci, cj, s + 2)
            val dx = x + 0.5 - lx; val dy = y + 0.5 - ly; val dd = hypot(dx, dy)
            if (dd < best) { best = dd; bid = cj * 8 + ci; bdx = dx; bdy = dy }
        }
        val rad = 2.9
        if (best < rad) { val c = p[(h(bid % 8, bid / 8, s + 3) * p.size).toInt().coerceIn(0, p.size - 1)]; val t = best / rad
            val f = (1.12 - 0.42 * t - 0.2 * ((bdx * 0.6 + bdy * 0.8) / rad)).coerceIn(0.45, 1.35)
            if (best < 0.8) rgb(c[0] * 0.6 + 100.0, c[1] * 0.6 + 100.0, c[2] * 0.4 + 40.0, 1.0) else rgb(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), f) }
        else base(x, y)
    }
    /** Leaves: every 8x8 cell holds one leaf; leaves facing the sun (up-left) are bright, the gaps between them dark. Tiles seamlessly. */
    private fun leaves(s: Int) = { x: Int, y: Int ->
        var best = 99.0; var bid = 0; var bdx = 0.0; var bdy = 0.0
        val cx0 = x / 8; val cy0 = y / 8
        for (j in -1..1) for (i in -1..1) {
            val ci = Math.floorMod(cx0 + i, 8); val cj = Math.floorMod(cy0 + j, 8)
            val lx = (cx0 + i) * 8 + 2.0 + 4.0 * h(ci, cj, s); val ly = (cy0 + j) * 8 + 2.0 + 4.0 * h(ci, cj, s + 1)
            val dx = x + 0.5 - lx; val dy = y + 0.5 - ly; val dd = hypot(dx * 0.82, dy * 1.12)
            if (dd < best) { best = dd; bid = cj * 8 + ci; bdx = dx; bdy = dy }
        }
        val id = h(bid % 8, bid / 8, s + 2)
        if (best > 3.4) rgb(12.0, 40.0, 18.0, 0.8 + 0.5 * h(x, y, s + 3))
        else { val t = best / 3.4; val lightSide = (-(bdx * 0.6 + bdy * 0.8)) / 3.4
            val f = (1.05 - 0.45 * t + 0.34 * lightSide + 0.2 * (h(x, y, s + 4) - 0.5)).coerceIn(0.35, 1.5)
            if (abs(bdx * 0.8 - bdy * 0.6) < 0.4 && t < 0.85) rgb(60.0, 120.0, 52.0, f)                     // midrib
            else if (id > 0.82) rgb(112.0, 182.0, 70.0, f) else if (id > 0.4) rgb(52.0, 128.0, 54.0, f) else rgb(34.0, 100.0, 46.0, f) }
    }
    /** Cheap bump mapping: the height of each texel is its brightness; texels whose upper-left neighbours are higher get lit, the others shaded. */
    private fun emboss(t: IntArray, k: Double): IntArray {
        fun lum(x: Int, y: Int): Double { val c = t[(y and 63) * 64 + (x and 63)]; return (0.3 * ((c shr 16) and 255) + 0.59 * ((c shr 8) and 255) + 0.11 * (c and 255)) / 255.0 }
        return IntArray(4096) { i -> val x = i % 64; val y = i / 64; val c = t[i]
            if ((c ushr 24) == 0xFE) c else { val sh = (1.0 + k * ((lum(x - 1, y) - lum(x + 1, y)) * 0.7 + (lum(x, y - 1) - lum(x, y + 1)) * 0.7)).coerceIn(0.55, 1.5)
                pack((((c shr 16) and 255) * sh).toInt(), (((c shr 8) and 255) * sh).toInt(), ((c and 255) * sh).toInt()) } }
    }
    private fun bake(a: Array<IntArray>) = Array(a.size) { i -> when (i) { 0 -> emboss(a[i], 1.5); 1, 2 -> emboss(a[i], 1.0); 3, 4 -> emboss(a[i], 0.45); 7 -> emboss(a[i], 1.3); 9, 10 -> emboss(a[i], 1.1); 11 -> emboss(a[i], 1.6); else -> a[i] } }
    private fun ashlar(s: Int, w: Boolean) = tex { x, y -> val row = y / 13; val xo = (x + (row and 1) * 16) % 32
        if (y % 13 == 0 || xo == 0) rgb(166.0, 156.0, 136.0) else { val f = 0.88 + 0.2 * h(xo / 32 + row * 7, row, s) * 0.5 + 0.12 * h(x, y, s + 1)
            if (w && x in 28..35 && y in 16..46 && (y > 22 || abs(x - 31.5) < 3.5 + (y - 16) * 0.5)) rgb(42.0, 42.0, 56.0) else rgb(226.0, 216.0, 192.0, f) } }

    override val tex: Array<IntArray> by lazy { bake(arrayOf(
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
        tex { x, y -> if (x % 16 < 2 || y % 32 < 2 || (x + y) % 16 < 2) rgb(236.0, 240.0, 236.0) else rgb(150.0, 206.0, 228.0, 0.88 + 0.24 * h(x / 8, y / 8, 4321)) })) }  // 13 glass dome

    override val skyW = 4096
    override val skyH = 512
    override val sun = Sun(PI / 4, Math.toRadians(35.0), pack(255, 238, 205), shafts = 0.3, dust = 1.0)

    /** 4096 x 512 spring sky: azimuth around, 0 at the horizon up to the zenith. Sun-lit clouds, the sun, layers of far mountains with snow, a forest line. */
    override val sky: IntArray by lazy {
        val w = skyW; val hh = skyH; val s = IntArray(w * hh)
        val sunU = w / 8.0; val sunEl = Math.toRadians(35.0); val sunV = sunEl / (PI / 2) * (hh - 1)
        fun lerp(a: Int, b: Int, t: Double) = (a + (b - a) * t).toInt()
        // clouds live on a flat layer high up: sky pixels are projected onto it, so they bunch up and shrink towards the horizon (perspective)
        fun cloudN(x: Double, y: Double) = 0.50 * valueNoise(x, y, 4190) + 0.27 * valueNoise(x * 2.03 + 5.2, y * 2.03 + 1.3, 4191) + 0.15 * valueNoise(x * 4.1 + 9.7, y * 4.1 + 3.3, 4192) + 0.08 * valueNoise(x * 8.3 + 2.1, y * 8.3 + 7.9, 4193)
        fun smooth(e0: Double, e1: Double, x: Double): Double { val t = ((x - e0) / (e1 - e0)).coerceIn(0.0, 1.0); return t * t * (3 - 2 * t) }
        val sunAz = PI / 4
        java.util.stream.IntStream.range(0, hh).parallel().forEach { v ->
            val e = v / (hh - 1.0); val k = e.pow(0.5); val elev = (v + 0.5) / hh * (PI / 2)
            val dist = 1.0 / (tan(elev) + 0.05)
            for (u in 0 until w) {
                var r = lerp(206, 58, k).toDouble(); var g = lerp(228, 124, k).toDouble(); var b = lerp(244, 222, k).toDouble()
                var du = u - sunU; if (du > w / 2) du -= w else if (du < -w / 2) du += w
                val dd = hypot(du * (360.0 / w) * cos(sunEl), (v - sunV) * (90.0 / (hh - 1)))   // degrees from the sun
                val az = u.toDouble() / w * 2 * PI
                val cx = cos(az) * dist * 1.7; val cy = sin(az) * dist * 1.7
                val cn = cloudN(cx, cy)
                val cov = smooth(0.545, 0.655, cn) * (0.97 - 0.3 * e)
                if (cov > 0.004) { // a cloud is darker where more cloud lies between it and the sun, brighter on the thin rim facing it
                    val cs = cloudN(cx + cos(sunAz) * 0.22, cy + sin(sunAz) * 0.22)
                    val lit = (0.12 + 0.88 * (1.0 - 5.0 * max(0.0, cs - cn))).coerceIn(0.0, 1.0)
                    val rim = (cov * (1 - cov) * 4.0) * exp(-dd / 25.0)
                    val cr = 142 + 113 * lit + 40 * rim; val cg = 160 + 91 * lit + 34 * rim; val cb = 192 + 52 * lit + 14 * rim
                    r += (cr - r) * cov; g += (cg - g) * cov; b += (cb - b) * cov
                }
                if (dd < 1.3) { val core = smooth(1.3, 0.7, dd); r += (255 - r) * core; g += (252 - g) * core; b += (240 - b) * core }
                val gl = 0.85 * exp(-dd / 5.0) + 0.38 * exp(-dd / 20.0) + 0.14 * exp(-dd / 70.0)
                r += 95 * gl; g += 80 * gl; b += 46 * gl
                s[v * w + u] = pack(r.toInt(), g.toInt(), b.toInt())
            }
        }
        val hazeC = intArrayOf(206, 228, 240)
        // three ranges, far to near: each is hazier the farther it is; the sunny slope of every peak is lit
        val ranges = arrayOf(doubleArrayOf(4210.0, 24.0, 40.0, 0.62, 150.0, 172.0, 208.0), doubleArrayOf(4220.0, 14.0, 34.0, 0.34, 112.0, 142.0, 176.0), doubleArrayOf(4230.0, 8.0, 20.0, 0.10, 84.0, 118.0, 96.0))
        for ((li, rg) in ranges.withIndex()) {
            val seed = rg[0].toInt(); val hgt = IntArray(w); val frac = DoubleArray(w)
            for (u in 0 until w) {
                val big = 0.5 * valueNoise(u * 3.0 / w, 0.5, seed, 3) + 0.3 * valueNoise(u * 9.0 / w, 0.5, seed + 1, 9) + 0.2 * valueNoise(u * 27.0 / w, 0.5, seed + 2, 27)
                val ridge = 1.0 - abs(2 * valueNoise(u * 40.0 / w, 0.5, seed + 3, 40) - 1)       // sharp crests
                val fine = valueNoise(u * 160.0 / w, 0.5, seed + 4, 160)
                val m = rg[1] + rg[2] * (0.35 * big + 0.55 * big * ridge) + 2.2 * fine * (if (li == 2) 1.0 else 0.4)
                hgt[u] = m.toInt(); frac[u] = m - m.toInt()
            }
            for (u in 0 until w) {
                val slope = (hgt[(u + w - 2) % w] - hgt[(u + 2) % w]).toDouble()                    // > 0: the slope faces right
                var du = sunU - u; if (du > w / 2) du -= w else if (du < -w / 2) du += w
                val sunny = (slope * (if (du > 0) 1 else -1) * 0.07).coerceIn(-0.28, 0.28)
                for (v in 0..hgt[u]) {
                    val t = v / (hgt[u] + 1.0); val rock = 0.88 + 0.26 * valueNoise(u * 220.0 / w, v * 0.09, seed + 5, 220)
                    var r = rg[4] * rock * (0.92 + 0.12 * t) * (1 + sunny); var g = rg[5] * rock * (0.92 + 0.12 * t) * (1 + sunny); var b = rg[6] * rock * (0.94 + 0.1 * t) * (1 + sunny)
                    if (li < 2) { val snow = ((v - (rg[1] + rg[2] * 0.55) - 5 * valueNoise(u * 60.0 / w, 0.3, seed + 6, 60)) / 6.0).coerceIn(0.0, 1.0)   // a snow line that follows the altitude, not the peak
                        if (snow > 0) { r += (238.0 * (1 + sunny * 0.6) - r) * snow; g += (242.0 * (1 + sunny * 0.6) - g) * snow; b += (252.0 - b) * snow } }
                    val hz = rg[3]; r += (hazeC[0] - r) * hz; g += (hazeC[1] - g) * hz; b += (hazeC[2] - b) * hz
                    val under = s[v * w + u]; val a = if (v == hgt[u]) frac[u] else 1.0           // anti-aliased crest
                    s[v * w + u] = pack(lerp((under shr 16) and 255, r.toInt(), a), lerp((under shr 8) and 255, g.toInt(), a), lerp(under and 255, b.toInt(), a))
                }
            }
        }
        for (u in 0 until w) { // the forest line: rounded crowns
            val fh = (5 + 9 * valueNoise(u * 30.0 / w, 1.3, 4193, 30) + 5 * valueNoise(u * 190.0 / w, 2.1, 4196, 190)).toInt()
            for (v in 0 until fh) { val t = v / fh.toDouble(); val leaf = 0.85 + 0.3 * h(u / 2, v / 2, 4197)
                s[v * w + u] = pack((lerp(40, 82, t) * leaf).toInt(), (lerp(86, 140, t) * leaf).toInt(), (lerp(52, 78, t) * leaf).toInt()) }
        }
        for (v in 0 until 28) { val a = (1 - v / 28.0).pow(2) * 0.5 // thin haze lying on the horizon
            for (u in 0 until w) { val c = s[v * w + u]; s[v * w + u] = pack(lerp((c shr 16) and 255, hazeC[0], a), lerp((c shr 8) and 255, hazeC[1], a), lerp(c and 255, hazeC[2], a)) } }
        s
    }
}
