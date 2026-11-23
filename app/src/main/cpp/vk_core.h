#pragma once
// vk_core.h - Vulkan backend that shows one CPU-rendered frame (w x h, 0xAARRGGBB pixels) stretched over a surface.
// No JNI and no platform headers here: the caller supplies a surface factory, so the same code runs on Android
// (vk_jni.cpp) and on a Linux desktop (tools/vk-host-test).
//
// libvulkan is loaded with dlopen instead of being linked: the NDK only offers -lvulkan from API 24, the game's
// minSdk is 21, and this way a device without Vulkan simply fails init() and the app falls back to another backend.
#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

#include <array>
#include <cstddef>
#include <cstdint>
#include <functional>
#include <string>
#include <vector>

#define VK_INSTANCE_FNS(X)                                                                                         \
    X(DestroyInstance) X(EnumeratePhysicalDevices) X(GetPhysicalDeviceProperties)                                  \
    X(GetPhysicalDeviceQueueFamilyProperties) X(GetPhysicalDeviceMemoryProperties)                                 \
    X(GetPhysicalDeviceSurfaceSupportKHR) X(GetPhysicalDeviceSurfaceCapabilitiesKHR)                               \
    X(GetPhysicalDeviceSurfaceFormatsKHR) X(GetPhysicalDeviceSurfacePresentModesKHR)                               \
    X(EnumerateDeviceExtensionProperties) X(CreateDevice)                                                          \
    X(GetDeviceProcAddr) X(DestroySurfaceKHR)

#define VK_DEVICE_FNS(X)                                                                                           \
    X(DestroyDevice) X(GetDeviceQueue) X(DeviceWaitIdle) X(CreateSwapchainKHR) X(DestroySwapchainKHR)              \
    X(GetSwapchainImagesKHR) X(AcquireNextImageKHR) X(QueuePresentKHR) X(QueueSubmit) X(CreateImage)               \
    X(DestroyImage) X(GetImageMemoryRequirements) X(AllocateMemory) X(FreeMemory) X(BindImageMemory)               \
    X(CreateImageView) X(DestroyImageView) X(CreateBuffer) X(DestroyBuffer) X(GetBufferMemoryRequirements)         \
    X(BindBufferMemory) X(MapMemory) X(UnmapMemory) X(CreateSampler) X(DestroySampler) X(CreateRenderPass)         \
    X(DestroyRenderPass) X(CreateFramebuffer) X(DestroyFramebuffer) X(CreateShaderModule)                          \
    X(DestroyShaderModule) X(CreateDescriptorSetLayout) X(DestroyDescriptorSetLayout) X(CreateDescriptorPool)      \
    X(DestroyDescriptorPool) X(AllocateDescriptorSets) X(UpdateDescriptorSets) X(CreatePipelineLayout)             \
    X(DestroyPipelineLayout) X(CreateGraphicsPipelines) X(DestroyPipeline) X(CreateCommandPool)                    \
    X(DestroyCommandPool) X(AllocateCommandBuffers) X(BeginCommandBuffer) X(EndCommandBuffer)                      \
    X(CmdPipelineBarrier) X(CmdCopyBufferToImage) X(CmdBeginRenderPass) X(CmdEndRenderPass) X(CmdBindPipeline)    \
    X(CmdBindDescriptorSets) X(CmdSetViewport) X(CmdSetScissor) X(CmdDraw) X(CreateSemaphore)                      \
    X(DestroySemaphore) X(CreateFence) X(DestroyFence) X(WaitForFences) X(ResetFences)

class VkFramePresenter {
public:
    /** Creates the VkSurfaceKHR for an already-created instance. Returns VK_NULL_HANDLE on failure. */
    using SurfaceFactory = std::function<VkSurfaceKHR(VkInstance, PFN_vkGetInstanceProcAddr)>;
    /** Copies exactly frameW*frameH*4 bytes of pixels to dst. */
    using FrameFill = std::function<void(void* dst, size_t bytes)>;

    // Scene data texture (RGBA32F, see core/Scene.kt), the level's texture array, and the CPU-drawn frame.
    static constexpr uint32_t kDataW = 2048, kDataH = 8, kTexLayers = 8, kTexSize = 64;
    static constexpr size_t kDataBytes = size_t(kDataW) * kDataH * 16;
    static constexpr size_t kTexBytes = size_t(kTexLayers) * kTexSize * kTexSize * 4;
    static constexpr int kViewTexel = 12;  // vec4 (view width, view height, mode): written here, not by the caller

    /** One frame. fillData is required. Give fillFrame for a frame the CPU drew (mode 1), fillTex when the textures changed. */
    struct FrameInput {
        std::function<void(void* dst)> fillData;   // kDataBytes
        std::function<void(void* dst)> fillFrame;  // frameW*frameH*4 bytes, 0xAARRGGBB; makes this frame mode 1
        std::function<void(void* dst)> fillTex;    // kTexBytes: texture layers, 0xAARRGGBB
    };

