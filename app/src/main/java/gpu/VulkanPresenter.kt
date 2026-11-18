package com.backrooms.game
// VulkanPresenter.kt - Vulkan backend. The work is in C++ (app/src/main/cpp/vk_core.cpp); this is the thin Kotlin side.

import android.view.Surface

class VulkanPresenter private constructor(private var handle: Long, override val name: String) : FramePresenter {
    override fun present(px: IntArray, viewW: Int, viewH: Int): Boolean {
        val h = handle
        return h != 0L && VkNative.present(h, px, viewW, viewH)
    }

    override fun release() {
        val h = handle; handle = 0L
        if (h != 0L) VkNative.release(h)
    }

    companion object {
        /** @throws IllegalStateException with the reason when Vulkan cannot be used on this device or surface. */
        fun create(surface: Surface, w: Int, h: Int): VulkanPresenter {
            val handle = VkNative.create(surface, w, h)
            check(handle != 0L) { VkNative.lastError().ifEmpty { "Vulkan failed to start" } }
            return VulkanPresenter(handle, "Vulkan (${VkNative.deviceName(handle)})")
        }
    }
}
