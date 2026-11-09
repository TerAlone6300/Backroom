package com.backrooms.game
// Renderer.kt - the raycasting renderer. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

// ---------- Renderer: textured raycaster, pooled lighting, fog, look up/down, entities ----------
private val SINT = DoubleArray(4096) { sin(it * 2 * PI / 4096) }
/** Table sine for cosmetic shimmer (water); accuracy does not matter there, speed does. */
private fun fsin(x: Double) = SINT[(x * (4096 / (2 * PI))).toInt() and 4095]

/** Floor/ceiling rows, wall columns and the post pass are split over up to `threads` threads (the caller works too). */
class Renderer(val W: Int = 480, val H: Int = 270, threads: Int = min(4, Runtime.getRuntime().availableProcessors())) {
    private val zb = DoubleArray(W)
    private val vig = IntArray(W * H)
    private val camX = DoubleArray(W) { 2.0 * it / W - 1 }
    private var grain = 1234567
    private var frame = 0
    private var lpx = 0.0; private var lpy = 0.0; private var lant = 0.0; private var pres = 0.0
    private val nt = max(1, threads)
    private val pool = if (nt > 1) java.util.concurrent.Executors.newFixedThreadPool(nt - 1) { r -> Thread(r).also { it.isDaemon = true } } else null

    /** Per-thread lamp memo: a lamp's brightness (flicker) is worked out once per frame instead of once per pixel. */
    private inner class Lamps {
        val kx = IntArray(256); val ky = IntArray(256); val fr = IntArray(256); val v = DoubleArray(256)
        var mx = 0; var my = 0; var mv = 1.0; var mf = -1
        val hmx = IntArray(8); val hmy = IntArray(8); val hsd = IntArray(8); val hht = IntArray(8); val hpp = DoubleArray(8) // wall hits along one city ray
        fun at(lv: Int, x: Int, y: Int, t: Double): Double {
            if (mf == frame && x == mx && y == my) return mv // neighbouring pixels almost always share the lamp
            val i = ((x * 73856093) xor (y * 19349663)) and 255
            val r = if (fr[i] == frame && kx[i] == x && ky[i] == y) v[i]
                    else (flick(lv, x, y, t) * spook(x, y, t)).also { kx[i] = x; ky[i] = y; fr[i] = frame; v[i] = it }
            mf = frame; mx = x; my = y; mv = r
            return r
        }
    }
    private val bands = Array(nt) { Lamps() }
    private val rowTmp = IntArray(W)
    private val colU = IntArray(W); private val rowV = IntArray(H) // city sky lookup: azimuth per column, elevation per row

    /** body(band, from, to) over [0, n) in `nt` slices; the last slice runs on the calling thread. */
    private fun par(n: Int, body: (Int, Int, Int) -> Unit) {
        val p = pool
        if (p == null) { body(0, 0, n); return }
        val fs = ArrayList<java.util.concurrent.Future<*>>(nt)
        for (b in 0 until nt - 1) { val from = n * b / nt; val to = n * (b + 1) / nt; fs.add(p.submit(Runnable { body(b, from, to) })) }
        body(nt - 1, n * (nt - 1) / nt, n)
        for (f in fs) f.get()
    }

    init {
        for (y in 0 until H) for (x in 0 until W) {
            val nx = (x - W / 2.0) / (W / 2.0); val ny = (y - H / 2.0) / (H / 2.0)
            vig[y * W + x] = ((1 - 0.38 * (nx * nx + ny * ny)).coerceIn(0.3, 1.0) * 256).toInt()
        }
    }

    private fun sq(a: Double) = a * a
    private val HZC = pack(100, 62, 90) // horizon glow: distant ground fades into it, matching the sky
    private fun blend(a: Int, b: Int, t: Int): Int { val u = 256 - t
        return pack((((a shr 16) and 255) * u + ((b shr 16) and 255) * t) shr 8, (((a shr 8) and 255) * u + ((b shr 8) and 255) * t) shr 8, ((a and 255) * u + (b and 255) * t) shr 8) }

