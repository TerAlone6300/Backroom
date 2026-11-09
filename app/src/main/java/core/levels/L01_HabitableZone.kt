package com.backrooms.game
// L01_HabitableZone.kt - Level 1: Habitable Zone. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Level 1: Habitable Zone. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level01HabitableZone : LevelSpec {
    override val def = LevelDef(1, "LEVEL 1", "HABITABLE ZONE", "Cold concrete halls. Something is breathing in the dark.", "HOUNDS", 1, 3, 5, 0.17, 0.30, 0.018, intArrayOf(232, 250, 244), 0x9FD4C8, danger = 1)
    override val noclip = intArrayOf(2, 2, 2, 2)
    override val flicker = doubleArrayOf(0.20, 0.28, 0.40)

    override fun wall(x: Int, y: Int): Boolean { // Habitable Zone: huge hall, pillar grid, long concrete walls
        if (Math.floorMod(x, 5) == 0 && Math.floorMod(y, 5) == 0) return true
        val bx = Math.floorDiv(x, 10); val by = Math.floorDiv(y, 10)
        val lx = Math.floorMod(x, 10); val ly = Math.floorMod(y, 10)
        val gv = 1 + (h(bx, by, 12) * 5).toInt(); val gh = 1 + (h(bx, by, 14) * 5).toInt()
        if (lx == 0 && h(bx, by, 11) < 0.45 && ly !in gv..gv + 3) return true
        if (ly == 0 && h(bx, by, 13) < 0.45 && lx !in gh..gh + 3) return true
        return false
    }

    override fun textures(t: TexSet) = t.paint { x, y, i -> // concrete, hazard stripe, wet floor with parking lines, tube lights
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
}
