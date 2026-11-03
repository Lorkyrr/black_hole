package blackhole;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK13.*;

import blackhole.sim.Camera;
import blackhole.sim.Obj;
import blackhole.sim.Sim;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.joml.Matrix4f;
import org.joml.Vector3f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

/**
 * Renderer Vulkan 1.3: porte de C++/black_hole_vk.cpp. Mesma simulação, mesmo ray tracer em compute shader, mesmo grid.
 * Usa dynamic rendering + synchronization2, então não há render pass nem framebuffers.
 * Os shaders GLSL são compilados para SPIR-V na hora (shaderc), em vez de um glslc no build como no C++.
 */
public final class VulkanRenderer {
    static final int WIDTH = 800, HEIGHT = 600;
    static final int COMPUTE_WIDTH = 200, COMPUTE_HEIGHT = 150;
    private static final int CAMERA_SIZE = 80, DISK_SIZE = 16, OBJECTS_SIZE = 16 + 16 * 16 * 2; // tamanhos std140

    /** Setado pelo callback de resize do GLFW. */
    static volatile boolean resized = false;

    private record Buf(long buf, long mem, ByteBuffer map) {}

    private final long window;
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice phys;
    private VkDevice device;
    private int queueFamily;
    private VkQueue queue;

    private long swapchain = VK_NULL_HANDLE;
    private int scFormat;
    private int scWidth, scHeight;
    private long[] scImages, scViews, renderDone;

    private long image, imageMem, imageView, sampler;
    private Buf camU, diskU, objU, gridV, gridI;
    private int gridIndexCount;
    private long dsl, pipelineLayout, pool, descriptorSet;
    private long computePipe, gridPipe, quadPipe;
    private long cmdPool, imageAvail, fence;
    private VkCommandBuffer cmd;

