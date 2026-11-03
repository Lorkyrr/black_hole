# Black hole em Python (Vulkan 1.3, Wayland)

Mesmo desenho do `C++/`: compute shader de geodésicas -> imagem 200x150 -> quad de tela cheia, com a grade de espaço-tempo por baixo.
Vulkan "cru" via bindings [`vulkan`](https://pypi.org/project/vulkan/) (cffi); a janela vem do [`glfw`](https://pypi.org/project/glfw/) (Wayland nativo).
Os shaders em `shaders/` são cópias dos de `C++/` e são compilados para SPIR-V com `glslc` na primeira execução.

Documentação: [`PORT_PYTHON.md`](PORT_PYTHON.md) (o que foi feito e por quê) e [`TUTORIAL_PYTHON.md`](TUTORIAL_PYTHON.md) (passo a passo).
Código gerado com ajuda de IA (Claude Code), como base de estudo para aprender Vulkan com Python.

## Dependências (Ubuntu 26.04+, Wayland)

    sudo apt install glslc vulkan-validationlayers mesa-vulkan-drivers   # + driver NVIDIA proprietário, se for o caso
    curl -LsSf https://astral.sh/uv/install.sh | sh                      # uv (gerencia Python e pacotes)

`vulkaninfo --summary` (pacote `vulkan-tools`) deve listar uma GPU com Vulkan 1.3. Python e bibliotecas (`vulkan`, `glfw`, `numpy`) vêm do `uv`.

## Rodar

    cd Python
    uv run main.py                # janela (Wayland nativo)
    uv run main.py --frames 60    # sai após 60 frames (teste de fumaça)
    uv run pytest                 # física, câmera e layout dos UBOs (sem GPU)

Sem `glslc` no PATH, aponte para ele com `GLSLC=/caminho/glslc`. Com `vulkan-validationlayers` instalado a validation layer liga sozinha
(`BH_VALIDATION=0` desliga).

Controles: botão esquerdo/meio = orbitar, scroll = zoom, botão direito ou G = gravidade, Esc = sair.
