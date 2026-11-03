# Tutorial: refazendo o port Java/Vulkan do zero

Siga na ordem. Cada passo termina com algo que você consegue rodar. O **porquê** das decisões está em [`PORT_JAVA.md`](PORT_JAVA.md).
Este tutorial assume que você já leu (ou vai ler junto) [`../C++/TUTORIAL_VULKAN.md`](../C++/TUTORIAL_VULKAN.md): os conceitos de Vulkan são os mesmos; aqui só muda a linguagem.

> O código foi gerado com ajuda de IA. Para aprender de verdade, **digite você mesmo** cada passo e só depois compare com o repositório.

## Passo 0: ambiente

```bash
sudo apt install openjdk-25-jdk maven mesa-vulkan-drivers
java -version && mvn -v          # Java 25+
vulkaninfo --summary             # uma GPU com Vulkan 1.3
```

**Maven** é a ferramenta de build (como `cmake` ou `cargo`): o `pom.xml` lista as dependências, ele baixa, compila, testa e empacota num `.jar`.

## Passo 1: o projeto Maven

Crie `Java/pom.xml` com: `maven.compiler.release` 25; o `lwjgl-bom` (versões alinhadas); dependências `lwjgl`, `lwjgl-glfw`, `lwjgl-vulkan`, `lwjgl-shaderc`
**mais** as mesmas (menos `vulkan`) com `<classifier>natives-linux</classifier>` — são as `.so` que o Java carrega; `joml`; `junit-jupiter` (test); e o `maven-shade-plugin`
com `mainClass blackhole.Main` para gerar um jar único.
Fixe também o `maven-surefire-plugin` em 3.5.2: o padrão do Maven não executa JUnit 5 e `mvn test` "passa" com `Tests run: 0`.

```bash
mvn -q package        # ainda sem código: só confere que as dependências baixam
```

## Passo 2: `sim` (física, sem gráficos)

Em `src/main/java/blackhole/sim/`: `Obj` (posRadius, color, mass, velocity), `Camera` (raio, azimute, elevação, `position()`, `mouseMove`, `setDragging`, `scroll`)
e `Sim` (`schwarzschildRadius`, `defaultObjects`, `stepGravity`, `buildGrid`). É o mesmo conteúdo de `C++/sim.h` e `Go/sim/sim.go`.
Escreva o teste junto (`SimTest`): zoom preso entre 1e10 e 1e12, órbita só ao arrastar, gravidade aproxima os corpos, tamanhos do grid.

```bash
mvn test              # sem GPU
```

Lição de Java: `float` e `double` não convertem sozinhos de `double` para `float`; veja os `(float)` — a massa é `float` e as contas intermediárias `double`, como no C++.

## Passo 3: janela GLFW (`Main`)

`glfwInit`, `glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API)` (Vulkan cuida da superfície, não o GLFW), `glfwCreateWindow`, callbacks de mouse/teclado como lambdas,
laço `pollEvents → stepGravity → renderer.frame`. Comece com um laço vazio e confira que a janela abre.
**Primeira linha do `main`:** `System.setProperty("org.lwjgl.system.stackSize", "1024")` (senão `OutOfMemoryError: Out of stack space` no passo 4).

## Passo 4: instância, superfície, dispositivo

Em `VulkanRenderer`, na ordem do construtor: `createInstance` (extensões de `glfwGetRequiredInstanceExtensions`), `glfwCreateWindowSurface`,
`pickDevice` (GPU 1.3 com fila gráfica + compute + present, preferindo a discreta; habilitar `dynamicRendering` e `synchronization2`).
Padrão de todo o LWJGL: `try (MemoryStack stack = stackPush()) { ... Struct.calloc(stack) ... }`. Rode: deve imprimir `Vulkan device: <sua GPU>`.

## Passo 5: swapchain

Formato `B8G8R8A8_UNORM` com colorspace `SRGB_NONLINEAR`, FIFO (vsync), uma `ImageView` e um semáforo `renderDone` **por imagem**. Escreva também `recreateSwapchain`.

## Passo 6: recursos

Imagem de saída 200×150 `R8G8B8A8_UNORM` (`STORAGE | SAMPLED`), sampler `CLAMP_TO_EDGE`, 3 UBOs (80, 16, 528 bytes), vertex buffer do grid, index buffer (preenchido uma vez),
descriptor set layout (bindings 0–4), pipeline layout com push constant de 64 bytes (a matriz `viewProj`), pool e set.
`makeBuffer` aloca memória host-visible e mapeia; devolve um `ByteBuffer` para escrever.

## Passo 7: shaders e pipelines

Copie os 5 `*_vk.*` de `C++/` para `src/main/resources/shaders/`. `loadShader` lê o GLSL do jar, chama `shaderc_compile_into_spv` (alvo `vulkan1.3`)
e cria o `VkShaderModule`. Crie o pipeline de compute e os dois gráficos (grid em `LINE_LIST` com vertex input; quad em `TRIANGLE_LIST` sem vertex buffer), blend ligado nos dois.

## Passo 8: um frame

`frame()`: espera a fence → `vkAcquireNextImageKHR` → `uploadUBOs` (offsets std140 à mão: câmera em 0/16/32/48, `tanHalfFov` 64, `aspect` 68, `moving` 72)
→ `record` → `vkQueueSubmit` → `vkQueuePresentKHR`.
`record`: barreira `UNDEFINED→GENERAL`, `vkCmdDispatch(13,10,1)`, barreira compute→fragment, barreira do swapchain, `vkCmdBeginRendering`, grid, quad, `vkCmdEndRendering`, barreira → `PRESENT_SRC`.

```bash
mvn -q package && java --enable-native-access=ALL-UNNAMED -jar target/black_hole_java.jar
```

## Passo 9: quebre de propósito

1. Instale `vulkan-validationlayers` e rode com `-Dvk.validation=true`.
2. Remova uma barreira, troque um `stageMask`, mude um offset do UBO: veja a layer reclamar ou a imagem quebrar.
3. Exercícios: 2 frames em voo; exercitar o redimensionamento; comparar a imagem com `C++/` e `Go/`.
