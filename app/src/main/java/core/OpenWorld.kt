package com.backrooms.game
// OpenWorld.kt - levels under an open sky. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** A level with no ceiling: walls of different heights, its own ground colours and a sky panorama. The city, the island and the graveyard are open worlds. */
interface OpenWorld {
    /** Sky panorama of [skyW] x [skyH] (powers of two; 2048 x 256 unless the level says otherwise): azimuth around, elevation 0..90 degrees upward. */
    val sky: IntArray
    val skyW: Int get() = 2048
    val skyH: Int get() = 256
    /** A sun turns on the rich look: smooth textures, haze that takes the colour of the horizon and the sun, light shafts and floating pollen (Options > quality). null = the plain look. */
    val sun: Sun? get() = null
    /** Wall textures (64x64); alpha 0xFE marks glowing texels (lit windows). */
    val tex: Array<IntArray>
    /** Colour far things fade into, and how fast (the city uses 0.0007). */
    val haze: Int
    val hazeK: Double
    /** Colour of the rock overhead where roofed() is true. */
    val roofColor: Int get() = pack(30, 26, 24)
    /** Floors (wall units, 1 = as tall as you are) of the wall cell. */
    fun height(x: Int, y: Int): Int
    /** Worked out once per wall hit: which texture family the wall cell uses. */
    fun style(x: Int, y: Int): Int
    /** Texture for floor row `unit` (0 = the top one) of a wall `ht` floors tall, for the style above. */
    fun texIndex(style: Int, unit: Int, ht: Int): Int
    /** Ground colour at (wx, wy) in cell (ix, iy); `base` is the grain taken from the level's floor texture. */
    fun ground(wx: Double, wy: Double, ix: Int, iy: Int, base: Int): Int
    /** From this distance (world units) the renderer reads the ground from [TerrainBake] (baked on the CPU) instead of calling [ground] per pixel; null = never. */
    val bakeFrom: Double? get() = null
    /** Changes whenever [ground] changes while the game runs (e.g. a path opens): baked tiles of an older version are rebuilt. */
    val groundVersion: Int get() = 0
    /** Cheaper ground for the extra samples taken on far ground (no sub-pixel detail needed there). */
    fun groundFar(wx: Double, wy: Double, ix: Int, iy: Int, base: Int): Int = ground(wx, wy, ix, iy, base)
    /** Light factor of a floor cell (dark buildings, caves): 1 = normal. */
    fun dim(x: Int, y: Int): Double = 1.0
    /** True where there is rock overhead (tunnels): the sky is hidden. */
    fun roofed(x: Int, y: Int): Boolean = false
    /** Extra sprites around (x, y): [x, y, sprite code]. */
    fun extraSprites(x: Double, y: Double, out: MutableList<DoubleArray>) {}
}

/** [valueNoise] on the fixed world seed (see [hf]). */
fun valueNoiseF(x: Double, y: Double, s: Int, wrap: Int = 1 shl 20): Double {
    val xi = floor(x).toInt(); val yi = floor(y).toInt(); val fx = x - xi; val fy = y - yi
    val x0 = Math.floorMod(xi, wrap); val x1 = Math.floorMod(xi + 1, wrap)
    val a = hf(x0, yi, s); val b = hf(x1, yi, s); val c = hf(x0, yi + 1, s); val d = hf(x1, yi + 1, s)
    val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
    return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
}

/** Smooth value noise, tiling every `wrap` cells along x. */
fun valueNoise(x: Double, y: Double, s: Int, wrap: Int = 1 shl 20): Double {
    val xi = floor(x).toInt(); val yi = floor(y).toInt(); val fx = x - xi; val fy = y - yi
    val x0 = Math.floorMod(xi, wrap); val x1 = Math.floorMod(xi + 1, wrap)
    val a = h(x0, yi, s); val b = h(x1, yi, s); val c = h(x0, yi + 1, s); val d = h(x1, yi + 1, s)
    val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
    return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
}
