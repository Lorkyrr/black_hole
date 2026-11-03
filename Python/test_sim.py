import math

import numpy as np

import gpu_layout
import sim


def test_sagittarius_radius_matches_shader_constant():
    # geodesic_vk.comp usa SagA_rs = 1.269e10
    assert abs(sim.SAGA_RS - 1.269e10) / 1.269e10 < 1e-3


def test_camera_starts_on_x_axis():
    x, y, z = sim.Camera().position()
    assert abs(x - 6.34194e10) < 1e6 and abs(y) < 1e7 and abs(z) < 1e6


def test_camera_zoom_is_clamped():
    c = sim.Camera()
    c.scroll(1000)
    assert c.radius == 1e10
    c.scroll(-1000)
    assert c.radius == 1e12


def test_orbit_only_while_dragging():
    c = sim.Camera()
    c.mouse_move(10, 10)
    assert c.azimuth == 0
    c.set_dragging(True, (10, 10))
    c.mouse_move(20, 10)
    assert math.isclose(c.azimuth, 0.1, abs_tol=1e-9)


def test_gravity_off_does_nothing_and_on_attracts():
    objs = sim.default_objects()
    before = objs[0].pos_radius[0]
    sim.step_gravity(objs, False)
    assert objs[0].pos_radius[0] == before
    for _ in range(20):
        sim.step_gravity(objs, True)
    assert objs[0].pos_radius[0] < before  # puxado para o buraco negro na origem


def test_grid_has_expected_sizes():
    v, i = sim.build_grid(sim.default_objects())
    assert len(v) == (sim.GRID_SIZE + 1) ** 2
    assert len(i) == sim.GRID_SIZE ** 2 * 4
    assert max(i) < len(v)


def test_ubo_sizes_match_std140_layout_of_the_shaders():
    # Camera: 4 vec3 (+pad) + tanHalfFov, aspect, moving, pad = 80 bytes. Objects: int + pad + 2 × vec4[16] = 16 + 512.
    cam = gpu_layout.pack_camera(sim.Camera(), 4 / 3)
    assert len(cam) == 80
    assert len(gpu_layout.pack_objects(sim.default_objects())) == 16 + 16 * 16 * 2
    assert len(gpu_layout.pack_disk()) == 16
    # forward aponta para a origem: a câmera começa em +x, então forward ≈ (-1, 0, 0)
    f = np.frombuffer(cam, dtype=np.float32)[12:15]
    assert np.allclose(f, [-1, 0, 0], atol=1e-3)


def test_view_proj_projects_origin_to_screen_center():
    vp = gpu_layout.view_proj(sim.Camera())
    clip = np.frombuffer(vp, dtype=np.float32).reshape(4, 4).T @ np.array([0, 0, 0, 1], dtype=np.float32)
    ndc = clip[:3] / clip[3]
    assert np.allclose(ndc[:2], 0, atol=1e-3) and 0 < ndc[2] < 1  # profundidade Vulkan: 0..1
