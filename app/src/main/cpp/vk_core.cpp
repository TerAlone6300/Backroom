// vk_core.cpp - see vk_core.h.
#include "vk_core.h"
#include "vk_shaders.h"

#include <dlfcn.h>

#include <algorithm>
#include <cstdio>
#include <cstring>

#ifdef __ANDROID__
#include <android/log.h>
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, "BackroomsVk", __VA_ARGS__)
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, "BackroomsVk", __VA_ARGS__)
static const char* kLibName = "libvulkan.so";
#else
#define LOGW(...) (std::fprintf(stderr, "[vk] " __VA_ARGS__), std::fputc('\n', stderr))
#define LOGI(...) (std::fprintf(stderr, "[vk] " __VA_ARGS__), std::fputc('\n', stderr))
static const char* kLibName = "libvulkan.so.1";
#endif

namespace {
constexpr uint64_t kFenceTimeoutNs = 2000000000ull;  // a frame that takes longer than 2 s means the GPU is gone
constexpr uint64_t kAcquireTimeoutNs = 100000000ull;  // never block the render thread (or surfaceDestroyed) for long

template <typename T>
T clampU(T v, T lo, T hi) { return std::max(lo, std::min(v, hi)); }
}  // namespace

bool VkFramePresenter::fail(const char* what, VkResult r) {
    error_ = what;
    if (r != VK_SUCCESS) error_ += " (VkResult " + std::to_string(static_cast<int>(r)) + ")";
    LOGW("%s", error_.c_str());
    return false;
}

#define VK_TRY(call, what)                          \
    do {                                            \
        VkResult r_ = (call);                       \
        if (r_ != VK_SUCCESS) return fail(what, r_); \
    } while (0)

// ---------------------------------------------------------------------------------------------------------------
// init
// ---------------------------------------------------------------------------------------------------------------

bool VkFramePresenter::init(const std::vector<const char*>& platformExts, SurfaceFactory factory, uint32_t frameW,
                            uint32_t frameH) {
    fw_ = frameW;
    fh_ = frameH;
    factory_ = std::move(factory);
    if (!loadLibrary() || !createInstance(platformExts)) return false;

    surface_ = factory_(instance_, fn_.getInstanceProcAddr);
    if (surface_ == VK_NULL_HANDLE) return fail("surface creation failed");

    if (!pickDevice() || !createDevice() || !createFrameResources()) return false;
    if (!createSwapchain(frameW, frameH)) return false;
    if (swapchain_ == VK_NULL_HANDLE) return fail("surface has zero size");
    if (!createDescriptors() || !createRenderPassAndPipeline()) return false;

    // Framebuffers need the render pass, which needs the swapchain format: build them now.
    framebuffers_.resize(views_.size());
    for (size_t i = 0; i < views_.size(); i++) {
        VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
        fi.renderPass = renderPass_;
        fi.attachmentCount = 1;
        fi.pAttachments = &views_[i];
        fi.width = extent_.width;
        fi.height = extent_.height;
        fi.layers = 1;
        VK_TRY(fn_.CreateFramebuffer(device_, &fi, nullptr, &framebuffers_[i]), "vkCreateFramebuffer");
    }
    ready_ = true;
    LOGI("Vulkan ready on %s, swapchain %ux%u", deviceName_.c_str(), extent_.width, extent_.height);
    return true;
}

bool VkFramePresenter::loadLibrary() {
    lib_ = dlopen(kLibName, RTLD_NOW | RTLD_LOCAL);
    if (!lib_) return fail("Vulkan loader not found");
    fn_.getInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib_, "vkGetInstanceProcAddr"));
    if (!fn_.getInstanceProcAddr) return fail("vkGetInstanceProcAddr missing");
    fn_.CreateInstance = reinterpret_cast<PFN_vkCreateInstance>(fn_.getInstanceProcAddr(nullptr, "vkCreateInstance"));
    fn_.EnumerateInstanceExtensionProperties = reinterpret_cast<PFN_vkEnumerateInstanceExtensionProperties>(
        fn_.getInstanceProcAddr(nullptr, "vkEnumerateInstanceExtensionProperties"));
    if (!fn_.CreateInstance || !fn_.EnumerateInstanceExtensionProperties) return fail("Vulkan global functions missing");
    return true;
}

