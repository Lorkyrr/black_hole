"""Física e câmera: porte de sim.h / sim.rs (sem nenhuma chamada de API gráfica)."""
import math
from dataclasses import dataclass, field

C = 299_792_458.0
G = 6.674_30e-11
GRID_SIZE = 25
MAX_OBJECTS = 16


def schwarzschild_radius(mass: float) -> float:
    """Raio de Schwarzschild: 2GM/c²."""
    return 2.0 * G * mass / (C * C)


SAGA_MASS = 8.54e36  # Sagittarius A*
SAGA_RS = schwarzschild_radius(SAGA_MASS)


@dataclass
class Object:
    pos_radius: list  # [x, y, z, raio]
    color: tuple      # (r, g, b, a)
    mass: float
    velocity: list = field(default_factory=lambda: [0.0, 0.0, 0.0])


def default_objects() -> list[Object]:
    return [
        Object([4e11, 0.0, 0.0, 4e10], (1.0, 1.0, 0.0, 1.0), 1.98892e30),
        Object([0.0, 0.0, 4e11, 4e10], (1.0, 0.0, 0.0, 1.0), 1.98892e30),
        Object([0.0, 0.0, 0.0, SAGA_RS], (0.0, 0.0, 0.0, 1.0), SAGA_MASS),
    ]


@dataclass
class Camera:
    """Orbita em torno do buraco negro, que fica na origem (a câmera sempre olha para lá)."""
    radius: float = 6.34194e10
    azimuth: float = 0.0
    elevation: float = math.pi / 2
    dragging: bool = False
    last: tuple = (0.0, 0.0)

    MIN_RADIUS = 1e10
    MAX_RADIUS = 1e12
    ORBIT_SPEED = 0.01
    ZOOM_SPEED = 25e9
    _EL_MIN, _EL_MAX = 0.01, math.pi - 0.01

    def position(self) -> tuple:
        e = min(max(self.elevation, self._EL_MIN), self._EL_MAX)
        return (self.radius * math.sin(e) * math.cos(self.azimuth),
                self.radius * math.cos(e),
                self.radius * math.sin(e) * math.sin(self.azimuth))

    def mouse_move(self, x: float, y: float) -> None:
        if self.dragging:
            self.azimuth += (x - self.last[0]) * self.ORBIT_SPEED
            self.elevation = min(max(self.elevation - (y - self.last[1]) * self.ORBIT_SPEED,
                                     self._EL_MIN), self._EL_MAX)
        self.last = (x, y)

    def set_dragging(self, on: bool, cursor: tuple) -> None:
        """`cursor` é a posição atual do mouse (o C++ lia com glfwGetCursorPos)."""
        self.dragging = on
        if on:
            self.last = cursor

    def scroll(self, dy: float) -> None:
        self.radius = min(max(self.radius - dy * self.ZOOM_SPEED, self.MIN_RADIUS), self.MAX_RADIUS)


def step_gravity(objects: list[Object], gravity: bool) -> None:
    """Um passo de gravidade newtoniana (sem dt, como no original). Só mexe nos objetos se `gravity`."""
    if not gravity:
        return
    for i, o in enumerate(objects):
        for j, p in enumerate(objects):
            if i == j:
                continue
            d = [p.pos_radius[k] - o.pos_radius[k] for k in range(3)]
            dist = math.sqrt(d[0] ** 2 + d[1] ** 2 + d[2] ** 2)
            if dist <= 0.0:
                continue
            acc = G * p.mass / (dist * dist)  # G·m_i·m_j/d² dividido por m_i
            for k in range(3):
                o.velocity[k] += d[k] / dist * acc
                o.pos_radius[k] += o.velocity[k]


def build_grid(objects: list[Object]) -> tuple[list, list]:
    """Vértices (x, y, z) da grade deformada (geometria de Schwarzschild) e índices das linhas."""
    spacing = 1e10
    n = GRID_SIZE
    vertices = []
    for z in range(n + 1):
        for x in range(n + 1):
            wx, wz = (x - n // 2) * spacing, (z - n // 2) * spacing
            y = 0.0
            for o in objects:
                rs = schwarzschild_radius(o.mass)
                dist = math.hypot(wx - o.pos_radius[0], wz - o.pos_radius[2])
                y += (2.0 * math.sqrt(rs * (dist - rs)) if dist > rs else 2.0 * rs) - 3e10
            vertices.append((wx, y, wz))
    indices = []
    for z in range(n):
        for x in range(n):
            i = z * (n + 1) + x
            indices += [i, i + 1, i, i + n + 1]
    return vertices, indices
