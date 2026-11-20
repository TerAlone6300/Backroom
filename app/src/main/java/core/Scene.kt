package com.backrooms.game
// Scene.kt - what the GPU renderer needs per frame. Pure Kotlin/JVM, shared by desktop and Android.
//
// The GPU draws every pixel itself (floor, ceiling, walls, sprites, post effects, HUD shapes) from the scene shader
// (app/src/main/cpp/shaders/scene.glsl). The CPU only does the parts that are cheap and awkward on a GPU: the wall DDA
// (one ray per column), lamp flicker, the sprite list and the post-effect numbers. All of it is written into ONE float
// texture (`f`, DATA_W x DATA_H texels of 4 floats), so both OpenGL ES and Vulkan need just one upload per frame.
// The offsets below are the same numbers as in scene.glsl: change them in both places.

object Scene {
    const val DATA_W = 2048; const val DATA_H = 8
    /** Texel (vec4) index of each region; texel i sits at x = i % DATA_W, y = i / DATA_W. */
    const val HDR = 0                      // 64 header vec4s, see Renderer.buildScene
    const val SPR = 64;  const val MAX_SPR = 64   // 2 vec4 per sprite
    const val GLI = 192; const val MAX_GLI = 64   // 1 vec4 per glitch row: (row, shift)
    const val MAP = 256                    // 17 vec4: wall bitmask of each minimap row in .x
    const val HUD = 288; const val MAX_HUD = 24   // 2 vec4 per HUD rectangle
    const val LMP = 512; const val LR = 32; const val LW = 2 * LR + 1   // lamp window: LW x LW lamps around the player, vec4 (flicker, zone, 0, 0)
    const val VIEW = 12                    // vec4: (view width, view height, mode 0 = scene / 1 = uploaded frame, 0), filled in by the presenter's caller
    const val CELL = 13                    // vec4: (floor x, floor y, fraction x, fraction y) of the camera: float32 cannot hold far-away positions exactly
    const val COL = 8192                   // 3 vec4 per screen column: wall hit
    /** Texture layers: 0 wall, 1 floor, 2 ceiling, 3 lamp panel of the level, 4..7 the same for the Manila Room. */
    const val TEX_LAYERS = 8
    const val TEX_SIZE = 64
}

class SceneData {
    val f = FloatArray(Scene.DATA_W * Scene.DATA_H * 4)
    /** Texture layers (see Scene.TEX_LAYERS), 0xAARRGGBB, layer after layer. */
    val tex = IntArray(Scene.TEX_LAYERS * Scene.TEX_SIZE * Scene.TEX_SIZE)
    /** Level whose textures are in `tex`. */
    var texLevel = Int.MIN_VALUE
    /** Bumped whenever `tex` changed: a presenter re-uploads the textures when it differs from the one it last saw. */
    var texSerial = 0

    fun put(i: Int, a: Double, b: Double, c: Double, d: Double) {
        val k = i * 4
        f[k] = a.toFloat(); f[k + 1] = b.toFloat(); f[k + 2] = c.toFloat(); f[k + 3] = d.toFloat()
    }

    /** Copies the 64x64 texture sets of `lv` (and the Manila Room's) into `tex` if they are not there yet. */
    fun useTextures(lv: Int) {
        if (texLevel == lv) return
        val n = Scene.TEX_SIZE * Scene.TEX_SIZE
        fun set(ts: TexSet, layer: Int) {
            System.arraycopy(ts.wall, 0, tex, layer * n, n); System.arraycopy(ts.floor, 0, tex, (layer + 1) * n, n)
            System.arraycopy(ts.ceil, 0, tex, (layer + 2) * n, n); System.arraycopy(ts.panel, 0, tex, (layer + 3) * n, n)
        }
        set(texOf(lv), 0); set(MANILA, 4)
        texLevel = lv; texSerial++
    }
}
