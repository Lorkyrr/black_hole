# Black hole em C++ (Vulkan 1.3)

Renderer Vulkan da simulação 3D: compute shader de geodésicas -> imagem 200x150 -> quad de tela cheia, com a grade de espaço-tempo por baixo.
Usa *dynamic rendering* e *synchronization2* (Vulkan 1.3), sem render pass nem framebuffers.

Documentação: [`PORT_VULKAN.md`](PORT_VULKAN.md) (o que foi feito e por quê) e [`TUTORIAL_VULKAN.md`](TUTORIAL_VULKAN.md) (passo a passo).
Código gerado com ajuda de IA (Claude Code), como base de estudo para aprender Vulkan.

## Dependências (Debian/Ubuntu)

    sudo apt install build-essential cmake libglfw3-dev libglm-dev libvulkan-dev glslc \
                     vulkan-validationlayers mesa-vulkan-drivers

`vulkaninfo --summary` deve listar uma GPU com Vulkan 1.3.

## Rodar

    cmake -B build -S .
    cmake --build build
    cd build && ./black_hole_cpp      # precisa rodar daqui: carrega os .spv do diretório atual

Sem `-DCMAKE_BUILD_TYPE=Release`, a validation layer fica ligada se estiver instalada. Docker: veja o cabeçalho do [`Dockerfile`](Dockerfile).

Controles: botão esquerdo/meio = orbitar, scroll = zoom, botão direito ou G = gravidade.
