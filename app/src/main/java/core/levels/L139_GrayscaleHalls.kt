package com.backrooms.game
// L139_GrayscaleHalls.kt - Level 139: Grayscale Halls. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).

/** Level 139: Grayscale Halls. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level139GrayscaleHalls : LevelSpec {
    override val def = LevelDef(139, "LEVEL 139", "GRAYSCALE HALLS", "Colourless concrete. The paintings look back.", "NONE", 0, 0, 4, 0.14, 0.38, 0.016, intArrayOf(236, 236, 236), 0xBEBEBE, safe = true, danger = 2)
    override val noclip = intArrayOf(0, 2, 201, 11)
    override val flicker = doubleArrayOf(0.10, 0.20, 0.28)
    private val SPEC = TexSpec(intArrayOf(150, 150, 150), intArrayOf(95, 95, 95), intArrayOf(135, 135, 135), intArrayOf(235, 235, 235), 8, 3)

    override fun wall(x: Int, y: Int): Boolean = grid(x, y, 7, 130, 0.7, 2)

    override fun textures(t: TexSet) = t.fromSpec(9, SPEC)
}
