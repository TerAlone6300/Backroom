package com.backrooms.game
// L03_ElectricalStation.kt - Level 3: Electrical Station. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Internal id 3 = Backrooms Level 3, Electrical Station. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level03ElectricalStation : LevelSpec {
    override val def = LevelDef(3, "LEVEL 3", "ELECTRICAL STATION", "Humming transformers in a grid of machines. Hunters follow the sparks.", "HOUNDS", 1, 4, 4, 0.06, 0.50, 0.028, intArrayOf(230, 240, 256), 0x6FA8FF)
    override val noclip = intArrayOf(4, 2, 11, 12)
    override val flicker = doubleArrayOf(0.25, 0.35, 0.45)

    override fun wall(x: Int, y: Int): Boolean { // Electrical Station: 3x3 machines in a grid of 2-wide streets
        val bx = Math.floorDiv(x, 5); val by = Math.floorDiv(y, 5)
        return Math.floorMod(x, 5) in 1..3 && Math.floorMod(y, 5) in 1..3 && h(bx, by, 31) < 0.85
    }

    override fun textures(t: TexSet) = t.paint { x, y, i -> // Electrical Station: caution bands, conduit, diamond plate, strobing lamps
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
}
