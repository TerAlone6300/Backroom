package com.backrooms.game
// Terrain.kt - the ground of an open world, generated on the CPU and then only sampled.
//
// OpenWorld.ground() is a procedural function (noise, flowers, paving...) and costs hundreds of nanoseconds per pixel. TerrainBake runs it ONCE
// per ground point instead: the world is cut into square tiles, each baked in a background thread into a colour map with mip levels, and the
// renderer only reads colours back (trilinear). Beyond the baked area a very coarse colour grid gives the far ground. Nothing here depends
// on how the picture is drawn: the same maps are what a GPU renderer samples. Pure Kotlin/JVM, shared by desktop and Android.
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.*

class TerrainBake(private val ow: OpenWorld, private val floorTex: IntArray) {
    companion object {
        const val TILE = 4                 // world units per tile side
        const val RES = 64                 // texels per unit in the finest level
        const val PAD = 8                  // border texels, so bilinear never reads a neighbouring tile
        const val SZ = TILE * RES + 2 * PAD
        const val LODS = 4                 // 64, 32, 16, 8 texels per unit
        const val RADIUS = 10.0            // tiles are baked within this distance of the player (units)
        private const val RING = 16        // tiles live in a RING x RING direct-mapped table
        private const val CRING = 32       // coarse colours: CRING x CRING
    }

    class Tile(val cx: Int, val cy: Int, val version: Int, val mips: Array<IntArray>)
    private class Coarse(val cx: Int, val cy: Int, val version: Int, val color: Int)

    private val ring = AtomicReferenceArray<Tile?>(RING * RING)
    private val coarseRing = AtomicReferenceArray<Coarse?>(CRING * CRING)
    private val queue = LinkedBlockingQueue<Long>()
    private val queued = ConcurrentHashMap<Long, Boolean>()
    @Volatile private var version = ow.groundVersion
    @Volatile private var alive = true
    private val workers = (1..2).map { n -> Thread({ work() }, "backrooms-terrain-$n").apply { isDaemon = true; start() } }

