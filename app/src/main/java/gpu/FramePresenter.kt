package com.backrooms.game
// FramePresenter.kt - Android only. Kept out of core/ because the desktop JAR compiles core/*.kt without the Android SDK.

/** Backend that puts the Renderer's frame on a Surface. px holds 0xAARRGGBB pixels (see pack()), w*h of them. */
interface FramePresenter {
    /** Human-readable backend and GPU, e.g. "Vulkan (Adreno 650)". */
    val name: String
    /** Shows a frame the CPU drew (open-world levels, whose ground and buildings are code, not data). false = this backend failed. */
    fun present(px: IntArray, viewW: Int, viewH: Int): Boolean
    /** Draws the 3D view itself, on the GPU, from the scene data Renderer.buildScene made. false = this backend failed. */
    fun presentScene(scene: SceneData, viewW: Int, viewH: Int): Boolean
    fun release()
}
