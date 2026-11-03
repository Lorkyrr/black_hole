# Black hole em Rust (wgpu)

Mesmo desenho dos outros ports: compute shader de geodésicas (WGSL) -> textura 200x150 -> quad, com a grade de espaço-tempo por baixo.
wgpu usa Vulkan por baixo no Linux; a janela vem do winit.

Documentação: [`PORT_RUST.md`](PORT_RUST.md) (o que foi feito e por quê) e [`TUTORIAL_RUST.md`](TUTORIAL_RUST.md) (passo a passo).
Código gerado com ajuda de IA (Claude Code), como base de estudo para aprender Rust e wgpu.

## Dependências

Rust recente (edition 2024) via [rustup](https://rustup.rs) e uma GPU com driver Vulkan (`vulkaninfo --summary`).

## Rodar

    cargo run --release          # janela
    cargo test                   # física, câmera e layout dos uniforms (sem GPU)
    cargo build --release && cp target/release/black_hole_rust ../Rust_exe   # executável na raiz (ignorado pelo git)

Controles: botão esquerdo/meio = orbitar, scroll = zoom, botão direito ou G = gravidade, Esc = sair.

`target/` ocupa ~2 GB; `cargo clean` libera o espaço.
