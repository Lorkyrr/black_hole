# Tutorial: refazendo o port Go do zero

Siga na ordem. Cada passo termina com algo que você consegue rodar. O **porquê** das decisões está em [`PORT_GO.md`](PORT_GO.md).

> O código foi gerado com ajuda de IA. Para aprender Go de verdade, **digite você mesmo** cada passo e só depois compare com o repositório.

## Passo 0: ambiente

```bash
go version                                   # 1.22+ (o go.mod usa a versão instalada)
sudo apt install build-essential libgl1-mesa-dev libegl-dev libwayland-dev libxkbcommon-dev   # Wayland
# em X11 puro: sudo apt install build-essential libgl1-mesa-dev xorg-dev
```

GLFW e `go-gl` usam **cgo** (Go chamando C). Por isso precisam de `gcc` e dos headers.

## Passo 1: o módulo

```bash
mkdir Go && cd Go
go mod init blackhole
go get github.com/go-gl/gl/v4.3-core/gl
go get github.com/go-gl/glfw/v3.3/glfw
go get github.com/go-gl/mathgl/mgl32
```

`go.mod` registra o nome do módulo (`blackhole`) e as dependências. Os pacotes internos são importados como `blackhole/sim`.

## Passo 2: o pacote `sim` (física, sem gráficos)

Crie `sim/sim.go` com `package sim`. Escreva nesta ordem, **testando a cada item**:

1. Constantes `C`, `G`, `GridSize = 25`, `MaxObjects = 16` e `SchwarzschildRadius(m) = 2·G·m/c²`.
2. `type Object struct { PosRadius, Color mgl32.Vec4; Mass float32; Velocity mgl32.Vec3 }` e `DefaultObjects()`
   (dois corpos a 4e11 m e Sagittarius A* na origem, massa `float32(8.54e36)`).
3. `type Camera` (raio, azimute, elevação) com `Position()` em coordenadas esféricas, `MouseMove`, `SetDragging`, `Scroll`.
   A elevação é presa em `[0.01, π-0.01]` para a câmera não virar no polo; o zoom fica entre 1e10 e 1e12.
4. `StepGravity(objs, gravity)`: gravidade newtoniana par a par, sem `dt` (igual ao original).
5. `BuildGrid(objs)`: malha de 26×26 vértices; a altura `y` soma, para cada objeto, `2·√(rs·(dist-rs))`: a forma do "funil" de
   Schwarzschild. Os índices formam linhas (`i, i+1, i, i+n+1`).

Teste (`sim/sim_test.go`):

```go
func TestCameraZoomIsClamped(t *testing.T) {
	c := NewCamera()
	c.Scroll(1000)
	if c.Radius != 1e10 { t.Fatalf("min: %v", c.Radius) }
	c.Scroll(-1000)
	if c.Radius != 1e12 { t.Fatalf("max: %v", c.Radius) }
}
```

```bash
go test ./sim/        # roda sem GPU e sem janela
```

Lição de Go: tipos numéricos **não** convertem sozinhos. Você verá muito `float32(...)`/`float64(...)`; é o preço de manter a
mesma precisão do C++ (a massa é `float32`, as contas intermediárias são `float64`).

## Passo 3: shaders embutidos

Copie `geodesic.comp`, `grid.vert`, `grid.frag` do projeto original (upstream, ou `git show ff07b3b:C++/geodesic.comp`) para `shaders/`. Em `render.go`:

```go
//go:embed shaders/*
var shaderFS embed.FS
```

`embed` coloca os arquivos dentro do binário em tempo de compilação. Os shaders `quad` são strings Go no próprio código.

## Passo 4: a janela (`main.go`)

```go
func init() { runtime.LockOSThread() }   // OBRIGATÓRIO: GLFW/OpenGL só na thread principal

glfw.Init()
glfw.WindowHint(glfw.ContextVersionMajor, 4)
glfw.WindowHint(glfw.ContextVersionMinor, 3)       // compute shader exige 4.3
glfw.WindowHint(glfw.OpenGLProfile, glfw.OpenGLCoreProfile)
win, _ := glfw.CreateWindow(800, 600, "Black Hole", nil, nil)
win.MakeContextCurrent()
glfw.SwapInterval(1)                               // vsync
gl.InitWithProcAddrFunc(glfw.GetProcAddress)       // Wayland/EGL: não use gl.Init() puro
```

