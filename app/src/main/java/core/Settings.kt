package com.backrooms.game
// Settings.kt - menu screens, options, difficulty modes, Explore rules. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

// ---------- Menu screens (shared by both clients), settings, saves ----------
enum class Scr { MAIN, SAVES, MULTI, OPTIONS, PAUSE, GAME, DIFFICULTY }

/** Settings.renderer values: the frame drawn by Canvas, by OpenGL ES, or by Vulkan. */
const val R_CANVAS = 0; const val R_GLES = 1; const val R_VULKAN = 2
val RENDERER_NAMES = listOf("Canvas", "OpenGL ES", "Vulkan")

object Settings {
    const val FOV_MIN = 30; const val FOV_MAX = 110
    @Volatile var fov = 60
    @Volatile var fog = 100  // % of each level's own fog density (0 = no fog)
    @Volatile var renderer = R_CANVAS // how the Android client puts the frame on screen (desktop ignores it)
    /** Height of the frame the game draws (always 16:9, so 1080 = 1920x1080). The screen scales it. Lower is much faster: the cost grows with the pixel count. */
    val FRAME_HEIGHTS = intArrayOf(270, 360, 540, 720, 1080)
    @Volatile var frameH = FRAME_H
    val frameW get() = frameH * 16 / 9
    fun putFrame(h: Int) { frameH = FRAME_HEIGHTS.minByOrNull { abs(it - h) } ?: FRAME_H }

    /** Cap the game loop at this many frames per second. 0 = Ultimate (no limit). Clamped to 20..290 when non-zero. */
    const val FPS_MIN = 20; const val FPS_MAX = 290
    @Volatile var fpsLimit = 0  // 0 = Ultimate
    /** Sticky points on the FPS slider (plus the continuous range). */
    val FPS_PRESETS = listOf(30, 60, 90, 120, 144, 240, 290)
    fun putFpsLimit(v: Int) { fpsLimit = if (v <= 0) 0 else v.coerceIn(FPS_MIN, FPS_MAX) }
    fun fpsLimitName(v: Int = fpsLimit) = if (v <= 0) "Ultimate" else "$v"
    /** Slider 0..1: left = 20, near right = 290, far right = Ultimate. */
    fun fpsLimitFrac(v: Int = fpsLimit): Double =
        if (v <= 0) 1.0 else ((v - FPS_MIN).toDouble() / (FPS_MAX - FPS_MIN)).coerceIn(0.0, 0.97)
    fun fpsLimitFromFrac(t: Double): Int {
        if (t >= 0.985) return 0  // Ultimate
        val raw = (FPS_MIN + t.coerceIn(0.0, 1.0) * (FPS_MAX - FPS_MIN)).roundToInt()
        return FPS_PRESETS.firstOrNull { abs(it - raw) <= 4 } ?: raw.coerceIn(FPS_MIN, FPS_MAX)
    }

    /** When true, prefer vsync (FIFO / eglSwapInterval 1). When false, prefer uncapped present (MAILBOX/IMMEDIATE / interval 0). */
    @Volatile var vsync = true
    fun putVsync(on: Boolean) { vsync = on }

    @Volatile var diff = 1   // index into DIFFS: the difficulty of the game being played. Chosen when a game starts and kept in its save, never in the profile
    val FOG_PRESETS = listOf(0 to "Off", 50 to "Light", 100 to "Default")
    val fogScale get() = fog / 100.0
    fun putFog(f: Int) { fog = f.coerceIn(0, 100) }
    fun fogName(f: Int = fog) = FOG_PRESETS.firstOrNull { it.first == f }?.let { "${it.second} ($f%)" } ?: "Custom ($f%)"
    fun fogFromFrac(t: Double): Int {
        val f = (t.coerceIn(0.0, 1.0) * 100).roundToInt()
        return FOG_PRESETS.firstOrNull { abs(it.first - f) <= 3 }?.first ?: f
    }
    val explore get() = DIFFS[diff].explore
    val hardcore get() = DIFFS[diff].hardcore
    /** Tag shown next to the FPS counter. */
    fun modeTag() = if (explore) "  ·  EXPLORE" else if (hardcore) "  ·  HARDCORE" else ""
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
           val regen: Double, val spawn: Double, val catchR: Double, val hunger: Double, val kitRations: Int = 2, val kitWater: Int = 1, val explore: Boolean = false, val hardcore: Boolean = false)
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
    Diff("Explore", "Friendly monsters. Safe levels.", 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.6, 0.6, 0, 0, explore = true),
    // Nightmare numbers, one life: the difficulty is locked for the whole run and death leads to The Grave (Unnumbered Level), which has no exit
    Diff("Hardcore", "Nightmare, one life. Death leads to The Grave.", 2.0, 1.3, 1.5, 1.6, 0.7, 0.7, 0.7, 1.6, 1, 0, hardcore = true))
val HARDCORE_IDX = DIFFS.indexOfFirst { it.hardcore }
val NIGHTMARE_IDX = DIFFS.indexOfFirst { it.name == "Nightmare" }