    public VulkanRenderer(long window) {
        this.window = window;
        createInstance();
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            check(glfwCreateWindowSurface(instance, window, null, p), "glfwCreateWindowSurface");
            surface = p.get(0);
        }
        pickDevice();
        createSwapchain();
        createResources();
        createPipelines();

        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateCommandPool(device, VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(queueFamily), null, p), "vkCreateCommandPool");
            cmdPool = p.get(0);
            PointerBuffer pc = stack.mallocPointer(1);
            check(vkAllocateCommandBuffers(device, VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                    .commandPool(cmdPool).level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1), pc), "vkAllocateCommandBuffers");
            cmd = new VkCommandBuffer(pc.get(0), device);
            check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, p), "vkCreateSemaphore");
            imageAvail = p.get(0);
            check(vkCreateFence(device, VkFenceCreateInfo.calloc(stack).sType$Default().flags(VK_FENCE_CREATE_SIGNALED_BIT), null, p), "vkCreateFence");
            fence = p.get(0);
        }
    }

    static void check(int result, String what) {
        if (result != VK_SUCCESS) throw new IllegalStateException(what + " failed: VkResult " + result);
    }

    // ---------- init ----------

    private void createInstance() {
        try (MemoryStack stack = stackPush()) {
            PointerBuffer exts = glfwGetRequiredInstanceExtensions();
            if (exts == null) throw new IllegalStateException("GLFW: no Vulkan instance extensions");
            VkInstanceCreateInfo ci = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .pApplicationInfo(VkApplicationInfo.calloc(stack).sType$Default()
                            .pApplicationName(stack.UTF8("Black Hole")).apiVersion(VK_API_VERSION_1_3))
                    .ppEnabledExtensionNames(exts);
            // Validation layer só com -Dvk.validation=true (e se estiver instalada), como o build Debug do C++.
            String validation = "VK_LAYER_KHRONOS_validation";
            if (Boolean.getBoolean("vk.validation")) {
                IntBuffer n = stack.mallocInt(1);
                vkEnumerateInstanceLayerProperties(n, null);
                VkLayerProperties.Buffer layers = VkLayerProperties.calloc(n.get(0), stack);
                vkEnumerateInstanceLayerProperties(n, layers);
                for (VkLayerProperties l : layers) {
                    if (l.layerNameString().equals(validation)) {
                        ci.ppEnabledLayerNames(stack.pointers(stack.UTF8(validation)));
                        System.out.println("[INFO] Vulkan validation layer ON");
                    }
                }
            }
            PointerBuffer p = stack.mallocPointer(1);
            check(vkCreateInstance(ci, null, p), "vkCreateInstance");
            instance = new VkInstance(p.get(0), ci);
        }
    }

    private void pickDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer n = stack.mallocInt(1);
            vkEnumeratePhysicalDevices(instance, n, null);
            PointerBuffer devs = stack.mallocPointer(n.get(0));
            vkEnumeratePhysicalDevices(instance, n, devs);
            int bestScore = -1;
            VkPhysicalDeviceProperties props = VkPhysicalDeviceProperties.calloc(stack);
            for (int d = 0; d < devs.capacity(); d++) {
                VkPhysicalDevice pd = new VkPhysicalDevice(devs.get(d), instance);
                vkGetPhysicalDeviceProperties(pd, props);
                if (props.apiVersion() < VK_API_VERSION_1_3) continue;
                IntBuffer qn = stack.mallocInt(1);
                vkGetPhysicalDeviceQueueFamilyProperties(pd, qn, null);
                VkQueueFamilyProperties.Buffer qf = VkQueueFamilyProperties.calloc(qn.get(0), stack);
                vkGetPhysicalDeviceQueueFamilyProperties(pd, qn, qf);
                for (int i = 0; i < qf.capacity(); i++) {
                    IntBuffer present = stack.mallocInt(1);
                    vkGetPhysicalDeviceSurfaceSupportKHR(pd, i, surface, present);
                    int flags = qf.get(i).queueFlags();
                    if (present.get(0) == 0 || (flags & VK_QUEUE_GRAPHICS_BIT) == 0 || (flags & VK_QUEUE_COMPUTE_BIT) == 0) continue;
                    int score = props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 2
                            : props.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU ? 1 : 0;
                    if (score > bestScore) {
                        bestScore = score;
                        phys = pd;
                        queueFamily = i;
                    }
                    break;
                }
            }
            if (bestScore < 0) throw new IllegalStateException("No Vulkan 1.3 GPU with graphics+compute+present queue");
            vkGetPhysicalDeviceProperties(phys, props);
            System.out.println("Vulkan device: " + props.deviceNameString());

            VkDeviceCreateInfo di = VkDeviceCreateInfo.calloc(stack).sType$Default()
                    .pNext(VkPhysicalDeviceVulkan13Features.calloc(stack).sType$Default()
                            .dynamicRendering(true).synchronization2(true))
                    .pQueueCreateInfos(VkDeviceQueueCreateInfo.calloc(1, stack).sType$Default()
                            .queueFamilyIndex(queueFamily).pQueuePriorities(stack.floats(1f)))
                    .ppEnabledExtensionNames(stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME)));
            PointerBuffer pd = stack.mallocPointer(1);
            check(vkCreateDevice(phys, di, null, pd), "vkCreateDevice");
            device = new VkDevice(pd.get(0), phys, di);
            PointerBuffer pq = stack.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamily, 0, pq);
            queue = new VkQueue(pq.get(0), device);
        }
    }

    private void createSwapchain() {
        try (MemoryStack stack = stackPush()) {
            VkSurfaceCapabilitiesKHR caps = VkSurfaceCapabilitiesKHR.calloc(stack);
            check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(phys, surface, caps), "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
            IntBuffer n = stack.mallocInt(1);
            vkGetPhysicalDeviceSurfaceFormatsKHR(phys, surface, n, null);
            VkSurfaceFormatKHR.Buffer fmts = VkSurfaceFormatKHR.calloc(n.get(0), stack);
            vkGetPhysicalDeviceSurfaceFormatsKHR(phys, surface, n, fmts);
            // Só colorspaces SRGB_NONLINEAR (os outros exigem VK_EXT_swapchain_colorspace). UNORM, não *_SRGB: o framebuffer do GL também gravava valores crus.
            VkSurfaceFormatKHR chosen = fmts.get(0);
            for (VkSurfaceFormatKHR f : fmts) {
                if (f.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                    chosen = f;
                    if (f.format() == VK_FORMAT_B8G8R8A8_UNORM) break;
                }
            }
            scFormat = chosen.format();
            int colorSpace = chosen.colorSpace();

            if (caps.currentExtent().width() != 0xFFFFFFFF) {
                scWidth = caps.currentExtent().width();
                scHeight = caps.currentExtent().height();
            } else {
                IntBuffer w = stack.mallocInt(1), h = stack.mallocInt(1);
                glfwGetFramebufferSize(window, w, h);
                scWidth = Math.clamp(w.get(0), caps.minImageExtent().width(), caps.maxImageExtent().width());
                scHeight = Math.clamp(h.get(0), caps.minImageExtent().height(), caps.maxImageExtent().height());
            }
            int count = caps.minImageCount() + 1;
            if (caps.maxImageCount() > 0 && count > caps.maxImageCount()) count = caps.maxImageCount();

            VkSwapchainCreateInfoKHR ci = VkSwapchainCreateInfoKHR.calloc(stack).sType$Default()
                    .surface(surface).minImageCount(count).imageFormat(scFormat).imageColorSpace(colorSpace)
                    .imageArrayLayers(1).imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .imageSharingMode(VK_SHARING_MODE_EXCLUSIVE).preTransform(caps.currentTransform())
                    .compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
                    .presentMode(VK_PRESENT_MODE_FIFO_KHR) // sempre disponível, vsync
                    .clipped(true);
            ci.imageExtent().set(scWidth, scHeight);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateSwapchainKHR(device, ci, null, p), "vkCreateSwapchainKHR");
            swapchain = p.get(0);

            vkGetSwapchainImagesKHR(device, swapchain, n, null);
            LongBuffer imgs = stack.mallocLong(n.get(0));
            vkGetSwapchainImagesKHR(device, swapchain, n, imgs);
            scImages = new long[n.get(0)];
            scViews = new long[n.get(0)];
            renderDone = new long[n.get(0)];
            for (int i = 0; i < scImages.length; i++) {
                scImages[i] = imgs.get(i);
                scViews[i] = createView(stack, scImages[i], scFormat);
                check(vkCreateSemaphore(device, VkSemaphoreCreateInfo.calloc(stack).sType$Default(), null, p), "vkCreateSemaphore");
                renderDone[i] = p.get(0); // um por imagem do swapchain: o present ainda pode estar segurando
            }
        }
    }

    private long createView(MemoryStack stack, long img, int format) {
        LongBuffer p = stack.mallocLong(1);
        check(vkCreateImageView(device, VkImageViewCreateInfo.calloc(stack).sType$Default()
                .image(img).viewType(VK_IMAGE_VIEW_TYPE_2D).format(format)
                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1)), null, p), "vkCreateImageView");
        return p.get(0);
    }

    private void destroySwapchain() {
        for (long s : renderDone) vkDestroySemaphore(device, s, null);
        for (long v : scViews) vkDestroyImageView(device, v, null);
        vkDestroySwapchainKHR(device, swapchain, null);
    }

    private void recreateSwapchain() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer w = stack.ints(0), h = stack.ints(0);
            glfwGetFramebufferSize(window, w, h);
            while (w.get(0) == 0 || h.get(0) == 0) { // minimizada
                glfwWaitEvents();
                glfwGetFramebufferSize(window, w, h);
            }
        }
        vkDeviceWaitIdle(device);
        destroySwapchain();
        createSwapchain();
        resized = false;
    }

    private int findMemType(MemoryStack stack, int typeBits, int want) {
        VkPhysicalDeviceMemoryProperties mp = VkPhysicalDeviceMemoryProperties.calloc(stack);
        vkGetPhysicalDeviceMemoryProperties(phys, mp);
        for (int i = 0; i < mp.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0 && (mp.memoryTypes(i).propertyFlags() & want) == want) return i;
        }
        throw new IllegalStateException("No suitable memory type");
    }

    // shortcut: todo buffer é host-visible + mapeado para sempre (minúsculos, reescritos a cada frame); sem VMA, uma alocação por buffer. Melhorar se os buffers crescerem.
    private Buf makeBuffer(long size, int usage) {
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateBuffer(device, VkBufferCreateInfo.calloc(stack).sType$Default()
                    .size(size).usage(usage).sharingMode(VK_SHARING_MODE_EXCLUSIVE), null, p), "vkCreateBuffer");
            long buf = p.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(stack);
            vkGetBufferMemoryRequirements(device, buf, mr);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(mr.size())
                    .memoryTypeIndex(findMemType(stack, mr.memoryTypeBits(),
                            VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)), null, p), "vkAllocateMemory");
            long mem = p.get(0);
            check(vkBindBufferMemory(device, buf, mem, 0), "vkBindBufferMemory");
            PointerBuffer pp = stack.mallocPointer(1);
            check(vkMapMemory(device, mem, 0, size, 0, pp), "vkMapMemory");
            return new Buf(buf, mem, memByteBuffer(pp.get(0), (int) size));
        }
    }

    private void createResources() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer p = stack.mallocLong(1);
            // saída do ray tracer: escrita pelo compute, amostrada pelo quad. Fixa em 200x150.
            check(vkCreateImage(device, VkImageCreateInfo.calloc(stack).sType$Default()
                    .imageType(VK_IMAGE_TYPE_2D).format(VK_FORMAT_R8G8B8A8_UNORM)
                    .extent(e -> e.set(COMPUTE_WIDTH, COMPUTE_HEIGHT, 1))
                    .mipLevels(1).arrayLayers(1).samples(VK_SAMPLE_COUNT_1_BIT).tiling(VK_IMAGE_TILING_OPTIMAL)
                    .usage(VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED), null, p), "vkCreateImage");
            image = p.get(0);
            VkMemoryRequirements mr = VkMemoryRequirements.calloc(stack);
            vkGetImageMemoryRequirements(device, image, mr);
            check(vkAllocateMemory(device, VkMemoryAllocateInfo.calloc(stack).sType$Default()
                    .allocationSize(mr.size())
                    .memoryTypeIndex(findMemType(stack, mr.memoryTypeBits(), VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), null, p), "vkAllocateMemory");
            imageMem = p.get(0);
            check(vkBindImageMemory(device, image, imageMem, 0), "vkBindImageMemory");
            imageView = createView(stack, image, VK_FORMAT_R8G8B8A8_UNORM);
            check(vkCreateSampler(device, VkSamplerCreateInfo.calloc(stack).sType$Default()
                    .magFilter(VK_FILTER_LINEAR).minFilter(VK_FILTER_LINEAR)
                    .addressModeU(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeV(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE)
                    .addressModeW(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE), null, p), "vkCreateSampler");
            sampler = p.get(0);

            camU = makeBuffer(CAMERA_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            diskU = makeBuffer(DISK_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            objU = makeBuffer(OBJECTS_SIZE, VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            int nVerts = (Sim.GRID_SIZE + 1) * (Sim.GRID_SIZE + 1);
            gridV = makeBuffer((long) nVerts * 12, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
            int[] idx = Sim.buildGrid(Sim.defaultObjects()).indices();
            gridIndexCount = idx.length;
            gridI = makeBuffer((long) idx.length * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            gridI.map().asIntBuffer().put(idx); // os índices nunca mudam

            // um set / um layout compartilhado pelos pipelines de compute e gráfico
            VkDescriptorSetLayoutBinding.Buffer b = VkDescriptorSetLayoutBinding.calloc(5, stack);
            b.get(0).binding(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            for (int i = 1; i <= 3; i++)
                b.get(i).binding(i).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_COMPUTE_BIT);
            b.get(4).binding(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).stageFlags(VK_SHADER_STAGE_FRAGMENT_BIT);
            check(vkCreateDescriptorSetLayout(device, VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(b), null, p),
                    "vkCreateDescriptorSetLayout");
            dsl = p.get(0);
            check(vkCreatePipelineLayout(device, VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(dsl))
                    .pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                            .stageFlags(VK_SHADER_STAGE_VERTEX_BIT).offset(0).size(64)), null, p), "vkCreatePipelineLayout");
            pipelineLayout = p.get(0);

            VkDescriptorPoolSize.Buffer ps = VkDescriptorPoolSize.calloc(3, stack);
            ps.get(0).type(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1);
            ps.get(1).type(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER).descriptorCount(3);
            ps.get(2).type(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1);
            check(vkCreateDescriptorPool(device, VkDescriptorPoolCreateInfo.calloc(stack).sType$Default()
                    .maxSets(1).pPoolSizes(ps), null, p), "vkCreateDescriptorPool");
            pool = p.get(0);
            check(vkAllocateDescriptorSets(device, VkDescriptorSetAllocateInfo.calloc(stack).sType$Default()
                    .descriptorPool(pool).pSetLayouts(stack.longs(dsl)), p), "vkAllocateDescriptorSets");
            descriptorSet = p.get(0);

            // A imagem fica em GENERAL tanto para a escrita (storage) quanto para a leitura amostrada.
            VkDescriptorImageInfo.Buffer storage = VkDescriptorImageInfo.calloc(1, stack)
                    .imageView(imageView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkDescriptorImageInfo.Buffer sampled = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(sampler).imageView(imageView).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            Buf[] ubos = {camU, diskU, objU};
            int[] sizes = {CAMERA_SIZE, DISK_SIZE, OBJECTS_SIZE};
            VkWriteDescriptorSet.Buffer w = VkWriteDescriptorSet.calloc(5, stack);
            for (int i = 0; i < 5; i++) w.get(i).sType$Default().dstSet(descriptorSet).dstBinding(i).descriptorCount(1);
            w.get(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).pImageInfo(storage);
            for (int i = 0; i < 3; i++) {
                w.get(i + 1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                        .pBufferInfo(VkDescriptorBufferInfo.calloc(1, stack).buffer(ubos[i].buf()).offset(0).range(sizes[i]));
            }
            w.get(4).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).pImageInfo(sampled);
            vkUpdateDescriptorSets(device, w, null);
        }
    }

    // ---------- shaders e pipelines ----------

    /** GLSL (resources/shaders) -> SPIR-V com shaderc, e já cria o VkShaderModule. */
    private long loadShader(String name, int kind) {
        String src;
        try (InputStream in = VulkanRenderer.class.getResourceAsStream("/shaders/" + name)) {
            if (in == null) throw new IllegalStateException("shader not found: " + name);
            src = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan, shaderc_env_version_vulkan_1_3);
        long result = shaderc_compile_into_spv(compiler, src, kind, name, "main", options);
        try (MemoryStack stack = stackPush()) {
            if (shaderc_result_get_compilation_status(result) != shaderc_compilation_status_success)
                throw new IllegalStateException("shader compile error (" + name + "):\n" + shaderc_result_get_error_message(result));
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateShaderModule(device, VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                    .pCode(shaderc_result_get_bytes(result)), null, p), "vkCreateShaderModule");
            return p.get(0);
        } finally {
            shaderc_result_release(result);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }

    private long makeGraphicsPipeline(String vert, String frag, int topology, boolean vertexInput) {
        long vs = loadShader(vert, shaderc_glsl_vertex_shader), fs = loadShader(frag, shaderc_glsl_fragment_shader);
        try (MemoryStack stack = stackPush()) {
            ByteBuffer main = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer st = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            st.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vs).pName(main);
            st.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fs).pName(main);

            VkPipelineVertexInputStateCreateInfo vin = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (vertexInput) {
                vin.pVertexBindingDescriptions(VkVertexInputBindingDescription.calloc(1, stack)
                                .binding(0).stride(12).inputRate(VK_VERTEX_INPUT_RATE_VERTEX))
                        .pVertexAttributeDescriptions(VkVertexInputAttributeDescription.calloc(1, stack)
                                .location(0).binding(0).format(VK_FORMAT_R32G32B32_SFLOAT).offset(0));
            }
            // GL deixou GL_BLEND ligado depois do drawGrid, então o quad (alpha 0 onde os raios escapam) mostrava o grid atrás. Aqui o blend é explícito.
            VkPipelineColorBlendAttachmentState.Buffer ba = VkPipelineColorBlendAttachmentState.calloc(1, stack)
                    .blendEnable(true)
                    .srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA).srcAlphaBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
                    .dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA).dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
                    .colorBlendOp(VK_BLEND_OP_ADD).alphaBlendOp(VK_BLEND_OP_ADD)
                    .colorWriteMask(VK_COLOR_COMPONENT_R_BIT | VK_COLOR_COMPONENT_G_BIT | VK_COLOR_COMPONENT_B_BIT | VK_COLOR_COMPONENT_A_BIT);

            VkGraphicsPipelineCreateInfo.Buffer gi = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                    .pNext(VkPipelineRenderingCreateInfo.calloc(stack).sType$Default()
                            .colorAttachmentCount(1).pColorAttachmentFormats(stack.ints(scFormat)))
                    .pStages(st)
                    .pVertexInputState(vin)
                    .pInputAssemblyState(VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(topology))
                    .pViewportState(VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                            .viewportCount(1).scissorCount(1)) // dinâmicos
                    .pRasterizationState(VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                            .polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
                            .frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1f))
                    .pMultisampleState(VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                            .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT))
                    .pColorBlendState(VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(ba))
                    .pDynamicState(VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                            .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR)))
                    .layout(pipelineLayout);
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, gi, null, p), "vkCreateGraphicsPipelines");
            vkDestroyShaderModule(device, vs, null);
            vkDestroyShaderModule(device, fs, null);
            return p.get(0);
        }
    }

    private void createPipelines() {
        long cs = loadShader("geodesic_vk.comp", shaderc_glsl_compute_shader);
        try (MemoryStack stack = stackPush()) {
            VkComputePipelineCreateInfo.Buffer ci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                    .layout(pipelineLayout);
            ci.stage().sType$Default().stage(VK_SHADER_STAGE_COMPUTE_BIT).module(cs).pName(stack.UTF8("main"));
            LongBuffer p = stack.mallocLong(1);
            check(vkCreateComputePipelines(device, VK_NULL_HANDLE, ci, null, p), "vkCreateComputePipelines");
            computePipe = p.get(0);
            vkDestroyShaderModule(device, cs, null);
        }
        gridPipe = makeGraphicsPipeline("grid_vk.vert", "grid_vk.frag", VK_PRIMITIVE_TOPOLOGY_LINE_LIST, true);
        quadPipe = makeGraphicsPipeline("quad_vk.vert", "quad_vk.frag", VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, false);
    }

    // ---------- por frame ----------

    /** Layout std140 escrito à mão, com os offsets de geodesic_vk.comp. */
    private void uploadUBOs(Camera cam, List<Obj> objs) {
        Vector3f pos = cam.position();
        Vector3f fwd = new Vector3f(Camera.TARGET).sub(pos).normalize();
        Vector3f right = new Vector3f(fwd).cross(0, 1, 0).normalize(); // y é "para cima": o disco fica no plano x-z
        Vector3f up = new Vector3f(right).cross(fwd);
        ByteBuffer c = camU.map();
        pos.get(0, c);
        right.get(16, c);
        up.get(32, c);
        fwd.get(48, c);
        c.putFloat(64, (float) Math.tan(Math.toRadians(60.0 * 0.5)));
        c.putFloat(68, (float) WIDTH / HEIGHT);
        c.putInt(72, cam.dragging ? 1 : 0);

        float rs = (float) Sim.sagaRs();
        ByteBuffer d = diskU.map();
        d.putFloat(0, rs * 2.2f).putFloat(4, rs * 5.2f).putFloat(8, 2f).putFloat(12, 1e9f); // r1, r2, num, thickness

        ByteBuffer o = objU.map();
        int n = Math.min(objs.size(), Sim.MAX_OBJECTS);
        o.putInt(0, n);
        for (int i = 0; i < n; i++) {
            objs.get(i).posRadius.get(16 + 16 * i, o);
            objs.get(i).color.get(16 + 16 * Sim.MAX_OBJECTS + 16 * i, o);
        }

        gridV.map().asFloatBuffer().put(Sim.buildGrid(objs).vertices());
    }

    private void barrier(MemoryStack stack, long img, int from, int to, long srcStage, long srcAccess, long dstStage, long dstAccess) {
        VkImageMemoryBarrier2.Buffer b = VkImageMemoryBarrier2.calloc(1, stack).sType$Default()
                .srcStageMask(srcStage).srcAccessMask(srcAccess).dstStageMask(dstStage).dstAccessMask(dstAccess)
                .oldLayout(from).newLayout(to)
                .srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED).dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED)
                .image(img)
                .subresourceRange(r -> r.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1));
        vkCmdPipelineBarrier2(cmd, VkDependencyInfo.calloc(stack).sType$Default().pImageMemoryBarriers(b));
    }

    private void record(int i, Camera cam) {
        try (MemoryStack stack = stackPush()) {
            check(vkResetCommandBuffer(cmd, 0), "vkResetCommandBuffer");
            check(vkBeginCommandBuffer(cmd, VkCommandBufferBeginInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT)), "vkBeginCommandBuffer");

            // 1) ray tracer -> imagem de storage (o conteúdo anterior é todo sobrescrito, então UNDEFINED serve)
            barrier(stack, image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, 0, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, computePipe);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipelineLayout, 0, stack.longs(descriptorSet), null);
            vkCmdDispatch(cmd, (COMPUTE_WIDTH + 15) / 16, (COMPUTE_HEIGHT + 15) / 16, 1);
            barrier(stack, image, VK_IMAGE_LAYOUT_GENERAL, VK_IMAGE_LAYOUT_GENERAL,
                    VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_SAMPLED_READ_BIT);

            // 2) grid, e o quad com o ray tracer misturado por cima
            barrier(stack, scImages[i], VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, 0,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT);
            VkRenderingAttachmentInfo.Buffer ca = VkRenderingAttachmentInfo.calloc(1, stack).sType$Default()
                    .imageView(scViews[i]).imageLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            ca.clearValue().color().float32(stack.floats(0f, 0f, 0f, 1f));
            VkRenderingInfo ri = VkRenderingInfo.calloc(stack).sType$Default().layerCount(1).pColorAttachments(ca);
            ri.renderArea().offset().set(0, 0);
            ri.renderArea().extent().set(scWidth, scHeight);
            vkCmdBeginRendering(cmd, ri);
            vkCmdSetViewport(cmd, 0, VkViewport.calloc(1, stack).width(scWidth).height(scHeight).minDepth(0f).maxDepth(1f));
            VkRect2D.Buffer scissor = VkRect2D.calloc(1, stack);
            scissor.extent().set(scWidth, scHeight);
            vkCmdSetScissor(cmd, 0, scissor);

            Matrix4f view = new Matrix4f().lookAt(cam.position(), Camera.TARGET, new Vector3f(0, 1, 0));
            Matrix4f proj = new Matrix4f().perspective((float) Math.toRadians(60), (float) COMPUTE_WIDTH / COMPUTE_HEIGHT, 1e9f, 1e14f, true);
            proj.m11(-proj.m11()); // o eixo y do clip space do Vulkan aponta para baixo
            ByteBuffer pc = stack.malloc(64);
            proj.mul(view).get(pc);
            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, gridPipe);
            vkCmdPushConstants(cmd, pipelineLayout, VK_SHADER_STAGE_VERTEX_BIT, 0, pc);
            vkCmdBindVertexBuffers(cmd, 0, stack.longs(gridV.buf()), stack.longs(0));
            vkCmdBindIndexBuffer(cmd, gridI.buf(), 0, VK_INDEX_TYPE_UINT32);
            vkCmdDrawIndexed(cmd, gridIndexCount, 1, 0, 0, 0);

            vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, quadPipe);
            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineLayout, 0, stack.longs(descriptorSet), null);
            vkCmdDraw(cmd, 6, 1, 0, 0);
            vkCmdEndRendering(cmd);

            barrier(stack, scImages[i], VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                    VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT,
                    VK_PIPELINE_STAGE_2_NONE, 0);
            check(vkEndCommandBuffer(cmd), "vkEndCommandBuffer");
        }
    }

    // shortcut: 1 frame em voo (espera a fence no início). Cópias dos UBOs por frame deixariam a CPU adiantar; não vale aqui.
    public void frame(Camera cam, List<Obj> objs) {
        try (MemoryStack stack = stackPush()) {
            check(vkWaitForFences(device, fence, true, Long.MAX_VALUE), "vkWaitForFences");
            IntBuffer pi = stack.mallocInt(1);
            int r = vkAcquireNextImageKHR(device, swapchain, Long.MAX_VALUE, imageAvail, VK_NULL_HANDLE, pi);
            if (r == VK_ERROR_OUT_OF_DATE_KHR) {
                recreateSwapchain();
                return;
            }
            if (r != VK_SUCCESS && r != VK_SUBOPTIMAL_KHR) check(r, "vkAcquireNextImageKHR");
            check(vkResetFences(device, fence), "vkResetFences");
            int i = pi.get(0);

            uploadUBOs(cam, objs);
            record(i, cam);

            check(vkQueueSubmit(queue, VkSubmitInfo.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(imageAvail))
                    .pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT))
                    .pCommandBuffers(stack.pointers(cmd))
                    .pSignalSemaphores(stack.longs(renderDone[i])), fence), "vkQueueSubmit");

            r = vkQueuePresentKHR(queue, VkPresentInfoKHR.calloc(stack).sType$Default()
                    .pWaitSemaphores(stack.longs(renderDone[i]))
                    .swapchainCount(1).pSwapchains(stack.longs(swapchain)).pImageIndices(pi));
            if (r == VK_ERROR_OUT_OF_DATE_KHR || r == VK_SUBOPTIMAL_KHR || resized) recreateSwapchain();
            else check(r, "vkQueuePresentKHR");
        }
    }

    public void waitIdle() {
        vkDeviceWaitIdle(device);
    }
}
