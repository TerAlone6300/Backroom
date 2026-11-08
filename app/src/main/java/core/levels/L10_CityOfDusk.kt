package com.backrooms.game
// L10_CityOfDusk.kt - Level 10: City of Dusk. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).

/** Internal id 10 = Backrooms Level 182, City of Dusk. Everything specific to this level lives here: definition, maze, textures, flicker, noclip links. */
object Level10CityOfDusk : LevelSpec {
    override val def = LevelDef(10, "LEVEL 182", "CITY OF DUSK", "A sunset that never sets over empty concrete rooms.", "NONE", 0, 0, 4, 0.22, 0.18, 0.014, intArrayOf(256, 200, 150), 0xFF9A5A, safe = true)
    override val noclip = intArrayOf(2, 5, 8, 12)
    override val flicker = doubleArrayOf(0.0, 0.05, 0.08)
    private val SPEC = TexSpec(intArrayOf(130, 126, 120), intArrayOf(88, 84, 80), intArrayOf(100, 96, 92), intArrayOf(255, 160, 90), 3, 0)

    override fun wall(x: Int, y: Int): Boolean = Level05TerrorHotel.wall(x + 37, y + 11)

    override fun textures(t: TexSet) = t.fromSpec(10, SPEC)
}