    /** City rows: above the horizon the star sky (no ceiling), below it street, sidewalk, grass, hazing into the horizon glow. */
    private fun cityRows(px: IntArray, lp: Lamps, r0: Int, r1: Int, hz: Int, vs: Double, fk: Double, gl: Double, t: Double,
                         x: Double, y: Double, dx: Double, dy: Double, plX: Double, plY: Double, d: LevelDef, ts: TexSet) {
        val sky = City.sky; val tn = d.tint
        for (yy in r0 until r1) {
            val dr = yy - hz
            if (dr <= 0) { val base = rowV[yy] * 2048; for (xx in 0 until W) px[yy * W + xx] = sky[base + colU[xx]]; continue }
            val rd = 0.5 * H * vs / dr
            val fog = 1.0 / (1 + rd * rd * fk)
            val ha = ((1.0 - 1.0 / (1 + rd * rd * 0.0007 * Settings.fogScale)) * 256).toInt() // distance haze
            for (xx in 0 until W) {
                val cx = camX[xx]
                val wx = x + (dx + plX * cx) * rd; val wy = y + (dy + plY * cx) * rd
                val ix = floor(wx).toInt(); val iy = floor(wy).toInt()
                val tx = ((wx - ix) * 64).toInt() and 63; val ty = ((wy - iy) * 64).toInt() and 63
                val lit = mul(City.ground(wx, wy, ix, iy, ts.floor[ty * 64 + tx]), lightAt(d, lp, wx, wy, t) * gl * fog * 0.95 * (if (City.darkCell(ix, iy)) 0.12 else 1.0), tn)
                px[yy * W + xx] = if (ha > 0) blend(lit, HZC, ha) else lit
            }
        }
    }

    /** One city column. The ray keeps going past low buildings so taller ones behind show above them; hits are drawn far to near. */
    private fun cityCol(px: IntArray, xx: Int, lp: Lamps, x: Double, y: Double, rx: Double, ry: Double, hz: Int, vs: Double, fk: Double, gl: Double, t: Double, d: LevelDef) {
        var mapX = floor(x).toInt(); var mapY = floor(y).toInt()
        val ddx = abs(1 / rx); val ddy = abs(1 / ry)
        val stepX = if (rx < 0) -1 else 1; val stepY = if (ry < 0) -1 else 1
        var sdx = if (rx < 0) (x - mapX) * ddx else (mapX + 1 - x) * ddx
        var sdy = if (ry < 0) (y - mapY) * ddy else (mapY + 1 - y) * ddy
        var side = 0; var n = 0; var cnt = 0; var topBest = Int.MAX_VALUE
        while (n++ < 72) {
            if (sdx < sdy) { sdx += ddx; mapX += stepX; side = 0 } else { sdy += ddy; mapY += stepY; side = 1 }
            if (!isWall(LV_CITY, mapX, mapY)) continue
            val ht = City.height(mapX, mapY)
            val pp = (if (side == 0) sdx - ddx else sdy - ddy).coerceAtLeast(0.05)
            val lh = (H * vs / pp).toInt().coerceAtLeast(1)
            val top = hz + lh / 2 - lh * ht
            if (top < topBest) { lp.hmx[cnt] = mapX; lp.hmy[cnt] = mapY; lp.hsd[cnt] = side; lp.hht[cnt] = ht; lp.hpp[cnt] = pp; topBest = top; cnt++ }
            if (top <= 0 || cnt == 8) break
        }
        zb[xx] = if (cnt > 0) lp.hpp[0] else 72.0
        val tex = City.tex; val tn = d.tint
        for (k in cnt - 1 downTo 0) {
            val mx = lp.hmx[k]; val my = lp.hmy[k]; val sd = lp.hsd[k]; val ht = lp.hht[k]; val pp = lp.hpp[k]
            val hx = x + pp * rx; val hy = y + pp * ry
            var tx = (((if (sd == 0) hy else hx).let { it - floor(it) }) * 64).toInt() and 63
            if ((sd == 0 && rx > 0) || (sd == 1 && ry < 0)) tx = 63 - tx
            val fogF = 1.0 / (1 + pp * pp * fk); val sideF = if (sd == 1) 0.82 else 1.0
            // the face is inside a dark building when the cell the ray came from is: no lamps, and the windows are dead too
            val dk = City.darkCell(if (sd == 0) mx - (if (rx < 0) -1 else 1) else mx, if (sd == 0) my else my - (if (ry < 0) -1 else 1))
            val kk = (lightAt(d, lp, hx, hy, t) * gl * fogF * sideF * (if (dk) 0.12 else 1.0) * 256).toInt().coerceIn(0, 640)
            val ke = if (dk) kk else (256 * (0.45 + 0.55 * fogF) * sideF).toInt() // lit windows glow: they dim with distance but not with the lamps
            val kr = kk * tn[0]; val kg = kk * tn[1]; val kb = kk * tn[2]; val er = ke * tn[0]; val eg = ke * tn[1]; val eb = ke * tn[2]
            val ha = ((1.0 - 1.0 / (1 + pp * pp * 0.0007 * Settings.fogScale)) * 256).toInt()
            val lh = (H * vs / pp).toInt().coerceAtLeast(1)
            val bottom = hz + lh / 2; val top = bottom - lh * ht
            val y0 = max(0, top); val y1 = min(H - 1, bottom)
            val step = 64.0 / lh; var tpos = (y0 - top) * step
            val kind = City.kind(mx, my); val vv = (mx * 7 + my * 13) and 3; val unst = unstable(LV_CITY, mx, my)
            for (yy in y0..y1) {
                val tp = tpos.toInt(); tpos += step
                val unit = tp shr 6 // floors counted from the roof line; the last one is the ground floor
                val ti = if (kind == 4) 18 else if (kind == 3) (if (unit >= ht - 1) 13 else 12) else if (unit >= ht - 1) 14 + vv else kind * 4 + ((vv + unit) and 3)
                val tx2 = if (unst) (tx + (sin(t * 11 + yy * 0.25) * 4).toInt()) and 63 else tx
                val c = tex[ti][(tp and 63) * 64 + tx2]
                var o = if ((c ushr 24) == 0xFE) mul2(c, er, eg, eb) else mul2(c, kr, kg, kb)
                if (unst) o = pack((((o shr 16) and 255) * 3) / 4, ((o shr 8) and 255) + 25, (o and 255) + 60) // cold, wobbling = not solid
                px[yy * W + xx] = if (ha > 0) blend(o, HZC, ha) else o
            }
        }
    }
    private fun sc(r: Int, g: Int, b: Int, dim: Double) = pack((r * dim).toInt(), (g * dim).toInt(), (b * dim).toInt())

