package com.backrooms.game
// Textures.kt - procedural 64x64 textures, one set per level. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

// ---------- Procedural textures (64x64), one set per level ----------

/** Colours and patterns for the data-driven levels (7..12). wp: wall pattern, fp: floor pattern. */
class TexSpec(val w: IntArray, val f: IntArray, val c: IntArray, val p: IntArray, val wp: Int, val fp: Int)
val SPECS = listOf(
    TexSpec(intArrayOf(190, 170, 90), intArrayOf(100, 84, 44), intArrayOf(180, 170, 140), intArrayOf(255, 255, 200), 0, 0),   // 7  Lights Out
    TexSpec(intArrayOf(222, 196, 138), intArrayOf(224, 192, 122), intArrayOf(205, 185, 135), intArrayOf(255, 246, 215), 2, 1), // 8  Liminality (sand)
    TexSpec(intArrayOf(150, 150, 150), intArrayOf(95, 95, 95), intArrayOf(135, 135, 135), intArrayOf(235, 235, 235), 8, 3),    // 9  Grayscale Halls
    TexSpec(intArrayOf(130, 126, 120), intArrayOf(88, 84, 80), intArrayOf(100, 96, 92), intArrayOf(255, 160, 90), 3, 0),      // 10 City of Dusk
    TexSpec(intArrayOf(200, 200, 206), intArrayOf(168, 168, 176), intArrayOf(210, 210, 216), intArrayOf(255, 255, 255), 5, 3), // 11 Threshold
    TexSpec(intArrayOf(104, 100, 114), intArrayOf(58, 58, 64), intArrayOf(16, 20, 40), intArrayOf(255, 236, 170), 0, 0),         // 12 The City (Level 11): drawn by City, only the asphalt grain is used
)

class TexSet(lv: Int) {
    val wall = IntArray(4096); val floor = IntArray(4096); val ceil = IntArray(4096); val panel = IntArray(4096)
    private fun c(r: Double, g: Double, b: Double, f: Double) = pack((r * f).toInt(), (g * f).toInt(), (b * f).toInt())

