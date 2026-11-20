package com.backrooms.game
// Util.kt - shared constants, the hash function and colour packing. Shared by desktop and Android (pure Kotlin/JVM, no AWT/Android imports).

/** The seed every world had before worlds became random: saves and servers that predate the change still mean this world. */
const val LEGACY_SEED = 1337
fun randomWorldSeed(): Int = java.util.concurrent.ThreadLocalRandom.current().nextInt()
/** Seed of the world being played. Every new game picks a random one, a save stores it, and the multiplayer server gives its own to every client (see useWorld). */
@Volatile var worldSeed = randomWorldSeed()
const val PORT = 7777
/** Size of the frame the Renderer draws (always 16:9). Every client scales it to its screen. */
const val FRAME_W = 1920
const val FRAME_H = 1080

fun h(x: Int, y: Int, s: Int): Double {
    var n = x * 374761393 + y * 668265263 + s * 1274126177 + worldSeed
    n = (n xor (n ushr 13)) * 1274126177
    n = n xor (n ushr 16)
    return (n and 0xFFFF) / 65536.0
}

fun pack(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)
