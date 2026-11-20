// vk-scene-test.cpp - draws the scene data of every case with the Vulkan scene shader (software Vulkan on a Linux desktop)
// and compares it with the frame the CPU renderer drew for the same state. Make the cases with SceneDump.kt first.
//   ./run.sh /path/to/cases [name-filter] [--save]      (--save writes <case>.gpu.ppm next to the data)
// The film grain is the same amount of noise from a different random stream, so frames are compared in 16x16 blocks
// (averages), plus the per-pixel error for reference.
#define VK_USE_PLATFORM_XLIB_KHR
#include "../../app/src/main/cpp/vk_core.h"

#include <X11/Xlib.h>
#include <X11/Xutil.h>
#include <unistd.h>

#include "common.h"

int main(int argc, char** argv) {
    if (argc < 2) { std::fprintf(stderr, "usage: vk-scene-test cases-dir [filter] [--save]\n"); return 2; }
    std::string dir = argv[1], filter; bool save = false;
    for (int i = 2; i < argc; i++) { if (!std::strcmp(argv[i], "--save")) save = true; else filter = argv[i]; }
    int W = 0, H = 0;
    { std::ifstream m(dir + "/meta.txt"); m >> W >> H; }
    if (W <= 0) { std::fprintf(stderr, "no meta.txt in %s\n", dir.c_str()); return 2; }

    std::vector<std::string> names = listCases(dir);

    Display* d = XOpenDisplay(nullptr);
    if (!d) { std::fprintf(stderr, "no X display\n"); return 2; }
    Window w = XCreateSimpleWindow(d, DefaultRootWindow(d), 0, 0, W, H, 0, 0, 0);
    XSelectInput(d, w, StructureNotifyMask); XMapWindow(d, w);
    for (XEvent e;;) { XNextEvent(d, &e); if (e.type == MapNotify) break; }

    VkFramePresenter p;
    bool ok = p.init({VK_KHR_XLIB_SURFACE_EXTENSION_NAME}, [d, w](VkInstance inst, PFN_vkGetInstanceProcAddr gipa) -> VkSurfaceKHR {
        auto create = reinterpret_cast<PFN_vkCreateXlibSurfaceKHR>(gipa(inst, "vkCreateXlibSurfaceKHR"));
        VkXlibSurfaceCreateInfoKHR ci{VK_STRUCTURE_TYPE_XLIB_SURFACE_CREATE_INFO_KHR}; ci.dpy = d; ci.window = w;
        VkSurfaceKHR s = VK_NULL_HANDLE; return create && create(inst, &ci, nullptr, &s) == VK_SUCCESS ? s : VK_NULL_HANDLE; }, W, H);
    if (!ok) { std::fprintf(stderr, "Vulkan init failed: %s\n", p.error().c_str()); return 1; }
    std::printf("device: %s, frame %dx%d\n", p.deviceName().c_str(), W, H);
    std::printf("%-26s %8s %8s %8s %8s\n", "case", "block", "maxblock", "blk>4%", "pixel");

    int bad = 0, run = 0; double worst = 0;
    for (const std::string& n : names) {
        if (!filter.empty() && n.find(filter) == std::string::npos) continue;
        auto scene = readFile(dir + "/" + n + ".scene.f32"), tex = readFile(dir + "/" + n + ".tex.u32"), cpu = readFile(dir + "/" + n + ".cpu.raw");
        if (scene.size() != VkFramePresenter::kDataBytes || tex.size() != VkFramePresenter::kTexBytes || cpu.size() != size_t(W) * H * 4) { std::printf("%-26s bad data sizes\n", n.c_str()); bad++; continue; }
        VkFramePresenter::FrameInput in;
        in.fillData = [&](void* dst) { std::memcpy(dst, scene.data(), scene.size()); };
        in.fillTex = [&](void* dst) { std::memcpy(dst, tex.data(), tex.size()); };
        bool alive = true;
        for (int i = 0; i < 3; i++) { alive &= p.present(in, W, H); XSync(d, False); usleep(30000); }
        if (!alive) { std::printf("%-26s present() failed: %s\n", n.c_str(), p.error().c_str()); return 1; }
        XImage* im = XGetImage(d, w, 0, 0, W, H, AllPlanes, ZPixmap);
        std::vector<uint32_t> gpu(size_t(W) * H);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) gpu[size_t(y) * W + x] = uint32_t(XGetPixel(im, x, y)) & 0xFFFFFF;
        XDestroyImage(im);
        Metrics m = compare(gpu.data(), reinterpret_cast<const uint32_t*>(cpu.data()), W, H);
        // later frames of a level do not send the textures again: they must still be there
        VkFramePresenter::FrameInput noTex; noTex.fillData = in.fillData;
        for (int i = 0; i < 2; i++) { alive &= p.present(noTex, W, H); XSync(d, False); usleep(30000); }
        XImage* im2 = XGetImage(d, w, 0, 0, W, H, AllPlanes, ZPixmap);
        std::vector<uint32_t> again(size_t(W) * H);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) again[size_t(y) * W + x] = uint32_t(XGetPixel(im2, x, y)) & 0xFFFFFF;
        XDestroyImage(im2);
        Metrics m2 = compare(again.data(), reinterpret_cast<const uint32_t*>(cpu.data()), W, H);
        if (!alive || m2.block > m.block + 0.3) { std::printf("%-26s textures lost without a re-upload (block %.2f vs %.2f)\n", n.c_str(), m2.block, m.block); bad++; }
        // the same presenter then shows a frame the CPU drew (what an open-world level needs): 1:1, so it must come out the same
        VkFramePresenter::FrameInput cpuFrame; std::vector<uint8_t> zeros(VkFramePresenter::kDataBytes, 0);
        cpuFrame.fillData = [&](void* dst) { std::memcpy(dst, zeros.data(), zeros.size()); };
        cpuFrame.fillFrame = [&](void* dst) { std::memcpy(dst, cpu.data(), cpu.size()); };
        for (int i = 0; i < 2; i++) { alive &= p.present(cpuFrame, W, H); XSync(d, False); usleep(30000); }
        XImage* im3 = XGetImage(d, w, 0, 0, W, H, AllPlanes, ZPixmap);
        std::vector<uint32_t> shown(size_t(W) * H);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) shown[size_t(y) * W + x] = uint32_t(XGetPixel(im3, x, y)) & 0xFFFFFF;
        XDestroyImage(im3);
        Metrics m3 = compare(shown.data(), reinterpret_cast<const uint32_t*>(cpu.data()), W, H);
        if (!alive || m3.pixel > 0.5) { std::printf("%-26s CPU frame (mode 1) differs from itself: pixel error %.2f\n", n.c_str(), m3.pixel); bad++; }
        run++; worst = std::max(worst, m.block);
        std::printf("%-26s %8.2f %8.2f %8.1f %8.2f\n", n.c_str(), m.block, m.maxBlock, m.over4, m.pixel); std::fflush(stdout);
        if (save) savePpm(dir + "/" + n + ".gpu.ppm", gpu, W, H);
    }
    std::printf("\n%d cases, worst block error %.2f\n", run, worst);
    return bad ? 1 : 0;
}