    private fun mul(c: Int, f: Double, tn: IntArray): Int {
        val k = (f * 256).toInt().coerceIn(0, 640)
        return pack((((c shr 16) and 255) * k * tn[0]) shr 16, (((c shr 8) and 255) * k * tn[1]) shr 16, ((c and 255) * k * tn[2]) shr 16)
    }

    /** Same as mul() with the per-colour multipliers (k * tint) worked out once per wall column. */
    private fun mul2(c: Int, kr: Int, kg: Int, kb: Int) = pack((((c shr 16) and 255) * kr) shr 16, (((c shr 8) and 255) * kg) shr 16, ((c and 255) * kb) shr 16)

    private fun flick(lv: Int, kx: Int, ky: Int, t: Double): Double {
        val v = h(kx, ky, 9 + lv * 13); val th = levelSpec(lv).flicker
        return when {
            v < th[0] * 0.4 -> if (sin(t * (6 + v * 20) + v * 50) > 0.5) 0.45 else 1.0 // broken: few, slow, shallow stutter
            v < th[1] -> 0.96 + 0.04 * sin(t * 40)                                    // humming (barely visible)
            v < th[2] -> 0.0                                                     // dead
            else -> 1.0
        }
    }

    /** Lamps near an entity stutter: more of them, and for longer, the closer it is. 1.0 = steady. */
    private fun spook(kx: Int, ky: Int, t: Double): Double {
        if (pres < 0.02 || h(kx, ky, 77) > pres * 1.3) return 1.0
        return if (sin(t * (16 + h(kx, ky, 78) * 30) + h(kx, ky, 79) * 20) > 0.7 - pres) 0.1 else 1.0
    }

