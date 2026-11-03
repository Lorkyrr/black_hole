# Port para Python: o que foi feito e por quê

Documento de estudo. Complementa o [`TUTORIAL_PYTHON.md`](TUTORIAL_PYTHON.md) (como refazer).

> Este código foi escrito com ajuda de IA (Claude Code) sob minha direção, como **base de estudo para aprender Vulkan com Python**.
> Não foi escrito linha a linha por mim; o objetivo é ler, quebrar e modificar.

## 1. Resumo

- `Python/` é o quarto port da simulação 3D, depois de `C++/` (Vulkan), `Go/` (OpenGL) e `Rust/` (wgpu); veio depois `Java/` (Vulkan).
- É um porte **linha a linha do renderer Vulkan do C++** (`black_hole_vk.cpp`), em Python: mesmos objetos Vulkan, mesma ordem, mesmas barreiras.
  Por isso ele serve para comparar com o C++ lado a lado.
- Shaders: **os mesmos GLSL do C++**, sem alteração, compilados com `glslc`.
- Python só orquestra: o trabalho pesado (60000 passos por pixel) roda na GPU, então a linguagem não muda a velocidade em nada.

## 2. Mapa de arquivos

```
Python/
├── pyproject.toml          # uv: vulkan, glfw, numpy (+ pytest no grupo dev); package = false
├── main.py                 # janela GLFW (Wayland), callbacks de entrada, laço principal
├── gpu.py                  # Renderer: instance, device, swapchain, pipelines, frame (porte de black_hole_vk.cpp)
├── gpu_layout.py           # bytes dos UBOs std140 e da push constant (numpy, sem importar Vulkan)
├── sim.py                  # física e câmera (Python puro): porte de sim.h
├── test_sim.py             # 8 testes, sem GPU
└── shaders/                # cópias dos .comp/.vert/.frag de C++/ (os .spv gerados ficam fora do git)
```

Mesma divisão dos outros ports: `sim.py` não conhece a GPU; `gpu_layout.py` só empacota bytes; `gpu.py` é o único que fala Vulkan.

## 3. Decisões e por quê

| Decisão | Por quê |
|---|---|
| `uv` + `pyproject.toml` | um comando (`uv run`) cria o venv, instala e executa; sem `pip`/`venv` manual |
| Bindings `vulkan` (cffi) | é Vulkan de verdade, com os mesmos nomes da API C; alternativas (wgpu-py) esconderiam o que se quer estudar |
| `glfw` (pyGLFW) | o wheel traz a `libglfw` com backend Wayland; não exige `libglfw3` do sistema. `init_hint(PLATFORM, WAYLAND)` força Wayland nativo, sem XWayland |
| `glfw.create_window_surface` | o pyGLFW converte o handle cffi do `vulkan` sozinho; não precisa de `vkCreateWaylandSurfaceKHR` à mão |
| Funções de extensão por `vkGetInstanceProcAddr`/`vkGetDeviceProcAddr` | o módulo `vulkan` não exporta `vkCreateSwapchainKHR`, `vkQueuePresentKHR` etc.; o `Renderer` as guarda como atributos |
| `glslc` chamado por `subprocess` na primeira execução | o CMake do C++ compila no build; aqui não há build. Só recompila se o `.spv` for mais velho que o fonte. `GLSLC` aponta para um binário fora do PATH |
| `gpu_layout.py` separado | o layout std140 é a parte mais fácil de errar; isolado em numpy dá para testar sem GPU (`test_ubo_sizes_...`) |
| Matriz de câmera em numpy (float64, cast para float32 no fim) | o intervalo 1e9..1e14 do plano near/far pede a precisão extra antes de gravar; a matriz é gravada coluna-maior (transposta) |
| Mesmas escolhas do C++ | dynamic rendering + synchronization2, swapchain `B8G8R8A8_UNORM` (não sRGB), FIFO, blend explícito, imagem de saída sempre em `GENERAL`, 1 frame em voo |

## 4. Mapeamento C++ → Python

| C++ (`black_hole_vk.cpp`) | Python (`gpu.py`) |
|---|---|
| `VkXxxCreateInfo ci{...}; vkCreateXxx(dev, &ci, nullptr, &h)` | `h = vk.vkCreateXxx(dev, vk.VkXxxCreateInfo(...), None)` (sType preenchido pelo binding) |
| `VK(x)` aborta se `!= VK_SUCCESS` | os wrappers lançam exceção (`VkErrorOutOfDateKhr`, ...) |
| `makeBuffer` + `memcpy` no ponteiro mapeado | classe `Buf` + `buf.map[:n] = bytes` (`vkMapMemory` devolve um `ffi.buffer`) |
| `vkCmdPushConstants(..., &viewProj)` | `ffi.cast("void *", ffi.from_buffer(bytes))`: o wrapper não aceita `bytes` direto em `void*` |
| `VkPhysicalDeviceVulkan13Features` em `pNext` | idem, passando a struct em `pNext=` (precisa continuar viva até a chamada) |
| `vkAcquireNextImageKHR` devolve `VkResult` | devolve o índice; `OUT_OF_DATE` vira exceção |
| `glm::lookAt/perspective` (`GLM_FORCE_DEPTH_ZERO_TO_ONE`) | `gpu_layout.view_proj`: mesma fórmula à mão, profundidade 0..1 e `proj[1][1] *= -1` |
| `GLFW_INCLUDE_VULKAN`, callbacks com `glfwSetUserPointer` | closures em `main.py` (`nonlocal gravity`) |

## 5. Como foi verificado

| Verificação | Resultado |
|---|---|
| `uv run pytest` | 8 testes OK (física, câmera, tamanhos dos UBOs, projeção da origem no centro da tela) |
| `uv run main.py --frames 60` em Ubuntu 26.10, Wayland, RTX 3050, **com a validation layer** | rodou e saiu sem **nenhuma mensagem** da validation layer |
| Captura do swapchain (script descartável) com a câmera fora do eixo | disco com lente gravitacional, sombra e grade deformada, como esperado |

## 6. O que NÃO foi verificado / riscos

- Não comparei a imagem pixel a pixel com a do C++. Os shaders e os bytes dos UBOs são os mesmos por construção, mas isso não foi medido.
- **Câmera inicial no plano do disco** (elevação π/2): a metade de baixo da tela sai toda amarela. É o comportamento do shader original (caso
  degenerado com o eixo polar em z), não do port; basta orbitar um pouco com o mouse.
- Redimensionar a janela e minimizar: o código existe (`recreate_swapchain`), mas **não foi exercitado**.
- Sem `vkDestroy*` no encerramento (como no C++). `VK_SUBOPTIMAL_KHR` no *acquire* não é tratado separadamente do `OUT_OF_DATE`.
- Só testado na GPU deste computador (NVIDIA); Intel/AMD e o `lavapipe` não.
- O wrapper `vulkan` tem a versão 1.3.275; a API de wrappers de extensão pode mudar entre versões.
- A física usa `float` do Python (64 bits); o C++ usava 32 bits. Os números diferem na casa do `1e-7`, sem efeito visível.
- O `glslc` do apt do Ubuntu 26.10 era a única fonte de compilador testada (`pyshaderc` não compila no Python 3.14).
