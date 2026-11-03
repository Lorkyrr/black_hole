//! Física e câmera: porte de sim.h (sem nenhuma chamada de API gráfica).
use glam::{Vec3, Vec4};
use std::f32::consts::PI;

pub const C: f64 = 299_792_458.0;
pub const G: f64 = 6.674_30e-11;
pub const GRID_SIZE: usize = 25;
pub const MAX_OBJECTS: usize = 16;

/// Raio de Schwarzschild: 2GM/c².
pub fn schwarzschild_radius(mass: f64) -> f64 {
    2.0 * G * mass / (C * C)
}

/// Sagittarius A*. O C++ guardava a massa num `float`, então o valor exato é o do f32.
pub const SAGA_MASS: f32 = 8.54e36;
pub fn saga_rs() -> f64 {
    schwarzschild_radius(SAGA_MASS as f64)
}

#[derive(Clone, Copy, Debug)]
pub struct Object {
    pub pos_radius: Vec4, // xyz = posição, w = raio
    pub color: Vec4,
    pub mass: f32,
    pub velocity: Vec3,
}

pub fn default_objects() -> Vec<Object> {
    let obj = |pos_radius, color, mass| Object { pos_radius, color, mass, velocity: Vec3::ZERO };
    vec![
        obj(Vec4::new(4e11, 0.0, 0.0, 4e10), Vec4::new(1.0, 1.0, 0.0, 1.0), 1.98892e30),
        obj(Vec4::new(0.0, 0.0, 4e11, 4e10), Vec4::new(1.0, 0.0, 0.0, 1.0), 1.98892e30),
        obj(Vec4::new(0.0, 0.0, 0.0, saga_rs() as f32), Vec4::new(0.0, 0.0, 0.0, 1.0), SAGA_MASS),
    ]
}

pub struct Camera {
    pub radius: f32,
    pub azimuth: f32,
    pub elevation: f32,
    pub dragging: bool,
    pub last: (f64, f64),
}

impl Camera {
    const MIN_RADIUS: f32 = 1e10;
    const MAX_RADIUS: f32 = 1e12;
    const ORBIT_SPEED: f32 = 0.01;
    const ZOOM_SPEED: f64 = 25e9;
    /// A câmera sempre olha para o buraco negro, na origem.
    pub const TARGET: Vec3 = Vec3::ZERO;

    pub fn new() -> Self {
        Self { radius: 6.34194e10, azimuth: 0.0, elevation: PI / 2.0, dragging: false, last: (0.0, 0.0) }
    }

    pub fn position(&self) -> Vec3 {
        let e = self.elevation.clamp(0.01, PI - 0.01);
        Vec3::new(
            self.radius * e.sin() * self.azimuth.cos(),
            self.radius * e.cos(),
            self.radius * e.sin() * self.azimuth.sin(),
        )
    }

    pub fn mouse_move(&mut self, x: f64, y: f64) {
        if self.dragging {
            self.azimuth += (x - self.last.0) as f32 * Self::ORBIT_SPEED;
            self.elevation = (self.elevation - (y - self.last.1) as f32 * Self::ORBIT_SPEED).clamp(0.01, PI - 0.01);
        }
        self.last = (x, y);
    }

    /// `cursor` é a posição atual do mouse (o C++ lia com glfwGetCursorPos).
    pub fn set_dragging(&mut self, on: bool, cursor: (f64, f64)) {
        self.dragging = on;
        if on {
            self.last = cursor;
        }
    }

    pub fn scroll(&mut self, dy: f64) {
        self.radius = (self.radius as f64 - dy * Self::ZOOM_SPEED).clamp(Self::MIN_RADIUS as f64, Self::MAX_RADIUS as f64) as f32;
    }
}

