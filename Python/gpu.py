"""Renderer Vulkan 1.3 (dynamic rendering + synchronization2): porte de black_hole_vk.cpp com os bindings `vulkan` (cffi).

Mesmo desenho do C++: compute (geodésicas) -> imagem 200x150 -> quad de tela cheia, desenhado por cima da grade.
"""
import os
import shutil
import subprocess
import sys
from pathlib import Path

import glfw
import numpy as np
import vulkan as vk

import gpu_layout as L
import sim

SHADER_DIR = Path(__file__).parent / "shaders"
SHADERS = ["geodesic_vk.comp", "grid_vk.vert", "grid_vk.frag", "quad_vk.vert", "quad_vk.frag"]
API_1_3 = vk.VK_MAKE_VERSION(1, 3, 0)
COLOR = vk.VK_IMAGE_ASPECT_COLOR_BIT


def die(msg: str):
    sys.exit(f"erro: {msg}")


def compile_shaders() -> None:
    """GLSL -> SPIR-V com glslc (o CMake do C++ faz o mesmo no build). Só recompila se o .spv estiver velho."""
    glslc = os.environ.get("GLSLC") or shutil.which("glslc") or die("glslc não encontrado (sudo apt install glslc)")
    for name in SHADERS:
        src, spv = SHADER_DIR / name, SHADER_DIR / (name + ".spv")
        if not spv.exists() or spv.stat().st_mtime < src.stat().st_mtime:
            subprocess.run([glslc, "--target-env=vulkan1.3", str(src), "-o", str(spv)], check=True)


def ubo_range(data_len: int) -> int:
    return max(data_len, 16)


