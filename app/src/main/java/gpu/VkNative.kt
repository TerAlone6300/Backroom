package com.backrooms.game
// VkNative.kt - JNI entry points of libbackrooms_vk.so (app/src/main/cpp/vk_jni.cpp). Android only.

object VkNative {
    init { System.loadLibrary("backrooms_vk") }  // UnsatisfiedLinkError here (no NDK build, odd ABI) is caught by GpuFrameView

    /** Native presenter handle, or 0 when Vulkan cannot be used (then lastError() says why). */
    @JvmStatic external fun create(surface: android.view.Surface, w: Int, h: Int): Long
    @JvmStatic external fun present(handle: Long, px: IntArray, viewW: Int, viewH: Int): Boolean
    /** data: the scene data (Scene.DATA_W x DATA_H x 4 floats); tex: the texture layers when they changed, else null. */
    @JvmStatic external fun presentScene(handle: Long, data: FloatArray, tex: IntArray?, viewW: Int, viewH: Int): Boolean
    @JvmStatic external fun deviceName(handle: Long): String
    @JvmStatic external fun release(handle: Long)
    @JvmStatic external fun lastError(): String
}
