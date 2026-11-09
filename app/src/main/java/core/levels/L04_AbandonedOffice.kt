package com.backrooms.game
// L04_AbandonedOffice.kt - Level 4: Abandoned Office. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Level 4: Abandoned Office. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level04AbandonedOffice : LevelSpec {
    override val def = LevelDef(4, "LEVEL 4", "ABANDONED OFFICE", "Dead cubicles, stained ceilings. Someone is still typing.", "SKIN-STEALERS", 3, 2, 4, 0.18, 0.34, 0.018, intArrayOf(256, 252, 240), 0xD8CFA8, danger = 1)
    override val noclip = intArrayOf(5, 6, 5, 3)
    override val flicker = doubleArrayOf(0.15, 0.22, 0.30)

    override fun wall(x: Int, y: Int): Boolean { // Abandoned Office: cubicle partitions with 1-cell openings
        val bx = Math.floorDiv(x, 4); val by = Math.floorDiv(y, 4)
        val lx = Math.floorMod(x, 4); val ly = Math.floorMod(y, 4)
        if (lx == 0 && ly == 0) return true
        if (lx == 0 && h(bx, by, 41) < 0.65 && ly != 3) return true
        if (ly == 0 && h(bx, by, 43) < 0.65 && lx != 3) return true
        return false
    }

    override fun textures(t: TexSet) = t.paint { x, y, i -> // Abandoned Office: stained beige walls, grey carpet, drop ceiling
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
}
