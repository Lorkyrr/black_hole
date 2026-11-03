# Port para Vulkan: o que foi feito e por quê

Documento de estudo. Complementa o [`README.md`](README.md) (como rodar) e o [`TUTORIAL_VULKAN.md`](TUTORIAL_VULKAN.md) (como refazer).

> Este código foi escrito com ajuda de IA (Claude Code) sob minha direção, como **base de estudo para aprender Vulkan na prática**.
> Não foi escrito linha a linha por mim; o objetivo é ler, quebrar e modificar.

> **Estado atual:** os programas OpenGL do C++ (`black_hole.cpp`, `2D_lensing.cpp`, `CPU-geodesic.cpp`, `ray_tracing.cpp`) e os shaders GL
> foram **removidos do fork**. Eles existem no upstream e no histórico (`git show ff07b3b:C++/black_hole.cpp`). As menções a eles abaixo
> descrevem o port como foi feito e servem de comparação OpenGL → Vulkan; as seções marcadas *(histórico)* já não se aplicam ao código atual.

## 1. Resumo

- Nasceu uma versão Vulkan 1.3 da simulação 3D: `black_hole_vk.cpp` + 5 shaders `*_vk.*`.
- O OpenGL original (`black_hole.cpp`) serviu de referência e foi refatorado para dividir código com o Vulkan (`sim.h`). Depois foi removido do fork (ver nota acima).
- Todo o código C++ do projeto foi movido para a pasta `C++/`, junto com um `Dockerfile`, este documento e o tutorial.
- O Vulkan **não deixa a simulação mais rápida**: o gargalo é o compute shader (60000 passos por pixel), que é o mesmo trabalho nas duas APIs. O ganho do port é controle explícito e aprendizado.

## 2. Mapa de arquivos

```
black_hole/
├── README.md                  # README geral (inglês)
├── Go/, Rust/                 # ports posteriores (Go/OpenGL e Rust/wgpu), cada um com seus docs
├── Gravity_Sim.zip            # não foi tocado (contém código-fonte antigo do autor)
└── C++/
    ├── README.md              # como compilar e rodar
    ├── PORT_VULKAN.md         # este documento
    ├── TUTORIAL_VULKAN.md     # tutorial passo a passo
    ├── CMakeLists.txt         # só Vulkan: alvo BlackHoleVK (executável black_hole_cpp) + compilação dos shaders
    ├── sim.h                  # código sem API gráfica (câmera, gravidade, grid)
    ├── black_hole_vk.cpp      # o renderer Vulkan (~450 linhas)
    ├── geodesic_vk.comp       # ray tracer para Vulkan (derivado do geodesic.comp original)
    ├── grid_vk.vert/.frag     # linhas do grid
    ├── quad_vk.vert/.frag     # quad de tela cheia que mostra o resultado do ray tracer
    └── Dockerfile, .dockerignore
```

Os arquivos originais foram movidos para `C++/` com `git mv` (commit `ff07b3b`), então o histórico é preservado.

## 3. O que mudou em cada arquivo

### 3.1 `sim.h` e a refatoração do `black_hole.cpp` *(histórico)*

`sim.h` recebeu, **sem alterar a lógica**, o que não depende de API gráfica:
`Camera`, `BlackHole`, `ObjectData`, a lista `objects`, `setupCameraCallbacks`, o laço de gravidade (agora `stepGravity()`)
e a geração da malha do grid (agora `buildGrid(objects, vertices, indices)`, com `GRID_SIZE = 25`).

No `black_hole.cpp` da época: 247 linhas removidas e 5 inseridas. Ele passou a fazer `#include "sim.h"`, chamar `stepGravity()` e `buildGrid()`,
e o que sobrou era só OpenGL (`Engine`: shaders, UBOs, VAO/VBO, desenho).

Regra para quem inclui `sim.h`: definir antes `GLFW_INCLUDE_VULKAN` (como faz `black_hole_vk.cpp`). Hoje só o renderer Vulkan o inclui.

### 3.2 Shaders (`*_vk.*`)

`geodesic_vk.comp` é o `geodesic.comp` original (`git show ff07b3b:C++/geodesic.comp`, ou `Go/shaders/geodesic.comp`) com:

