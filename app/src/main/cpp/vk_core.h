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
    X(GetPhysicalDeviceSurfaceFormatsKHR) X(EnumerateDeviceExtensionProperties) X(CreateDevice)                    \
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

    VkFramePresenter() = default;
    ~VkFramePresenter() { destroy(); }
    VkFramePresenter(const VkFramePresenter&) = delete;
    VkFramePresenter& operator=(const VkFramePresenter&) = delete;

    /** platformExts: the instance extension(s) the surface factory needs (VK_KHR_surface is added here). */
    bool init(const std::vector<const char*>& platformExts, SurfaceFactory factory, uint32_t frameW, uint32_t frameH);
    /**
     * Draws one frame. false = unrecoverable (device or surface lost): stop using this presenter.
     * true also covers "skipped this frame" (window minimised, no image available within 100 ms).
     */
    bool present(const FrameFill& fill, uint32_t viewW, uint32_t viewH);
    void destroy();

    uint32_t frameW() const { return fw_; }
    uint32_t frameH() const { return fh_; }
    const std::string& deviceName() const { return deviceName_; }
    const std::string& error() const { return error_; }

private:
    static constexpr int kFrames = 2;  // frames in flight
    struct Frame {
        VkCommandBuffer cmd = VK_NULL_HANDLE;
        VkFence fence = VK_NULL_HANDLE;
        VkSemaphore acquired = VK_NULL_HANDLE;
        VkBuffer staging = VK_NULL_HANDLE;
        VkDeviceMemory stagingMem = VK_NULL_HANDLE;
        void* mapped = nullptr;
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
    bool record(const Frame& f, uint32_t imageIndex);

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

    VkImage tex_ = VK_NULL_HANDLE;
    VkDeviceMemory texMem_ = VK_NULL_HANDLE;
    VkImageView texView_ = VK_NULL_HANDLE;
    VkSampler sampler_ = VK_NULL_HANDLE;

    VkCommandPool pool_ = VK_NULL_HANDLE;
    std::array<Frame, kFrames> frames_{};
    int cur_ = 0;
    bool ready_ = false;
};
