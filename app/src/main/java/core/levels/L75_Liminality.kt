package com.backrooms.game
// L75_Liminality.kt - Level 75: Liminality. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).

/** Level 75: Liminality. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level75Liminality : LevelSpec {
    override val def = LevelDef(75, "LEVEL 75", "LIMINALITY", "Sand drifts through quiet corridors. You have been here before.", "NONE", 0, 0, 5, 0.42, 0.12, 0.010, intArrayOf(256, 244, 215), 0xE6C98A, safe = true, danger = 1)
    override val noclip = intArrayOf(37, 0, 182, 4)
    override val flicker = doubleArrayOf(0.0, 0.02, 0.03)
    private val SPEC = TexSpec(intArrayOf(222, 196, 138), intArrayOf(224, 192, 122), intArrayOf(205, 185, 135), intArrayOf(255, 246, 215), 2, 1)

    override fun wall(x: Int, y: Int): Boolean = grid(x, y, 8, 120, 0.6, 3)

    override fun textures(t: TexSet) = t.fromSpec(8, SPEC)
}