    /** Light pooled around each ceiling panel (one every `period` cells). */
    private fun lightAt(d: LevelDef, lp: Lamps, wx: Double, wy: Double, t: Double): Double {
        val p = d.period
        val kx = floor((wx - d.lamp0) * d.ip + 0.5).toInt(); val ky = floor((wy - d.lamp0) * d.ip + 0.5).toInt()
        val ddx = wx - (kx * p + d.lamp0); val ddy = wy - (ky * p + d.lamp0)
        val lamp = d.ambient + lp.at(d.id, kx, ky, t) / (1 + (ddx * ddx + ddy * ddy) * d.falloff)
        return if (lant > 0.0) lamp + lant / (1 + (sq(wx - lpx) + sq(wy - lpy)) * 0.35) else lamp
    }

    private fun put(px: IntArray, xx: Int, yy: Int, col: Int, a: Int) {
        if (xx < 0 || yy < 0 || xx >= W || yy >= H) return
        val d = px[yy * W + xx]
        px[yy * W + xx] = pack(
            ((((d shr 16) and 255) * (256 - a)) + (((col shr 16) and 255) * a)) shr 8,
            ((((d shr 8) and 255) * (256 - a)) + (((col shr 8) and 255) * a)) shr 8,
            (((d and 255) * (256 - a)) + ((col and 255) * a)) shr 8)
    }

    private fun rect(px: IntArray, x0: Int, y0: Int, w: Int, hh: Int, col: Int, a: Int) {
        for (yy in y0 until y0 + hh) for (xx in x0 until x0 + w) put(px, xx, yy, col, a)
    }

    /** Sprite shapes in (u,v) space; u in -1..1 across, v in 0..1 top to bottom. 0 = transparent. */
    private fun sprite(type: Int, u: Double, v: Double, dim: Double, fr: Boolean = false): Int { // fr = friendly (Explore): green eyes, soft glow
        val dark = pack((16 * dim).toInt(), (14 * dim).toInt(), (10 * dim).toInt())
        return when (type) {
            0, 3, 4 -> { // player = tall dark figure with glowing eyes; type 3 (Skin-Stealer) copies it, minus the glow; type 4 (Faceling) has a pale head with misplaced features
                val inside = when {
                    v < 0.17 -> sq(u / 0.42) + sq((v - 0.09) / 0.085) < 1
                    v < 0.22 -> abs(u) < 0.18
                    v < 0.62 -> abs(u) < 0.8 - (v - 0.22) * 0.5
                    else -> abs(abs(u) - 0.35) < 0.22
                }
                if (!inside) 0
                else if (type == 4) { // Faceling: an ordinary person in a grey jacket, but the face is a blank, only a trace of eyes and mouth smudged into the skin
                    if (v < 0.17) {
                        val sm = exp(-(sq((abs(u) - 0.12) / 0.07) + sq((v - 0.075) / 0.02))) * 0.25 + exp(-(sq(u / 0.1) + sq((v - 0.135) / 0.012))) * 0.2
                        val f = 1.0 - 0.5 * sm
                        sc((226 * f).toInt(), (206 * f).toInt(), (190 * f).toInt(), dim)
                    } else if (v < 0.58) sc(62, 68, 90, dim) else sc(38, 40, 50, dim)
                }
                else if (type == 0 && v in 0.075..0.10 && abs(abs(u) - 0.17) < 0.07) pack(255, 255, 225)
                else if (fr && type == 3 && v in 0.075..0.10 && abs(abs(u) - 0.17) < 0.07) pack(130, 255, 160) else dark
            }
            9 -> if (v < 0.06 && abs(u) < 0.9) pack(255, 236, 170) else if (abs(u) < 0.18) sc(70, 72, 80, dim) else 0 // street lamp
            7 -> { // ration: tin can
                if (abs(u) > 0.7 || v < 0.15) 0
                else if (v < 0.3 || v > 0.9) sc(196, 200, 204, dim)
                else if (v in 0.45..0.75) sc(236, 226, 190, dim) else sc(170, 60, 46, dim)
            }
            8 -> { // almond water: bottle with a cream label
                val neck = abs(u) < 0.28 && v in 0.08..0.3; val body = abs(u) < 0.7 && v in 0.3..1.0
                if (!neck && !body) 0
                else if (v < 0.14) sc(210, 170, 60, dim)
                else if (body && v in 0.52..0.82) (if (abs(u) < 0.2 && v in 0.6..0.74) sc(150, 98, 52, dim) else sc(240, 226, 190, dim))
                else sc(170, 212, 238, dim)
            }
            1 -> { // Hound: low quadruped, red eye
                val body = sq((u - 0.1) / 0.7) + sq((v - 0.55) / 0.32) < 1
                val head = sq((u + 0.72) / 0.26) + sq((v - 0.38) / 0.22) < 1
                val legs = v > 0.78 && (abs(u - 0.65) < 0.09 || abs(u - 0.35) < 0.09 || abs(u + 0.25) < 0.09 || abs(u + 0.5) < 0.09)
                if (sq((u + 0.82) / 0.07) + sq((v - 0.33) / 0.07) < 1) (if (fr) pack(120, 255, 150) else pack(255, 50, 30)) else if (body || head || legs) dark else 0
            }
            else -> { // Smiler: floating dark face, glowing eyes and grin
                val yc = 0.47 + 0.06 * (1 - sq(u / 0.62))
                val glow = sq((abs(u) - 0.34) / 0.16) + sq((v - 0.34) / 0.03) < 1 || (abs(u) < 0.62 && abs(v - yc) < 0.022)
                if (glow) (if (fr) pack(190, 255, 200) else pack(255, 250, 215)) else if (sq(u / 0.88) + sq((v - 0.4) / 0.22) < 1) pack(26, 22, 20) else 0
            }
        }
    }