    private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) or (cy.toLong() and 0xFFFFFFFFL)

    private fun work() {
        while (alive) {
            val k = try { queue.take() } catch (_: InterruptedException) { return }
            val cx = (k shr 32).toInt(); val cy = k.toInt()
            try { val v = version; ring.set(slot(cx, cy), build(cx, cy, v)) } catch (_: Throwable) {}
            queued.remove(k)
        }
    }
    private fun slot(cx: Int, cy: Int) = (cx and (RING - 1)) * RING + (cy and (RING - 1))

    /** Bakes one tile: the ground colour of every texel, then its mip levels (box filter). */
    fun build(cx: Int, cy: Int, ver: Int): Tile {
        val base = IntArray(SZ * SZ); val ox = cx * TILE.toDouble(); val oy = cy * TILE.toDouble()
        for (j in 0 until SZ) {
            val wy = oy + (j - PAD + 0.5) / RES; val iy = floor(wy).toInt(); val ty = ((wy - iy) * 64).toInt() and 63
            for (i in 0 until SZ) {
                val wx = ox + (i - PAD + 0.5) / RES; val ix = floor(wx).toInt(); val tx = ((wx - ix) * 64).toInt() and 63
                base[j * SZ + i] = ow.ground(wx, wy, ix, iy, floorTex[ty * 64 + tx])
            }
        }
        val mips = arrayOfNulls<IntArray>(LODS); mips[0] = base
        for (l in 1 until LODS) {
            val n = SZ shr l; val src = mips[l - 1]!!; val sn = SZ shr (l - 1); val d = IntArray(n * n)
            for (j in 0 until n) for (i in 0 until n) {
                val a = src[(2 * j) * sn + 2 * i]; val b = src[(2 * j) * sn + 2 * i + 1]; val c = src[(2 * j + 1) * sn + 2 * i]; val e = src[(2 * j + 1) * sn + 2 * i + 1]
                d[j * n + i] = (0xFF shl 24) or ((((a shr 16 and 255) + (b shr 16 and 255) + (c shr 16 and 255) + (e shr 16 and 255)) shr 2) shl 16) or
                    ((((a shr 8 and 255) + (b shr 8 and 255) + (c shr 8 and 255) + (e shr 8 and 255)) shr 2) shl 8) or (((a and 255) + (b and 255) + (c and 255) + (e and 255)) shr 2)
            }
            mips[l] = d
        }
        @Suppress("UNCHECKED_CAST")
        return Tile(cx, cy, ver, mips as Array<IntArray>)
    }

    /** Called once per frame with the player's position: asks for the tiles around the player (nearest first) and forgets the far ones. */
    fun update(px: Double, py: Double) {
        val v = ow.groundVersion; if (v != version) { version = v; queued.clear(); queue.clear() } // the ground changed (a path opened): stale tiles are ignored and rebuilt
        val x0 = floor((px - RADIUS) / TILE).toInt(); val x1 = floor((px + RADIUS) / TILE).toInt(); val y0 = floor((py - RADIUS) / TILE).toInt(); val y1 = floor((py + RADIUS) / TILE).toInt()
        val want = ArrayList<Long>(); val dist = HashMap<Long, Double>()
        for (cy in y0..y1) for (cx in x0..x1) {
            val d = hypot((cx + 0.5) * TILE - px, (cy + 0.5) * TILE - py); if (d > RADIUS + TILE) continue
            val t = ring.get(slot(cx, cy))
            if (t != null && t.cx == cx && t.cy == cy && t.version == version) continue
            val k = key(cx, cy); if (queued.putIfAbsent(k, true) == null) { want.add(k); dist[k] = d }
        }
        want.sortBy { dist[it] }; for (k in want) queue.offer(k)
        for (i in 0 until RING * RING) { val t = ring.get(i) ?: continue; if (hypot((t.cx + 0.5) * TILE - px, (t.cy + 0.5) * TILE - py) > RADIUS + 3 * TILE) ring.compareAndSet(i, t, null) }
    }

    /** Bakes the tiles right round the player now (first frame of a level), so the ground is not blurry while the workers catch up. */
    fun prewarm(px: Double, py: Double) {
        val cx0 = floor(px / TILE).toInt(); val cy0 = floor(py / TILE).toInt()
        for (dy in -1..1) for (dx in -1..1) { val cx = cx0 + dx; val cy = cy0 + dy; val t = ring.get(slot(cx, cy)); if (t == null || t.cx != cx || t.cy != cy || t.version != version) ring.set(slot(cx, cy), build(cx, cy, version)) }
    }

    /** Baked colour at a ground point, trilinear in `lod` (0 = finest); 0 if that tile is not baked (yet). */
    fun sample(wx: Double, wy: Double, lod: Double): Int {
        val cx = floor(wx / TILE).toInt(); val cy = floor(wy / TILE).toInt()
        val t = ring.get(slot(cx, cy)) ?: return 0
        if (t.cx != cx || t.cy != cy || t.version != version) return 0
        val lx = wx - cx * TILE; val ly = wy - cy * TILE
        val l0 = lod.toInt().coerceIn(0, LODS - 1); val a = bilin(t.mips[l0], l0, lx, ly)
        val f = ((lod - l0).coerceIn(0.0, 1.0) * 256).toInt()
        if (f < 8 || l0 == LODS - 1) return a
        val b = bilin(t.mips[l0 + 1], l0 + 1, lx, ly)
        return mix(a, b, f)
    }

    private fun bilin(m: IntArray, l: Int, lx: Double, ly: Double): Int {
        val n = SZ shr l; val s = 1.0 / (1 shl l)
        val u = (lx * RES + PAD) * s - 0.5; val v = (ly * RES + PAD) * s - 0.5
        val u0 = floor(u).toInt(); val v0 = floor(v).toInt(); val fu = ((u - u0) * 256).toInt(); val fv = ((v - v0) * 256).toInt()
        val i = v0 * n + u0
        return mix(mix(m[i], m[i + 1], fu), mix(m[i + n], m[i + n + 1], fu), fv)
    }
    private fun mix(a: Int, b: Int, f: Int): Int {
        val g = 256 - f
        return (0xFF shl 24) or ((((a shr 16 and 255) * g + (b shr 16 and 255) * f) shr 8) shl 16) or ((((a shr 8 and 255) * g + (b shr 8 and 255) * f) shr 8) shl 8) or (((a and 255) * g + (b and 255) * f) shr 8)
    }

    /** Average colour of one tile (6 x 6 samples of the real ground), computed on demand and remembered. */
    private fun coarseAt(cx: Int, cy: Int): Int {
        val s = (cx and (CRING - 1)) * CRING + (cy and (CRING - 1)); val c = coarseRing.get(s)
        if (c != null && c.cx == cx && c.cy == cy && c.version == version) return c.color
        var r = 0; var g = 0; var b = 0; val n = 6
        for (j in 0 until n) for (i in 0 until n) {
            val wx = cx * TILE + (i + 0.5) * TILE / n; val wy = cy * TILE + (j + 0.5) * TILE / n; val ix = floor(wx).toInt(); val iy = floor(wy).toInt()
            val p = ow.groundFar(wx, wy, ix, iy, floorTex[(((wy - iy) * 64).toInt() and 63) * 64 + (((wx - ix) * 64).toInt() and 63)])
            r += p shr 16 and 255; g += p shr 8 and 255; b += p and 255
        }
        val col = pack(r / (n * n), g / (n * n), b / (n * n))
        coarseRing.set(s, Coarse(cx, cy, version, col)); return col
    }
    /** Far ground: the tile averages blended smoothly. */
    fun coarse(wx: Double, wy: Double): Int {
        val gx = wx / TILE - 0.5; val gy = wy / TILE - 0.5; val x0 = floor(gx).toInt(); val y0 = floor(gy).toInt()
        val fu = ((gx - x0) * 256).toInt(); val fv = ((gy - y0) * 256).toInt()
        return mix(mix(coarseAt(x0, y0), coarseAt(x0 + 1, y0), fu), mix(coarseAt(x0, y0 + 1), coarseAt(x0 + 1, y0 + 1), fu), fv)
    }

    fun close() { alive = false; workers.forEach { it.interrupt() } }
}