| Mudança | Motivo |
|---|---|
| `#version 430` → `450` | GLSL para Vulkan precisa de 450+ |
| `layout(set = 0, binding = N)` em tudo | no Vulkan todo recurso pertence a um *descriptor set* |
| `bool moving` → `int moving` | `bool` do GLSL tem 4 bytes e o do C++ tem 1; a diferença causava bytes de lixo no UBO |
| `float mass[16]` removido | nunca era usado, e em std140 o stride de `float[]` é 16 bytes, não 4 (o struct C++ do GL estava errado) |
| `double` → `float` (`ESCAPE_R`, `r` do disco) | `double` exige a feature `shaderFloat64`; o valor cabe em float |

Os outros quatro são pequenos:
- `grid_vk.vert`: `uniform mat4 viewProj` virou **push constant** (64 bytes, sem buffer).
- `grid_vk.frag`: mesma cor cinza translúcida do original.
- `quad_vk.vert`: gera os 6 vértices a partir de `gl_VertexIndex`, sem vertex buffer. Mapeia `v = 0` para o **fundo** da tela, como no GL (no Vulkan o eixo Y do clip space aponta para baixo).
- `quad_vk.frag`: amostra a textura (`set 0, binding 4`).

Compilação: `glslc --target-env=vulkan1.3 arquivo -o arquivo.spv` (o CMake faz isso).

### 3.3 `black_hole_vk.cpp`: o renderer

Escrito à mão, sem bibliotecas extras (só Vulkan + GLFW + GLM). Usa **dynamic rendering** e **synchronization2**
(Vulkan 1.3), então não existem render pass nem framebuffers.

Ordem do `App` (construtor):
1. Janela GLFW com `GLFW_NO_API`.
2. `createInstance`: Vulkan 1.3, extensões do GLFW, validation layer se existir (e se não for `NDEBUG`).
3. Surface (`glfwCreateWindowSurface`) e `pickDevice`: escolhe uma GPU 1.3 com fila gráfica+compute+present, preferindo a discreta.
4. `createSwapchain`: formato `B8G8R8A8_UNORM`, FIFO (vsync).
5. `createResources`: imagem 200×150 de saída, sampler, 3 UBOs, vertex/index buffer do grid, descriptor set.
6. `createPipelines`: 1 de compute, 2 gráficos (grid e quad).

Um frame (`frame()`):
1. Espera a fence e adquire a imagem do swapchain.
2. `uploadUBOs`: copia câmera, disco, objetos e vértices do grid para memória mapeada.
3. `record` grava o command buffer:
   1. barreira da imagem de saída (`UNDEFINED → GENERAL`);
   2. `vkCmdDispatch(13, 10, 1)`: o ray tracer;
   3. barreira *compute escreveu → fragment vai ler*;
   4. barreira do swapchain → `COLOR_ATTACHMENT`;
   5. render: limpa, desenha o **grid**, depois o **quad** (com blending) por cima;
   6. barreira do swapchain → `PRESENT_SRC`.
4. `vkQueueSubmit` (espera o semáforo de aquisição, sinaliza um semáforo por imagem) e `vkQueuePresentKHR`.
5. Se o swapchain ficou obsoleto ou a janela foi redimensionada, `recreateSwapchain()`.

### 3.4 `CMakeLists.txt`

Originalmente era um bloco opcional ao final do arquivo; **hoje o `CMakeLists.txt` é só Vulkan** (os alvos OpenGL e 2D foram removidos)
e `find_package(Vulkan REQUIRED COMPONENTS glslc)` é obrigatório. Ele define o alvo `BlackHoleVK` (executável `black_hole_cpp`),
compila os 5 shaders para `build/shaders/*.spv` (com `DEPENDS`, então recompila quando o `.comp` muda) e copia os `.spv`
para a pasta do executável.

### 3.5 `Dockerfile`

Multi-stage em `ubuntu:24.04`:
- **build**: instala toolchain, GLFW, GLM, Vulkan e glslc, e compila o projeto (que só tem o alvo `BlackHoleVK`).
- **runtime**: `libvulkan1`, `mesa-vulkan-drivers` (Intel/AMD e lavapipe), `libglfw3`, e copia o executável + `.spv`. Imagem final: 553 MB.
- Comandos de execução estão no cabeçalho do arquivo e no README. O driver NVIDIA entra via `nvidia-container-toolkit`.

## 4. Mapeamento OpenGL → Vulkan (a parte para estudar)

(A coluna da esquerda descreve o `black_hole.cpp` original do upstream.)