    fun render(px: IntArray, g: Game, t: Double, others: Collection<DoubleArray>, hud: Boolean = true) {
        val lv = g.level; val d = levelDef(lv); val ts = texOf(lv); val tn = d.tint
        val x = g.x; val y = g.y; val a = g.a
        frame++; lpx = x; lpy = y; lant = d.lantern; pres = g.presence
        val hz = (H / 2 + sin(g.bob) * g.bobAmp * 3.0 + g.pitch * H * 0.5).toInt()
        val gl = if (sin(t * 2.7) * sin(t * 13.1) > 0.985) 0.75 else 1.0 // very rare, mild global flicker
        val dx = cos(a); val dy = sin(a)
        val pl = tan(Math.toRadians(Settings.fov / 2.0)); val vs = 0.66 / pl // camera plane length from FOV; vs keeps heights in proportion
        val plX = -dy * pl; val plY = dx * pl
        val fk = d.fogK * Settings.fogScale // Options > Fog scales every level's own fog

        // floor + ceiling (rows are split between threads)
        if (lv == LV_CITY) { // the city: a sky instead of a ceiling needs the azimuth of every column and the elevation of every row above the horizon
            for (xx in 0 until W) colU[xx] = (((atan2(dy + plY * camX[xx], dx + plX * camX[xx]) / (2 * PI)) * 2048).toInt()) and 2047
            for (yy in 0 until min(H, hz + 1)) rowV[yy] = (atan((hz - yy) / (H * vs)) / (PI / 2) * 255).toInt().coerceIn(0, 255)
        }
        par(H) { b, r0, r1 ->
            val lp = bands[b]
            if (lv == LV_CITY) { cityRows(px, lp, r0, r1, hz, vs, fk, gl, t, x, y, dx, dy, plX, plY, d, ts); return@par }
            for (yy in r0 until r1) {
                val dr = yy - hz
                if (dr == 0) { for (xx in 0 until W) px[yy * W + xx] = pack(8, 6, 4); continue }
                val rd = 0.5 * H * vs / abs(dr)
                val fog = 1.0 / (1 + rd * rd * fk)
                val isFloor = dr > 0
                val water = lv == LV_SUBLIMITY && isFloor
                for (xx in 0 until W) {
                    val cx = camX[xx]
                    val wx = x + (dx + plX * cx) * rd; val wy = y + (dy + plY * cx) * rd
                    val tx = ((wx - floor(wx)) * 64).toInt() and 63; val ty = ((wy - floor(wy)) * 64).toInt() and 63
                    val mt = if (lv == 0 && Manila.inside(wx, wy)) MANILA else ts
                    val l = if (mt === MANILA) 1.05 else lightAt(d, lp, wx, wy, t) * gl
                    px[yy * W + xx] = if (isFloor) mul(mt.floor[ty * 64 + tx], l * fog * 0.85 * (if (water) 0.85 + 0.2 * fsin(wx * 9 + t * 1.5) * fsin(wy * 9 + t * 1.1) else 1.0), tn)
                    else if (Math.floorMod(floor(wx).toInt(), d.period) == 1 && Math.floorMod(floor(wy).toInt(), d.period) == 1)
                        mul(mt.panel[ty * 64 + tx], l * max(fog, 0.6), tn)
                    else mul(mt.ceil[ty * 64 + tx], l * fog, tn)
                }
            }
        }

        // textured walls (DDA; columns are split between threads)
        par(W) { b, c0, c1 ->
            val lp = bands[b]
            if (lv == LV_CITY) { for (xx in c0 until c1) cityCol(px, xx, lp, x, y, dx + plX * camX[xx], dy + plY * camX[xx], hz, vs, fk, gl, t, d); return@par }
            for (xx in c0 until c1) {
                val cx = camX[xx]
                val rx = dx + plX * cx; val ry = dy + plY * cx
                var mapX = floor(x).toInt(); var mapY = floor(y).toInt()
                val ddx = abs(1 / rx); val ddy = abs(1 / ry)
                val stepX = if (rx < 0) -1 else 1; val stepY = if (ry < 0) -1 else 1
                var sdx = if (rx < 0) (x - mapX) * ddx else (mapX + 1 - x) * ddx
                var sdy = if (ry < 0) (y - mapY) * ddy else (mapY + 1 - y) * ddy
                var side = 0; var n = 0
                while (n++ < 64) {
                    if (sdx < sdy) { sdx += ddx; mapX += stepX; side = 0 } else { sdy += ddy; mapY += stepY; side = 1 }
                    if (isWall(lv, mapX, mapY)) break
                }
                val unst = unstable(lv, mapX, mapY)
                val wt = if (lv == 0 && Manila.inBox(mapX, mapY)) MANILA else ts
                val perp = (if (side == 0) sdx - ddx else sdy - ddy).coerceAtLeast(0.05)
                zb[xx] = perp
                val hx = x + perp * rx; val hy = y + perp * ry
                var tx = (((if (side == 0) hy else hx).let { it - floor(it) }) * 64).toInt() and 63
                if ((side == 0 && rx > 0) || (side == 1 && ry < 0)) tx = 63 - tx
                val br = (if (wt === MANILA) 1.05 else lightAt(d, lp, hx, hy, t) * gl) / (1 + perp * perp * fk) * (if (side == 1) 0.82 else 1.0)
                val lineH = (H * vs / perp).toInt().coerceAtLeast(1)
                val start = hz - lineH / 2
                val y0 = max(0, start); val y1 = min(H - 1, start + lineH)
                val step = 64.0 / lineH
                var tpos = (y0 - start) * step // texture row, stepped instead of divided per pixel
                if (unst) {
                    for (yy in y0..y1) { // cold, wobbling texture = not solid
                        val ty = min(63, tpos.toInt()); tpos += step
                        val tx2 = (tx + (sin(t * 11 + yy * 0.25) * 4).toInt()) and 63
                        val c0v = mul(wt.wall[ty * 64 + tx2], br * (0.85 + 0.3 * sin(t * 7 + xx * 0.1)), tn)
                        px[yy * W + xx] = pack((((c0v shr 16) and 255) * 3) / 4, ((c0v shr 8) and 255) + 25, (c0v and 255) + 60)
                    }
                } else {
                    val k = (br * 256).toInt().coerceIn(0, 640); val kr = k * tn[0]; val kg = k * tn[1]; val kb = k * tn[2]
                    for (yy in y0..y1) { val ty = min(63, tpos.toInt()); tpos += step; px[yy * W + xx] = mul2(wt.wall[ty * 64 + tx], kr, kg, kb) }
                }
            }
        }

        // sprites: other players on this level + this level's entities, far to near
        val items = ArrayList<DoubleArray>()
        for (o in others) if (o.size > 2 && o[2].toInt() == lv) {
            // isolated levels hide other players, even right next to you (except inside the Manila Room)
            if (d.isolated && !(lv == 0 && Manila.inside(x, y) && Manila.inside(o[0], o[1]))) continue
            items.add(doubleArrayOf(o[0], o[1], 0.0))
        }
        if (lv == LV_CITY) { // street lamps on the lamp lattice (the light pools come from lightAt)
            for (ky in floor((y - 14 - 0.5) / 8).toInt()..floor((y + 14 - 0.5) / 8).toInt()) for (kx in floor((x - 14 - 0.5) / 8).toInt()..floor((x + 14 - 0.5) / 8).toInt())
                if (City.lampOk(kx * 8, ky * 8)) items.add(doubleArrayOf(kx * 8 + 0.5, ky * 8 + 0.5, 9.0))
        }
        for (e in g.entities) items.add(if (e.passive && e.type in 1..4) doubleArrayOf(e.x, e.y, e.type.toDouble(), 2.0) else doubleArrayOf(e.x, e.y, e.type.toDouble()))
        for (sp in g.supplies) items.add(doubleArrayOf(sp.x + 0.5, sp.y + 0.5, if (sp.type == 1) 7.0 else 8.0))
        for (gh in g.ghosts) if (sin(t * 11 + gh.x * 3) > -0.4) items.add(doubleArrayOf(gh.x, gh.y, gh.type.toDouble(), 1.0)) // hallucinations flicker in and out
        items.sortByDescending { sq(it[0] - x) + sq(it[1] - y) }
        for (o in items) {
            val rx = o[0] - x; val ry = o[1] - y; val type = o[2].toInt()
            val inv = 1.0 / (plX * dy - dx * plY)
            val tx = inv * (dy * rx - dx * ry); val ty = inv * (-plY * rx + plX * ry)
            if (ty <= 0.2) continue
            val sx = (W / 2 * (1 + tx / ty)).toInt(); val full = H * vs / ty
            val hk = when (type) { 1 -> 0.6; 7 -> 0.2; 8 -> 0.26; 9 -> 1.7; else -> 0.9 }
            val wk = when (type) { 1 -> 1.7; 2 -> 0.5; 7 -> 0.9; 8 -> 0.55; 9 -> 0.25; else -> 0.42 }
            val sh = (full * hk).toInt().coerceAtLeast(2)
            val sw = (sh * wk).toInt().coerceAtLeast(2)
            val bottom = hz + (full / 2).toInt(); val top = bottom - sh
            val dim = (0.55 + 0.45 / (1 + ty * 0.3)).let { if (type >= 7) max(0.85, it) else if (o.size > 3 && o[3] == 1.0) it * 0.6 else it } // supplies stay easy to spot; ghosts are dim
            for (col in max(0, sx - sw / 2)..min(W - 1, sx + sw / 2)) {
                if (ty >= zb[col]) continue
                val u = (col - sx) / (sw / 2.0)
                for (row in max(0, top)..min(H - 1, bottom)) {
                    val c = sprite(type, u, (row - top).toDouble() / sh, dim, o.size > 3 && o[3] == 2.0)
                    if (c != 0) px[row * W + col] = c
                }
            }
        }

        // post: vignette, film grain, danger pulse, death fade (rows are split between threads, each with its own grain stream)
        val dgr = max(g.danger * g.danger * (0.5 + 0.5 * sin(t * (5 + 9 * g.danger))), g.halluc * 0.3 * (0.5 + 0.5 * sin(t * 1.9))) // danger pulse; hallucinations breathe red
        val rk = (dgr * 180).toInt()
        val fadeI = ((if (g.dead) (1 - g.deadT / 1.2).coerceIn(0.0, 1.0) else 1.0) * 256).toInt()
        val wash = ((g.noclip * g.noclip * 150) + g.flash * 255).toInt()
        val amp = 1 + (g.near * 2).toInt()
        val seed = grain
        par(H) { b, r0, r1 ->
            var s = seed xor (b * -1640531535)
            if (rk == 0 && wash == 0) for (i in r0 * W until r1 * W) { // usual case: no red pulse, no flash
                val c = px[i]; val v = (vig[i] * fadeI) shr 8
                s = s * 1664525 + 1013904223
                val nz = (((s ushr 28) and 15) - 8) * amp
                px[i] = pack((((c shr 16) and 255) * v shr 8) + nz, (((c shr 8) and 255) * v shr 8) + nz, ((c and 255) * v shr 8) + nz)
            } else for (i in r0 * W until r1 * W) {
                val c = px[i]; val v = (vig[i] * fadeI) shr 8
                s = s * 1664525 + 1013904223
                val nz = (((s ushr 28) and 15) - 8) * amp
                val red = ((256 - vig[i]) * rk) shr 8
                px[i] = pack((((c shr 16) and 255) * v shr 8) + nz + red + wash, (((c shr 8) and 255) * v shr 8) + nz + wash, ((c and 255) * v shr 8) + nz + wash)
            }
        }
        grain = seed * 1664525 + 1013904223
        val gk = max(g.noclip, g.near * 0.3) // reality tears while noclipping, and faintly when a not-quite-solid wall is near
        if (gk > 0.02) {
            var q = grain
            repeat((gk * 50).toInt() + 1) {
                q = q * 1664525 + 1013904223; val row = ((q ushr 8) and 0xFFFF) % H
                q = q * 1664525 + 1013904223; val sh = ((((q ushr 8) and 0xFF) - 128) * gk * 0.35).toInt()
                if (sh != 0) {
                    val base = row * W; val tmp = px.copyOfRange(base, base + W)
                    for (i in 0 until W) px[base + (((i + sh) % W) + W) % W] = tmp[i]
                }
            }
        }

        if (g.halluc > 0.04) { // hallucination: the picture sways from side to side
            val amp = g.halluc * 5.0
            for (row in 0 until H) {
                val sh = (sin(row * 0.11 + t * 2.3) * amp + sin(row * 0.047 - t * 1.1) * amp * 0.6).toInt()
                if (sh == 0) continue
                val base = row * W; System.arraycopy(px, base, rowTmp, 0, W)
                if (sh > 0) { System.arraycopy(rowTmp, 0, px, base + sh, W - sh); System.arraycopy(rowTmp, W - sh, px, base, sh) }
                else { val k = -sh; System.arraycopy(rowTmp, k, px, base, W - k); System.arraycopy(rowTmp, 0, px, base + W - k, k) }
            }
        }

        if (hud) drawHud(px, g, others)
    }

