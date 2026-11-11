package com.backrooms.game
// L02_AbandonedUtilityHalls.kt - Level 2: Abandoned Utility Halls. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** Level 2: Abandoned Utility Halls (once an industrial hub, then two years of blackout). Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level02AbandonedUtilityHalls : LevelSpec {
    override val def = LevelDef(2, "LEVEL 2", "ABANDONED UTILITY HALLS", "Endless maintenance tunnels, long since left to rot.", "SMILERS, CLUMPS, FACELINGS, MORE", 2, 11, 4, 0.07, 0.45, 0.030,
        intArrayOf(240, 248, 245), 0xA8C8C0, cast = intArrayOf(4, 4, 4, 6, 2, 5, 6, 5, 6, 2, 5), danger = 3) // a Faceling group, Clumps, Smilers, Deathmoths (Jerry lives in his own room)
    override val noclip = intArrayOf(3, 4, 3, 1)
    override val flicker = doubleArrayOf(0.15, 0.25, 0.32)

    // Blackouts: 24x24 zones where the power never came back
    override val zones = true
    override fun darkness(x: Int, y: Int): Double {
        val c = jerryRoom(Math.floorDiv(x, JERRY_SECTOR), Math.floorDiv(y, JERRY_SECTOR))
        if (c != null && max(abs(x - c[0]), abs(y - c[1])) <= 8) return 1.0 // Jerry's room keeps its lights
        return if (h(Math.floorDiv(x, 24), Math.floorDiv(y, 24), 320) < 0.30) 0.22 else 1.0
    }

    /** Centre [x, y] of the cult room of the 96x96 sector (sx, sy), or null: Jerry lives there with his cult. */
    fun jerryRoom(sx: Int, sy: Int): IntArray? {
        if (h(sx, sy, 310) >= 0.5) return null
        return intArrayOf(sx * JERRY_SECTOR + 24 + (h(sx, sy, 311) * 48).toInt(), sy * JERRY_SECTOR + 24 + (h(sx, sy, 312) * 48).toInt())
    }
    /** 0 = maze, 1 = room wall, 2 = room floor: an 11x11 chamber (door in the south wall) inside a cleared 15x15 square. */
    private fun roomCell(x: Int, y: Int): Int {
        val c = jerryRoom(Math.floorDiv(x, JERRY_SECTOR), Math.floorDiv(y, JERRY_SECTOR)) ?: return 0
        val dx = x - c[0]; val dy = y - c[1]; val m = max(abs(dx), abs(dy))
        return if (m > 7) 0 else if (m != 5) 2 else if (dy == 5 && abs(dx) <= 1) 2 else 1
    }

    override fun wall(x: Int, y: Int): Boolean { // tight 2-wide tunnels, 1-cell doors, and now and then a cult room
        when (roomCell(x, y)) { 1 -> return true; 2 -> return false }
        val bx = Math.floorDiv(x, 3); val by = Math.floorDiv(y, 3)
        val lx = Math.floorMod(x, 3); val ly = Math.floorMod(y, 3)
        if (lx == 0 && ly == 0) return true
        if (lx == 0 && h(bx, by, 21) < 0.78 && ly != 1 + (h(bx, by, 22) * 2).toInt()) return true
        if (ly == 0 && h(bx, by, 23) < 0.78 && lx != 1 + (h(bx, by, 24) * 2).toInt()) return true
        return false
    }

    override fun textures(t: TexSet) = t.paint { x, y, i -> // concrete, pipe runs, metal grating, cold fluorescent tubes
        var r = 118.0; var g = 120.0; var b = 118.0
        var f = (0.8 + 0.4 * h(x, y, 61)) * (0.92 + 0.16 * h(x / 3, y / 3, 62))
        if (y in 8..15) { r = 96.0; g = 108.0; b = 112.0; f = (0.55 + 0.65 * sin(PI * (y - 8) / 8.0)) * (0.9 + 0.2 * h(x, y, 63)) }
        else if (y in 34..40) { r = 140.0; g = 138.0; b = 130.0; f = (0.5 + 0.7 * sin(PI * (y - 34) / 6.0)) * (0.9 + 0.2 * h(x, y, 64)) }
        else {
            if (y in 16..32 && h(x / 2, 0, 65) < 0.2) { r = 120.0; g = 80.0; b = 56.0; f *= 0.8 } // rust streaks
            if (x % 16 == 3 && (y % 16 == 2 || y % 16 == 12)) f *= 1.5
        }
        if (y in 6..17 && x % 32 < 3) { r = 70.0; g = 74.0; b = 76.0; f = 0.9 }
        if (y >= 58) f *= 0.5
        wall[i] = c(r, g, b, f)
        floor[i] = if (x % 8 == 0 || y % 8 == 0) c(22.0, 22.0, 24.0, 1.0) else c(70.0, 70.0, 72.0, 0.8 + 0.4 * h(x, y, 66))
        ceil[i] = if (y in 22..34) c(100.0, 106.0, 108.0, (0.4 + 0.8 * sin(PI * (y - 22) / 12.0)) * (0.9 + 0.2 * h(x, y, 67)))
                  else c(88.0, 90.0, 90.0, 0.8 + 0.3 * h(x, y, 68))
        panel[i] = if (x % 8 == 0 || y % 8 == 0 || x < 4 || x > 59 || y < 4 || y > 59) pack(120, 125, 125) else pack(235, 244, 238)
    }
}

const val LV_UTILITY = 2        // Level 2: Abandoned Utility Halls
const val JERRY_SECTOR = 96     // one cult room per sector at most