bool VkFramePresenter::createInstance(const std::vector<const char*>& platformExts) {
    std::vector<const char*> exts{VK_KHR_SURFACE_EXTENSION_NAME};
    exts.insert(exts.end(), platformExts.begin(), platformExts.end());

    uint32_t n = 0;
    VK_TRY(fn_.EnumerateInstanceExtensionProperties(nullptr, &n, nullptr), "enumerate instance extensions");
    std::vector<VkExtensionProperties> have(n);
    VK_TRY(fn_.EnumerateInstanceExtensionProperties(nullptr, &n, have.data()), "enumerate instance extensions");
    for (const char* want : exts) {
        bool found = std::any_of(have.begin(), have.end(), [&](const VkExtensionProperties& p) { return !std::strcmp(p.extensionName, want); });
        if (!found) return fail((std::string("missing instance extension ") + want).c_str());
    }

    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Backrooms";
    app.apiVersion = VK_API_VERSION_1_0;  // 1.0 is all this needs, and the widest device support
    VkInstanceCreateInfo ci{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    ci.pApplicationInfo = &app;
    ci.enabledExtensionCount = static_cast<uint32_t>(exts.size());
    ci.ppEnabledExtensionNames = exts.data();
    VK_TRY(fn_.CreateInstance(&ci, nullptr, &instance_), "vkCreateInstance");

#define X(name)                                                                                               \
    fn_.name = reinterpret_cast<PFN_vk##name>(fn_.getInstanceProcAddr(instance_, "vk" #name));                \
    if (!fn_.name) return fail("missing instance function vk" #name);
    VK_INSTANCE_FNS(X)
#undef X
    return true;
}

bool VkFramePresenter::pickDevice() {
    uint32_t n = 0;
    VK_TRY(fn_.EnumeratePhysicalDevices(instance_, &n, nullptr), "enumerate devices");
    if (n == 0) return fail("no Vulkan device");
    std::vector<VkPhysicalDevice> devs(n);
    VK_TRY(fn_.EnumeratePhysicalDevices(instance_, &n, devs.data()), "enumerate devices");

    int bestScore = -1;
    for (VkPhysicalDevice d : devs) {
        uint32_t en = 0;
        fn_.EnumerateDeviceExtensionProperties(d, nullptr, &en, nullptr);
        std::vector<VkExtensionProperties> ext(en);
        fn_.EnumerateDeviceExtensionProperties(d, nullptr, &en, ext.data());
        bool swap = std::any_of(ext.begin(), ext.end(), [](const VkExtensionProperties& p) { return !std::strcmp(p.extensionName, VK_KHR_SWAPCHAIN_EXTENSION_NAME); });
        if (!swap) continue;

        uint32_t qn = 0;
        fn_.GetPhysicalDeviceQueueFamilyProperties(d, &qn, nullptr);
        std::vector<VkQueueFamilyProperties> qf(qn);
        fn_.GetPhysicalDeviceQueueFamilyProperties(d, &qn, qf.data());
        for (uint32_t q = 0; q < qn; q++) {
            VkBool32 present = VK_FALSE;
            fn_.GetPhysicalDeviceSurfaceSupportKHR(d, q, surface_, &present);
            if (!(qf[q].queueFlags & VK_QUEUE_GRAPHICS_BIT) || !present) continue;
            VkPhysicalDeviceProperties props;
            fn_.GetPhysicalDeviceProperties(d, &props);
            int score = props.deviceType == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU     ? 3
                        : props.deviceType == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 2
                        : props.deviceType == VK_PHYSICAL_DEVICE_TYPE_CPU            ? 0
                                                                                      : 1;
            if (score > bestScore) {
                bestScore = score;
                phys_ = d;
                queueFamily_ = q;
                deviceName_ = props.deviceName;
            }
            break;
        }
    }
    if (phys_ == VK_NULL_HANDLE) return fail("no device can present to this surface");
    fn_.GetPhysicalDeviceMemoryProperties(phys_, &memProps_);
    return true;
}

bool VkFramePresenter::createDevice() {
    float prio = 1.0f;
    VkDeviceQueueCreateInfo qi{VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO};
    qi.queueFamilyIndex = queueFamily_;
    qi.queueCount = 1;
    qi.pQueuePriorities = &prio;
    const char* exts[] = {VK_KHR_SWAPCHAIN_EXTENSION_NAME};
    VkDeviceCreateInfo di{VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO};
    di.queueCreateInfoCount = 1;
    di.pQueueCreateInfos = &qi;
    di.enabledExtensionCount = 1;
    di.ppEnabledExtensionNames = exts;
    VK_TRY(fn_.CreateDevice(phys_, &di, nullptr, &device_), "vkCreateDevice");

#define X(name)                                                                                                \
    fn_.name = reinterpret_cast<PFN_vk##name>(fn_.GetDeviceProcAddr(device_, "vk" #name));                     \
    if (!fn_.name) return fail("missing device function vk" #name);
    VK_DEVICE_FNS(X)
#undef X
    fn_.GetDeviceQueue(device_, queueFamily_, 0, &queue_);
    return true;
}

bool VkFramePresenter::findMemoryType(uint32_t typeBits, VkMemoryPropertyFlags want, uint32_t* out) const {
    for (uint32_t i = 0; i < memProps_.memoryTypeCount; i++) {
        if ((typeBits & (1u << i)) && (memProps_.memoryTypes[i].propertyFlags & want) == want) {
            *out = i;
            return true;
        }
    }
    return false;
}

bool VkFramePresenter::createFrameResources() {
    const VkDeviceSize bytes = VkDeviceSize(fw_) * fh_ * 4;

    // The frame texture. B8G8R8A8 matches the in-memory order of the game's 0xAARRGGBB ints (little endian), so the
    // pixels are copied as they are. UNORM, not SRGB: the pixels are already display-encoded.
    VkImageCreateInfo ii{VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO};
    ii.imageType = VK_IMAGE_TYPE_2D;
    ii.format = VK_FORMAT_B8G8R8A8_UNORM;
    ii.extent = {fw_, fh_, 1};
    ii.mipLevels = 1;
    ii.arrayLayers = 1;
    ii.samples = VK_SAMPLE_COUNT_1_BIT;
    ii.tiling = VK_IMAGE_TILING_OPTIMAL;
    ii.usage = VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_SAMPLED_BIT;
    ii.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
    ii.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    VK_TRY(fn_.CreateImage(device_, &ii, nullptr, &tex_), "create frame texture");
    VkMemoryRequirements req;
    fn_.GetImageMemoryRequirements(device_, tex_, &req);
    VkMemoryAllocateInfo ai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
    ai.allocationSize = req.size;
    if (!findMemoryType(req.memoryTypeBits, VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, &ai.memoryTypeIndex) &&
        !findMemoryType(req.memoryTypeBits, 0, &ai.memoryTypeIndex))
        return fail("no memory type for the frame texture");
    VK_TRY(fn_.AllocateMemory(device_, &ai, nullptr, &texMem_), "allocate frame texture");
    VK_TRY(fn_.BindImageMemory(device_, tex_, texMem_, 0), "bind frame texture");

    VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
    vi.image = tex_;
    vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
    vi.format = ii.format;
    vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    VK_TRY(fn_.CreateImageView(device_, &vi, nullptr, &texView_), "create frame texture view");

    VkSamplerCreateInfo si{VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO};
    si.magFilter = si.minFilter = VK_FILTER_LINEAR;  // same smoothing as the Canvas and GLES paths
    si.addressModeU = si.addressModeV = si.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
    VK_TRY(fn_.CreateSampler(device_, &si, nullptr, &sampler_), "create sampler");

    VkCommandPoolCreateInfo pi{VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO};
    pi.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
    pi.queueFamilyIndex = queueFamily_;
    VK_TRY(fn_.CreateCommandPool(device_, &pi, nullptr, &pool_), "create command pool");

    for (Frame& f : frames_) {
        VkCommandBufferAllocateInfo cai{VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO};
        cai.commandPool = pool_;
        cai.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        cai.commandBufferCount = 1;
        VK_TRY(fn_.AllocateCommandBuffers(device_, &cai, &f.cmd), "allocate command buffer");

        VkFenceCreateInfo fi{VK_STRUCTURE_TYPE_FENCE_CREATE_INFO};
        fi.flags = VK_FENCE_CREATE_SIGNALED_BIT;  // the first wait must not block
        VK_TRY(fn_.CreateFence(device_, &fi, nullptr, &f.fence), "create fence");
        VkSemaphoreCreateInfo semi{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VK_TRY(fn_.CreateSemaphore(device_, &semi, nullptr, &f.acquired), "create semaphore");

        // One staging buffer per frame in flight, so the CPU can fill the next one while the GPU still reads this one.
        VkBufferCreateInfo bi{VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO};
        bi.size = bytes;
        bi.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
        bi.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        VK_TRY(fn_.CreateBuffer(device_, &bi, nullptr, &f.staging), "create staging buffer");
        VkMemoryRequirements breq;
        fn_.GetBufferMemoryRequirements(device_, f.staging, &breq);
        VkMemoryAllocateInfo bai{VK_STRUCTURE_TYPE_MEMORY_ALLOCATE_INFO};
        bai.allocationSize = breq.size;
        if (!findMemoryType(breq.memoryTypeBits, VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT, &bai.memoryTypeIndex))
            return fail("no host-visible coherent memory for the staging buffer");
        VK_TRY(fn_.AllocateMemory(device_, &bai, nullptr, &f.stagingMem), "allocate staging buffer");
        VK_TRY(fn_.BindBufferMemory(device_, f.staging, f.stagingMem, 0), "bind staging buffer");
        VK_TRY(fn_.MapMemory(device_, f.stagingMem, 0, VK_WHOLE_SIZE, 0, &f.mapped), "map staging buffer");
    }
    return true;
}

bool VkFramePresenter::createDescriptors() {
    VkDescriptorSetLayoutBinding b{};
    b.binding = 0;
    b.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    b.descriptorCount = 1;
    b.stageFlags = VK_SHADER_STAGE_FRAGMENT_BIT;
    VkDescriptorSetLayoutCreateInfo li{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO};
    li.bindingCount = 1;
    li.pBindings = &b;
    VK_TRY(fn_.CreateDescriptorSetLayout(device_, &li, nullptr, &setLayout_), "create descriptor set layout");

    VkDescriptorPoolSize ps{VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1};
    VkDescriptorPoolCreateInfo pi{VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO};
    pi.maxSets = 1;
    pi.poolSizeCount = 1;
    pi.pPoolSizes = &ps;
    VK_TRY(fn_.CreateDescriptorPool(device_, &pi, nullptr, &descPool_), "create descriptor pool");

    VkDescriptorSetAllocateInfo ai{VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO};
    ai.descriptorPool = descPool_;
    ai.descriptorSetCount = 1;
    ai.pSetLayouts = &setLayout_;
    VK_TRY(fn_.AllocateDescriptorSets(device_, &ai, &descSet_), "allocate descriptor set");

    VkDescriptorImageInfo img{sampler_, texView_, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL};
    VkWriteDescriptorSet w{VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET};
    w.dstSet = descSet_;
    w.dstBinding = 0;
    w.descriptorCount = 1;
    w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
    w.pImageInfo = &img;
    fn_.UpdateDescriptorSets(device_, 1, &w, 0, nullptr);
    return true;
}

bool VkFramePresenter::createRenderPassAndPipeline() {
    VkAttachmentDescription att{};
    att.format = format_;
    att.samples = VK_SAMPLE_COUNT_1_BIT;
    att.loadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;  // the triangle covers every pixel
    att.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
    att.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
    att.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
    att.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    att.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
    VkAttachmentReference ref{0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL};
    VkSubpassDescription sub{};
    sub.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
    sub.colorAttachmentCount = 1;
    sub.pColorAttachments = &ref;
    VkSubpassDependency dep{};  // pairs with the acquire semaphore's wait stage in submit
    dep.srcSubpass = VK_SUBPASS_EXTERNAL;
    dep.dstSubpass = 0;
    dep.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    dep.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;
    VkRenderPassCreateInfo rpi{VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO};
    rpi.attachmentCount = 1;
    rpi.pAttachments = &att;
    rpi.subpassCount = 1;
    rpi.pSubpasses = &sub;
    rpi.dependencyCount = 1;
    rpi.pDependencies = &dep;
    VK_TRY(fn_.CreateRenderPass(device_, &rpi, nullptr, &renderPass_), "create render pass");

    VkShaderModuleCreateInfo mi{VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO};
    mi.codeSize = sizeof(kVertSpv);
    mi.pCode = kVertSpv;
    VK_TRY(fn_.CreateShaderModule(device_, &mi, nullptr, &vert_), "create vertex shader");
    mi.codeSize = sizeof(kFragSpv);
    mi.pCode = kFragSpv;
    VK_TRY(fn_.CreateShaderModule(device_, &mi, nullptr, &frag_), "create fragment shader");

    VkPipelineLayoutCreateInfo pli{VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO};
    pli.setLayoutCount = 1;
    pli.pSetLayouts = &setLayout_;
    VK_TRY(fn_.CreatePipelineLayout(device_, &pli, nullptr, &pipeLayout_), "create pipeline layout");

    VkPipelineShaderStageCreateInfo stages[2]{};
    stages[0].sType = stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
    stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
    stages[0].module = vert_;
    stages[0].pName = "main";
    stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
    stages[1].module = frag_;
    stages[1].pName = "main";

    VkPipelineVertexInputStateCreateInfo vin{VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO};
    VkPipelineInputAssemblyStateCreateInfo ia{VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO};
    ia.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
    VkPipelineViewportStateCreateInfo vp{VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO};
    vp.viewportCount = 1;  // viewport and scissor are dynamic, so a resize does not rebuild the pipeline
    vp.scissorCount = 1;
    VkPipelineRasterizationStateCreateInfo rs{VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO};
    rs.polygonMode = VK_POLYGON_MODE_FILL;
    rs.cullMode = VK_CULL_MODE_NONE;
    rs.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
    rs.lineWidth = 1.0f;
    VkPipelineMultisampleStateCreateInfo ms{VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO};
    ms.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
    VkPipelineColorBlendAttachmentState cba{};
    cba.colorWriteMask = VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT;
    VkPipelineColorBlendStateCreateInfo cb{VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO};
    cb.attachmentCount = 1;
    cb.pAttachments = &cba;
    const VkDynamicState dyn[] = {VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR};
    VkPipelineDynamicStateCreateInfo ds{VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO};
    ds.dynamicStateCount = 2;
    ds.pDynamicStates = dyn;

    VkGraphicsPipelineCreateInfo gi{VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO};
    gi.stageCount = 2;
    gi.pStages = stages;
    gi.pVertexInputState = &vin;
    gi.pInputAssemblyState = &ia;
    gi.pViewportState = &vp;
    gi.pRasterizationState = &rs;
    gi.pMultisampleState = &ms;
    gi.pColorBlendState = &cb;
    gi.pDynamicState = &ds;
    gi.layout = pipeLayout_;
    gi.renderPass = renderPass_;
    gi.subpass = 0;
    VK_TRY(fn_.CreateGraphicsPipelines(device_, VK_NULL_HANDLE, 1, &gi, nullptr, &pipeline_), "create pipeline");
    return true;
}

// ---------------------------------------------------------------------------------------------------------------
// swapchain
// ---------------------------------------------------------------------------------------------------------------

bool VkFramePresenter::createSwapchain(uint32_t viewW, uint32_t viewH) {
    VkSurfaceCapabilitiesKHR caps;
    VK_TRY(fn_.GetPhysicalDeviceSurfaceCapabilitiesKHR(phys_, surface_, &caps), "query surface capabilities");

    extentFromView_ = caps.currentExtent.width == 0xFFFFFFFFu;
    VkExtent2D ext = caps.currentExtent;
    if (extentFromView_) {
        ext.width = clampU(viewW, caps.minImageExtent.width, caps.maxImageExtent.width);
        ext.height = clampU(viewH, caps.minImageExtent.height, caps.maxImageExtent.height);
    }
    if (ext.width == 0 || ext.height == 0) {  // minimised: no swapchain until the surface has a size again
        destroySwapchain(false);
        return true;
    }

    uint32_t fn = 0;
    VK_TRY(fn_.GetPhysicalDeviceSurfaceFormatsKHR(phys_, surface_, &fn, nullptr), "query surface formats");
    std::vector<VkSurfaceFormatKHR> formats(fn);
    VK_TRY(fn_.GetPhysicalDeviceSurfaceFormatsKHR(phys_, surface_, &fn, formats.data()), "query surface formats");
    if (formats.empty()) return fail("surface reports no formats");
    // A UNORM format: the frame's colours are already display-encoded, an SRGB format would encode them a second time.
    VkSurfaceFormatKHR chosen = formats[0];
    bool found = false;
    for (VkFormat want : {VK_FORMAT_B8G8R8A8_UNORM, VK_FORMAT_R8G8B8A8_UNORM}) {
        for (const VkSurfaceFormatKHR& f : formats) {
            if (f.format == want && f.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) { chosen = f; found = true; break; }
        }
        if (found) break;
    }
    if (!found) LOGW("no UNORM swapchain format offered; colours may look washed out");
    if (renderPass_ != VK_NULL_HANDLE && chosen.format != format_) return fail("swapchain format changed");
    format_ = chosen.format;

    VkSwapchainCreateInfoKHR sci{VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR};
    sci.surface = surface_;
    sci.minImageCount = caps.minImageCount + 1;
    if (caps.maxImageCount > 0 && sci.minImageCount > caps.maxImageCount) sci.minImageCount = caps.maxImageCount;
    sci.imageFormat = chosen.format;
    sci.imageColorSpace = chosen.colorSpace;
    sci.imageExtent = ext;
    sci.imageArrayLayers = 1;
    sci.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
    sci.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
    // Identity: draw upright and let the compositor rotate if the display is rotated (the game is sensorLandscape).
    sci.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR) ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR : caps.currentTransform;
    sci.compositeAlpha = VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR;
    for (VkCompositeAlphaFlagBitsKHR a : {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR, VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR}) {
        if (caps.supportedCompositeAlpha & a) { sci.compositeAlpha = a; break; }
    }
    sci.presentMode = VK_PRESENT_MODE_FIFO_KHR;  // the only mode every implementation must offer; also the vsync pacing
    sci.clipped = VK_TRUE;
    VkSwapchainKHR old = swapchain_;
    sci.oldSwapchain = old;
    VkSwapchainKHR created = VK_NULL_HANDLE;
    VkResult r = fn_.CreateSwapchainKHR(device_, &sci, nullptr, &created);
    if (old != VK_NULL_HANDLE) fn_.DestroySwapchainKHR(device_, old, nullptr);  // retired by the create call, free it either way
    swapchain_ = created;
    if (r != VK_SUCCESS) { swapchain_ = VK_NULL_HANDLE; return fail("vkCreateSwapchainKHR", r); }
    extent_ = ext;

    uint32_t n = 0;
    VK_TRY(fn_.GetSwapchainImagesKHR(device_, swapchain_, &n, nullptr), "get swapchain images");
    images_.resize(n);
    VK_TRY(fn_.GetSwapchainImagesKHR(device_, swapchain_, &n, images_.data()), "get swapchain images");

    views_.assign(n, VK_NULL_HANDLE);
    renderDone_.assign(n, VK_NULL_HANDLE);
    for (uint32_t i = 0; i < n; i++) {
        VkImageViewCreateInfo vi{VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO};
        vi.image = images_[i];
        vi.viewType = VK_IMAGE_VIEW_TYPE_2D;
        vi.format = format_;
        vi.subresourceRange = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
        VK_TRY(fn_.CreateImageView(device_, &vi, nullptr, &views_[i]), "create swapchain view");
        VkSemaphoreCreateInfo semi{VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO};
        VK_TRY(fn_.CreateSemaphore(device_, &semi, nullptr, &renderDone_[i]), "create semaphore");
    }
    // Framebuffers once the render pass exists (init builds them; recreateSwapchain builds them right after this).
    if (renderPass_ != VK_NULL_HANDLE) {
        framebuffers_.assign(n, VK_NULL_HANDLE);
        for (uint32_t i = 0; i < n; i++) {
            VkFramebufferCreateInfo fi{VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO};
            fi.renderPass = renderPass_;
            fi.attachmentCount = 1;
            fi.pAttachments = &views_[i];
            fi.width = extent_.width;
            fi.height = extent_.height;
            fi.layers = 1;
            VK_TRY(fn_.CreateFramebuffer(device_, &fi, nullptr, &framebuffers_[i]), "create framebuffer");
        }
    }
    return true;
}

void VkFramePresenter::destroySwapchain(bool keepHandle) {
    for (VkFramebuffer f : framebuffers_) if (f) fn_.DestroyFramebuffer(device_, f, nullptr);
    for (VkImageView v : views_) if (v) fn_.DestroyImageView(device_, v, nullptr);
    for (VkSemaphore s : renderDone_) if (s) fn_.DestroySemaphore(device_, s, nullptr);
    framebuffers_.clear();
    views_.clear();
    renderDone_.clear();
    images_.clear();
    if (!keepHandle && swapchain_ != VK_NULL_HANDLE) {
        fn_.DestroySwapchainKHR(device_, swapchain_, nullptr);
        swapchain_ = VK_NULL_HANDLE;
    }
}

bool VkFramePresenter::recreateSwapchain(uint32_t viewW, uint32_t viewH) {
    fn_.DeviceWaitIdle(device_);
    destroySwapchain(true);  // keep the handle: it becomes oldSwapchain of the new one
    return createSwapchain(viewW, viewH);
}

// ---------------------------------------------------------------------------------------------------------------
// per frame
// ---------------------------------------------------------------------------------------------------------------

bool VkFramePresenter::record(const Frame& f, uint32_t imageIndex) {
    VkCommandBufferBeginInfo bi{VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO};
    bi.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
    VK_TRY(fn_.BeginCommandBuffer(f.cmd, &bi), "begin command buffer");

    const VkImageSubresourceRange range{VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1};
    // Texture: discard the old contents (the whole image is overwritten) and wait for last frame's sampling to finish.
    VkImageMemoryBarrier toDst{VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER};
    toDst.srcAccessMask = 0;
    toDst.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toDst.oldLayout = VK_IMAGE_LAYOUT_UNDEFINED;
    toDst.newLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toDst.srcQueueFamilyIndex = toDst.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
    toDst.image = tex_;
    toDst.subresourceRange = range;
    fn_.CmdPipelineBarrier(f.cmd, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toDst);

    VkBufferImageCopy copy{};
    copy.imageSubresource = {VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1};
    copy.imageExtent = {fw_, fh_, 1};
    fn_.CmdCopyBufferToImage(f.cmd, f.staging, tex_, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);

    VkImageMemoryBarrier toRead = toDst;
    toRead.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
    toRead.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
    toRead.oldLayout = VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL;
    toRead.newLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
    fn_.CmdPipelineBarrier(f.cmd, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, 0, 0, nullptr, 0, nullptr, 1, &toRead);

    VkRenderPassBeginInfo rb{VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO};
    rb.renderPass = renderPass_;
    rb.framebuffer = framebuffers_[imageIndex];
    rb.renderArea = {{0, 0}, extent_};
    fn_.CmdBeginRenderPass(f.cmd, &rb, VK_SUBPASS_CONTENTS_INLINE);
    fn_.CmdBindPipeline(f.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeline_);
    fn_.CmdBindDescriptorSets(f.cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipeLayout_, 0, 1, &descSet_, 0, nullptr);
    VkViewport vp{0.0f, 0.0f, static_cast<float>(extent_.width), static_cast<float>(extent_.height), 0.0f, 1.0f};
    VkRect2D sc{{0, 0}, extent_};
    fn_.CmdSetViewport(f.cmd, 0, 1, &vp);
    fn_.CmdSetScissor(f.cmd, 0, 1, &sc);
    fn_.CmdDraw(f.cmd, 3, 1, 0, 0);
    fn_.CmdEndRenderPass(f.cmd);
    VK_TRY(fn_.EndCommandBuffer(f.cmd), "end command buffer");
    return true;
}

bool VkFramePresenter::present(const FrameFill& fill, uint32_t viewW, uint32_t viewH) {
    if (!ready_) return false;
    if (swapchain_ == VK_NULL_HANDLE || needRecreate_ || (extentFromView_ && (viewW != extent_.width || viewH != extent_.height))) {
        if (!recreateSwapchain(viewW, viewH)) return false;
        needRecreate_ = false;
        if (swapchain_ == VK_NULL_HANDLE) return true;  // still zero-sized: nothing to draw on
    }

    Frame& f = frames_[cur_];
    VkResult r = fn_.WaitForFences(device_, 1, &f.fence, VK_TRUE, kFenceTimeoutNs);
    if (r != VK_SUCCESS) return fail("waiting for the previous frame", r);

    uint32_t idx = 0;
    r = fn_.AcquireNextImageKHR(device_, swapchain_, kAcquireTimeoutNs, f.acquired, VK_NULL_HANDLE, &idx);
    if (r == VK_TIMEOUT || r == VK_NOT_READY) return true;  // no image yet; the fence is still signalled, try next frame
    if (r == VK_ERROR_OUT_OF_DATE_KHR) { needRecreate_ = true; return true; }
    if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return fail("vkAcquireNextImageKHR", r);
    // VK_SUBOPTIMAL_KHR is fine: with an identity preTransform on a rotated display it is reported on every frame.

    VK_TRY(fn_.ResetFences(device_, 1, &f.fence), "reset fence");
    fill(f.mapped, size_t(fw_) * fh_ * 4);
    if (!record(f, idx)) return false;

    const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
    VkSubmitInfo si{VK_STRUCTURE_TYPE_SUBMIT_INFO};
    si.waitSemaphoreCount = 1;
    si.pWaitSemaphores = &f.acquired;
    si.pWaitDstStageMask = &waitStage;
    si.commandBufferCount = 1;
    si.pCommandBuffers = &f.cmd;
    si.signalSemaphoreCount = 1;
    si.pSignalSemaphores = &renderDone_[idx];
    VK_TRY(fn_.QueueSubmit(queue_, 1, &si, f.fence), "vkQueueSubmit");

    VkPresentInfoKHR pi{VK_STRUCTURE_TYPE_PRESENT_INFO_KHR};
    pi.waitSemaphoreCount = 1;
    pi.pWaitSemaphores = &renderDone_[idx];
    pi.swapchainCount = 1;
    pi.pSwapchains = &swapchain_;
    pi.pImageIndices = &idx;
    r = fn_.QueuePresentKHR(queue_, &pi);
    if (r == VK_ERROR_OUT_OF_DATE_KHR) needRecreate_ = true;
    else if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) return fail("vkQueuePresentKHR", r);

    cur_ = (cur_ + 1) % kFrames;
    return true;
}

// ---------------------------------------------------------------------------------------------------------------
// teardown (safe on a half-initialised object)
// ---------------------------------------------------------------------------------------------------------------

void VkFramePresenter::destroy() {
    ready_ = false;
    if (device_ != VK_NULL_HANDLE) {
        fn_.DeviceWaitIdle(device_);
        for (Frame& f : frames_) {
            if (f.mapped) fn_.UnmapMemory(device_, f.stagingMem);
            if (f.staging) fn_.DestroyBuffer(device_, f.staging, nullptr);
            if (f.stagingMem) fn_.FreeMemory(device_, f.stagingMem, nullptr);
            if (f.acquired) fn_.DestroySemaphore(device_, f.acquired, nullptr);
            if (f.fence) fn_.DestroyFence(device_, f.fence, nullptr);
            f = Frame{};
        }
        if (pool_) fn_.DestroyCommandPool(device_, pool_, nullptr);  // frees the command buffers
        destroySwapchain(false);
        if (pipeline_) fn_.DestroyPipeline(device_, pipeline_, nullptr);
        if (pipeLayout_) fn_.DestroyPipelineLayout(device_, pipeLayout_, nullptr);
        if (vert_) fn_.DestroyShaderModule(device_, vert_, nullptr);
        if (frag_) fn_.DestroyShaderModule(device_, frag_, nullptr);
        if (renderPass_) fn_.DestroyRenderPass(device_, renderPass_, nullptr);
        if (descPool_) fn_.DestroyDescriptorPool(device_, descPool_, nullptr);  // frees the descriptor set
        if (setLayout_) fn_.DestroyDescriptorSetLayout(device_, setLayout_, nullptr);
        if (sampler_) fn_.DestroySampler(device_, sampler_, nullptr);
        if (texView_) fn_.DestroyImageView(device_, texView_, nullptr);
        if (tex_) fn_.DestroyImage(device_, tex_, nullptr);
        if (texMem_) fn_.FreeMemory(device_, texMem_, nullptr);
        fn_.DestroyDevice(device_, nullptr);
    }
    if (instance_ != VK_NULL_HANDLE) {
        if (surface_) fn_.DestroySurfaceKHR(instance_, surface_, nullptr);
        fn_.DestroyInstance(instance_, nullptr);
    }
    pool_ = VK_NULL_HANDLE; pipeline_ = VK_NULL_HANDLE; pipeLayout_ = VK_NULL_HANDLE; vert_ = frag_ = VK_NULL_HANDLE;
    renderPass_ = VK_NULL_HANDLE; descPool_ = VK_NULL_HANDLE; setLayout_ = VK_NULL_HANDLE; sampler_ = VK_NULL_HANDLE;
    texView_ = VK_NULL_HANDLE; tex_ = VK_NULL_HANDLE; texMem_ = VK_NULL_HANDLE; descSet_ = VK_NULL_HANDLE;
    device_ = VK_NULL_HANDLE; surface_ = VK_NULL_HANDLE; instance_ = VK_NULL_HANDLE; swapchain_ = VK_NULL_HANDLE;
    // libvulkan stays loaded on purpose: unloading a Vulkan driver after use is a known source of crashes.
}
