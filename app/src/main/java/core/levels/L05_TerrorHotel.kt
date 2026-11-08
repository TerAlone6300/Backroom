package com.backrooms.game
// L05_TerrorHotel.kt - Level 5: Terror Hotel. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Internal id 5 = Backrooms Level 5, Terror Hotel. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level05TerrorHotel : LevelSpec {
    override val def = LevelDef(5, "LEVEL 5", "TERROR HOTEL", "Red carpet, endless corridors, a door for every secret.", "SMILERS", 2, 3, 4, 0.08, 0.45, 0.026, intArrayOf(256, 225, 190), 0xD04A4A)
    override val noclip = intArrayOf(6, 4, 10, 2)
    override val flicker = doubleArrayOf(0.18, 0.30, 0.38)

    override fun wall(x: Int, y: Int): Boolean { // Terror Hotel: long corridors, rooms with a door each
        val bx = Math.floorDiv(x, 8)
        val lx = Math.floorMod(x, 8); val ly = Math.floorMod(y, 7)
        if (Math.floorMod(bx, 3) == 0 && lx < 2) return false // vertical corridor
        if (ly < 2) return false                               // horizontal corridor
        if (ly == 2) return lx !in 4..5                        // front wall with a door
        if (ly == 6) return true                               // back wall
        return lx == 2                                         // partition between rooms
    }

    override fun textures(t: TexSet) = t.paint { x, y, i -> // Terror Hotel: damask wallpaper, wood wainscot, red carpet, wall sconces
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
}
