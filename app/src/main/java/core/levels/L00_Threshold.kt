package com.backrooms.game
// L00_Threshold.kt - Level 0: Threshold. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Level 0: Threshold. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level00Threshold : LevelSpec {
    override val def = LevelDef(0, "LEVEL 0", "THRESHOLD", "Endless yellow rooms. Humming lights. Nobody else can see you.", "NONE", 0, 0, 3, 0.18, 0.40, 0.015, intArrayOf(256, 256, 256), 0xF4E482, isolated = true, danger = 1)
    override val noclip = intArrayOf(1, 1, 1, 1)
    override val flicker = doubleArrayOf(0.12, 0.20, 0.27)

    override fun wall(x: Int, y: Int): Boolean { // The Lobby: 6x6 rooms, 2-wide doors, pillars
        if (Manila.inClear(x, y)) return Manila.wall(x, y)
        val bx = Math.floorDiv(x, 6); val by = Math.floorDiv(y, 6)
        val lx = Math.floorMod(x, 6); val ly = Math.floorMod(y, 6)
        if (lx == 0 && ly == 0) return true
        val dv = 1 + (h(bx, by, 2) * 3).toInt(); val dh = 1 + (h(bx, by, 4) * 3).toInt()
        if (lx == 0 && h(bx, by, 1) < 0.7 && ly !in dv..dv + 1) return true
        if (ly == 0 && h(bx, by, 3) < 0.7 && lx !in dh..dh + 1) return true
        return false
    }

    override fun keepsMaze(x: Int, y: Int) = Manila.inClear(x, y) // the Manila Room stays untouched by long hallways

    override fun textures(t: TexSet) = t.paint { x, y, i -> // yellow wallpaper, damp carpet, drop ceiling, fluorescent panel
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
}

/** The Manila Room: the one and only room of Level 0 where isolation does not apply. */
object Manila {
    const val CX = 1313; const val CY = -2024 // 9x9 interior starting here, door on the south side; unique in the endless level
    fun inside(x: Double, y: Double) = x >= CX && x < CX + 9 && y >= CY && y < CY + 9
    fun inBox(ix: Int, iy: Int) = ix in CX - 1..CX + 9 && iy in CY - 1..CY + 9
    fun inClear(ix: Int, iy: Int) = ix in CX - 4..CX + 12 && iy in CY - 4..CY + 12 // room plus a free moat around it
    fun wall(ix: Int, iy: Int): Boolean {
        if (!inBox(ix, iy)) return false
        val edge = ix == CX - 1 || ix == CX + 9 || iy == CY - 1 || iy == CY + 9
        return edge && !(iy == CY + 9 && ix in CX + 3..CX + 5)
    }
}

/** Texture set of the Manila Room: everything the colour of old folders. */
val MANILA = TexSet { t ->
    t.paint { x, y, i -> // Manila Room: everything the colour of old folders
        val f = 0.9 + 0.2 * h(x, y, 301) * (if (h(x, y / 9, 302) < 0.1) 0.9 else 1.0)
        wall[i] = c(226.0, 204.0, 150.0, f); floor[i] = c(214.0, 192.0, 138.0, f); ceil[i] = c(230.0, 210.0, 158.0, f)
        panel[i] = if (x < 5 || x > 58 || y < 5 || y > 58) pack(200, 180, 130) else pack(255, 240, 200)
    }
}
