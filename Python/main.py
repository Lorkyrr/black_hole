"""Black hole em Python: Vulkan 1.3 + GLFW (Wayland nativo). Porte de black_hole_vk.cpp.

    uv run main.py            # janela
    uv run main.py --frames N # sai depois de N frames (teste de fumaça)
"""
import os
import shutil
import sys

try:
    import glfw
except ModuleNotFoundError:  # rodou com o python do sistema: as dependências vivem no venv do uv
    if uv := shutil.which("uv"):
        os.execv(uv, [uv, "run", os.path.abspath(__file__), *sys.argv[1:]])
    sys.exit("glfw não encontrado: instale o uv (curl -LsSf https://astral.sh/uv/install.sh | sh) e rode `uv run main.py`")

import gpu
import gpu_layout as L
import sim


def main() -> None:
    max_frames = int(sys.argv[sys.argv.index("--frames") + 1]) if "--frames" in sys.argv else None
    gpu.compile_shaders()

    if not glfw.init():
        gpu.die("glfw.init falhou")
    if glfw.platform_supported(glfw.PLATFORM_WAYLAND):  # Wayland nativo, sem XWayland
        glfw.init_hint(glfw.PLATFORM, glfw.PLATFORM_WAYLAND)
    if not glfw.vulkan_supported():
        gpu.die("loader/driver Vulkan não encontrado (vulkaninfo --summary)")
    glfw.window_hint(glfw.CLIENT_API, glfw.NO_API)
    window = glfw.create_window(L.WIDTH, L.HEIGHT, "Black Hole (Python + Vulkan)", None, None)
    if not window:
        gpu.die("não foi possível criar a janela")

    camera, objects = sim.Camera(), sim.default_objects()
    gravity = False

    def on_mouse_button(win, button, action, mods):
        nonlocal gravity
        if button in (glfw.MOUSE_BUTTON_LEFT, glfw.MOUSE_BUTTON_MIDDLE):
            camera.set_dragging(action == glfw.PRESS, glfw.get_cursor_pos(win))
        elif button == glfw.MOUSE_BUTTON_RIGHT:
            gravity = action == glfw.PRESS

    def on_key(win, key, scancode, action, mods):
        nonlocal gravity
        if action == glfw.PRESS and key == glfw.KEY_G:
            gravity = not gravity
            print(f"[INFO] Gravity turned {'ON' if gravity else 'OFF'}")
        elif action == glfw.PRESS and key == glfw.KEY_ESCAPE:
            glfw.set_window_should_close(win, True)

    renderer = gpu.Renderer(window)  # cria o seu callback de resize; os de entrada vão depois
    glfw.set_mouse_button_callback(window, on_mouse_button)
    glfw.set_cursor_pos_callback(window, lambda _w, x, y: camera.mouse_move(x, y))
    glfw.set_scroll_callback(window, lambda _w, _dx, dy: camera.scroll(dy))
    glfw.set_key_callback(window, on_key)

    frames = 0
    while not glfw.window_should_close(window) and (max_frames is None or frames < max_frames):
        glfw.poll_events()
        sim.step_gravity(objects, gravity)
        renderer.frame(camera, objects)
        frames += 1

    renderer.wait_idle()
    # shortcut: sem vkDestroy* no encerramento, o processo termina e o SO recupera tudo (igual ao C++).
    glfw.destroy_window(window)
    glfw.terminate()


if __name__ == "__main__":
    main()
