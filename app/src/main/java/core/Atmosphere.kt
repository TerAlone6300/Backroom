package com.backrooms.game
// Atmosphere.kt - sun light shafts and drifting pollen / dust for open worlds that declare an OpenWorld.sun. Shared by desktop and Android (pure Kotlin/JVM).
import kotlin.math.*

/** The sun of an open world: [azimuth] (world angle, like the player's heading), [elevation] in radians, its [color], how strong the light [shafts] and the floating [dust] are. */
class Sun(val azimuth: Double, val elevation: Double, val color: Int, val shafts: Double = 0.45, val dust: Double = 1.0)

class Atmosphere(private val w: Int, private val h: Int) {
    private val cell = 8
    private val lw = w / cell; private val lh = h / cell
    private val mask = FloatArray(lw * lh); private val acc = FloatArray(lw * lh)
    private var tint = 0; private var active = false

    /**
     * Light shafts: where the sky is visible (not hidden by hedges, trees, towers) it is bright; blurring that mask radially towards the sun
     * makes beams stream between the things that block it. Works on a coarse grid; [skyAt] gives the untouched sky colour of a screen pixel.
     */
    fun prepare(px: IntArray, hz: Int, sunX: Double, sunY: Double, strength: Double, color: Int, skyAt: (Int, Int) -> Int) {
        active = false; if (strength <= 0.01) return
        for (ly in 0 until lh) for (lx in 0 until lw) {
            val xx = lx * cell + cell / 2; val yy = ly * cell + cell / 2
            var m = 0f
            if (yy < hz) {
                val s = skyAt(xx, yy); val c = px[yy * w + xx]
                if (abs(((c shr 16) and 255) - ((s shr 16) and 255)) < 5 && abs(((c shr 8) and 255) - ((s shr 8) and 255)) < 5 && abs((c and 255) - (s and 255)) < 5) {
                    val l = (0.3f * ((s shr 16) and 255) + 0.59f * ((s shr 8) and 255) + 0.11f * (s and 255)) / 255f
                    m = l * l * l
                }
            }
            mask[ly * lw + lx] = m
        }
        val sx = (sunX.coerceIn(-6.0 * w, 7.0 * w) / cell).toFloat(); val sy = (sunY.coerceIn(-8.0 * h, 1.0 * h) / cell).toFloat()
        val n = 20
        for (ly in 0 until lh) for (lx in 0 until lw) {
            var u = lx + 0.5f; var v = ly + 0.5f
            val du = (sx - u) * 0.92f / n; val dv = (sy - v) * 0.92f / n
            var a = 0f; var wt = 1f
            for (i in 0 until n) { u += du; v += dv; a += mask[v.toInt().coerceIn(0, lh - 1) * lw + u.toInt().coerceIn(0, lw - 1)] * wt; wt *= 0.93f }
            val ds = hypot((sx - lx).toDouble(), (sy - ly).toDouble()) * cell / w          // the glow fades with the distance to the sun
            acc[ly * lw + lx] = (a / 10f * strength / (1.0 + ds * ds * 0.9)).toFloat()
        }
        tint = color; active = true
    }

    /** Adds the shafts to rows [r0, r1) (call from the row-parallel pass). */
    fun blendRows(px: IntArray, r0: Int, r1: Int) {
        if (!active) return
        val cr = (tint shr 16) and 255; val cg = (tint shr 8) and 255; val cb = tint and 255
        for (yy in r0 until r1) {
            val fy = (yy + 0.5f) / cell - 0.5f; val y0 = floor(fy).toInt(); val ty = fy - y0
            val ya = y0.coerceIn(0, lh - 1) * lw; val yb = (y0 + 1).coerceIn(0, lh - 1) * lw
            for (xx in 0 until w) {
                val fx = (xx + 0.5f) / cell - 0.5f; val x0 = floor(fx).toInt(); val tx = fx - x0
                val xa = x0.coerceIn(0, lw - 1); val xb = (x0 + 1).coerceIn(0, lw - 1)
                val top = acc[ya + xa] + (acc[ya + xb] - acc[ya + xa]) * tx; val bot = acc[yb + xa] + (acc[yb + xb] - acc[yb + xa]) * tx
                val v = top + (bot - top) * ty
                if (v < 0.004f) continue
                val c = px[yy * w + xx]
                px[yy * w + xx] = pack(min(255, ((c shr 16) and 255) + (cr * v).toInt()), min(255, ((c shr 8) and 255) + (cg * v).toInt()), min(255, (c and 255) + (cb * v).toInt()))
            }
        }
    }

