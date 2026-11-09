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
               val safe: Boolean = false, val lamp0: Double = 1.5, val danger: Int = 1) { // danger: Backrooms survival difficulty class (3+ can drag you into Level 139); lamp0: where the lamp lattice starts; safe: rated Safe & Secure (Explore noclips favour these); calm: Level 11 Effect (hostiles notice you less); cast: entity types spawned (empty = eCount x eType)
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

/** Level numbers are the Backrooms Wikidot numbers: the id of a level IS its number (0 1 2 3 4 5 6 11 37 75 139 182 201), in this order. */
val LEVEL_SPECS: List<LevelSpec> = listOf(
    Level00Threshold, Level01HabitableZone, Level02PipeDreams, Level03ElectricalStation, Level04AbandonedOffice, Level05TerrorHotel, Level06LightsOut,
    Level11TheCity, Level37Sublimity, Level75Liminality, Level139GrayscaleHalls, Level182CityOfDusk, Level201Threshold)

val LEVELS: List<LevelDef> = LEVEL_SPECS.map { it.def }
private val SPECS_BY_ID: Map<Int, LevelSpec> = LEVEL_SPECS.associateBy { it.def.id }
fun levelExists(id: Int) = id in SPECS_BY_ID
/** The level with this number (Level 0 for an unknown number, e.g. a corrupt save). */
fun levelSpec(id: Int): LevelSpec = SPECS_BY_ID[id] ?: SPECS_BY_ID.getValue(0)
fun levelDef(id: Int): LevelDef = levelSpec(id).def

// Levels the code refers to by number
const val LV_THRESHOLD = 0   // Level 0: yellow halls, the Manila Room, Lobby hallucinations
const val LV_CITY = 11       // Level 11: The City That Never Sleeps
const val LV_SUBLIMITY = 37  // Level 37: pools (water shimmer)
