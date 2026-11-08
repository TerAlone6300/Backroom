package com.backrooms.game
// Textures.kt - texture sets and the shared generator for data-driven levels. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Texture set of one level: 64x64 wall, floor, ceiling and lamp-panel images. `fill` paints them (see LevelSpec.textures). */
class TexSet(fill: (TexSet) -> Unit) {
    val wall = IntArray(4096); val floor = IntArray(4096); val ceil = IntArray(4096); val panel = IntArray(4096)
    fun c(r: Double, g: Double, b: Double, f: Double) = pack((r * f).toInt(), (g * f).toInt(), (b * f).toInt())
    /** Runs `body` for every texel (x, y, index); `this` is the set, so the body can write wall[i], floor[i], ceil[i], panel[i]. */
    fun paint(body: TexSet.(Int, Int, Int) -> Unit) { for (y in 0 until 64) for (x in 0 until 64) body(x, y, y * 64 + x) }
    init { fill(this) }
}

val TEX = Array(LEVELS.size) { lv -> TexSet { LEVEL_SPECS[lv].textures(it) } }

/** Colours and patterns for the data-driven levels (7..12). wp: wall pattern, fp: floor pattern. */
class TexSpec(val w: IntArray, val f: IntArray, val c: IntArray, val p: IntArray, val wp: Int, val fp: Int)

/** Fills a texture set from a TexSpec (levels 7 to 12 share this generator; `lv` seeds the noise). */
fun TexSet.fromSpec(lv: Int, sp: TexSpec) = paint { x, y, i ->
    var r = sp.w[0].toDouble(); var g = sp.w[1].toDouble(); var b = sp.w[2].toDouble()
    var f = (0.88 + 0.24 * h(x, y, 201 + lv)) * (0.94 + 0.12 * h(x / 4, y / 4, 202 + lv))
    when (sp.wp) {
        2 -> if (x % 32 == 0 || y % 32 == 0) f *= 0.8
        3 -> { // apartment block: concrete seams and lit windows
            if (x % 32 == 0) f *= 0.85
            if (x in 18..46 && y in 10..34) {
                if (x in 20..44 && y in 12..32) { r = 255.0; g = 170.0; b = 80.0; f = 1.0 + 0.2 * (1 - (y - 12) / 20.0) } else { r = 50.0; g = 46.0; b = 44.0 }
            }
        }
        5 -> { // triangle outlines
            val tx = x % 32; val ty = y % 32
            if (ty in 7..25 && abs(abs(tx - 16) - (ty - 6) / 2) < 1) f *= 0.55
        }
        8 -> { // framed pictures
            if (x in 10..53 && y in 8..42) {
                if (x in 13..50 && y in 11..39) { val v = 70 + 110 * h(x / 3, y / 3, 206); r = v; g = v; b = v; f = 1.0 } else { r = 40.0; g = 40.0; b = 40.0 }
            }
        }
    }
    wall[i] = c(r, g, b, f)
    var ff = 0.85 + 0.3 * h(x, y, 211 + lv)
    when (sp.fp) {
        1 -> ff *= 0.88 + 0.16 * sin((y + 3 * sin(x * 0.25)) * 0.7)           // sand ripples
        3 -> if (x % 16 == 0 || y % 16 == 0) ff *= 0.7                         // tile grout
    }
    floor[i] = c(sp.f[0].toDouble(), sp.f[1].toDouble(), sp.f[2].toDouble(), ff)
    var tf = 0.9 + 0.15 * h(x, y, 221 + lv); if (x < 2 || y < 2) tf *= 0.75
    ceil[i] = c(sp.c[0].toDouble(), sp.c[1].toDouble(), sp.c[2].toDouble(), tf)
    panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(sp.p[0] * 3 / 4, sp.p[1] * 3 / 4, sp.p[2] * 3 / 4) else pack(sp.p[0], sp.p[1], sp.p[2])
}
