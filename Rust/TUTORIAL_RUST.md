# Tutorial: refazendo o port Rust do zero

Siga na ordem. O **porquê** das decisões está em [`PORT_RUST.md`](PORT_RUST.md).

> O código foi gerado com ajuda de IA. Para aprender Rust de verdade, **digite você mesmo** cada passo. Deixe o compilador
> reclamar: as mensagens do `rustc` são o professor aqui, como a validation layer é no Vulkan.

## Passo 0: ambiente

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh     # instala rustup/cargo
cargo --version                                                      # edition 2024 exige Rust recente
vulkaninfo --summary                                                 # o wgpu no Linux usa Vulkan por baixo
```

## Passo 1: o projeto

```bash
cargo new Rust --name black_hole_rust && cd Rust
cargo add bytemuck glam pollster wgpu winit
```

Versões usadas: `bytemuck 1.25`, `glam 0.34`, `pollster 1.0`, `wgpu 30`, `winit 0.30`. O `Cargo.lock` fixa as versões exatas.

Papel de cada crate: **winit** cria a janela e entrega eventos; **wgpu** fala com a GPU; **glam** é matemática de vetores/matrizes;
**bytemuck** transforma structs em bytes; **pollster** roda as funções `async` do wgpu de forma bloqueante.

## Passo 2: `sim.rs` (física, sem GPU)

Escreva em `src/sim.rs`, **com teste a cada item** (`cargo test`):

1. Constantes `C`, `G`, `GRID_SIZE = 25`, `MAX_OBJECTS = 16`; `schwarzschild_radius(m) = 2·G·m/c²`.
2. `struct Object { pos_radius: Vec4, color: Vec4, mass: f32, velocity: Vec3 }` e `default_objects()`.
3. `struct Camera` com `position()` esférica, `mouse_move`, `set_dragging`, `scroll` (zoom entre 1e10 e 1e12; elevação presa em `[0.01, π-0.01]`).
4. `step_gravity(&mut [Object], bool)`: gravidade newtoniana par a par.
5. `build_grid(&[Object]) -> (Vec<[f32; 3]>, Vec<u32>)`: malha 26×26 deformada por `2·√(rs·(dist-rs))`.

Lição de Rust: o `objects: &mut [Object]` impede dois `&mut` ao mesmo tempo. No laço duplo `i`/`j`, leia `objects[j]` **antes** de pegar
`let o = &mut objects[i]`; inverter a ordem não compila (o borrow checker te ensina).

```rust
#[test]
fn camera_zoom_is_clamped() {
    let mut c = Camera::new();
    c.scroll(1000.0);  assert_eq!(c.radius, 1e10);
    c.scroll(-1000.0); assert_eq!(c.radius, 1e12);
}
```

## Passo 3: a janela com winit 0.30 (`main.rs`)

O winit 0.30 inverte o controle: você implementa o trait `ApplicationHandler` e o `EventLoop` te chama.

```rust
impl ApplicationHandler for App {
    fn resumed(&mut self, el: &ActiveEventLoop) { /* cria Window (Arc) e o Renderer */ }
    fn window_event(&mut self, el: &ActiveEventLoop, _: WindowId, event: WindowEvent) { /* match */ }
    fn about_to_wait(&mut self, _: &ActiveEventLoop) { /* window.request_redraw() */ }
}
```

- A janela é um `Arc<Window>` porque a `Surface` do wgpu precisa de um dono que viva tanto quanto ela (`Surface<'static>`).
- Eventos: `CursorMoved`, `MouseInput`, `MouseWheel` (linhas ou pixels), `KeyboardInput` (`G` e `Esc`), `Resized`, `RedrawRequested`.
- `RedrawRequested` faz `step_gravity` e depois `renderer.render(...)`.

## Passo 4: iniciar o wgpu (`gpu.rs`, `Renderer::new`)

Sequência (cada passo devolve algo que o próximo usa):

1. `Instance` → `create_surface(window)`.
2. `request_adapter` com `compatible_surface` e `HighPerformance` (é `async`: `pollster::block_on(...)`).
3. `adapter.request_device` → `(Device, Queue)`.
4. `SurfaceConfiguration`: formato **não-sRGB**, `PresentMode::AutoVsync`, tamanho da janela (nunca 0).
5. `surface.configure(&device, &config)`.

## Passo 5: uniforms sem surpresa de padding

