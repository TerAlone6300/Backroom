package com.backrooms.game
// Settings.kt - menu screens, options, difficulty modes, Explore rules. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

// ---------- Menu screens (shared by both clients), settings, saves ----------
enum class Scr { MAIN, SAVES, MULTI, OPTIONS, PAUSE, GAME }

object Settings {
    const val FOV_MIN = 30; const val FOV_MAX = 110
    @Volatile var fov = 60
    @Volatile var fog = 100  // % of each level's own fog density (0 = no fog)
    @Volatile var diff = 1   // index into DIFFS
    val FOG_PRESETS = listOf(0 to "Off", 50 to "Light", 100 to "Default")
    val fogScale get() = fog / 100.0
    fun putFog(f: Int) { fog = f.coerceIn(0, 100) }
    fun fogName(f: Int = fog) = FOG_PRESETS.firstOrNull { it.first == f }?.let { "${it.second} ($f%)" } ?: "Custom ($f%)"
    fun fogFromFrac(t: Double): Int {
        val f = (t.coerceIn(0.0, 1.0) * 100).roundToInt()
        return FOG_PRESETS.firstOrNull { abs(it.first - f) <= 3 }?.first ?: f
    }
    val explore get() = DIFFS[diff].explore
    fun putDiff(i: Int) { diff = i.coerceIn(0, DIFFS.size - 1) }
    val PRESETS = listOf(30 to "Narrow", 60 to "Normal", 110 to "Quake Pro")
    fun set(f: Int) { fov = f.coerceIn(FOV_MIN, FOV_MAX) }
    fun name(f: Int = fov) = PRESETS.firstOrNull { it.first == f }?.let { "${it.second} ($f)" } ?: "Custom ($f)"
    fun frac(f: Int = fov) = (f - FOV_MIN).toDouble() / (FOV_MAX - FOV_MIN)
    /** Slider position 0..1 -> FOV; sticks to a preset when within 2 degrees of it. */
    fun fromFrac(t: Double): Int {
        val f = (FOV_MIN + t.coerceIn(0.0, 1.0) * (FOV_MAX - FOV_MIN)).roundToInt()
        return PRESETS.firstOrNull { abs(it.first - f) <= 2 }?.first ?: f
    }
}

/** Difficulty multipliers (Normal = the original game): entity count, entity speed, how far entities notice you,
 *  sprint drain / refill, spawn distance, catch distance, hunger rate, starting rations / almond water. */
class Diff(val name: String, val blurb: String, val count: Double, val speed: Double, val sight: Double, val drain: Double,
           val regen: Double, val spawn: Double, val catchR: Double, val hunger: Double, val kitRations: Int = 2, val kitWater: Int = 1, val explore: Boolean = false)
/** Explore mode knobs: everything to tune after player feedback is in this one place. */
object ExploreRules {
    /** false = levels you pass through are NOT remembered (they never reach the unlocked-levels drawer). true = remembered like in the other modes. */
    const val SAVE_VISITED_LEVELS = false
    /** Chance (0..1) that a noclip drops you on a Safe & Secure level (LevelDef.safe) instead of the wall's usual destination. */
    const val SAFE_NOCLIP_CHANCE = 0.8
    // Monsters are friendly in Explore: they wander and tag along at a polite distance, never chase or hurt (see Game.update).
}
val DIFFS = listOf(
    Diff("Easy", "Fewer, slower entities. Longer sprint.", 0.5, 0.8, 0.8, 0.7, 1.25, 1.4, 0.5, 0.6, 3, 2),
    Diff("Normal", "The intended experience.", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.6, 1.0, 2, 1),
    Diff("Hard", "More, faster entities. Shorter sprint.", 1.5, 1.15, 1.2, 1.3, 0.85, 0.85, 0.65, 1.25, 1, 1),
    Diff("Nightmare", "A horde, faster than your sprint.", 2.0, 1.3, 1.5, 1.6, 0.7, 0.7, 0.7, 1.6, 1, 0),
    Diff("Explore", "Friendly monsters. Safe levels.", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.6, 0.6, 0, 0, explore = true))