/// Um passo de gravidade newtoniana (sem dt, como no original). Só mexe nos objetos se `gravity`.
pub fn step_gravity(objects: &mut [Object], gravity: bool) {
    if !gravity {
        return;
    }
    for i in 0..objects.len() {
        for j in 0..objects.len() {
            if i == j {
                continue;
            }
            let d = (objects[j].pos_radius - objects[i].pos_radius).truncate();
            let dist = d.length();
            if dist <= 0.0 {
                continue;
            }
            let dir = d.as_dvec3() / dist as f64;
            let force = G * objects[i].mass as f64 * objects[j].mass as f64 / (dist as f64 * dist as f64);
            let acc = dir * (force / objects[i].mass as f64);
            let o = &mut objects[i];
            o.velocity += acc.as_vec3();
            o.pos_radius += o.velocity.extend(0.0);
        }
    }
}

/// Vértices da grade deformada (geometria de Schwarzschild) e índices das linhas.
pub fn build_grid(objects: &[Object]) -> (Vec<[f32; 3]>, Vec<u32>) {
    const SPACING: f32 = 1e10;
    let n = GRID_SIZE;
    let mut vertices = Vec::with_capacity((n + 1) * (n + 1));
    for z in 0..=n {
        for x in 0..=n {
            let wx = (x as i32 - (n / 2) as i32) as f32 * SPACING;
            let wz = (z as i32 - (n / 2) as i32) as f32 * SPACING;
            let mut y = 0.0f32;
            for o in objects {
                let rs = schwarzschild_radius(o.mass as f64);
                let dx = wx as f64 - o.pos_radius.x as f64;
                let dz = wz as f64 - o.pos_radius.z as f64;
                let dist = (dx * dx + dz * dz).sqrt();
                y += if dist > rs { (2.0 * (rs * (dist - rs)).sqrt()) as f32 } else { (2.0 * rs) as f32 } - 3e10;
            }
            vertices.push([wx, y, wz]);
        }
    }
    let mut indices = Vec::with_capacity(n * n * 4);
    for z in 0..n {
        for x in 0..n {
            let i = (z * (n + 1) + x) as u32;
            indices.extend([i, i + 1, i, i + n as u32 + 1]);
        }
    }
    (vertices, indices)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn sagittarius_radius_matches_shader_constant() {
        // geodesic.wgsl usa SAGA_RS = 1.269e10
        assert!((saga_rs() - 1.269e10).abs() / 1.269e10 < 1e-3);
    }

    #[test]
    fn camera_starts_on_x_axis() {
        let p = Camera::new().position();
        assert!((p.x - 6.34194e10).abs() < 1e6 && p.y.abs() < 1e7 && p.z.abs() < 1e6);
    }

    #[test]
    fn camera_zoom_is_clamped() {
        let mut c = Camera::new();
        c.scroll(1000.0);
        assert_eq!(c.radius, 1e10);
        c.scroll(-1000.0);
        assert_eq!(c.radius, 1e12);
    }

    #[test]
    fn orbit_only_while_dragging() {
        let mut c = Camera::new();
        c.mouse_move(10.0, 10.0);
        assert_eq!(c.azimuth, 0.0);
        c.set_dragging(true, (10.0, 10.0));
        c.mouse_move(20.0, 10.0);
        assert!((c.azimuth - 0.1).abs() < 1e-6);
    }

    #[test]
    fn gravity_off_does_nothing_and_on_attracts() {
        let mut objs = default_objects();
        let before = objs[0].pos_radius;
        step_gravity(&mut objs, false);
        assert_eq!(objs[0].pos_radius, before);
        // f32 perde passos pequenos perto de 4e11, então precisa acumular velocidade
        for _ in 0..20 {
            step_gravity(&mut objs, true);
        }
        assert!(objs[0].pos_radius.x < before.x); // puxado para o buraco negro na origem
    }

    #[test]
    fn grid_has_expected_sizes() {
        let (v, i) = build_grid(&default_objects());
        assert_eq!(v.len(), (GRID_SIZE + 1) * (GRID_SIZE + 1));
        assert_eq!(i.len(), GRID_SIZE * GRID_SIZE * 4);
        assert!(i.iter().all(|&k| (k as usize) < v.len()));
    }
}