```rust
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct CameraU { pos: [f32;4], right: [f32;4], up: [f32;4], forward: [f32;4], params: [f32;4] }
```

- `#[repr(C)]` garante a ordem dos campos como no C.
- Só `[f32; 4]`: cada campo já tem 16 bytes, o mesmo que o `vec4<f32>` do WGSL, então não existe padding escondido.
- O array `mass` vira `[[f32;4]; 4]` pelo mesmo motivo.
- Escreva o teste que trava esses tamanhos (80, 16 e 592 bytes). Se algum dia o shader mudar, o teste avisa antes da imagem quebrar:

```rust
assert_eq!(size_of::<CameraU>(), 80);
assert_eq!(size_of::<ObjectsU>(), 16 + 256 + 256 + 64);
```

## Passo 6: o shader em WGSL

Traduzir `geodesic.comp` para `geodesic.wgsl` é um bom exercício de sintaxe:

| GLSL | WGSL |
|---|---|
| `uniform Camera { ... } cam;` + `layout(binding=1)` | `@group(0) @binding(1) var<uniform> cam: CameraU;` |
| `image2D` | `texture_storage_2d<rgba8unorm, write>` |
| `double` | não existe: use `f32` |
| variável global mutável | `var<private>` |
| `vec3(1.0)` / `float` | `vec3<f32>(1.0)` / `f32` |
| `main()` com `layout(local_size_x=16,...)` | `@compute @workgroup_size(16, 16) fn main(@builtin(global_invocation_id) id: vec3<u32>)` |

O wgpu valida o WGSL na criação do módulo; os erros de tradução aparecem cedo e com a linha.

## Passo 7: pipelines e bind groups

- **Compute**: `create_compute_pipeline` com `layout: None`. O wgpu deduz o layout a partir do shader; você pega com
  `pipeline.get_bind_group_layout(0)` e cria o `BindGroup` com os 4 recursos (textura de saída + 3 uniforms).
- **Textura de saída** `Rgba8Unorm`, usos `STORAGE_BINDING | TEXTURE_BINDING`.
- **Grid**: `RenderPipeline` com topologia `LineList`, vertex buffer de `Float32x3` (stride 12) e blend `ALPHA_BLENDING`.
- **Quad**: `RenderPipeline` sem vertex buffer (o shader usa `@builtin(vertex_index)`), mesmo blend, amostrando a textura com um `Sampler` linear.

## Passo 8: o frame (`render`)

```rust
let frame = match self.surface.get_current_texture() {
    S::Success(f) | S::Suboptimal(f) => f,
    S::Outdated | S::Lost => { self.surface.configure(&self.device, &self.config); return; }
    _ => return,                         // tenta de novo no próximo frame
};
// 1. queue.write_buffer(camera / objetos / vértices do grid / view_proj)
// 2. encoder: compute pass  -> dispatch_workgroups(13, 10, 1)
// 3. encoder: render pass (clear preto) -> grade, depois quad
// 4. queue.submit([encoder.finish()]); queue.present(frame);
```

Repare no que **não** existe: barreiras, layouts de imagem, semáforos, fences. O wgpu cuida. Compare com
`C++/TUTORIAL_VULKAN.md` (passo 10) e veja quanto código essa automação economiza.

## Passo 9: rodar

```bash
cargo test                       # 7 testes, sem GPU
cargo run --release              # janela; --release importa: debug é muito mais lento
cargo build --release && cp target/release/black_hole_rust ../Rust_exe   # binário na raiz (ignorado pelo git)
```

Controles: botão esquerdo/meio orbita, scroll dá zoom, botão direito ou `G` liga a gravidade, `Esc` sai.

## Se o `cargo build` quebrar depois de atualizar

wgpu/glam/winit mudam a API com frequência. Erros típicos já vistos neste projeto: `missing field color_space` em
`SurfaceConfiguration` e `perspective_rh` não encontrado (agora é `glam::camera::rh::proj::directx::perspective`). Volte ao `Cargo.lock`
commitado e atualize **uma** dependência por vez.

## Exercícios

1. Trocar `AutoVsync` por `Immediate` e medir os FPS.
2. Fazer o ray tracer acompanhar o tamanho da janela (hoje fixo em 200×150).
3. Usar `queue.write_buffer` só quando câmera/objetos mudarem (hoje reenvia todo frame).
4. Capturar um frame para PNG e comparar com a versão C++ (diferença por pixel).
5. Reescrever `step_gravity` com iteradores e comparar legibilidade.
