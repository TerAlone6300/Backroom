package com.backrooms.game
// World.kt - maze generators, wall lookup (cached), noclip walls, collision and line of sight. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

// ---------- Worlds: deterministic per level (the mazes themselves are in core/levels), so every device sees the same maze ----------
/** Generic room grid: blocks of b cells, walls with probability p, doors dw cells wide. */
fun grid(x: Int, y: Int, b: Int, s: Int, p: Double, dw: Int): Boolean {
    val bx = Math.floorDiv(x, b); val by = Math.floorDiv(y, b)
    val lx = Math.floorMod(x, b); val ly = Math.floorMod(y, b)
    if (lx == 0 && ly == 0) return true
    val span = b - dw - 1
    val gv = 1 + (h(bx, by, s + 1) * span).toInt(); val gh = 1 + (h(bx, by, s + 3) * span).toInt()
    if (lx == 0 && h(bx, by, s) < p && ly !in gv until gv + dw) return true
    if (ly == 0 && h(bx, by, s + 2) < p && lx !in gh until gh + dw) return true
    return false
}

// Deterministic hallway overlays add varied routes without replacing the underlying room maze.
private const val SECT = 96
/** 0 = maze, 1 = corridor wall, 2 = corridor floor. */
private fun corridorCell(
    x: Int, y: Int, horizontal: Boolean,
    start: Int, end: Int, center: Int, width: Int
): Int {
    val along = if (horizontal) x else y
    val across = if (horizontal) y else x
    val half = width / 2
    if (along !in start..end || across < center - half - 1 || across > center + half + 1) return 0
    return if (across == center - half - 1 || across == center + half + 1) 1 else 2
}

private fun hallway(lv: Int, x: Int, y: Int): Int {
    val sx = Math.floorDiv(x, SECT); val sy = Math.floorDiv(y, SECT); val q = lv * 7
    if (h(sx, sy, 500 + q) > 0.44) return 0

    val horizontal = h(sx, sy, 501 + q) < 0.5
    val widthRoll = h(sx, sy, 502 + q)
    val width = if (widthRoll < 0.58) 3 else if (widthRoll < 0.88) 5 else 7
    val len = 22 + (h(sx, sy, 503 + q) * 31).toInt()
    val start = 8 + (h(sx, sy, 504 + q) * (SECT - len - 16)).toInt()
    val center = 18 + (h(sx, sy, 505 + q) * (SECT - 36)).toInt()
    val lx = Math.floorMod(x, SECT); val ly = Math.floorMod(y, SECT)

    var hasFloor = false
    var hasWall = false
    fun add(cell: Int) {
        if (cell == 2) hasFloor = true else if (cell == 1) hasWall = true
    }

    // Main passage: variable length and width.
    add(corridorCell(lx, ly, horizontal, start, start + len, center, width))

    // A perpendicular branch creates a T-junction instead of another featureless straight run.
    if (h(sx, sy, 506 + q) < 0.58) {
        val wanted = 12 + (h(sx, sy, 507 + q) * 18).toInt()
        val negativeRoom = center - 8
        val positiveRoom = SECT - 8 - center
        val positive = when {
            positiveRoom < wanted -> false
            negativeRoom < wanted -> true
            else -> h(sx, sy, 508 + q) < 0.5
        }
        val branchLen = min(wanted, if (positive) positiveRoom else negativeRoom)
        val branchStart = if (positive) center else center - branchLen
        val branchEnd = if (positive) center + branchLen else center
        val branchCenter = start + len / 2
        val branchWidth = if (h(sx, sy, 509 + q) < 0.72) 3 else 5
        add(corridorCell(lx, ly, !horizontal, branchStart, branchEnd, branchCenter, branchWidth))
    }

    // Some passages briefly open into a wider pocket, breaking up the repeated corridor silhouette.
    if (h(sx, sy, 510 + q) < 0.24) {
        val middle = start + len / 2
        add(corridorCell(
            lx, ly, horizontal,
            max(start, middle - 4), min(start + len, middle + 4),
            center, width + 4
        ))
    }

    return if (hasFloor) 2 else if (hasWall) 1 else 0
}
private fun rawWall(lv: Int, x: Int, y: Int): Boolean {
    val sp = levelSpec(lv)
    if (sp.keepsMaze(x, y)) return sp.wall(x, y)
    return when (hallway(lv, x, y)) { 1 -> true; 2 -> false; else -> sp.wall(x, y) }
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

/** Each level has 4 not-quite-solid walls at fixed spots (none if LevelSpec.noclip is empty); each leads to a fixed level (LevelSpec.noclip). */
val NOCLIPS: Map<Int, List<IntArray>> by lazy {
    LEVELS.map { it.id }.associateWith { lv ->
        // Level 0: scattered around the Manila Room. Other levels: around the spawn area.
        val ax = if (lv == 0) Manila.CX + 4 else 4; val ay = if (lv == 0) Manila.CY + 4 else 4
        val out = ArrayList<IntArray>()
        val dists = if (lv == 0) doubleArrayOf(58.0, 92.0, 126.0, 160.0, 74.0, 108.0, 142.0, 176.0)
                    else doubleArrayOf(18.0, 35.0, 52.0, 27.0)
        for (k in levelSpec(lv).noclip.indices) {
            val step = if (lv == 0) (2.0 * Math.PI / 8.0) else 2.094
            val ang = lv * 1.3 + k * step + (if (lv != 0 && k == 3) 1.047 else 0.0)
            val dist = dists[k]
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
    val l = NOCLIPS[lv] ?: return -1
    for (k in l.indices) if (l[k][0] == x && l[k][1] == y) return k
    return -1
}
fun unstable(lv: Int, x: Int, y: Int) = noclipIndex(lv, x, y) >= 0

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