class Buf:
    """Buffer host-visible + persistentemente mapeado.
    shortcut: uma alocação por buffer, sem VMA; são minúsculos e reescritos todo frame. Mude se crescerem."""

    def __init__(self, r: "Renderer", size: int, usage: int):
        self.buf = vk.vkCreateBuffer(r.dev, vk.VkBufferCreateInfo(size=size, usage=usage, sharingMode=vk.VK_SHARING_MODE_EXCLUSIVE), None)
        req = vk.vkGetBufferMemoryRequirements(r.dev, self.buf)
        mem_type = r.find_mem_type(req.memoryTypeBits, vk.VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | vk.VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
        self.mem = vk.vkAllocateMemory(r.dev, vk.VkMemoryAllocateInfo(allocationSize=req.size, memoryTypeIndex=mem_type), None)
        vk.vkBindBufferMemory(r.dev, self.buf, self.mem, 0)
        self.map = vk.vkMapMemory(r.dev, self.mem, 0, size, 0)

    def write(self, data: bytes) -> None:
        self.map[:len(data)] = data


class Renderer:
    def __init__(self, window):
        self.window = window
        self.resized = False
        glfw.set_framebuffer_size_callback(window, lambda *_: setattr(self, "resized", True))
        self.create_instance()
        surf = vk.ffi.new("VkSurfaceKHR*")
        if glfw.create_window_surface(self.inst, window, None, surf) != vk.VK_SUCCESS:
            die("glfwCreateWindowSurface falhou")
        self.surf = surf[0]
        self.pick_device()
        self.create_swapchain()
        self.create_resources()
        self.create_pipelines()
        self.cpool = vk.vkCreateCommandPool(self.dev, vk.VkCommandPoolCreateInfo(
            flags=vk.VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT, queueFamilyIndex=self.qfam), None)
        self.cmd = vk.vkAllocateCommandBuffers(self.dev, vk.VkCommandBufferAllocateInfo(
            commandPool=self.cpool, level=vk.VK_COMMAND_BUFFER_LEVEL_PRIMARY, commandBufferCount=1))[0]
        self.image_avail = vk.vkCreateSemaphore(self.dev, vk.VkSemaphoreCreateInfo(), None)
        self.fence = vk.vkCreateFence(self.dev, vk.VkFenceCreateInfo(flags=vk.VK_FENCE_CREATE_SIGNALED_BIT), None)

    # ---------- helpers ----------
    def find_mem_type(self, bits: int, want: int) -> int:
        mp = vk.vkGetPhysicalDeviceMemoryProperties(self.phys)
        for i in range(mp.memoryTypeCount):
            if bits & (1 << i) and mp.memoryTypes[i].propertyFlags & want == want:
                return i
        die("nenhum tipo de memória compatível")

    def load_shader(self, name: str):
        code = (SHADER_DIR / (name + ".spv")).read_bytes()
        return vk.vkCreateShaderModule(self.dev, vk.VkShaderModuleCreateInfo(codeSize=len(code), pCode=code), None)

    def barrier(self, image, old, new, src_stage, src_access, dst_stage, dst_access) -> None:
        b = vk.VkImageMemoryBarrier2(
            srcStageMask=src_stage, srcAccessMask=src_access, dstStageMask=dst_stage, dstAccessMask=dst_access,
            oldLayout=old, newLayout=new, srcQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED, dstQueueFamilyIndex=vk.VK_QUEUE_FAMILY_IGNORED,
            image=image, subresourceRange=vk.VkImageSubresourceRange(COLOR, 0, 1, 0, 1))
        vk.vkCmdPipelineBarrier2(self.cmd, vk.VkDependencyInfo(imageMemoryBarrierCount=1, pImageMemoryBarriers=[b]))

    # ---------- init ----------
    def create_instance(self) -> None:
        exts = glfw.get_required_instance_extensions()
        layers = []
        validation = "VK_LAYER_KHRONOS_validation"
        if os.environ.get("BH_VALIDATION") != "0" and validation in [l.layerName for l in vk.vkEnumerateInstanceLayerProperties()]:
            layers.append(validation)
            print("[INFO] Vulkan validation layer ON (BH_VALIDATION=0 desliga)")
        app = vk.VkApplicationInfo(pApplicationName="Black Hole", apiVersion=API_1_3)
        self.inst = vk.vkCreateInstance(vk.VkInstanceCreateInfo(
            pApplicationInfo=app, enabledLayerCount=len(layers), ppEnabledLayerNames=layers,
            enabledExtensionCount=len(exts), ppEnabledExtensionNames=exts), None)
        # funções de extensão não vêm no módulo `vulkan`: pegue pelo vkGetInstanceProcAddr
        for n in ("vkGetPhysicalDeviceSurfaceSupportKHR", "vkGetPhysicalDeviceSurfaceCapabilitiesKHR", "vkGetPhysicalDeviceSurfaceFormatsKHR"):
            setattr(self, n, vk.vkGetInstanceProcAddr(self.inst, n))

    def pick_device(self) -> None:
        best = -1
        for d in vk.vkEnumeratePhysicalDevices(self.inst):
            p = vk.vkGetPhysicalDeviceProperties(d)
            if p.apiVersion < API_1_3:
                continue
            for i, q in enumerate(vk.vkGetPhysicalDeviceQueueFamilyProperties(d)):
                need = vk.VK_QUEUE_GRAPHICS_BIT | vk.VK_QUEUE_COMPUTE_BIT
                if q.queueFlags & need != need or not self.vkGetPhysicalDeviceSurfaceSupportKHR(d, i, self.surf):
                    continue
                score = {vk.VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU: 2, vk.VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU: 1}.get(p.deviceType, 0)
                if score > best:
                    best, self.phys, self.qfam = score, d, i
                break
        if best < 0:
            die("nenhuma GPU Vulkan 1.3 com fila gráfica+compute+present")
        print("Vulkan device:", vk.vkGetPhysicalDeviceProperties(self.phys).deviceName)

        qi = vk.VkDeviceQueueCreateInfo(queueFamilyIndex=self.qfam, queueCount=1, pQueuePriorities=[1.0])
        f13 = vk.VkPhysicalDeviceVulkan13Features(dynamicRendering=True, synchronization2=True)
        self.dev = vk.vkCreateDevice(self.phys, vk.VkDeviceCreateInfo(
            pNext=f13, queueCreateInfoCount=1, pQueueCreateInfos=[qi],
            enabledExtensionCount=1, ppEnabledExtensionNames=["VK_KHR_swapchain"]), None)
        self.q = vk.vkGetDeviceQueue(self.dev, self.qfam, 0)
        for n in ("vkCreateSwapchainKHR", "vkDestroySwapchainKHR", "vkGetSwapchainImagesKHR", "vkAcquireNextImageKHR", "vkQueuePresentKHR"):
            setattr(self, n, vk.vkGetDeviceProcAddr(self.dev, n))

    def create_swapchain(self) -> None:
        caps = self.vkGetPhysicalDeviceSurfaceCapabilitiesKHR(self.phys, self.surf)
        fmts = self.vkGetPhysicalDeviceSurfaceFormatsKHR(self.phys, self.surf)
        # Só SRGB_NONLINEAR (outros espaços exigem VK_EXT_swapchain_colorspace). UNORM, não *_SRGB: o original gravava valores crus.
        chosen = fmts[0]
        for f in fmts:
            if f.colorSpace == vk.VK_COLOR_SPACE_SRGB_NONLINEAR_KHR:
                chosen = f
                if f.format == vk.VK_FORMAT_B8G8R8A8_UNORM:
                    break
        self.sc_format = chosen.format
        if caps.currentExtent.width != 0xFFFFFFFF:
            self.sc_extent = (caps.currentExtent.width, caps.currentExtent.height)
        else:  # Wayland: o tamanho vem da janela
            w, h = glfw.get_framebuffer_size(self.window)
            self.sc_extent = (min(max(w, caps.minImageExtent.width), caps.maxImageExtent.width),
                              min(max(h, caps.minImageExtent.height), caps.maxImageExtent.height))
        count = caps.minImageCount + 1
        if caps.maxImageCount:
            count = min(count, caps.maxImageCount)
        self.sc = self.vkCreateSwapchainKHR(self.dev, vk.VkSwapchainCreateInfoKHR(
            surface=self.surf, minImageCount=count, imageFormat=self.sc_format, imageColorSpace=chosen.colorSpace,
            imageExtent=vk.VkExtent2D(*self.sc_extent), imageArrayLayers=1, imageUsage=vk.VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT,
            imageSharingMode=vk.VK_SHARING_MODE_EXCLUSIVE, preTransform=caps.currentTransform,
            compositeAlpha=vk.VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            presentMode=vk.VK_PRESENT_MODE_FIFO_KHR,  # sempre disponível, vsync
            clipped=True), None)
        self.sc_images = list(self.vkGetSwapchainImagesKHR(self.dev, self.sc))
        self.sc_views = [vk.vkCreateImageView(self.dev, vk.VkImageViewCreateInfo(
            image=i, viewType=vk.VK_IMAGE_VIEW_TYPE_2D, format=self.sc_format,
            subresourceRange=vk.VkImageSubresourceRange(COLOR, 0, 1, 0, 1)), None) for i in self.sc_images]
        # um semáforo por imagem: o present ainda pode estar usando o anterior
        self.render_done = [vk.vkCreateSemaphore(self.dev, vk.VkSemaphoreCreateInfo(), None) for _ in self.sc_images]

    def recreate_swapchain(self) -> None:
        while glfw.get_framebuffer_size(self.window) in ((0, 0),) or 0 in glfw.get_framebuffer_size(self.window):
            glfw.wait_events()  # minimizada
        vk.vkDeviceWaitIdle(self.dev)
        for s in self.render_done:
            vk.vkDestroySemaphore(self.dev, s, None)
        for v in self.sc_views:
            vk.vkDestroyImageView(self.dev, v, None)
        self.vkDestroySwapchainKHR(self.dev, self.sc, None)
        self.create_swapchain()
        self.resized = False

    def create_resources(self) -> None:
        # saída do ray tracer: escrita pelo compute, amostrada pelo quad. Sempre 200x150.
        self.img = vk.vkCreateImage(self.dev, vk.VkImageCreateInfo(
            imageType=vk.VK_IMAGE_TYPE_2D, format=vk.VK_FORMAT_R8G8B8A8_UNORM, extent=vk.VkExtent3D(L.COMPUTE_WIDTH, L.COMPUTE_HEIGHT, 1),
            mipLevels=1, arrayLayers=1, samples=vk.VK_SAMPLE_COUNT_1_BIT, tiling=vk.VK_IMAGE_TILING_OPTIMAL,
            usage=vk.VK_IMAGE_USAGE_STORAGE_BIT | vk.VK_IMAGE_USAGE_SAMPLED_BIT, initialLayout=vk.VK_IMAGE_LAYOUT_UNDEFINED), None)
        req = vk.vkGetImageMemoryRequirements(self.dev, self.img)
        mem = vk.vkAllocateMemory(self.dev, vk.VkMemoryAllocateInfo(
            allocationSize=req.size, memoryTypeIndex=self.find_mem_type(req.memoryTypeBits, vk.VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)), None)
        vk.vkBindImageMemory(self.dev, self.img, mem, 0)
        img_view = vk.vkCreateImageView(self.dev, vk.VkImageViewCreateInfo(
            image=self.img, viewType=vk.VK_IMAGE_VIEW_TYPE_2D, format=vk.VK_FORMAT_R8G8B8A8_UNORM,
            subresourceRange=vk.VkImageSubresourceRange(COLOR, 0, 1, 0, 1)), None)
        edge = vk.VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE
        sampler = vk.vkCreateSampler(self.dev, vk.VkSamplerCreateInfo(
            magFilter=vk.VK_FILTER_LINEAR, minFilter=vk.VK_FILTER_LINEAR, addressModeU=edge, addressModeV=edge, addressModeW=edge), None)

        ubo = vk.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
        sizes = (128, 16, 16 + 16 * 16 * 2)
        self.cam_u, self.disk_u, self.obj_u = (Buf(self, s, ubo) for s in sizes)
        self.grid_v = Buf(self, (sim.GRID_SIZE + 1) ** 2 * 12, vk.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT)
        _, idx = sim.build_grid(sim.default_objects())
        self.grid_index_count = len(idx)
        self.grid_i = Buf(self, len(idx) * 4, vk.VK_BUFFER_USAGE_INDEX_BUFFER_BIT)
        self.grid_i.write(np.array(idx, dtype=np.uint32).tobytes())  # os índices nunca mudam

        # um set / um layout compartilhado pelo compute e pelos pipelines gráficos
        def binding(i, typ, stage):
            return vk.VkDescriptorSetLayoutBinding(binding=i, descriptorType=typ, descriptorCount=1, stageFlags=stage)
        bindings = [binding(0, vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, vk.VK_SHADER_STAGE_COMPUTE_BIT)]
        bindings += [binding(i, vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, vk.VK_SHADER_STAGE_COMPUTE_BIT) for i in (1, 2, 3)]
        bindings.append(binding(4, vk.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, vk.VK_SHADER_STAGE_FRAGMENT_BIT))
        dsl = vk.vkCreateDescriptorSetLayout(self.dev, vk.VkDescriptorSetLayoutCreateInfo(bindingCount=len(bindings), pBindings=bindings), None)
        pc = vk.VkPushConstantRange(stageFlags=vk.VK_SHADER_STAGE_VERTEX_BIT, offset=0, size=64)
        self.pl = vk.vkCreatePipelineLayout(self.dev, vk.VkPipelineLayoutCreateInfo(
            setLayoutCount=1, pSetLayouts=[dsl], pushConstantRangeCount=1, pPushConstantRanges=[pc]), None)

        pool = vk.vkCreateDescriptorPool(self.dev, vk.VkDescriptorPoolCreateInfo(maxSets=1, poolSizeCount=3, pPoolSizes=[
            vk.VkDescriptorPoolSize(vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, 1),
            vk.VkDescriptorPoolSize(vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 3),
            vk.VkDescriptorPoolSize(vk.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 1)]), None)
        self.ds = vk.vkAllocateDescriptorSets(self.dev, vk.VkDescriptorSetAllocateInfo(
            descriptorPool=pool, descriptorSetCount=1, pSetLayouts=[dsl]))[0]

        # a imagem fica em GENERAL tanto para escrita de storage quanto para leitura amostrada
        general = vk.VK_IMAGE_LAYOUT_GENERAL
        def write(i, typ, **info):
            return vk.VkWriteDescriptorSet(dstSet=self.ds, dstBinding=i, descriptorCount=1, descriptorType=typ, **info)
        def buf_info(b, size):
            return [vk.VkDescriptorBufferInfo(b.buf, 0, size)]
        vk.vkUpdateDescriptorSets(self.dev, 5, [
            write(0, vk.VK_DESCRIPTOR_TYPE_STORAGE_IMAGE, pImageInfo=[vk.VkDescriptorImageInfo(None, img_view, general)]),
            write(1, vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, pBufferInfo=buf_info(self.cam_u, sizes[0])),
            write(2, vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, pBufferInfo=buf_info(self.disk_u, sizes[1])),
            write(3, vk.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, pBufferInfo=buf_info(self.obj_u, sizes[2])),
            write(4, vk.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, pImageInfo=[vk.VkDescriptorImageInfo(sampler, img_view, general)]),
        ], 0, None)

    def make_graphics_pipeline(self, vert: str, frag: str, topology: int, vertex_input: bool):
        vs, fs = self.load_shader(vert), self.load_shader(frag)
        stages = [vk.VkPipelineShaderStageCreateInfo(stage=vk.VK_SHADER_STAGE_VERTEX_BIT, module=vs, pName="main"),
                  vk.VkPipelineShaderStageCreateInfo(stage=vk.VK_SHADER_STAGE_FRAGMENT_BIT, module=fs, pName="main")]
        if vertex_input:
            vin = vk.VkPipelineVertexInputStateCreateInfo(
                vertexBindingDescriptionCount=1, pVertexBindingDescriptions=[vk.VkVertexInputBindingDescription(0, 12, vk.VK_VERTEX_INPUT_RATE_VERTEX)],
                vertexAttributeDescriptionCount=1, pVertexAttributeDescriptions=[vk.VkVertexInputAttributeDescription(0, 0, vk.VK_FORMAT_R32G32B32_SFLOAT, 0)])
        else:
            vin = vk.VkPipelineVertexInputStateCreateInfo()
        # O GL deixou GL_BLEND ligado desde o drawGrid, então o quad (alpha 0 onde o raio escapa) mostrava a grade atrás. Aqui o blend é explícito.
        blend = vk.VkPipelineColorBlendAttachmentState(
            blendEnable=True,
            srcColorBlendFactor=vk.VK_BLEND_FACTOR_SRC_ALPHA, dstColorBlendFactor=vk.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, colorBlendOp=vk.VK_BLEND_OP_ADD,
            srcAlphaBlendFactor=vk.VK_BLEND_FACTOR_SRC_ALPHA, dstAlphaBlendFactor=vk.VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA, alphaBlendOp=vk.VK_BLEND_OP_ADD,
            colorWriteMask=0xF)
        rendering = vk.VkPipelineRenderingCreateInfo(colorAttachmentCount=1, pColorAttachmentFormats=[self.sc_format])
        gi = vk.VkGraphicsPipelineCreateInfo(
            pNext=rendering, stageCount=2, pStages=stages, pVertexInputState=vin,
            pInputAssemblyState=vk.VkPipelineInputAssemblyStateCreateInfo(topology=topology),
            pViewportState=vk.VkPipelineViewportStateCreateInfo(viewportCount=1, scissorCount=1),  # viewport/scissor dinâmicos
            pRasterizationState=vk.VkPipelineRasterizationStateCreateInfo(
                polygonMode=vk.VK_POLYGON_MODE_FILL, cullMode=vk.VK_CULL_MODE_NONE, frontFace=vk.VK_FRONT_FACE_COUNTER_CLOCKWISE, lineWidth=1.0),
            pMultisampleState=vk.VkPipelineMultisampleStateCreateInfo(rasterizationSamples=vk.VK_SAMPLE_COUNT_1_BIT),
            pColorBlendState=vk.VkPipelineColorBlendStateCreateInfo(attachmentCount=1, pAttachments=[blend]),
            pDynamicState=vk.VkPipelineDynamicStateCreateInfo(
                dynamicStateCount=2, pDynamicStates=[vk.VK_DYNAMIC_STATE_VIEWPORT, vk.VK_DYNAMIC_STATE_SCISSOR]),
            layout=self.pl)
        pipe = vk.vkCreateGraphicsPipelines(self.dev, None, 1, [gi], None)[0]
        vk.vkDestroyShaderModule(self.dev, vs, None)
        vk.vkDestroyShaderModule(self.dev, fs, None)
        return pipe

    def create_pipelines(self) -> None:
        cs = self.load_shader("geodesic_vk.comp")
        stage = vk.VkPipelineShaderStageCreateInfo(stage=vk.VK_SHADER_STAGE_COMPUTE_BIT, module=cs, pName="main")
        self.compute_pipe = vk.vkCreateComputePipelines(self.dev, None, 1, [vk.VkComputePipelineCreateInfo(stage=stage, layout=self.pl)], None)[0]
        vk.vkDestroyShaderModule(self.dev, cs, None)
        self.grid_pipe = self.make_graphics_pipeline("grid_vk.vert", "grid_vk.frag", vk.VK_PRIMITIVE_TOPOLOGY_LINE_LIST, True)
        self.quad_pipe = self.make_graphics_pipeline("quad_vk.vert", "quad_vk.frag", vk.VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST, False)

    # ---------- por frame ----------
    def upload_ubos(self, cam: sim.Camera, objects: list[sim.Object]) -> None:
        self.cam_u.write(L.pack_camera(cam, L.WIDTH / L.HEIGHT))
        self.disk_u.write(L.pack_disk())
        self.obj_u.write(L.pack_objects(objects))
        self.grid_v.write(np.array(sim.build_grid(objects)[0], dtype=np.float32).tobytes())

    def record(self, i: int, cam: sim.Camera) -> None:
        cmd = self.cmd
        vk.vkResetCommandBuffer(cmd, 0)
        vk.vkBeginCommandBuffer(cmd, vk.VkCommandBufferBeginInfo(flags=vk.VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT))
        undef, general = vk.VK_IMAGE_LAYOUT_UNDEFINED, vk.VK_IMAGE_LAYOUT_GENERAL
        frag, comp = vk.VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, vk.VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT
        out = vk.VK_PIPELINE_STAGE_2_COLOR_ATTACHMENT_OUTPUT_BIT

        # 1) ray tracer -> imagem de storage (o conteúdo anterior é sobrescrito, então UNDEFINED serve)
        self.barrier(self.img, undef, general, frag, 0, comp, vk.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT)
        vk.vkCmdBindPipeline(cmd, vk.VK_PIPELINE_BIND_POINT_COMPUTE, self.compute_pipe)
        vk.vkCmdBindDescriptorSets(cmd, vk.VK_PIPELINE_BIND_POINT_COMPUTE, self.pl, 0, 1, [self.ds], 0, None)
        vk.vkCmdDispatch(cmd, (L.COMPUTE_WIDTH + 15) // 16, (L.COMPUTE_HEIGHT + 15) // 16, 1)
        self.barrier(self.img, general, general, comp, vk.VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT, frag, vk.VK_ACCESS_2_SHADER_SAMPLED_READ_BIT)

        # 2) grade, depois o quad do ray tracer misturado por cima
        self.barrier(self.sc_images[i], undef, vk.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
                     out, 0, out, vk.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT)
        w, h = self.sc_extent
        attachment = vk.VkRenderingAttachmentInfo(
            imageView=self.sc_views[i], imageLayout=vk.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
            loadOp=vk.VK_ATTACHMENT_LOAD_OP_CLEAR, storeOp=vk.VK_ATTACHMENT_STORE_OP_STORE,
            clearValue=vk.VkClearValue(color=vk.VkClearColorValue(float32=[0.0, 0.0, 0.0, 1.0])))
        vk.vkCmdBeginRendering(cmd, vk.VkRenderingInfo(
            renderArea=vk.VkRect2D(vk.VkOffset2D(0, 0), vk.VkExtent2D(w, h)), layerCount=1,
            colorAttachmentCount=1, pColorAttachments=[attachment]))
        vk.vkCmdSetViewport(cmd, 0, 1, [vk.VkViewport(0, 0, w, h, 0.0, 1.0)])
        vk.vkCmdSetScissor(cmd, 0, 1, [vk.VkRect2D(vk.VkOffset2D(0, 0), vk.VkExtent2D(w, h))])

        vk.vkCmdBindPipeline(cmd, vk.VK_PIPELINE_BIND_POINT_GRAPHICS, self.grid_pipe)
        pc = vk.ffi.from_buffer(L.view_proj(cam))  # o wrapper não aceita bytes para void*
        vk.vkCmdPushConstants(cmd, self.pl, vk.VK_SHADER_STAGE_VERTEX_BIT, 0, 64, vk.ffi.cast("void *", pc))
        vk.vkCmdBindVertexBuffers(cmd, 0, 1, [self.grid_v.buf], [0])
        vk.vkCmdBindIndexBuffer(cmd, self.grid_i.buf, 0, vk.VK_INDEX_TYPE_UINT32)
        vk.vkCmdDrawIndexed(cmd, self.grid_index_count, 1, 0, 0, 0)

        vk.vkCmdBindPipeline(cmd, vk.VK_PIPELINE_BIND_POINT_GRAPHICS, self.quad_pipe)
        vk.vkCmdBindDescriptorSets(cmd, vk.VK_PIPELINE_BIND_POINT_GRAPHICS, self.pl, 0, 1, [self.ds], 0, None)
        vk.vkCmdDraw(cmd, 6, 1, 0, 0)
        vk.vkCmdEndRendering(cmd)

        self.barrier(self.sc_images[i], vk.VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, vk.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
                     out, vk.VK_ACCESS_2_COLOR_ATTACHMENT_WRITE_BIT, vk.VK_PIPELINE_STAGE_2_NONE, 0)
        vk.vkEndCommandBuffer(cmd)

    def frame(self, cam: sim.Camera, objects: list[sim.Object]) -> None:
        """shortcut: 1 frame em voo (espera a fence no início). Cópias dos UBOs por frame deixariam a CPU adiantar o próximo."""
        vk.vkWaitForFences(self.dev, 1, [self.fence], True, 2**64 - 1)
        try:
            i = self.vkAcquireNextImageKHR(self.dev, self.sc, 2**64 - 1, self.image_avail, None)
        except vk.VkErrorOutOfDateKhr:
            return self.recreate_swapchain()
        vk.vkResetFences(self.dev, 1, [self.fence])

        self.upload_ubos(cam, objects)
        self.record(i, cam)
        vk.vkQueueSubmit(self.q, 1, [vk.VkSubmitInfo(
            waitSemaphoreCount=1, pWaitSemaphores=[self.image_avail], pWaitDstStageMask=[vk.VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT],
            commandBufferCount=1, pCommandBuffers=[self.cmd], signalSemaphoreCount=1, pSignalSemaphores=[self.render_done[i]])], self.fence)
        try:
            self.vkQueuePresentKHR(self.q, vk.VkPresentInfoKHR(
                waitSemaphoreCount=1, pWaitSemaphores=[self.render_done[i]], swapchainCount=1, pSwapchains=[self.sc], pImageIndices=[i]))
        except (vk.VkErrorOutOfDateKhr, vk.VkSuboptimalKhr):
            self.resized = True
        if self.resized:
            self.recreate_swapchain()

    def wait_idle(self) -> None:
        vk.vkDeviceWaitIdle(self.dev)
