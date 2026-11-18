// Host test for the Vulkan backend: runs the same vk_core.cpp the Android build uses, on a Linux desktop (X11).
// It presents a frame with four coloured quadrants, reads the window back, and checks every quadrant. That catches
// swapped colour channels, a flipped image, stale frames, and resize / re-create bugs.
// Build and run: ./run.sh [frameW frameH]  (default 1920 1080)   (needs libvulkan-dev, libx11-dev and a Vulkan driver; mesa-vulkan-drivers gives lavapipe)
#define VK_USE_PLATFORM_XLIB_KHR
#include "../../app/src/main/cpp/vk_core.h"

#define FRAME_W_DEFAULT 1920
#define FRAME_H_DEFAULT 1080
#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <unistd.h>

#include <cstdio>
#include <cstdlib>
#include <cstring>

static uint32_t FW = FRAME_W_DEFAULT, FH = FRAME_H_DEFAULT;
static int failures = 0;
#include <vector>

static void check(bool ok, const char* what) {
    std::printf("  %-58s %s\n", what, ok ? "PASS" : "FAIL");
    if (!ok) failures++;
}

// A frame in the game's own pixel format: 0xAARRGGBB, quadrants TL, TR, BL, BR.
static void makeFrame(uint32_t* px, uint32_t tl, uint32_t tr, uint32_t bl, uint32_t br) {
    for (uint32_t y = 0; y < FH; y++)
        for (uint32_t x = 0; x < FW; x++)
            px[y * FW + x] = y < FH / 2 ? (x < FW / 2 ? tl : tr) : (x < FW / 2 ? bl : br);
}

static uint32_t pixelAt(Display* d, Window w, int x, int y) {
    XImage* im = XGetImage(d, w, x, y, 1, 1, AllPlanes, ZPixmap);
    uint32_t p = static_cast<uint32_t>(XGetPixel(im, 0, 0)) & 0xFFFFFF;
    XDestroyImage(im);
    return p;
}

static bool near(uint32_t a, uint32_t b) {
    for (int s = 0; s < 24; s += 8) {
        int da = int((a >> s) & 255) - int((b >> s) & 255);
        if (da < -2 || da > 2) return false;
    }
    return true;
}

static bool quadrants(Display* d, Window w, int W, int H, uint32_t tl, uint32_t tr, uint32_t bl, uint32_t br) {
    uint32_t got[4] = {pixelAt(d, w, W / 4, H / 4), pixelAt(d, w, 3 * W / 4, H / 4), pixelAt(d, w, W / 4, 3 * H / 4), pixelAt(d, w, 3 * W / 4, 3 * H / 4)};
    uint32_t want[4] = {tl & 0xFFFFFF, tr & 0xFFFFFF, bl & 0xFFFFFF, br & 0xFFFFFF};
    bool ok = true;
    for (int i = 0; i < 4; i++) {
        if (!near(got[i], want[i])) { std::printf("    quadrant %d: got %06x, want %06x\n", i, got[i], want[i]); ok = false; }
    }
    return ok;
}

static void pump(Display* d, VkFramePresenter& p, uint32_t* frame, int W, int H, int n) {
    for (int i = 0; i < n; i++) {
        p.present([&](void* dst, size_t bytes) { std::memcpy(dst, frame, bytes); }, W, H);
        XSync(d, False);
        usleep(20000);
    }
}

static bool initOn(VkFramePresenter& p, Display* d, Window w) {
    return p.init({VK_KHR_XLIB_SURFACE_EXTENSION_NAME},
                  [d, w](VkInstance inst, PFN_vkGetInstanceProcAddr gipa) -> VkSurfaceKHR {
                      auto create = reinterpret_cast<PFN_vkCreateXlibSurfaceKHR>(gipa(inst, "vkCreateXlibSurfaceKHR"));
                      if (!create) return VK_NULL_HANDLE;
                      VkXlibSurfaceCreateInfoKHR ci{VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR};
                      ci.dpy = d;
                      ci.window = w;
                      VkSurfaceKHR s = VK_NULL_HANDLE;
                      return create(inst, &ci, nullptr, &s) == VK_SUCCESS ? s : VK_NULL_HANDLE;
                  },
                  FW, FH);
}