    VkFramePresenter() = default;
    ~VkFramePresenter() { destroy(); }
    VkFramePresenter(const VkFramePresenter&) = delete;
    VkFramePresenter& operator=(const VkFramePresenter&) = delete;

    /** platformExts: the instance extension(s) the surface factory needs (VK_KHR_surface is added here).
     *  preferVsync: true → FIFO (display sync); false → MAILBOX then IMMEDIATE when available. */
    bool init(const std::vector<const char*>& platformExts, SurfaceFactory factory, uint32_t frameW, uint32_t frameH,
              bool preferVsync = true);
    /**
     * Draws one frame. false = unrecoverable (device or surface lost): stop using this presenter.
     * true also covers "skipped this frame" (window minimised, no image available within 100 ms).
     */
    bool present(const FrameInput& in, uint32_t viewW, uint32_t viewH);
    /** A frame the CPU drew (the scene data is empty and the shader just shows the frame). */
    bool present(const FrameFill& fill, uint32_t viewW, uint32_t viewH);
    void destroy();

    uint32_t frameW() const { return fw_; }
    uint32_t frameH() const { return fh_; }
    const std::string& deviceName() const { return deviceName_; }
    const std::string& error() const { return error_; }

private:
    static constexpr int kFrames = 2;  // frames in flight
    struct Staging { VkBuffer buf = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; void* mapped = nullptr; };
    struct Image { VkImage img = VK_NULL_HANDLE; VkDeviceMemory mem = VK_NULL_HANDLE; VkImageView view = VK_NULL_HANDLE; bool inited = false; };
    struct Frame {
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkSemaphore acquired = VK_NULL_HANDLE;
        Staging frame, data, tex;  // one set per frame in flight: the CPU fills the next while the GPU reads this one
    };

    struct Fns {
        PFN_vkGetInstanceProcAddr getInstanceProcAddr = nullptr;
        PFN_vkCreateInstance CreateInstance = nullptr;
        PFN_vkEnumerateInstanceExtensionProperties EnumerateInstanceExtensionProperties = nullptr;
#define X(n) PFN_vk##n n = nullptr;
        VK_INSTANCE_FNS(X)
        VK_DEVICE_FNS(X)
#undef X
    };

    bool fail(const char* what, VkResult r = VK_SUCCESS);
    bool loadLibrary();
    bool createInstance(const std::vector<const char*>& platformExts);
    bool pickDevice();
    bool createDevice();
    bool createFrameResources();
    bool createSwapchain(uint32_t viewW, uint32_t viewH);
    void destroySwapchain(bool keepHandle);
    bool recreateSwapchain(uint32_t viewW, uint32_t viewH);
    bool createRenderPassAndPipeline();
    bool createDescriptors();
    bool findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags want, uint32_t* out) const;
    bool makeImage(Image& im, VkFormat fmt, uint32_t w, uint32_t h, uint32_t layers, const char* what);
    bool makeStaging(Staging& s, VkDeviceSize bytes, const char* what);
    bool record(const Frame& f, uint32_t imageIndex, bool upFrame, bool upTex);

    std::string error_, deviceName_;
    SurfaceFactory factory_;
    uint32_t fw_ = 0, fh_ = 0;
    void* lib_ = nullptr;
    Fns fn_;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice phys_ = VK_NULL_HANDLE;
    VkPhysicalDeviceMemoryProperties memProps_{};
    uint32_t queueFamily_ = 0;
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue queue_ = VK_NULL_HANDLE;

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat format_ = VK_FORMAT_UNDEFINED;
    VkExtent2D extent_{};
    bool extentFromView_ = false;  // the surface lets us pick the size: follow the view
    bool needRecreate_ = false;
    bool preferVsync_ = true;  // FIFO when true; MAILBOX/IMMEDIATE when false
    std::vector<VkImage> images_;
    std::vector<VkImageView> views_;
    std::vector<VkFramebuffer> framebuffers_;
    std::vector<VkSemaphore> renderDone_;  // one per swapchain image

    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkDescriptorSetLayout setLayout_ = VK_NULL_HANDLE;
    VkPipelineLayout pipeLayout_ = VK_NULL_HANDLE;
    VkPipeline pipeline_ = VK_NULL_HANDLE;
    VkShaderModule vert_ = VK_NULL_HANDLE, frag_ = VK_NULL_HANDLE;
    VkDescriptorPool descPool_ = VK_NULL_HANDLE;
    VkDescriptorSet descSet_ = VK_NULL_HANDLE;

    Image frameTex_, dataTex_, arrTex_;   // binding 2, 0, 1 of the shader
    VkSampler nearest_ = VK_NULL_HANDLE, linear_ = VK_NULL_HANDLE;

    VkCommandPool pool_ = VK_NULL_HANDLE;
    std::array<Frame, kFrames> frames_{};
    int cur_ = 0;
    bool ready_ = false;
};
