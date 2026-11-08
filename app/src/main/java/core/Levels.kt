package com.backrooms.game
// Levels.kt - level definitions and the Manila Room. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).

// ---------- Levels ----------
/** eType: 0 none, 1 Hound (chases on sight), 2 Smiler (moves when unseen), 3 Skin-Stealer (looks like a player, closes in),
 *  4 Faceling (a person with no face; in Level 11 it follows you around).
 *  lantern: personal light that follows you. isolated: players cannot see each other on this level. */
class LevelDef(val id: Int, val label: String, val name: String, val blurb: String, val entities: String, val eType: Int, val eCount: Int,
               val period: Int, val ambient: Double, val falloff: Double, val fogK: Double, val tint: IntArray, val accent: Int,
               val lantern: Double = 0.0, val isolated: Boolean = false,
               val calm: Double = 1.0, val cast: IntArray = intArrayOf(),
               val safe: Boolean = false, val lamp0: Double = 1.5) { // lamp0: where the lamp lattice starts; safe: rated Safe & Secure (Explore noclips favour these); calm: Level 11 Effect (hostiles notice you less); cast: entity types spawned (empty = eCount x eType)
    val ip = 1.0 / period
}

val LEVELS = listOf(
    LevelDef(0, "LEVEL 0", "THE LOBBY", "Endless yellow rooms. Humming lights. Nobody else can see you.", "NONE", 0, 0, 3, 0.18, 0.40, 0.015, intArrayOf(256, 256, 256), 0xF4E482, isolated = true),
    LevelDef(1, "LEVEL 1", "HABITABLE ZONE", "Cold concrete halls. Something is breathing in the dark.", "HOUNDS", 1, 3, 5, 0.17, 0.30, 0.018, intArrayOf(232, 250, 244), 0x9FD4C8),
    LevelDef(2, "LEVEL 2", "PIPE DREAMS", "Hot, rusted tunnels. Don't look away.", "SMILERS", 2, 2, 4, 0.07, 0.45, 0.030, intArrayOf(256, 205, 150), 0xF0A050),
    LevelDef(3, "LEVEL 3", "ELECTRICAL STATION", "Humming transformers in a grid of machines. Hunters follow the sparks.", "HOUNDS", 1, 4, 4, 0.06, 0.50, 0.028, intArrayOf(230, 240, 256), 0x6FA8FF),
    LevelDef(4, "LEVEL 4", "ABANDONED OFFICE", "Dead cubicles, stained ceilings. Someone is still typing.", "SKIN-STEALERS", 3, 2, 4, 0.18, 0.34, 0.018, intArrayOf(256, 252, 240), 0xD8CFA8),
    LevelDef(5, "LEVEL 5", "TERROR HOTEL", "Red carpet, endless corridors, a door for every secret.", "SMILERS", 2, 3, 4, 0.08, 0.45, 0.026, intArrayOf(256, 225, 190), 0xD04A4A),
    LevelDef(6, "LEVEL 37", "THE POOLROOMS", "Warm tiled halls and still water. Finally, peace.", "NONE", 0, 0, 5, 0.55, 0.12, 0.006, intArrayOf(236, 250, 256), 0x6FD8E8, safe = true),
    LevelDef(7, "LEVEL 6", "LIGHTS OUT", "Pitch-dark halls. Your lantern is all you have.", "HOUNDS", 1, 2, 3, 0.015, 0.50, 0.030, intArrayOf(256, 246, 214), 0xE0C060, lantern = 1.1),
    LevelDef(8, "LEVEL 75", "LIMINALITY", "Sand drifts through quiet corridors. You have been here before.", "NONE", 0, 0, 5, 0.42, 0.12, 0.010, intArrayOf(256, 244, 215), 0xE6C98A, safe = true),
    LevelDef(9, "LEVEL 139", "GRAYSCALE HALLS", "Colourless concrete. The paintings look back.", "NONE", 0, 0, 4, 0.14, 0.38, 0.016, intArrayOf(236, 236, 236), 0xBEBEBE, safe = true),
    LevelDef(10, "LEVEL 182", "CITY OF DUSK", "A sunset that never sets over empty concrete rooms.", "NONE", 0, 0, 4, 0.22, 0.18, 0.014, intArrayOf(256, 200, 150), 0xFF9A5A, safe = true),
    LevelDef(11, "LEVEL 201", "THE THRESHOLD", "A transit between depths. No one else can reach you here.", "NONE", 0, 0, 4, 0.25, 0.30, 0.016, intArrayOf(236, 242, 256), 0xC8D0E8, isolated = true, safe = true),
    LevelDef(12, "LEVEL 11", "THE CITY THAT NEVER SLEEPS", "Endless streets, lit windows. Hostile things keep their distance.", "SMILERS · HOUNDS · FACELINGS", 4, 8, 8, 0.27, 0.15, 0.0012, intArrayOf(240, 238, 256), 0xFFC857,
        calm = 0.45, cast = intArrayOf(4, 1, 4, 2, 1, 4, 1, 2), safe = true, lamp0 = 0.5) // 3 Facelings, 3 Hounds, 2 Smilers (scaled by difficulty)
)

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
