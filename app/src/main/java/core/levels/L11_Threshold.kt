package com.backrooms.game
// L11_Threshold.kt - Level 11: The Threshold. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).

/** Internal id 11 = Backrooms Level 201, The Threshold. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level11Threshold : LevelSpec {
    override val def = LevelDef(11, "LEVEL 201", "THE THRESHOLD", "A transit between depths. No one else can reach you here.", "NONE", 0, 0, 4, 0.25, 0.30, 0.016, intArrayOf(236, 242, 256), 0xC8D0E8, isolated = true, safe = true)
    override val noclip = intArrayOf(3, 5, 8, 12)
    override val flicker = doubleArrayOf(0.08, 0.16, 0.20)
    private val SPEC = TexSpec(intArrayOf(200, 200, 206), intArrayOf(168, 168, 176), intArrayOf(210, 210, 216), intArrayOf(255, 255, 255), 5, 3)

    override fun wall(x: Int, y: Int): Boolean = grid(x, y, 4, 140, 0.9, 1)

    override fun textures(t: TexSet) = t.fromSpec(11, SPEC)
}
