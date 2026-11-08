package com.backrooms.game
// L07_LightsOut.kt - Level 7: Lights Out. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).

/** Internal id 7 = Backrooms Level 6, Lights Out. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level07LightsOut : LevelSpec {
    override val def = LevelDef(7, "LEVEL 6", "LIGHTS OUT", "Pitch-dark halls. Your lantern is all you have.", "HOUNDS", 1, 2, 3, 0.015, 0.50, 0.030, intArrayOf(256, 246, 214), 0xE0C060, lantern = 1.1)
    override val noclip = intArrayOf(0, 1, 9, 12)
    override val flicker = doubleArrayOf(0.02, 0.04, 0.95)
    private val SPEC = TexSpec(intArrayOf(190, 170, 90), intArrayOf(100, 84, 44), intArrayOf(180, 170, 140), intArrayOf(255, 255, 200), 0, 0)

    override fun wall(x: Int, y: Int): Boolean = grid(x, y, 5, 110, 0.8, 2)

    override fun textures(t: TexSet) = t.fromSpec(7, SPEC)
}
