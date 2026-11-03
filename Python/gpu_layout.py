"""Bytes que vão para a GPU (UBOs std140 e push constant), sem importar Vulkan: dá para testar sem GPU."""
import math

import numpy as np

import sim

WIDTH, HEIGHT = 800, 600
COMPUTE_WIDTH, COMPUTE_HEIGHT = 200, 150


def _norm(v):
    return v / np.linalg.norm(v)


def pack_camera(cam: sim.Camera, aspect: float) -> bytes:
    """struct Camera do geodesic_vk.comp: 4 × (vec3 + pad), tanHalfFov, aspect, moving, pad = 80 bytes."""
    pos = np.array(cam.position(), dtype=np.float64)
    fwd = _norm(-pos)  # alvo = origem
    right = _norm(np.cross(fwd, [0, 1, 0]))  # y é "para cima": o disco fica no plano x-z
    up = np.cross(right, fwd)
    f32 = lambda v: np.append(v, 0.0).astype(np.float32).tobytes()
    tail = np.array([math.tan(math.radians(30.0)), aspect], dtype=np.float32).tobytes()
    return f32(pos) + f32(right) + f32(up) + f32(fwd) + tail + np.array([1 if cam.dragging else 0, 0], dtype=np.int32).tobytes()


def pack_disk() -> bytes:
    """r1, r2, num, thickness."""
    return np.array([sim.SAGA_RS * 2.2, sim.SAGA_RS * 5.2, 2.0, 1e9], dtype=np.float32).tobytes()


def pack_objects(objects: list[sim.Object]) -> bytes:
    """int numObjects (+12 de padding), vec4 posRadius[16], vec4 color[16]."""
    pr, col = np.zeros((sim.MAX_OBJECTS, 4), np.float32), np.zeros((sim.MAX_OBJECTS, 4), np.float32)
    n = min(len(objects), sim.MAX_OBJECTS)
    for i in range(n):
        pr[i], col[i] = objects[i].pos_radius, objects[i].color
    return np.array([n, 0, 0, 0], dtype=np.int32).tobytes() + pr.tobytes() + col.tobytes()


def view_proj(cam: sim.Camera) -> bytes:
    """proj · view em coluna-maior (mat4 do GLSL). Perspectiva RH com profundidade 0..1 e Y invertido (clip space do Vulkan)."""
    eye = np.array(cam.position(), dtype=np.float64)
    f = _norm(-eye)
    s = _norm(np.cross(f, [0, 1, 0]))
    u = np.cross(s, f)
    view = np.eye(4)
    view[0, :3], view[1, :3], view[2, :3] = s, u, -f
    view[:3, 3] = [-s @ eye, -u @ eye, f @ eye]

    zn, zf = 1e9, 1e14
    t = 1.0 / math.tan(math.radians(60.0) / 2)
    proj = np.zeros((4, 4))
    proj[0, 0], proj[1, 1] = t / (COMPUTE_WIDTH / COMPUTE_HEIGHT), -t  # -t: Y do clip space aponta para baixo
    proj[2, 2], proj[2, 3], proj[3, 2] = zf / (zn - zf), -(zf * zn) / (zf - zn), -1.0
    return (proj @ view).T.astype(np.float32).tobytes()