Callbacks: cursor → `cam.MouseMove`; scroll → `cam.Scroll`; botão esquerdo/meio → `cam.SetDragging(press, x, y)`;
botão direito → liga a gravidade enquanto pressionado; `G` alterna; `Esc` fecha. Laço:

```go
for !win.ShouldClose() {
	sim.StepGravity(objs, gravity)
	w, h := win.GetFramebufferSize()
	r.frame(cam, objs, w, h)
	win.SwapBuffers()
	glfw.PollEvents()
}
```

Neste ponto uma janela preta deve abrir. Rode: `go run -tags wayland .` (ou sem a tag em X11).

## Passo 5: compilar shaders (`render.go`)

Três funções pequenas: `shaderNamed` (CreateShader → ShaderSource → CompileShader → checa `COMPILE_STATUS` e dá `panic` com o log),
`mustProgram` (attach → link → checa `LINK_STATUS` → deleta os shaders) e `infoLog`.

Armadilha de cgo: strings C terminam em `\x00`. Por isso `gl.Str("viewProj\x00")` e `gl.Strs(src + "\x00")`.

## Passo 6: buffers e textura

- **UBOs** (`newUBO(binding, size)`): `BindBufferBase(UNIFORM_BUFFER, binding, buf)`. O `binding` tem de casar com o
  `layout(binding = N)` do shader: câmera = 1 (80 bytes), disco = 2 (16), objetos = 3.
- **Textura de saída** 200×150 `RGBA8` com filtro linear. O compute escreve nela por `BindImageTexture`.
- **Quad** de tela cheia: 6 vértices (posição + texcoord) num VBO estático, desenhado com `gl.TRIANGLES`.
- **Grid**: VBO dinâmico (reenviado a cada frame, porque deforma) e EBO.

## Passo 7: layout std140 (onde dá errado em silêncio)

O struct Go que vira UBO precisa ter **o mesmo layout em bytes** do bloco `std140` do shader. Regras que importam aqui:

- `vec3` ocupa 16 bytes de alinhamento: por isso `Pos mgl32.Vec3; _ float32` na câmera.
- Em arrays, cada elemento tem stride múltiplo de 16: `Mass [16][4]float32` e não `[16]float32`.
- `bool` do GLSL tem 4 bytes: o campo é `int32`.

Se errar, não há erro algum: a imagem só fica errada. Cheque `unsafe.Sizeof(data)` contra o tamanho que você passou ao `newUBO`.

## Passo 8: o frame

```go
func (r *renderer) frame(cam *sim.Camera, objs []sim.Object, fbW, fbH int) {
	// 1. limpa e define o viewport
	// 2. drawGrid: viewProj = proj * view; blend ligado; sem depth test
	// 3. dispatchCompute: sobe câmera/disco/objetos, BindImageTexture, DispatchCompute(13,10,1),
	//    gl.MemoryBarrier(gl.SHADER_IMAGE_ACCESS_BARRIER_BIT)
	// 4. desenha o quad com a textura por cima
}
```

A `MemoryBarrier` é a versão OpenGL das barreiras do Vulkan: garante que as escritas do compute estejam visíveis antes de a textura
ser amostrada. Compare com `C++/TUTORIAL_VULKAN.md` (passo 10) para ver o que o Vulkan te obriga a escrever e o OpenGL faz sozinho.

## Passo 9: rodar e conferir

```bash
go test -count=1 ./sim/
go vet -tags wayland ./...
go run -tags wayland .
go build -tags wayland -o ../Go_exe .      # binário na raiz (ignorado pelo git)
```

Controles: botão esquerdo/meio orbita, scroll dá zoom, botão direito ou `G` liga a gravidade, `Esc` sai.

## Exercícios

1. Faça o ray tracer renderizar na resolução da janela (hoje é fixo em 200×150 no shader e no Go).
2. Troque `StepGravity` para usar `dt` e integrar com Verlet. Os testes vão quebrar; ajuste-os.
3. Medir o tempo do dispatch com `gl.GenQueries` / `TIME_ELAPSED`.
4. Mover o `render.go` para um pacote `render/` e ver o que `unexported` te obriga a mudar.
5. Escrever um teste que compara os bytes de `uploadObjects` com o layout esperado do std140.
