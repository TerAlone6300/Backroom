package com.backrooms.game
// L37_Sublimity.kt - Level 37: Sublimity. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Level 37: Sublimity. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level37Sublimity : LevelSpec {
    override val def = LevelDef(37, "LEVEL 37", "SUBLIMITY", "Warm tiled halls and still water. Finally, peace.", "NONE", 0, 0, 5, 0.55, 0.12, 0.006, intArrayOf(236, 250, 256), 0x6FD8E8, safe = true, danger = 1)
    override val noclip = intArrayOf(75, 5, 0, 11)
    override val flicker = doubleArrayOf(0.02, 0.05, 0.05)

    override fun wall(x: Int, y: Int): Boolean { // Poolrooms: bright halls, columns, wide archways
        if (Math.floorMod(x, 7) == 0 && Math.floorMod(y, 7) == 0) return true
        val bx = Math.floorDiv(x, 14); val by = Math.floorDiv(y, 14)
        val lx = Math.floorMod(x, 14); val ly = Math.floorMod(y, 14)
        val gv = 2 + (h(bx, by, 62) * 4).toInt(); val gh = 2 + (h(bx, by, 64) * 4).toInt()
        if (lx == 0 && h(bx, by, 61) < 0.6 && ly !in gv..gv + 5) return true
        if (ly == 0 && h(bx, by, 63) < 0.6 && lx !in gh..gh + 5) return true
        return false
    }

    override fun textures(t: TexSet) = t.paint { x, y, i -> // Poolrooms: glazed tile, blue band, still water, skylights
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
}