| OpenGL (`black_hole.cpp`) | Vulkan (`black_hole_vk.cpp`) |
|---|---|
| GLFW + GLEW, contexto 4.3 | `GLFW_NO_API`, instance, surface, device |
| `glDispatchCompute` + `glMemoryBarrier` | pipeline de compute, `vkCmdDispatch`, barreira explícita |
| `glTexImage2D` a cada frame | imagem criada uma vez (o tamanho nunca mudava) |
| `glBufferSubData` nos UBOs | buffers mapeados, `memcpy` |
| `glUniformMatrix4fv(viewProj)` | push constant |
| VAO/VBO/EBO do grid, EBO reenviado todo frame | vertex buffer reescrito por frame, index buffer preenchido **uma vez** |
| `glBlendFunc` (ligado e nunca desligado) | blend explícito no pipeline |
| `glfwSwapBuffers` | acquire → submit → present + semáforos e fence |
| Estado implícito do driver | layouts de imagem e barreiras escritos por você |

Atalhos assumidos (marcados como `shortcut:` no código):
- **1 frame em voo**: a CPU espera a fence no início de cada frame. Dispensa cópias por frame dos UBOs; o custo é a CPU não adiantar o próximo frame.
- **Todos os buffers em memória host-visible mapeada**, uma alocação por buffer, sem VMA. Bom para buffers minúsculos; ruim se crescerem.
- **Sem `vkDestroy*` no encerramento**: o processo termina e o SO recupera tudo.
- **Imagem de saída sempre em `GENERAL`** (serve para escrita de storage e leitura amostrada).

## 5. Problemas encontrados no caminho

Achados da validation layer e das comparações. Os marcados "original" já existiam no código OpenGL e **não foram corrigidos** lá.

1. **Espaço de cor (corrigido no port).** A validation layer reclamou: o driver Wayland lista formatos com `BT709_NONLINEAR`, que exigem a extensão `VK_EXT_swapchain_colorspace`. Agora só escolho formatos `SRGB_NONLINEAR`.
2. **Blending vazando (original).** `drawGrid` liga `GL_BLEND` e nunca desliga, e é por isso que o grid aparece por trás do quad. No Vulkan o blend é estado do pipeline, então o do quad é explícito. Por isso o quad **não** foi trocado por um `vkCmdBlitImage`: o blit apagaria o grid.
3. **Quad desenhado com triângulo duplicado (original).** `glDrawArrays(GL_TRIANGLE_STRIP, 0, 6)` com 6 vértices pensados para `GL_TRIANGLES` desenha um triângulo duas vezes; com o blend vazado, os pixels semitransparentes ficam mais claros no triângulo inferior-esquerdo (uma emenda diagonal visível). Correção sugerida, não aplicada: `GL_TRIANGLES`.
4. **Wrap `REPEAT` (original).** A textura GL usa o wrap padrão (`REPEAT`), então as bordas de cima e de baixo se misturam na filtragem linear. No port uso `CLAMP_TO_EDGE`.
5. **Layout std140 errado (original).** `float mass[16]` no UBO ocupa 16 bytes por elemento em std140, mas o struct C++ usa 4. Não quebra porque o shader nunca lê `mass`. E o `bool moving` do C++ (1 byte) vs GLSL (4 bytes).
6. **Código inerte (original).** No shader e no `dispatchCompute`, os ramos de `cam.moving` têm o mesmo valor nos dois lados (200×150, 60000 passos).
7. **A imagem em si (original).** A metade de cima da tela fica toda amarela com a câmera padrão. Isso também acontece na versão OpenGL, então vem do shader, não do port.

## 6. Como foi verificado

Ambiente sem os pacotes de desenvolvimento e sem `sudo`: baixei os `.deb` (`apt download`) e extraí num sysroot privado
no diretório temporário, sem instalar nada no sistema.

| Verificação | Resultado |
|---|---|
| Compilação de `black_hole_vk.cpp` (`-Wall -Wextra`) | OK, sem erros; único aviso é de código antigo (variável `radius` em `sim.h`) |
| Shaders → SPIR-V com `glslc` | os 5 compilam |
| `black_hole.cpp` refatorado *(histórico)* | passou em `-fsyntax-only` (com stub do GLEW); nunca foi executado já refatorado, e depois foi removido |
| Execução na RTX 3050 com a validation layer (cópia de teste que grava o frame) | 4 frames sem nenhuma mensagem de validação (depois da correção do item 5.1) |
| Execução em RADV (AMD) e lavapipe (CPU) | roda; RADV e NVIDIA diferem em só 1.757 pixels |
| IntelliSense do VS Code (`getDiagnostics`) | sem problemas em `black_hole_vk.cpp` |
| `docker build` | OK, imagem de 553 MB |
| `docker run` com `--gpus all` | roda na RTX 3050 e fica vivo durante os 12 s do teste |

