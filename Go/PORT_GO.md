# Port para Go: o que foi feito e por quê

Documento de estudo. Complementa o [`README.md`](README.md) (como rodar) e o [`TUTORIAL_GO.md`](TUTORIAL_GO.md) (como refazer).

> Este código foi escrito com ajuda de IA (Claude Code) sob minha direção, como **base de estudo para aprender Go e
> OpenGL via cgo na prática**. Não foi escrito linha a linha por mim; o objetivo é ler, quebrar e modificar.

## 1. Resumo

- `Go/` é o segundo port da simulação 3D do buraco negro, depois do C++/Vulkan em `C++/` (vieram depois: Rust/wgpu, Python/Vulkan e Java/Vulkan).
- Faz **o mesmo desenho do `black_hole.cpp` original em OpenGL 4.3**: compute shader de geodésicas → textura 200×150 → quad de tela
  cheia, com a grade de espaço-tempo deformada por baixo.
- Os shaders (`shaders/geodesic.comp`, `grid.vert`, `grid.frag`) são **idênticos byte a byte** aos do projeto original
  (verificado com `diff` contra o `geodesic.comp` do commit base). Nada de física foi reescrito: o Go só reproduz o lado CPU.
- Por que OpenGL e não Vulkan em Go? Porque o objetivo aqui era aprender **Go** (módulos, pacotes, cgo, testes, `embed`) sem
  somar a complexidade do Vulkan por cima. Vulkan ficou em C++, onde ele tem mais material de estudo.

## 2. Mapa de arquivos

```
Go/
├── go.mod, go.sum      # módulo "blackhole"; deps: go-gl/gl 4.3-core, go-gl/glfw 3.3, go-gl/mathgl
├── main.go             # janela GLFW, callbacks de mouse/teclado, laço principal
├── render.go           # todo o OpenGL: shaders, UBOs, VAO/VBO, textura, desenho
├── shaders/            # GLSL embutido no binário com //go:embed
└── sim/
    ├── sim.go          # física e câmera (Go puro, sem cgo)  <- porte de C++/sim.h
    └── sim_test.go     # 6 testes
```

A divisão `sim/` (lógica) × `main.go`/`render.go` (API gráfica) repete o que foi feito no C++ com `sim.h`: o pacote `sim`
**não importa nenhuma biblioteca gráfica** além de `mathgl` (só tipos de vetor). Consequência prática: `go test ./sim/`
roda em qualquer máquina, sem GPU e sem cgo.

## 3. Decisões e por quê

| Decisão | Por quê |
|---|---|
| Pacote `sim` separado, sem cgo | testável sem janela; deixa o limite entre física e desenho explícito |
| `//go:embed shaders/*` | o binário é autocontido; não depende do diretório de trabalho (o C++ precisa achar os `.comp`/`.spv` na pasta atual) |
| `runtime.LockOSThread()` em `init()` | GLFW e o contexto OpenGL só funcionam na thread principal; o runtime do Go move goroutines entre threads se você não travar |
| `sagaMass = float32(8.54e36)` | o C++ guardava a massa em `float`; para a imagem bater, o Go usa o mesmo valor arredondado |
| `Mass [MaxObjects][4]float32` no UBO | em std140 cada `float` de um array ocupa 16 bytes. O struct C++ original usava 4 e estava errado (ver `C++/PORT_VULKAN.md` §5.5); aqui o layout está certo |
| Quad com `gl.TRIANGLES` | o C++ usava `GL_TRIANGLE_STRIP` com 6 vértices pensados para triângulos, desenhando um triângulo duplicado |
| Blend continua ligado depois do grid | mantido **de propósito** para a imagem sair igual ao original: é isso que deixa a grade aparecer por trás do buraco negro |
| `gl.InitWithProcAddrFunc(glfw.GetProcAddress)` | no Wayland o contexto é EGL; o carregador padrão do `go-gl` usa `glXGetProcAddress` e falha |
| `-tags wayland` | compila o GLFW para Wayland nativo, sem XWayland; dispensa os headers X11 (`xorg-dev`) |
| `aspect` constante 800/600 | como no C++: o ray tracer sempre renderiza 200×150, independente do tamanho da janela |
| `moving` só em `Dragging` | o original também considerava `panning`; no port o botão do meio faz o mesmo que o esquerdo |

## 4. Mapeamento C++ → Go

| C++ (`sim.h` / `black_hole.cpp`) | Go |
|---|---|
| `struct Camera` + callbacks GLFW | `sim.Camera` com `MouseMove`, `SetDragging`, `Scroll`; os callbacks ficam em `main.go` |
| `stepGravity()` (variável global) | `sim.StepGravity(objs, gravity bool)`: sem estado global |
| `buildGrid(objects, v, idx)` com parâmetros de saída | `sim.BuildGrid(objs) ([]Vec3, []uint32)`: retorna os dois |
| `std::vector<ObjectData>` | `[]sim.Object` |
| `glm::vec3/mat4` | `mgl32.Vec3/Mat4` |
| UBO como `struct` C++ + `memcpy` | `struct` Go anônimo + `unsafe.Pointer` + `gl.BufferSubData`; os campos `_ float32` são o padding std140 |
| `exit(EXIT_FAILURE)` | `panic` com o log do compilador de shader |

## 5. Como foi verificado

| Verificação | Resultado |
|---|---|
| `go test -count=1 ./sim/` | OK, 6 testes (raio de Schwarzschild, posição inicial da câmera, limites do zoom, órbita só ao arrastar, gravidade ligada/desligada, tamanhos do grid) |
| `go build -tags wayland .` | OK |
| `go vet -tags wayland ./...` | OK, sem avisos |
| `diff` dos shaders contra `geodesic.comp` do commit base | idênticos |
| Executar a janela e comparar a imagem com a versão C++ | **não feito** nesta etapa (só compilação e testes) |

## 6. O que NÃO foi verificado / riscos

- Sem `-tags wayland` o build precisa dos headers X11 (`libx11-dev libxcursor-dev libxrandr-dev libxinerama-dev libxi-dev`, ou `xorg-dev`);
  numa máquina sem eles o `go build` falha dentro do cgo do GLFW.
- Não testado em X11 puro, macOS ou Windows. macOS limita o OpenGL a 4.1, então o compute shader não roda lá.
- O mesmo risco de timeout de driver (dispatch longo: 60000 passos por pixel) vale aqui.
- Os testes cobrem só o pacote `sim`. `render.go` não tem teste automático (precisa de contexto OpenGL).
- Os shaders GLSL originais (`geodesic.comp`, `grid.*`) saíram de `C++/`: as únicas cópias no fork são as de `Go/shaders/`; no histórico estão em `git show ff07b3b:C++/geodesic.comp`.
