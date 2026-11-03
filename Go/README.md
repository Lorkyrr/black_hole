# Black hole em Go (OpenGL 4.3, Wayland)

Mesmo desenho do `black_hole.cpp` original (OpenGL, removido deste fork mas presente no upstream): compute shader de geodésicas -> textura 200x150 -> quad, com a grade de espaço-tempo por baixo.
Os shaders em `shaders/` são cópias dos originais (`geodesic.comp`, `grid.vert`, `grid.frag`), embutidos no binário com `go:embed`.

Documentação: [`PORT_GO.md`](PORT_GO.md) (o que foi feito e por quê) e [`TUTORIAL_GO.md`](TUTORIAL_GO.md) (passo a passo). Código gerado com ajuda de IA, como base de estudo.

## Dependências (Debian/Ubuntu)

GLFW e `go-gl` usam cgo:

    sudo apt install build-essential libgl1-mesa-dev libegl-dev libwayland-dev libxkbcommon-dev

## Rodar

    go run -tags wayland .                      # janela (Wayland nativo, sem XWayland)
    go build -tags wayland -o ../Go_exe .       # gera o executável na raiz
    go test ./sim/    # física e câmera (Go puro, sem cgo)

Controles: botão esquerdo/meio = orbitar, scroll = zoom, botão direito ou G = gravidade, Esc = sair.

Sem `-tags wayland` o GLFW compila para X11 (precisa de `xorg-dev`).