    /** Pollen, seeds and dust hanging in the sunlight: soft dots that drift round the player, hidden behind walls, brightest when you look into the sun. */
    fun dust(px: IntArray, zb: DoubleArray, x: Double, y: Double, dirX: Double, dirY: Double, pl: Double, hz: Int, vs: Double, camH: Double, t: Double,
             facing: Double, color: Int, density: Double) {
        val count = (160 * density).toInt(); val box = 12.0
        val cr = (color shr 16) and 255; val cg = (color shr 8) and 255; val cb = color and 255
        val view = (0.35 + 0.65 * facing.coerceIn(0.0, 1.0))
        for (i in 0 until count) {
            val ox = h(i, 1, 7001); val oy = h(i, 2, 7001); val oz = h(i, 3, 7001); val sp = h(i, 4, 7001)
            val bx = ox * box + sin(t * 0.21 + i * 1.7) * 0.9; val by = oy * box + cos(t * 0.17 + i * 2.3) * 0.9
            val wx = bx + box * ceil((x - box / 2 - bx) / box); val wy = by + box * ceil((y - box / 2 - by) / box)
            val z = ((oz + t * 0.012 * (0.4 + sp)) % 1.0) * 1.15 // height above the ground in wall units, slowly rising
            val ddx = wx - x; val ddy = wy - y
            val depth = ddx * dirX + ddy * dirY
            if (depth < 0.25 || depth > 9.0) continue
            val camx = (ddx * -dirY + ddy * dirX) / (depth * pl)
            if (camx < -1.05 || camx > 1.05) continue
            val sx = ((camx + 1) * 0.5 * w).toInt(); val sy = (hz + (camH - z) * h * vs / depth).toInt()
            if (sx < 1 || sx >= w - 1 || sy < 1 || sy >= h - 1 || depth >= zb[sx]) continue
            val tw = 0.55 + 0.45 * sin(t * (1.5 + 2.5 * sp) + i * 3.1)
            val a = (tw * view * (1.0 - depth / 10.0)).coerceIn(0.0, 1.0)
            val rad = (0.024 * h * vs / depth).coerceIn(0.9, 4.0)
            val ri = ceil(rad).toInt()
            for (j in -ri..ri) for (k in -ri..ri) {
                val d = hypot(j.toDouble(), k.toDouble()) / rad; if (d >= 1.0) continue
                val px0 = sx + k; val py0 = sy + j; if (px0 !in 0 until w || py0 !in 0 until h || depth >= zb[px0]) continue
                val v = a * (1 - d) * (1 - d) * 0.9; val c = px[py0 * w + px0]
                px[py0 * w + px0] = pack(min(255, ((c shr 16) and 255) + (cr * v).toInt()), min(255, ((c shr 8) and 255) + (cg * v).toInt()), min(255, (c and 255) + (cb * v).toInt()))
            }
        }

        // petals: pink and white, falling slowly and swaying, drawn over the scene (not added to it)
        val pc = arrayOf(intArrayOf(248, 190, 210), intArrayOf(252, 236, 240), intArrayOf(240, 150, 186))
        val pn = (28 * density).toInt()
        for (i in 0 until pn) {
            val ox = h(i, 11, 7002); val oy = h(i, 12, 7002); val oz = h(i, 13, 7002); val sp = h(i, 14, 7002)
            val bx = ox * box + sin(t * 0.5 + i * 2.1) * 1.3; val by = oy * box + cos(t * 0.43 + i * 1.3) * 1.3
            val wx = bx + box * ceil((x - box / 2 - bx) / box); val wy = by + box * ceil((y - box / 2 - by) / box)
            val z = 1.3 - ((oz + t * 0.03 * (0.5 + sp)) % 1.0) * 1.3 // from above, down to the ground
            val ddx = wx - x; val ddy = wy - y; val depth = ddx * dirX + ddy * dirY
            if (depth < 0.3 || depth > 7.0) continue
            val camx = (ddx * -dirY + ddy * dirX) / (depth * pl); if (camx < -1.05 || camx > 1.05) continue
            val sx = ((camx + 1) * 0.5 * w).toInt(); val sy = (hz + (camH - z) * h * vs / depth).toInt()
            if (sx < 2 || sx >= w - 2 || sy < 2 || sy >= h - 2 || depth >= zb[sx]) continue
            val c = pc[(h(i, 15, 7002) * 3).toInt().coerceIn(0, 2)]; val rw = (0.018 * h * vs / depth).coerceIn(1.2, 6.0); val rh = rw * (0.45 + 0.3 * sin(t * 2 + i))
            val a = (0.9 * (1.0 - depth / 8.0)).coerceIn(0.0, 0.9)
            for (j in -ceil(rh).toInt()..ceil(rh).toInt()) for (k in -ceil(rw).toInt()..ceil(rw).toInt()) {
                val d = hypot(k / rw, j / max(rh, 0.5)); if (d >= 1.0) continue
                val px0 = sx + k; val py0 = sy + j; if (px0 !in 0 until w || py0 !in 0 until h) continue
                val o = px[py0 * w + px0]; val aa = a * (1 - d * d * 0.5) * (0.75 + 0.25 * (1 - d))
                px[py0 * w + px0] = pack((((o shr 16) and 255) * (1 - aa) + c[0] * aa).toInt(), (((o shr 8) and 255) * (1 - aa) + c[1] * aa).toInt(), ((o and 255) * (1 - aa) + c[2] * aa).toInt())
            }
        }
    }
}