**Paridade visual com o OpenGL.** Renderizei 1 frame do `black_hole.cpp` **original** (a cópia anterior à refatoração) e do Vulkan, e comparei pixel a pixel (800×600 = 480.000 px):

- Primeira comparação (GL na GPU AMD vs Vulkan): 74.810 px diferentes. Rodando os dois em software (llvmpipe vs lavapipe) o número continuou igual, o que descartou ruído numérico.
- Corrigindo só o item 5.3 na cópia de teste do GL: cai para **3.250 px (0,7%)**.
- Esses 3.250 se explicam por: linhas 0, 1 e 599 (2.400 px, item 5.4, causa provável não testada isoladamente) e ~850 px de linhas do grid (diferença de rasterização de linhas entre drivers).

Conclusão: o ray tracer produz a mesma imagem; as diferenças restantes vêm de comportamentos do OpenGL original que o port deliberadamente não copiou.

Todo o aparato de captura (patches que gravam o frame em PPM, sysroot, binários de teste) ficou fora do repositório, no scratchpad da sessão.

## 7. O que NÃO foi verificado

- **Redimensionar a janela**: o caminho `recreateSwapchain()` foi escrito mas não exercitado.
- **macOS** (MoltenVK) e GPUs Intel.
- **Container com a validation layer** (build `Debug`) e container em Intel/AMD: o teste foi só na NVIDIA.
- Execução prolongada ou interativa: os testes pegaram só os primeiros frames, sem mexer a câmera com o mouse nem ligar a gravidade (`G`).
- A validation layer foi usada na **cópia de teste** que grava o frame; o binário final do repositório foi executado com ela só antes da correção do item 5.1. O container usa build `Release` (sem validation layer).
- **Risco de timeout do driver (TDR/watchdog)**: um único dispatch longo pode ser morto em GPUs mais lentas. O Vulkan não protege disso.

## 8. Como compilar e rodar

Nativo (Ubuntu/Debian):

```bash
sudo apt install build-essential cmake libglfw3-dev libglm-dev \
                 libvulkan-dev glslc vulkan-validationlayers mesa-vulkan-drivers
cd C++
cmake -B build -S . && cmake --build build --target BlackHoleVK
cd build && ./black_hole_cpp
```

(Sem `-DCMAKE_BUILD_TYPE=Release`, a validation layer fica ligada se estiver instalada. Para desligar: `Release`.)

Docker, a partir da raiz do repositório:

```bash
docker build -t black-hole-vk C++
X="--network host -e DISPLAY=$DISPLAY -e XAUTHORITY=/tmp/.xauth -v $XAUTHORITY:/tmp/.xauth:ro -v /tmp/.X11-unix:/tmp/.X11-unix"
docker run --rm -it $X --gpus all -e NVIDIA_DRIVER_CAPABILITIES=all black-hole-vk   # NVIDIA
docker run --rm -it $X --device /dev/dri black-hole-vk                              # Intel / AMD
```

Controles (os mesmos do original): botão esquerdo orbita, scroll dá zoom, `G` ou botão direito liga a gravidade.

## 9. Roteiro de estudo sugerido

1. Leia `geodesic_vk.comp` e compare com o original (`git show ff07b3b:C++/geodesic.comp | diff - geodesic_vk.comp`): são só 7 mudanças pequenas.
2. Leia `sim.h` para ver o que é lógica e o que é API.
3. Leia `black_hole_vk.cpp` na ordem do construtor (seção 3.3) e depois `record()` com as barreiras. Pergunta-guia: *por que cada barreira existe e o que quebraria sem ela?*
4. Rode com a validation layer e **quebre de propósito**: remova uma barreira, troque um `stageMask`, esqueça o `set =` no shader. As mensagens da layer são o melhor professor.
5. Exercícios: (a) implementar 2 frames em voo; (b) trocar o quad por `vkCmdBlitImage` e ver o grid sumir; (c) corrigir os itens 5.3 e 5.4 no `black_hole.cpp` do upstream; (d) exercitar o redimensionamento.

## 10. Observações fora do escopo

- `Rust/` e `Go/` são ports posteriores da mesma simulação, documentados em [`../Rust/PORT_RUST.md`](../Rust/PORT_RUST.md) e [`../Go/PORT_GO.md`](../Go/PORT_GO.md). Os erros de compilação do `wgpu`/`glam` citados numa versão anterior deste texto foram corrigidos (`cargo test` passa).
