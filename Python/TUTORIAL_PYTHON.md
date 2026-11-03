# Tutorial: refazendo o port Python do zero

Siga na ordem. O **porquê** das decisões está em [`PORT_PYTHON.md`](PORT_PYTHON.md).

> O código foi gerado com ajuda de IA. Para aprender de verdade, **digite você mesmo** cada passo e leia a
> [`TUTORIAL_VULKAN.md`](../C++/TUTORIAL_VULKAN.md) do C++ em paralelo: aqui é a mesma sequência de objetos Vulkan.
> A validation layer é o professor: deixe-a ligada e leia cada mensagem.

## Passo 0: ambiente

```bash
sudo apt install glslc vulkan-validationlayers vulkan-tools mesa-vulkan-drivers
curl -LsSf https://astral.sh/uv/install.sh | sh
vulkaninfo --summary          # precisa listar uma GPU com Vulkan 1.3
echo $XDG_SESSION_TYPE        # wayland
```

## Passo 1: o projeto

```bash
mkdir Python && cd Python
uv init --bare
uv add vulkan glfw numpy
uv add --dev pytest
mkdir shaders && cp ../C++/{geodesic_vk.comp,grid_vk.vert,grid_vk.frag,quad_vk.vert,quad_vk.frag} shaders/
```

No `pyproject.toml`, acrescente `[tool.uv] package = false` (é um app, não uma biblioteca). `uv run` cria e usa o `.venv`.

## Passo 2: `sim.py` (física, sem GPU), com teste a cada item

1. Constantes `C`, `G`, `GRID_SIZE = 25`, `MAX_OBJECTS = 16`; `schwarzschild_radius(m) = 2·G·m/c²`.
2. `Object` (`pos_radius`, `color`, `mass`, `velocity`) e `default_objects()`.
3. `Camera` com `position()` esférica, `mouse_move`, `set_dragging`, `scroll` (zoom entre 1e10 e 1e12; elevação em `[0.01, π-0.01]`).
4. `step_gravity(objects, gravity)`: gravidade newtoniana par a par, sem `dt`.
5. `build_grid(objects)`: malha 26×26 deformada por `2·√(rs·(dist-rs))`, mais os índices das linhas.

```python
def test_camera_zoom_is_clamped():
    c = sim.Camera()
    c.scroll(1000);  assert c.radius == 1e10
    c.scroll(-1000); assert c.radius == 1e12
```

Rode `uv run pytest` a cada item (red → green).

## Passo 3: `gpu_layout.py` (bytes para a GPU, sem Vulkan)

Os shaders esperam layout **std140**. Escreva e teste o tamanho de cada bloco antes de tocar na GPU:

- `pack_camera`: `vec3 pos; pad; vec3 right; pad; vec3 up; pad; vec3 forward; pad; tanHalfFov; aspect; int moving; pad` = 80 bytes.
- `pack_disk`: `r1, r2, num, thickness` = 16 bytes.
- `pack_objects`: `int numObjects` + 12 de padding, `vec4 posRadius[16]`, `vec4 color[16]` = 528 bytes.
- `view_proj`: `proj · view` em coluna-maior (transposta ao gravar). Perspectiva com profundidade **0..1** e `proj[1][1] = -t` (Y do clip space do Vulkan aponta para baixo).

Teste útil: a origem projetada tem de cair no centro da tela, com profundidade entre 0 e 1.

## Passo 4: janela e instance (`gpu.py`, `main.py`)

1. `main.py`: `glfw.init()`, `init_hint(PLATFORM, PLATFORM_WAYLAND)`, `window_hint(CLIENT_API, NO_API)`, `create_window`.
2. `compile_shaders()`: roda `glslc --target-env=vulkan1.3 x.comp -o x.comp.spv` para os 5 shaders.
3. `create_instance`: `VkApplicationInfo(apiVersion=VK_MAKE_VERSION(1,3,0))`, extensões de `glfw.get_required_instance_extensions()`
   (no Wayland: `VK_KHR_surface` e `VK_KHR_wayland_surface`) e a layer `VK_LAYER_KHRONOS_validation` se existir.