int main(int argc, char** argv) {
    if (argc == 3) { FW = std::atoi(argv[1]); FH = std::atoi(argv[2]); }  // ./vk-host-test 480 270
    std::printf("frame size %ux%u\n", FW, FH);
    Display* d = XOpenDisplay(nullptr);
    if (!d) { std::fprintf(stderr, "no X display (run under xvfb-run)\n"); return 2; }
    int W = 960, H = 540;
    Window w = XCreateSimpleWindow(d, DefaultRootWindow(d), 0, 0, W, H, 0, 0, 0);
    XSelectInput(d, w, StructureNotifyMask);
    XMapWindow(d, w);
    for (XEvent e;;) { XNextEvent(d, &e); if (e.type == MapNotify) break; }

    std::vector<uint32_t> frameBuf(size_t(FW) * FH);
    uint32_t* frame = frameBuf.data();
    const uint32_t RED = 0xFFFF0000, GREEN = 0xFF00FF00, BLUE = 0xFF0000FF, WHITE = 0xFFFFFFFF, BLACK = 0xFF000000, YELLOW = 0xFFFFFF00;

    std::printf("1. init and first frames\n");
    {
        VkFramePresenter p;
        bool ok = initOn(p, d, w);
        check(ok, "init() succeeds");
        if (!ok) { std::printf("  error: %s\n", p.error().c_str()); return 1; }
        std::printf("  device: %s\n", p.deviceName().c_str());

        makeFrame(frame, RED, GREEN, BLUE, WHITE);
        pump(d, p, frame, W, H, 6);
        check(quadrants(d, w, W, H, RED, GREEN, BLUE, WHITE), "orientation and channel order (R,G / B,W)");

        std::printf("2. a new frame replaces the old one\n");
        makeFrame(frame, YELLOW, BLACK, WHITE, RED);
        pump(d, p, frame, W, H, 6);
        check(quadrants(d, w, W, H, YELLOW, BLACK, WHITE, RED), "second frame content shown (not stale)");

        std::printf("3. window resize\n");
        W = 640; H = 360;
        XResizeWindow(d, w, W, H);
        XSync(d, False);
        usleep(100000);
        makeFrame(frame, RED, GREEN, BLUE, WHITE);
        bool alive = true;
        for (int i = 0; i < 8; i++) {
            alive &= p.present([&](void* dst, size_t bytes) { std::memcpy(dst, frame, bytes); }, W, H);
            XSync(d, False);
            usleep(20000);
        }
        check(alive, "present() keeps succeeding across a resize");
        check(quadrants(d, w, W, H, RED, GREEN, BLUE, WHITE), "image fills the resized window");

        std::printf("4. frame skipping and teardown\n");
        check(p.present([&](void* dst, size_t bytes) { std::memcpy(dst, frame, bytes); }, W, H), "present() after resize still true");
    }  // destructor: full teardown with the device idle

    std::printf("5. re-create on the same window (surface recreated)\n");
    {
        VkFramePresenter p;
        bool ok = initOn(p, d, w);
        check(ok, "second init() on the same window");
        if (ok) {
            makeFrame(frame, BLUE, WHITE, RED, GREEN);
            pump(d, p, frame, W, H, 6);
            check(quadrants(d, w, W, H, BLUE, WHITE, RED, GREEN), "frames shown by the second presenter");
        }
    }

    std::printf("6. init fails cleanly with a bad surface\n");
    {
        VkFramePresenter p;
        bool ok = p.init({VK_KHR_XLIB_SURFACE_EXTENSION_NAME}, [](VkInstance, PFN_vkGetInstanceProcAddr) { return VK_NULL_HANDLE; }, FW, FH);
        check(!ok && !p.error().empty(), "init() returns false with an error message");
        std::printf("  error: %s\n", p.error().c_str());
    }

    XDestroyWindow(d, w);
    XCloseDisplay(d);
    std::printf("\n%s (%d failure%s)\n", failures ? "FAILED" : "ALL PASSED", failures, failures == 1 ? "" : "s");
    return failures ? 1 : 0;
}
