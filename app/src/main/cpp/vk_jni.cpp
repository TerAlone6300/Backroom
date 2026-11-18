// vk_jni.cpp - JNI glue between com.backrooms.game.VkNative and VkFramePresenter (Android only).
#define VK_USE_PLATFORM_ANDROID_KHR
#include "vk_core.h"

#include <android/native_window_jni.h>
#include <jni.h>

namespace {
thread_local std::string gLastError;  // create() runs on the render thread and lastError() is read right after

struct Handle {
    VkFramePresenter presenter;
    ANativeWindow* window = nullptr;
    ~Handle() {
        presenter.destroy();  // the VkSurfaceKHR must die before the window it wraps
        if (window) ANativeWindow_release(window);
    }
};
}  // namespace

extern "C" {

JNIEXPORT jlong JNICALL Java_com_backrooms_game_VkNative_create(JNIEnv* env, jclass, jobject surface, jint w, jint h) {
    ANativeWindow* win = ANativeWindow_fromSurface(env, surface);
    if (!win) { gLastError = "ANativeWindow_fromSurface failed"; return 0; }
    auto* hd = new Handle();
    hd->window = win;
    const bool ok = hd->presenter.init(
        {VK_KHR_ANDROID_SURFACE_EXTENSION_NAME},
        [win](VkInstance inst, PFN_vkGetInstanceProcAddr gipa) -> VkSurfaceKHR {
            auto create = reinterpret_cast<PFN_vkCreateAndroidSurfaceKHR>(gipa(inst, "vkCreateAndroidSurfaceKHR"));
            if (!create) return VK_NULL_HANDLE;
            VkAndroidSurfaceCreateInfoKHR ci{VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR};
            ci.window = win;
            VkSurfaceKHR s = VK_NULL_HANDLE;
            return create(inst, &ci, nullptr, &s) == VK_SUCCESS ? s : VK_NULL_HANDLE;
        },
        static_cast<uint32_t>(w), static_cast<uint32_t>(h));
    if (!ok) { gLastError = hd->presenter.error(); delete hd; return 0; }
    return reinterpret_cast<jlong>(hd);
}

JNIEXPORT jboolean JNICALL Java_com_backrooms_game_VkNative_present(JNIEnv* env, jclass, jlong handle, jintArray px, jint viewW, jint viewH) {
    auto* hd = reinterpret_cast<Handle*>(handle);
    if (!hd) return JNI_FALSE;
    const jsize need = static_cast<jsize>(hd->presenter.frameW() * hd->presenter.frameH());
    if (env->GetArrayLength(px) < need) return JNI_FALSE;
    // GetIntArrayRegion copies straight into the mapped staging buffer. GetPrimitiveArrayCritical would hold the GC
    // lock for the whole present(), which can wait for vsync.
    const bool ok = hd->presenter.present(
        [&](void* dst, size_t) { env->GetIntArrayRegion(px, 0, need, static_cast<jint*>(dst)); },
        static_cast<uint32_t>(viewW), static_cast<uint32_t>(viewH));
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL Java_com_backrooms_game_VkNative_deviceName(JNIEnv* env, jclass, jlong handle) {
    auto* hd = reinterpret_cast<Handle*>(handle);
    return env->NewStringUTF(hd ? hd->presenter.deviceName().c_str() : "");
}

JNIEXPORT void JNICALL Java_com_backrooms_game_VkNative_release(JNIEnv*, jclass, jlong handle) {
    delete reinterpret_cast<Handle*>(handle);
}

JNIEXPORT jstring JNICALL Java_com_backrooms_game_VkNative_lastError(JNIEnv* env, jclass) {
    return env->NewStringUTF(gLastError.c_str());
}

}  // extern "C"
