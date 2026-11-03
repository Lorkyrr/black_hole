# Tutorial: refazendo o port Vulkan do zero

Este tutorial ensina a construir `black_hole_vk.cpp` passo a passo. O **porquê** de cada decisão está em
[`VULKAN_PORT.md`](VULKAN_PORT.md); aqui o foco é o **como**, na ordem em que você escreveria o código.

Ideia central para não se perder: Vulkan não esconde nada. Cada coisa que o OpenGL fazia "por baixo dos panos"
(layout de imagem, sincronização, estado de blend, formato do swapchain) vira um objeto ou uma chamada que você escreve.
Por isso o programa tem ~450 linhas em vez de ~250.

> Este código foi gerado com ajuda de IA e serve de base de estudo. O jeito de aprender de verdade é seguir os passos abaixo
> **digitando você mesmo**, quebrando coisas de propósito e lendo as mensagens da validation layer.

## Passo 0: ambiente

```bash
sudo apt install build-essential cmake libglfw3-dev libglm-dev libvulkan-dev glslc \
                 vulkan-validationlayers mesa-vulkan-drivers vulkan-tools
vulkaninfo --summary     # precisa listar uma GPU com "apiVersion = 1.3.x"
glslc --version
```

Se `vulkaninfo` não mostra GPU, nada abaixo vai rodar: resolva o driver primeiro.

## Passo 1: separe a simulação da API gráfica

Antes de qualquer Vulkan, tudo que não desenha fica num header que não conhece nenhuma API: `sim.h`
(`Camera`, objetos, gravidade newtoniana `stepGravity()`, malha do grid `buildGrid()`).

Regra: `sim.h` não inclui GLEW nem Vulkan. Quem inclui define antes o que precisa:

```cpp
#define GLFW_INCLUDE_VULKAN
#define GLM_FORCE_DEPTH_ZERO_TO_ONE   // profundidade do clip space do Vulkan é 0..1 (no OpenGL é -1..1)
#include "sim.h"
```

Por quê: o renderer vira "só API". Quando o bug aparece você sabe se é física ou desenho.

## Passo 2: adapte os shaders (GLSL 430 → 450 para Vulkan)

O ray tracer (`geodesic.comp`) vira `geodesic_vk.comp`. As mudanças são pequenas, rode `diff` nos dois:

| Mudança | Motivo |
|---|---|
| `#version 450` | GLSL para Vulkan exige 450+ |
| `layout(set = 0, binding = N)` | todo recurso pertence a um descriptor set |
| `bool moving` → `int moving` | `bool` no GLSL ocupa 4 bytes, no C++ 1 byte: lixo no UBO |
| remover `float mass[16]` | não era lido; em std140 o stride de `float[]` é 16, não 4 |
| `double` → `float` | `double` exige a feature `shaderFloat64` |

Compile para SPIR-V (o CMake faz isso sozinho, mas faça à mão uma vez):

```bash
glslc --target-env=vulkan1.3 geodesic_vk.comp -o geodesic_vk.comp.spv
```

Os três shaders gráficos são curtos. O mais instrutivo é `quad_vk.vert`: ele gera os 6 vértices a partir de
`gl_VertexIndex`, sem vertex buffer, e usa `viewProj` como **push constant** (64 bytes direto no command buffer).

## Passo 3: janela e Instance

```cpp
glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);          // o GLFW não cria contexto OpenGL
window = glfwCreateWindow(800, 600, "Black Hole (Vulkan)", nullptr, nullptr);
```

`createInstance()` pede ao GLFW as extensões de janela (`glfwGetRequiredInstanceExtensions`), declara
`apiVersion = VK_API_VERSION_1_3` e, em build sem `NDEBUG`, liga `VK_LAYER_KHRONOS_validation` se ela existir.

**Faça já**: rode com a layer ligada. Ela é seu professor; sem ela Vulkan só "não funciona" sem explicação.

## Passo 4: Surface, GPU e Device

1. `glfwCreateWindowSurface` cria a `VkSurfaceKHR` (a ponte janela ↔ Vulkan).
2. `pickDevice()` percorre `vkEnumeratePhysicalDevices` e escolhe uma GPU 1.3 que tenha **uma família de filas** com
   gráfico + compute + present (`vkGetPhysicalDeviceSurfaceSupportKHR`), preferindo a discreta.
3. Cria o `VkDevice` ligando as features 1.3 de que o código depende:

```cpp
VkPhysicalDeviceVulkan13Features f13{VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_3_FEATURES};
f13.dynamicRendering = VK_TRUE;   // sem render pass nem framebuffer
f13.synchronization2 = VK_TRUE;   // vkCmdPipelineBarrier2, mais legível
```

Se esquecer de ligar uma feature e usá-la, a validation layer avisa.

## Passo 5: Swapchain

`createSwapchain()` decide:

- **Formato**: só espaços de cor `SRGB_NONLINEAR` (os outros exigem `VK_EXT_swapchain_colorspace`; foi um erro real que a
  validation layer apontou). Prefere `B8G8R8A8_UNORM`, não `_SRGB`, porque o OpenGL original gravava valores crus.
- **Extent**: `caps.currentExtent`, ou o tamanho do framebuffer limitado por min/max.
- **Present mode**: `FIFO` (vsync), o único garantido.
- **Quantidade de imagens**: `minImageCount + 1`.

Para cada imagem: um `VkImageView` e **um semáforo `renderDone` por imagem** (o `present` pode ainda estar usando o semáforo
quando você já começou o próximo frame; um semáforo único causaria erro de validação).

`recreateSwapchain()` espera `vkDeviceWaitIdle`, destrói e recria. Trata janela minimizada (`while (w == 0 || h == 0) glfwWaitEvents()`).

## Passo 6: recursos (imagem, sampler, buffers)

- **Imagem de saída 200×150** `R8G8B8A8_UNORM`, uso `STORAGE | SAMPLED`: o compute escreve, o quad lê. Memória `DEVICE_LOCAL`.
- **Sampler** linear com `CLAMP_TO_EDGE` (o `REPEAT` padrão do OpenGL misturava a borda de cima com a de baixo).
- **Buffers** via `makeBuffer()`: `vkCreateBuffer` → `vkGetBufferMemoryRequirements` → `findMemType` → `vkAllocateMemory` →
  `vkBindBufferMemory` → `vkMapMemory`. Tudo em memória `HOST_VISIBLE | HOST_COHERENT` e **mapeada para sempre**, então atualizar
  é um `memcpy`. Atalho consciente: um `vkAllocateMemory` por buffer (o limite do driver é na casa de milhares; aqui são 5).
  Em projeto grande use VMA.
- Os índices do grid são preenchidos **uma vez**; os vértices, a cada frame (o grid se deforma quando os objetos se movem).

Cuidado com o layout dos UBOs (std140): o struct C++ de câmera tem padding explícito (`_p0`, `_p1`...) para cada `vec3` ocupar 16 bytes.
Um `vec3` seguido de `float` empacota; seguido de outro `vec3` não. Errar isso dá imagem errada sem nenhum erro.

## Passo 7: Descriptors e Pipeline Layout

Um único `VkDescriptorSetLayout` com 5 bindings serve compute e gráfico:

| binding | tipo | quem usa |
|---|---|---|
| 0 | `STORAGE_IMAGE` | compute (escreve a imagem) |
| 1, 2, 3 | `UNIFORM_BUFFER` | compute (câmera, disco, objetos) |
| 4 | `COMBINED_IMAGE_SAMPLER` | fragment do quad |

Depois: `VkPipelineLayout` (este layout + o push constant de 64 bytes no estágio vertex), `VkDescriptorPool`,
`vkAllocateDescriptorSets` e **um** `vkUpdateDescriptorSets` apontando cada binding para seu recurso. Como os recursos nunca mudam
de endereço, o set é escrito uma única vez na inicialização.

A imagem fica sempre em `VK_IMAGE_LAYOUT_GENERAL`: serve para escrever como storage e ler como sampled. É simples; o preço é perder
otimizações de layout específicas.

## Passo 8: Pipelines

- **Compute**: `vkCreateComputePipelines` com o módulo `geodesic_vk.comp.spv`.
- **Gráficos** (`makeGraphicsPipeline`, usada para o grid em `LINE_LIST` e para o quad em `TRIANGLE_LIST`):
  - **Dynamic rendering**: em vez de render pass, o pipeline declara o formato do alvo em `VkPipelineRenderingCreateInfo`.
  - **Viewport e scissor dinâmicos**, para o resize não recriar o pipeline.
  - **Blend explícito** `SRC_ALPHA / ONE_MINUS_SRC_ALPHA`. No OpenGL o blend ficava ligado "sem querer" depois do grid;
    por isso o grid aparece por trás da imagem. Em Vulkan isso é estado do pipeline, então é uma decisão sua.
  - Módulos de shader são destruídos logo após criar o pipeline.

