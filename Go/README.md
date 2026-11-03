# Black hole em Go (OpenGL 4.3, Wayland)

Mesmo desenho do `C++/black_hole.cpp`: compute shader de geodésicas -> textura 200x150 -> quad, com a grade de espaço-tempo por baixo.
Os shaders em `shaders/` são cópias de `C++/geodesic.comp`, `grid.vert` e `grid.frag`, embutidos no binário com `go:embed`.

## Dependências (Debian/Ubuntu)

GLFW e `go-gl` usam cgo:

    sudo apt install build-essential libgl1-mesa-dev libegl-dev libwayland-dev libxkbcommon-dev

## Rodar

    go run -tags wayland .                      # janela (Wayland nativo, sem XWayland)
    go build -tags wayland -o ../Go_exe .       # gera o executável na raiz
    go test ./sim/    # física e câmera (Go puro, sem cgo)

Controles: botão esquerdo/meio = orbitar, scroll = zoom, botão direito ou G = gravidade, Esc = sair.

Sem `-tags wayland` o GLFW compila para X11 (precisa de `xorg-dev`).
