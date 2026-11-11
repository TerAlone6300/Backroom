package com.backrooms.game
// OpenWorld.kt - levels under an open sky. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).
import kotlin.math.*

/** A level with no ceiling: walls of different heights, its own ground colours and a sky panorama. The city, the island and the graveyard are open worlds. */
interface OpenWorld {
    /** 2048 x 256 panorama: azimuth around, elevation 0..90 degrees upward. */
    val sky: IntArray
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
    /** Light factor of a floor cell (dark buildings, caves): 1 = normal. */
    fun dim(x: Int, y: Int): Double = 1.0
    /** True where there is rock overhead (tunnels): the sky is hidden. */
    fun roofed(x: Int, y: Int): Boolean = false
    /** Extra sprites around (x, y): [x, y, sprite code]. */
    fun extraSprites(x: Double, y: Double, out: MutableList<DoubleArray>) {}
}

/** Smooth value noise, tiling every `wrap` cells along x. */
fun valueNoise(x: Double, y: Double, s: Int, wrap: Int = 1 shl 20): Double {
    val xi = floor(x).toInt(); val yi = floor(y).toInt(); val fx = x - xi; val fy = y - yi
    val x0 = Math.floorMod(xi, wrap); val x1 = Math.floorMod(xi + 1, wrap)
    val a = h(x0, yi, s); val b = h(x1, yi, s); val c = h(x0, yi + 1, s); val d = h(x1, yi + 1, s)
    val sx = fx * fx * (3 - 2 * fx); val sy = fy * fy * (3 - 2 * fy)
    return a + (b - a) * sx + (c - a) * sy + (a - b - c + d) * sx * sy
}
