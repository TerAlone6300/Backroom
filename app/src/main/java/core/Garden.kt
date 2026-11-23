package com.backrooms.game
// Garden.kt - how Level 414 (Hortus Aeturnus) is entered and left. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
//
// In: in some levels glowing vines appear, and following them leads to an arch covered in vines and flowers. They are part of
// the world like the walls are: worked out from the world seed, so every player of a world sees the same ones (see near()).
// Out: wishing to leave (standing still in the garden) opens a path of flagstones with flowers along it; the kind of flower says
// which level the path leads to (EXITS). The path itself is Hortus.path.
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

object Garden {
    const val SECTOR = 128
    /** Supply.type of the garden's objects in Game.garden. */
    const val T_VINE = 3; const val T_GATE = 4

    /**
     * Where the path of flowers can lead: a safe level, and the flowering bush that says so. `shape` only changes how the blossoms are drawn (0 big, 1 small and many, 2 trumpets).
     * The level's page pairs Dandelions with Levels 11 and 125; its other pairings (Pansies, Hibiscus, Morning Glory, Roses, Cherry Blossoms, Honeysuckle) lead to levels this game does not have,
     * so Pansies, Hibiscus, Morning Glory and Roses are given to the game's other safe levels, and Cherry Blossoms and Honeysuckle are left out until their levels exist.
     */
    class Exit(val flower: String, val r: Int, val g: Int, val b: Int, val shape: Int, val level: Int)
    val EXITS = listOf(
        Exit("DANDELIONS", 250, 225, 60, 1, 11), Exit("PANSIES", 134, 86, 196, 1, 37), Exit("HIBISCUS", 238, 72, 112, 0, 75),
        Exit("MORNING GLORIES", 98, 122, 234, 2, 182), Exit("ROSES", 214, 40, 76, 0, 201))

    /** How likely a 128 x 128 stretch of each level has a gate. The vines need sunlight to grow, so Level 6 (total darkness), the open worlds and the Grave have none. */
    private val GATES = mapOf(0 to 0.10, 1 to 0.35, 2 to 0.30, 3 to 0.30, 4 to 0.30, 5 to 0.25, 37 to 0.30, 75 to 0.35, 182 to 0.25, 201 to 0.15)

    // ---------- finding ways through a level ----------

    /** Breadth-first flood over the open cells within `r` of (sx, sy). */
    class Flood(val lv: Int, val sx: Int, val sy: Int, val r: Int) {
        val n = 2 * r + 1
        val dist = IntArray(n * n) { -1 }
        private val prev = IntArray(n * n) { -1 }
        init {
            val q = IntArray(n * n); var head = 0; var tail = 0
            val s = r * n + r; dist[s] = 0; q[tail++] = s
            while (head < tail) {
                val c = q[head++]; val cx = c % n; val cy = c / n
                for (k in 0 until 4) {
                    val nx = cx + (if (k == 0) 1 else if (k == 1) -1 else 0); val ny = cy + (if (k == 2) 1 else if (k == 3) -1 else 0)
                    if (nx < 0 || ny < 0 || nx >= n || ny >= n) continue
                    val ni = ny * n + nx
                    if (dist[ni] >= 0 || isWall(lv, sx - r + nx, sy - r + ny)) continue
                    dist[ni] = dist[c] + 1; prev[ni] = c; q[tail++] = ni
                }
            }
        }
        fun cellDist(x: Int, y: Int): Int { val ix = x - sx + r; val iy = y - sy + r; return if (ix < 0 || iy < 0 || ix >= n || iy >= n) -1 else dist[iy * n + ix] }
        /** Cells from the start to (x, y), or null if it cannot be reached. */
        fun pathTo(x: Int, y: Int): List<IntArray>? {
            if (cellDist(x, y) < 0) return null
            val out = ArrayList<IntArray>(); var c = (y - sy + r) * n + (x - sx + r)
            while (c >= 0) { out.add(intArrayOf(sx - r + c % n, sy - r + c / n)); c = prev[c] }
            out.reverse(); return out
        }
    }

    private fun nearestFree(lv: Int, x: Int, y: Int, r: Int): IntArray? {
        for (k in 0..r) for (dy in -k..k) for (dx in -k..k) { if (max(abs(dx), abs(dy)) != k) continue; if (!isWall(lv, x + dx, y + dy)) return intArrayOf(x + dx, y + dy) }
        return null
    }

    // ---------- the way in ----------

    private class Site(val gate: IntArray, val vines: List<IntArray>)
    private data class Key(val seed: Int, val lv: Int, val sx: Int, val sy: Int)
    private val NONE = Any()
    private val sites = ConcurrentHashMap<Key, Any>()

    private fun site(lv: Int, sx: Int, sy: Int): Site? {
        val key = Key(worldSeed, lv, sx, sy)
        sites[key]?.let { return it as? Site }
        val s = build(lv, sx, sy)
        if (sites.size > 300) sites.clear()
        sites[key] = s ?: NONE
        return s
    }

    /** One gate in the stretch (or none), and the glowing vines along a way to it that really can be walked: they are placed on the shortest route from a spot 30 cells away. */
    private fun build(lv: Int, sx: Int, sy: Int): Site? {
        val p = GATES[lv] ?: return null
        if (h(sx, sy, 4140 + lv) >= p) return null
        val gate = nearestFree(lv, sx * SECTOR + 24 + (h(sx, sy, 4141) * (SECTOR - 48)).toInt(), sy * SECTOR + 24 + (h(sx, sy, 4142) * (SECTOR - 48)).toInt(), 20) ?: return null
        val ang = h(sx, sy, 4143) * 2 * PI
        val start = nearestFree(lv, gate[0] + (cos(ang) * 30).roundToInt(), gate[1] + (sin(ang) * 30).roundToInt(), 12) ?: return null
        val path = Flood(lv, start[0], start[1], 48).pathTo(gate[0], gate[1]) ?: return null
        if (path.size < 16) return null // too short to follow
        val vines = ArrayList<IntArray>(); var i = 1
        while (i < path.size - 1) { vines.add(path[i]); i += 3 }
        return Site(gate, vines)
    }

    /** The gate and vines within `r` cells of (cx, cy), as Supply(type T_GATE / T_VINE). A trail can start well outside the stretch of its gate, hence the wide search. */
    fun near(lv: Int, cx: Int, cy: Int, r: Int): List<Supply> {
        if (lv !in GATES) return emptyList()
        val out = ArrayList<Supply>(); val m = r + 40
        for (sx in Math.floorDiv(cx - m, SECTOR)..Math.floorDiv(cx + m, SECTOR)) for (sy in Math.floorDiv(cy - m, SECTOR)..Math.floorDiv(cy + m, SECTOR)) {
            val s = site(lv, sx, sy) ?: continue
            if (abs(s.gate[0] - cx) <= r && abs(s.gate[1] - cy) <= r) out.add(Supply(s.gate[0], s.gate[1], T_GATE))
            for (v in s.vines) if (abs(v[0] - cx) <= r && abs(v[1] - cy) <= r) out.add(Supply(v[0], v[1], T_VINE))
        }
        return out
    }
}