## Passo 9: o frame

```
vkWaitForFences            espera o frame anterior terminar na GPU
vkAcquireNextImageKHR      pega uma imagem do swapchain (sinaliza imageAvail)
vkResetFences
uploadUBOs                 memcpy de câmera, disco, objetos, vértices do grid
record(i)                  grava o command buffer (passo 10)
vkQueueSubmit              espera imageAvail, sinaliza renderDone[i] e a fence
vkQueuePresentKHR          espera renderDone[i]
```

Se `acquire`/`present` devolvem `OUT_OF_DATE` ou `SUBOPTIMAL`, ou a janela foi redimensionada → `recreateSwapchain()`.

Atalho consciente (`shortcut:` no código): **1 frame em voo**. A CPU espera a fence no começo de cada frame. Com 2 frames em voo
você precisaria de 2 conjuntos de UBOs, 2 command buffers e 2 fences. É o primeiro exercício recomendado.

## Passo 10: gravar o command buffer e as barreiras

A parte mais difícil de Vulkan. A ordem em `record()`:

| # | O que acontece | Por que a barreira existe |
|---|---|---|
| 1 | imagem de saída `UNDEFINED → GENERAL` (src: fragment, dst: compute write) | o frame anterior ainda pode estar lendo a imagem no fragment; `UNDEFINED` vale porque o compute reescreve tudo |
| 2 | `vkCmdDispatch(13, 10, 1)` (grupos de 16×16 cobrem 200×150) | o ray tracer |
| 3 | `GENERAL → GENERAL` (src: compute write, dst: fragment read) | sem isso o quad pode ler a imagem antes do compute terminar: imagem com lixo/piscando |
| 4 | swapchain `UNDEFINED → COLOR_ATTACHMENT_OPTIMAL` | pronta para ser desenhada |
| 5 | `vkCmdBeginRendering` com `LOAD_OP_CLEAR`, desenha o grid e depois o quad | o quad por cima, com blend |
| 6 | swapchain `COLOR_ATTACHMENT → PRESENT_SRC_KHR` | só nesse layout a imagem pode ser apresentada |

Detalhes de matemática: `proj[1][1] *= -1` porque o eixo Y do clip space do Vulkan aponta para baixo, e a profundidade usa
`GLM_FORCE_DEPTH_ZERO_TO_ONE`.

## Passo 11: CMake

Bloco final do `CMakeLists.txt`: `find_package(Vulkan COMPONENTS glslc)`, e para cada shader um `add_custom_command` que roda
`glslc --target-env=vulkan1.3` (com `DEPENDS`, então recompila quando o shader muda) e uma etapa `POST_BUILD` que copia os `.spv`
para o lado do executável. O programa abre os `.spv` pelo diretório de trabalho (`readFile`), então rode de dentro de `build/`.

```bash
cd C++
cmake -B build -S . -DCMAKE_BUILD_TYPE=Debug     # Debug: validation layer ligada
cmake --build build
cd build && ./black_hole_cpp
```

## Passo 12: depurar e aprender

1. Rode em `Debug` e leia **toda** mensagem da validation layer. Zero mensagens é o objetivo.
2. Quebre de propósito, um por vez, e observe o que a layer diz:
   - remova a barreira 3 do passo 10;
   - troque um `stageMask` por `ALL_COMMANDS` e depois por `NONE`;
   - esqueça o `set = 0` num shader;
   - troque o formato do swapchain para `_SRGB` e compare as cores;
   - desligue o blend do quad e veja o grid sumir.
3. `RENDERDOC` (se instalado) mostra imagem de saída, UBOs e cada draw: ótimo para checar o std140.
4. Exercícios: dois frames em voo; `vkCmdBlitImage` no lugar do quad (e entender por que o grid some); exercitar o resize;
   trocar a memória host-visible por staging buffer + memória device-local.

## Armadilhas que já aconteceram neste projeto

- **Cores/validação do swapchain**: formato com espaço de cor sem a extensão ativada (`BT709_NONLINEAR`).
- **Eixo Y invertido**: o clip space do Vulkan tem Y para baixo; sem o `proj[1][1] *= -1` o grid sai espelhado verticalmente.
- **`bool` do GLSL**: 4 bytes. Use `int` nos dois lados.
- **Semáforo de render por imagem**, não um só.
- **TDR/watchdog**: um dispatch de 60000 passos por pixel pode ser morto por driver em GPU fraca. Vulkan não protege disso.
