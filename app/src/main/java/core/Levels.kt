package com.backrooms.game
// Levels.kt - the level frame: LevelDef and the LevelSpec registry. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

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

/** Everything that makes one level. Each level is one object in core/levels (L00_Lobby.kt ... L12_TheCity.kt). */
interface LevelSpec {
    val def: LevelDef
    /** Level ids its 4 not-quite-solid noclip walls lead to. */
    val noclip: IntArray
    /** Lamp flicker: thresholds on a per-lamp random number: below [0] (x0.4) the lamp is broken (stutters), below [1] it hums, below [2] it is dead. */
    val flicker: DoubleArray
    /** The maze: true where there is a wall. A pure function of (x, y), so every device sees the same level. */
    fun wall(x: Int, y: Int): Boolean
    /** Paint the 64x64 wall, floor, ceiling and lamp-panel textures. */
    fun textures(t: TexSet)
    /** True where the long-hallway overlay must leave the maze alone. */
    fun keepsMaze(x: Int, y: Int): Boolean = false
}

/** Index = level id (the order of the levels in the game). Backrooms wiki numbers: 0 1 2 3 4 5 37 6 75 139 182 201 11. */
val LEVEL_SPECS: List<LevelSpec> = listOf(
    Level00Lobby, Level01HabitableZone, Level02PipeDreams, Level03ElectricalStation, Level04AbandonedOffice, Level05TerrorHotel, Level06Poolrooms,
    Level07LightsOut, Level08Liminality, Level09GrayscaleHalls, Level10CityOfDusk, Level11Threshold, Level12TheCity)

val LEVELS: List<LevelDef> = LEVEL_SPECS.map { it.def }
