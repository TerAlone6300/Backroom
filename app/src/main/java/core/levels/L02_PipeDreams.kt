package com.backrooms.game
// L02_PipeDreams.kt - Level 2: Pipe Dreams. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Level 2: Pipe Dreams. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level02PipeDreams : LevelSpec {
    override val def = LevelDef(2, "LEVEL 2", "PIPE DREAMS", "Hot, rusted tunnels. Don't look away.", "SMILERS", 2, 2, 4, 0.07, 0.45, 0.030, intArrayOf(256, 205, 150), 0xF0A050, danger = 3)
    override val noclip = intArrayOf(3, 4, 3, 1)
    override val flicker = doubleArrayOf(0.15, 0.25, 0.32)

    override fun wall(x: Int, y: Int): Boolean { // Pipe Dreams: tight 2-wide tunnels, 1-cell doors
        val bx = Math.floorDiv(x, 3); val by = Math.floorDiv(y, 3)
        val lx = Math.floorMod(x, 3); val ly = Math.floorMod(y, 3)
        if (lx == 0 && ly == 0) return true
        if (lx == 0 && h(bx, by, 21) < 0.78 && ly != 1 + (h(bx, by, 22) * 2).toInt()) return true
        if (ly == 0 && h(bx, by, 23) < 0.78 && lx != 1 + (h(bx, by, 24) * 2).toInt()) return true
        return false
    }

    override fun textures(t: TexSet) = t.paint { x, y, i -> // rusty pipes, metal grating, caged amber lamps
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
}
