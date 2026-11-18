package com.backrooms.game
// FramePresenter.kt - Android only. Kept out of core/ because the desktop JAR compiles core/*.kt without the Android SDK.

/** Backend that puts the Renderer's frame on a Surface. px holds 0xAARRGGBB pixels (see pack()), w*h of them. */
interface FramePresenter {
    /** Human-readable backend and GPU, e.g. "Vulkan (Adreno 650)". */
    val name: String
    /** Draws one frame. false = this backend failed and must not be used again. */
    fun present(px: IntArray, viewW: Int, viewH: Int): Boolean
    fun release()
}