4. Funções de extensão: `vk.vkGetInstanceProcAddr(instance, "vkGetPhysicalDeviceSurface...KHR")`.
5. Surface: `surf = vk.ffi.new("VkSurfaceKHR*")` e `glfw.create_window_surface(instance, window, None, surf)`.

## Passo 5: device e swapchain

1. `pick_device`: GPU 1.3 com fila gráfica+compute+present, preferindo a discreta. Habilite `dynamicRendering` e `synchronization2`
   (`VkPhysicalDeviceVulkan13Features` em `pNext`) e a extensão `VK_KHR_swapchain`. Pegue `vkCreateSwapchainKHR`, `vkAcquireNextImageKHR`,
   `vkQueuePresentKHR` etc. com `vkGetDeviceProcAddr`.
2. `create_swapchain`: formato `B8G8R8A8_UNORM` com espaço `SRGB_NONLINEAR`, FIFO. **No Wayland `currentExtent` vem `0xFFFFFFFF`**:
   use `glfw.get_framebuffer_size`. Uma image view e **um semáforo por imagem**.

## Passo 6: recursos, descriptors e pipelines

1. Imagem `R8G8B8A8_UNORM` 200×150 (`STORAGE | SAMPLED`), memória `DEVICE_LOCAL`, view e sampler linear.
2. Classe `Buf` (host-visible, coerente, mapeada): 3 UBOs, vertex buffer do grid, index buffer (preenchido uma vez).
3. Um `VkDescriptorSetLayout` com 5 bindings (0 storage image, 1-3 UBOs, 4 combined sampler) e um pipeline layout com **push constant** de 64 bytes.
4. Pool com 1 set, `vkUpdateDescriptorSets` (imagem sempre em `GENERAL`).
5. Pipeline de compute e dois gráficos (grid em `LINE_LIST` com vertex input, quad em `TRIANGLE_LIST` sem). Dynamic rendering: `VkPipelineRenderingCreateInfo` em `pNext`.
   Blend `SRC_ALPHA / ONE_MINUS_SRC_ALPHA` explícito.

## Passo 7: o frame

Na ordem do C++ (`record` e `frame`):

1. Espera a fence; `vkAcquireNextImageKHR`; `upload_ubos`.
2. Barreira da imagem `UNDEFINED → GENERAL`; bind + `vkCmdDispatch(13, 10, 1)`; barreira *compute escreveu → fragment lê*.
3. Barreira do swapchain → `COLOR_ATTACHMENT_OPTIMAL`; `vkCmdBeginRendering` (clear preto); desenha o grid (push constant) e o quad; `vkCmdEndRendering`.
4. Barreira → `PRESENT_SRC_KHR`; `vkQueueSubmit` (espera `image_avail`, sinaliza `render_done[i]`); `vkQueuePresentKHR`.
5. `OUT_OF_DATE` ou resize → `recreate_swapchain`.

Armadilhas do binding `vulkan` (todas apareceram no caminho):

- `vkGetPhysicalDeviceProperties(...).deviceName` já é `str`; não use `ffi.string`.
- `vkCmdPushConstants` não aceita `bytes` em `void*`: use `vk.ffi.cast("void *", vk.ffi.from_buffer(data))`.
- `vkMapMemory` devolve um `ffi.buffer`: grave com `buf[:n] = data`.
- Structs passadas em `pNext` e listas em `p...=[...]` precisam continuar referenciadas até a chamada terminar.

## Passo 8: verificar

```bash
uv run pytest
uv run main.py --frames 60       # com a validation layer: não pode imprimir "Validation Error"
uv run main.py                   # orbite com o mouse; G liga a gravidade
```

Exercícios: (1) com a validation layer ligada, remova uma barreira em `record` para vê-la reclamar; (2) troque `FIFO` por `MAILBOX` e meça os frames;
(3) implemente `vkDestroy*` no encerramento; (4) trate `VkSuboptimalKhr` no acquire.
