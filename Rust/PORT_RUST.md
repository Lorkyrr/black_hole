# Port para Rust: o que foi feito e por quê

Documento de estudo. Complementa o [`TUTORIAL_RUST.md`](TUTORIAL_RUST.md) (como refazer).

> Este código foi escrito com ajuda de IA (Claude Code) sob minha direção, como **base de estudo para aprender Rust e wgpu na
> prática**. Não foi escrito linha a linha por mim; o objetivo é ler, quebrar e modificar.

## 1. Resumo

- `Rust/` é o terceiro port da simulação 3D (os outros: C++/Vulkan em `C++/` e Go/OpenGL em `Go/`).
- Usa **wgpu**: uma API gráfica moderna e portátil escrita em Rust, que por baixo usa Vulkan (Linux), Metal (macOS) ou D3D12 (Windows).
  É a rota "idiomática em Rust" para fazer o que o `C++/` faz com Vulkan puro.
- O shader é um **porte para WGSL** (`shaders/geodesic.wgsl`) do `geodesic.comp` original (`git show ff07b3b:C++/geodesic.comp` ou `Go/shaders/geodesic.comp`). WGSL é a linguagem de shader do WebGPU.
- Mesmo desenho dos outros: compute (geodésicas) → textura 200×150 → quad, com a grade por baixo.

## 2. Mapa de arquivos

```
Rust/
├── Cargo.toml / Cargo.lock   # edition 2024; wgpu 30, winit 0.30, glam 0.34, bytemuck, pollster
├── src/
│   ├── main.rs               # winit: janela, eventos, laço (ApplicationHandler)
│   ├── gpu.rs                # Renderer: device, pipelines, uniforms, frame; 1 teste de layout
│   └── sim.rs                # física e câmera (Rust puro); 6 testes
└── shaders/
    ├── geodesic.wgsl         # ray tracer (porte do GLSL)
    ├── grid.wgsl             # linhas da grade
    └── quad.wgsl             # quad de tela cheia (6 vértices pelo vertex_index)
```

Repete a divisão dos outros ports: `sim.rs` não conhece a GPU; `gpu.rs` não conhece física além de `Object`/`Camera`.

## 3. Decisões e por quê

| Decisão | Por quê |
|---|---|
| wgpu em vez de Vulkan puro (`ash`) | em Rust, o Vulkan "cru" é muito `unsafe`. wgpu entrega segurança de memória e sincronização automática; o preço é menos controle |
| WGSL em vez de GLSL | wgpu aceita WGSL nativamente (GLSL exigiria passos extras). Foi uma tradução manual de ~160 linhas |
| `f64` → `f32` no shader | WGSL não tem `f64`; no GLSL só eram `double` `ESCAPE_R` e o `r` do disco |
| Uniforms em `[f32; 4]` por campo (`CameraU`, `ObjectsU`) | evita surpresas de padding: tudo é múltiplo de 16 bytes, igual ao WGSL |
| `mass: [[f32; 4]; MAX_OBJECTS / 4]` | `uniform` em WGSL exige stride 16 para arrays; 16 floats viram 4 `vec4` |
| `bytemuck::Pod` + `Zeroable` | converte structs em bytes com segurança (`bytes_of`, `cast_slice`), sem `unsafe` espalhado |
| Teste `uniform_sizes_match_wgsl_layout` | se alguém mudar um campo, o tamanho deixa de bater com o shader e o teste falha antes da imagem sair errada |
| Formato do swapchain **não-sRGB** | o original gravava valores crus no framebuffer; um formato sRGB clarearia a imagem |
| `PowerPreference::HighPerformance` | notebook híbrido: escolher a GPU dedicada, não a integrada |
| Um único `CommandEncoder`: compute pass + render pass | no wgpu a barreira entre os dois é inserida **automaticamente** (no Vulkan são escritas à mão) |
| `ApplicationHandler` do winit 0.30 | é a API atual (a antiga com closure foi descontinuada); o `Renderer` só é criado em `resumed()` |
| Redesenho por `about_to_wait` → `request_redraw` + `ControlFlow::Poll` | laço contínuo, como o `while(!shouldClose)` dos outros |
| `SAGA_MASS: f32` | o C++ guardava a massa em `float`; manter para a imagem bater |

## 4. Mapeamento C++/Vulkan → Rust/wgpu

| Vulkan (`black_hole_vk.cpp`) | wgpu (`gpu.rs`) |
|---|---|
| instance + surface + physical device + device | `Instance` → `create_surface` → `request_adapter` → `request_device` |
| swapchain + image views + semáforos | `surface.configure(&device, &config)` + `get_current_texture()` |
| `vkAllocateMemory` + `vkBindBufferMemory` + `memcpy` | `create_buffer` + `queue.write_buffer` |
| descriptor set layout/pool/set | `get_bind_group_layout(0)` (inferido do shader) + `create_bind_group` |
| `VkPipelineLayout` com push constant | `layout: None` (automático); o `view_proj` vai num uniform |
| `vkCmdPipelineBarrier2` × 6 | nenhuma: o wgpu rastreia o uso de cada recurso |
| `vkQueueSubmit` + `vkQueuePresentKHR` + fence | `queue.submit` + `queue.present(frame)` |
| `recreateSwapchain()` | `resize()` e, em `Outdated/Lost`, `surface.configure` de novo |

## 5. Como foi verificado

| Verificação | Resultado |
|---|---|
| `cargo test --offline` | OK, 7 testes (6 de `sim.rs` + o de layout dos uniforms) |
| Binário compilado e executado por 6 s | abriu e continuou rodando sem travar (não houve captura da imagem) |
| Comparação pixel a pixel com a versão Vulkan/OpenGL | **não feita** |

## 6. O que NÃO foi verificado / riscos

- Não comparei a imagem com a versão C++. A tradução do shader para WGSL (principalmente o `f64 → f32`) pode mudar pixels perto do horizonte.
- As versões das dependências são muito novas (`wgpu 30`, `glam 0.34`, edition 2024); a API muda rápido. Se um `cargo update` quebrar a
  compilação, o `Cargo.lock` commitado é o ponto de referência.
- `render()` ignora silenciosamente timeout/oclusão da surface e tenta no frame seguinte.
- `Rust/target/` ocupa ~2,3 GB. Está no `.gitignore`; apague com `cargo clean` quando não precisar.
- Não testado em macOS/Windows nem em Intel/AMD (só a GPU deste computador).