    // ---------- shape-only HUD (text is drawn by each platform) ----------
    private fun drawHud(px: IntArray, g: Game, others: Collection<DoubleArray>) {
        if (g.showMap && !levelDef(g.level).isolated) { // no maps on isolated levels
            val R = 8; val cs = 3; val size = (2 * R + 1) * cs
            val ox = W - size - 8; val oy = 8
            val fx = floor(g.x).toInt(); val fy = floor(g.y).toInt()
            rect(px, ox - 2, oy - 2, size + 4, size + 4, pack(0, 0, 0), 150)
            val ac = levelDef(g.level).accent
            for (cy in -R..R) for (cx in -R..R)
                if (isWall(g.level, fx + cx, fy + cy)) rect(px, ox + (cx + R) * cs, oy + (cy + R) * cs, cs, cs, pack((ac shr 16) and 255, (ac shr 8) and 255, ac and 255), 210)
            for (o in others) if (o.size > 2 && o[2].toInt() == g.level && abs(o[0] - g.x) < R && abs(o[1] - g.y) < R)
                rect(px, ox + ((o[0] - fx + R) * cs).toInt() - 1, oy + ((o[1] - fy + R) * cs).toInt() - 1, 3, 3, pack(230, 60, 40), 256)
            val pxx = ox + ((g.x - fx + R) * cs).toInt(); val pyy = oy + ((g.y - fy + R) * cs).toInt()
            for (k in 2..6) rect(px, pxx + (cos(g.a) * k).toInt(), pyy + (sin(g.a) * k).toInt(), 1, 1, pack(255, 255, 255), 200)
            rect(px, pxx - 1, pyy - 1, 3, 3, pack(255, 255, 255), 256)
        }
        if (g.stamina < 0.999) {
            val bw = 90; val bx = (W - bw) / 2; val by = H - 16
            rect(px, bx - 2, by - 2, bw + 4, 8, pack(0, 0, 0), 150)
            rect(px, bx, by, (bw * g.stamina).toInt(), 4, if (g.exhausted) pack(210, 60, 40) else pack(240, 225, 140), 240)
        }
    }
}
