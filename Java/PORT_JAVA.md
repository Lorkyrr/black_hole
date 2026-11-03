# Port para Java (Vulkan 1.3): o que foi feito e por quê

Documento de estudo. Complementa o [`README.md`](README.md) (como rodar) e o [`TUTORIAL_JAVA.md`](TUTORIAL_JAVA.md) (como refazer).

> Este código foi escrito com ajuda de IA (Claude Code) sob minha direção, como **base de estudo para aprender Vulkan e Java na prática**.
> Não foi escrito linha a linha por mim; o objetivo é ler, quebrar e modificar.

## 1. Resumo

- `Java/` é mais um port da simulação 3D do buraco negro (os outros: C++/Vulkan, Go/OpenGL, Rust/wgpu).
- É um **porte quase linha a linha de `C++/black_hole_vk.cpp`** para Java, usando os bindings do **LWJGL 3** (GLFW + Vulkan).
  Mesmo desenho: compute de geodésicas → imagem 200×150 → quad, sobre a grade deformada; Vulkan 1.3 com dynamic rendering e synchronization2.
- Os 5 shaders GLSL são **cópias idênticas** dos de `C++/` (`*_vk.*`). Nada de física foi reescrito.
- Por que Vulkan e não OpenGL, como o Go? Porque aqui a graça é comparar o **mesmo código Vulkan** em outra linguagem: o que o C++ escreve com structs na pilha vira `Struct.calloc(stack)` no Java.

## 2. Mapa de arquivos

```
Java/
├── pom.xml                              # Maven: LWJGL, JOML, JUnit; maven-shade gera o jar único
├── src/main/java/blackhole/
│   ├── Main.java                        # janela GLFW, callbacks, laço principal
│   ├── VulkanRenderer.java              # todo o Vulkan (~470 linhas)   <- porte de black_hole_vk.cpp
│   └── sim/{Sim,Camera,Obj}.java        # física e câmera, sem gráficos <- porte de sim.h
├── src/main/resources/shaders/          # GLSL Vulkan, empacotado no jar
└── src/test/java/blackhole/sim/SimTest.java   # 6 testes
```

A divisão `sim` × renderer repete C++ (`sim.h`), Go e Rust: o pacote `sim` só usa JOML (tipos de vetor), então `mvn test` roda sem GPU.

## 3. Decisões e por quê

| Decisão | Por quê |
|---|---|
| LWJGL 3.3.6 | bindings Vulkan 1.3 completos (`VK13`, `VkImageMemoryBarrier2`, `vkCmdBeginRendering`) e GLFW com Wayland |
| shaderc em runtime, não `glslc` | o C++ compila GLSL→SPIR-V no build com `glslc`. No Java o LWJGL traz shaderc: o jar é autocontido e não precisa instalar `glslc` |
| `MemoryStack` (`try (stack = stackPush())`) | é o equivalente das structs locais do C++: memória nativa liberada ao sair do bloco |
| Buffers mapeados + `ByteBuffer` com offsets std140 escritos à mão | mesmo papel do `memcpy` de structs no C++; Java não tem layout de struct, então os offsets (0, 16, 32, 48, 64, 68, 72) estão no código e vêm de `geodesic_vk.comp` |
| `org.lwjgl.system.stackSize=1024` (KB) | a pilha padrão (64 KB) estoura em `vkCreateInstance` com o driver NVIDIA (muitas extensões): `OutOfMemoryError: Out of stack space` |
| JOML com `perspective(..., zZeroToOne=true)` e `m11 = -m11` | equivale a `GLM_FORCE_DEPTH_ZERO_TO_ONE` e ao `proj[1][1] *= -1` do C++ |
| Mesmos atalhos do C++ (`shortcut:` no código) | 1 frame em voo, buffers host-visible, sem `vkDestroy*` no fim |
| Blend explícito no pipeline, `CLAMP_TO_EDGE`, `moving` como `int`, UBO sem `mass[]` | iguais ao C++ — ver `C++/PORT_VULKAN.md` §5 |
| Validation layer só com `-Dvk.validation=true` | no C++ depende de `NDEBUG`; Java não tem equivalente natural |
| `natives-linux` fixo no `pom.xml` | só Linux x64 foi testado |

## 4. Mapeamento C++ → Java

| C++ | Java |
|---|---|
| `VkBufferCreateInfo bi{...}; bi.size = ...` | `VkBufferCreateInfo.calloc(stack).sType$Default().size(...)` (encadeado) |
| `VK(x)` (aborta) | `check(x, "x")` (lança `IllegalStateException`) |
| `uint32_t* + vkEnumerate...` (duas chamadas) | `IntBuffer n` + `Buffer.calloc(n, stack)` + segunda chamada |
| `glslc` no CMake | `shaderc_compile_into_spv` em `loadShader()` |
| `glm::vec3/mat4` | `org.joml.Vector3f/Matrix4f` |
| `glfwCreateWindowSurface(inst, win, nullptr, &surf)` | `glfwCreateWindowSurface(instance, win, null, LongBuffer)` |
| `memcpy(buf.map, &ubo, sizeof ubo)` | `ByteBuffer.putFloat(offset, v)` / `Vector3f.get(offset, buf)` |
| `struct Camera` + callbacks globais | `sim.Camera`; callbacks são lambdas em `Main` |

## 5. Como foi verificado

| Verificação | Resultado |
|---|---|
| `mvn package` (compila, 6 testes, jar) | OK, 6 testes passam |
| `java -jar target/black_hole_java.jar` por 10–15 s na RTX 3050 (Wayland) | abre, escolhe a GPU, renderiza sem exceção até o `timeout` encerrar |
| Shaders compilam com shaderc (`vulkan1.3`) | sim — os 5 compilam na inicialização |

## 6. O que NÃO foi verificado / riscos

- **Não olhei a imagem**: só confirmei que roda sem exceção. Não foi comparada pixel a pixel com o C++.
- **Validation layer não instalada** nesta máquina: o caminho `-Dvk.validation=true` não foi exercitado, e **nenhum erro de sincronização foi checado pela layer**. É o primeiro passo recomendado de estudo.
- Não testados: redimensionar a janela (`recreateSwapchain`), mouse, `G`/gravidade, X11, Windows, macOS, Intel/AMD.
- JDK 25 imprime avisos (`Unsafe`, acesso nativo). Inofensivos; para silenciar: `java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -jar ...`.
- Mesmo risco de timeout de driver do C++ (um dispatch longo, 60000 passos por pixel).
- Testes cobrem só `sim`; `VulkanRenderer` precisa de GPU.