    init {
        for (y in 0 until 64) for (x in 0 until 64) {
            val i = y * 64 + x
            when (lv) {
                0 -> { // yellow wallpaper, damp carpet, drop ceiling, fluorescent panel
                    var r = 214.0; var g = 190.0; var b = 98.0
                    var f = 0.92 + 0.16 * h(x, y, 21)
                    if (x % 16 < 2) { r *= 0.88; g *= 0.88; b *= 0.8 }
                    if (abs(x % 16 - 8) + abs(y % 16 - 8) <= 3) f *= 0.93
                    val drip = h(x / 3, 0, 22)
                    if (drip < 0.3) f *= 1 - 0.3 * (y / 64.0).pow(1.5) * (1 - drip)
                    if (y >= 56) { r = 92.0; g = 62.0; b = 34.0; f = 0.9 + 0.2 * h(x, y, 23); if (y == 56) f = 1.3; if (y >= 62) f *= 0.8 }
                    wall[i] = c(r, g, b, f)
                    var cf = (0.82 + 0.3 * h(x, y, 31)) * (0.9 + 0.2 * h(x / 2, y / 2, 32))
                    if (x == 0 || y == 0) cf *= 0.85
                    floor[i] = c(122.0, 98.0, 50.0, cf)
                    var tf = 0.95 + 0.1 * h(x, y, 41)
                    if (h(x, y, 42) < 0.05) tf *= 0.75
                    if (x < 2 || y < 2) tf *= 0.72
                    ceil[i] = c(222.0, 214.0, 176.0, tf)
                    panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(196, 196, 186) else if (x % 16 == 0) pack(235, 235, 222) else pack(255, 255, 244)
                }
                1 -> { // concrete, hazard stripe, wet floor with parking lines, tube lights
                    var r = 152.0; var g = 150.0; var b = 142.0
                    var f = (0.85 + 0.3 * h(x, y, 51)) * (0.9 + 0.2 * h(x / 4, y / 4, 52))
                    if (h(x / 2, y / 6, 53) < 0.03) f *= 0.6
                    if (h(x / 2, 0, 54) < 0.25) f *= 1 - 0.35 * (y / 64.0)
                    if (y in 40..45) { if (((x + y) / 6) % 2 == 0) { r = 30.0; g = 30.0; b = 28.0 } else { r = 200.0; g = 176.0; b = 60.0 }; f = 0.95 + 0.1 * h(x, y, 55) }
                    if (y >= 58) { r = 86.0; g = 86.0; b = 84.0 }
                    wall[i] = c(r, g, b, f)
                    var fr = 112.0; var fg = 114.0; var fb = 110.0
                    var ff = (0.8 + 0.35 * h(x, y, 56)) * (0.9 + 0.2 * h(x / 4, y / 4, 57))
                    if (h(x / 10, y / 10, 58) < 0.18) { ff *= 0.7; fb *= 1.2 }
                    if (x < 2) { fr = 200.0; fg = 176.0; fb = 60.0; ff *= 0.85 }
                    floor[i] = c(fr, fg, fb, ff)
                    var cf = 0.85 + 0.25 * h(x, y, 59); if (y < 6 || y > 57) cf *= 0.6
                    ceil[i] = c(98.0, 98.0, 94.0, cf)
                    panel[i] = if (x < 4 || x > 59 || y < 20 || y > 43) pack(120, 126, 120) else pack(228, 252, 242)
                }
                2 -> { // rusty pipes, metal grating, caged amber lamps
                    var r = 82.0; var g = 96.0; var b = 92.0
                    var f = (0.85 + 0.3 * h(x, y, 61)) * (0.92 + 0.16 * h(x / 3, y / 3, 62))
                    if (y in 8..15) { r = 160.0; g = 92.0; b = 52.0; f = (0.55 + 0.65 * sin(PI * (y - 8) / 8.0)) * (0.9 + 0.2 * h(x, y, 63)) }
                    else if (y in 34..40) { r = 128.0; g = 132.0; b = 132.0; f = (0.5 + 0.7 * sin(PI * (y - 34) / 6.0)) * (0.9 + 0.2 * h(x, y, 64)) }
                    else {
                        if (y in 16..32 && h(x / 2, 0, 65) < 0.2) { r = 110.0; g = 62.0; b = 34.0; f *= 0.8 }
                        if (x % 16 == 3 && (y % 16 == 2 || y % 16 == 12)) f *= 1.5
                    }
                    if (y in 6..17 && x % 32 < 3) { r = 90.0; g = 70.0; b = 60.0; f = 0.9 }
                    if (y >= 58) f *= 0.5
                    wall[i] = c(r, g, b, f)
                    floor[i] = if (x % 8 == 0 || y % 8 == 0) c(24.0, 24.0, 24.0, 1.0) else c(46.0, 38.0, 30.0, 0.8 + 0.4 * h(x, y, 66))
                    ceil[i] = if (y in 22..34) c(110.0, 70.0, 45.0, (0.4 + 0.8 * sin(PI * (y - 22) / 12.0)) * (0.9 + 0.2 * h(x, y, 67)))
                              else c(58.0, 62.0, 60.0, 0.8 + 0.3 * h(x, y, 68))
                    panel[i] = if (x % 8 == 0 || y % 8 == 0 || x < 4 || x > 59 || y < 4 || y > 59) pack(120, 70, 24) else pack(255, 176, 72)
                }
                3 -> { // Electrical Station: caution bands, conduit, diamond plate, strobing lamps
                    var r = 98.0; var g = 106.0; var b = 120.0
                    var f = (0.85 + 0.3 * h(x, y, 71)) * (0.92 + 0.16 * h(x / 4, y / 4, 72))
                    if (y in 12..19) { if (((x + y) / 5) % 2 == 0) { r = 28.0; g = 28.0; b = 30.0 } else { r = 230.0; g = 190.0; b = 40.0 }; f = 0.95 + 0.1 * h(x, y, 73) }
                    else if (y in 44..50) { r = 40.0; g = 44.0; b = 52.0; f = 0.55 + 0.9 * sin(PI * (y - 44) / 6.0) }
                    else if (x % 32 == 5 && y % 16 in 2..3) f *= 1.6
                    if (y >= 58) f *= 0.55
                    wall[i] = c(r, g, b, f)
                    var pf = 0.8 + 0.3 * h(x, y, 74)
                    if ((x + y) % 16 < 2 || (x - y + 64) % 16 < 2) pf *= 1.5
                    floor[i] = c(52.0, 56.0, 64.0, pf)
                    ceil[i] = if (x % 16 < 3) c(30.0, 32.0, 36.0, 1.0) else c(60.0, 66.0, 76.0, 0.8 + 0.3 * h(x, y, 75))
                    panel[i] = if (x < 6 || x > 57 || y < 14 || y > 49) pack(40, 46, 60) else pack(205, 228, 255)
                }
                4 -> { // Abandoned Office: stained beige walls, grey carpet, drop ceiling
                    var r = 206.0; var g = 198.0; var b = 172.0
                    var f = 0.9 + 0.2 * h(x, y, 81)
                    if (y >= 34) { r = 170.0; g = 160.0; b = 135.0 }
                    if (y in 32..34) { r = 120.0; g = 110.0; b = 90.0 }
                    if (h(x / 6, y / 6, 82) < 0.1) f *= 0.82
                    if (y >= 58) { r = 70.0; g = 62.0; b = 50.0 }
                    wall[i] = c(r, g, b, f)
                    var cf = 0.85 + 0.3 * h(x, y, 83); if (x == 0 || y == 0) cf *= 0.8
                    floor[i] = c(92.0, 98.0, 108.0, cf)
                    var tf = 0.95 + 0.1 * h(x, y, 84); if (h(x / 8, y / 8, 85) < 0.12) tf *= 0.8; if (x < 2 || y < 2) tf *= 0.75
                    ceil[i] = c(232.0, 230.0, 220.0, tf)
                    panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(190, 190, 182) else pack(250, 252, 246)
                }
                5 -> { // Terror Hotel: damask wallpaper, wood wainscot, red carpet, wall sconces
                    var r = 112.0; var g = 26.0; var b = 34.0
                    var f = 0.9 + 0.2 * h(x, y, 91)
                    if (abs(x % 16 - 8) + abs(y % 16 - 8) <= 4) { r = 150.0; g = 104.0; b = 56.0; f *= 0.85 }
                    if (y >= 42) { r = 84.0; g = 50.0; b = 28.0; f = 0.85 + 0.25 * h(x, y, 92); if (y == 42 || x % 16 == 0) f *= 0.6 }
                    wall[i] = c(r, g, b, f)
                    var fr = 104.0; var fg = 24.0; var fb = 30.0; val ff = 0.8 + 0.3 * h(x, y, 93)
                    if (x < 6 || x > 57) { fr = 170.0; fg = 130.0; fb = 60.0 } else if ((x + y) % 12 < 2) { fr = 150.0; fg = 100.0; fb = 50.0 }
                    floor[i] = c(fr, fg, fb, ff)
                    ceil[i] = c(74.0, 56.0, 44.0, 0.8 + 0.3 * h(x, y, 94))
                    panel[i] = if (x < 12 || x > 51 || y < 12 || y > 51) pack(60, 40, 24) else pack(255, 205, 130)
                }
                6 -> { // Poolrooms: glazed tile, blue band, still water, skylights
                    var r = 226.0; var g = 233.0; var b = 236.0
                    var f = 0.95 + 0.1 * h(x, y, 101)
                    if (x % 16 == 0 || y % 16 == 0) { r = 186.0; g = 198.0; b = 204.0 }
                    if (y in 44..52) { r = 64.0; g = 156.0; b = 196.0; f = 0.95 + 0.1 * h(x, y, 102); if (x % 16 == 0 || y % 16 == 0) f *= 0.8 }
                    if (y >= 58) { r = 96.0; g = 150.0; b = 176.0 }
                    wall[i] = c(r, g, b, f)
                    var wr = 70.0; var wg = 170.0; var wb = 205.0
                    if (x % 16 == 0 || y % 16 == 0) { wr = 40.0; wg = 120.0; wb = 150.0 }
                    floor[i] = c(wr, wg, wb, 0.9 + 0.2 * h(x, y, 103))
                    ceil[i] = c(232.0, 238.0, 240.0, (0.95 + 0.1 * h(x, y, 104)) * (if (x % 32 < 2 || y % 32 < 2) 0.85 else 1.0))
                    panel[i] = if (x < 4 || x > 59 || y < 4 || y > 59) pack(200, 210, 214) else pack(246, 253, 255)
                }
                99 -> { // Manila Room: everything the colour of old folders
                    val f = 0.9 + 0.2 * h(x, y, 301) * (if (h(x, y / 9, 302) < 0.1) 0.9 else 1.0)
                    wall[i] = c(226.0, 204.0, 150.0, f); floor[i] = c(214.0, 192.0, 138.0, f); ceil[i] = c(230.0, 210.0, 158.0, f)
                    panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(200, 180, 130) else pack(255, 240, 200)
                }
                else -> { // data-driven levels
                    val sp = SPECS[lv - 7]
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
            }
        }
    }
}

val TEX = Array(LEVELS.size) { TexSet(it) }

val MANILA = TexSet(99)
