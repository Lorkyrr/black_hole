// Porte de geodesic.comp: traça geodésicas de Schwarzschild por pixel.
// WGSL não tem f64: onde o GLSL usava double (ESCAPE_R, cor do disco) usamos f32.

struct CameraU {
    pos: vec4<f32>,
    right: vec4<f32>,
    up: vec4<f32>,
    forward: vec4<f32>,
    params: vec4<f32>, // x = tan(fov/2), y = aspect, z = moving (não usado, igual ao original)
}
struct DiskU { r1: f32, r2: f32, num: f32, thickness: f32 }
struct ObjectsU {
    num_objects: u32,
    pos_radius: array<vec4<f32>, 16>,
    color: array<vec4<f32>, 16>,
    mass: array<vec4<f32>, 4>, // 16 floats empacotados (uniform exige stride 16)
}

@group(0) @binding(0) var out_image: texture_storage_2d<rgba8unorm, write>;
@group(0) @binding(1) var<uniform> cam: CameraU;
@group(0) @binding(2) var<uniform> disk: DiskU;
@group(0) @binding(3) var<uniform> objs: ObjectsU;

const SAGA_RS: f32 = 1.269e10;
const D_LAMBDA: f32 = 1e7;
const ESCAPE_R: f32 = 1e30;
const STEPS: i32 = 60000;

struct Ray {
    x: f32, y: f32, z: f32, r: f32, theta: f32, phi: f32,
    dr: f32, dtheta: f32, dphi: f32,
    E: f32, L: f32,
}

// Informação do objeto atingido (globais como no GLSL).
var<private> object_color: vec4<f32>;
var<private> hit_center: vec3<f32>;

fn init_ray(pos: vec3<f32>, dir: vec3<f32>) -> Ray {
    var ray: Ray;
    ray.x = pos.x; ray.y = pos.y; ray.z = pos.z;
    ray.r = length(pos);
    ray.theta = acos(pos.z / ray.r);
    ray.phi = atan2(pos.y, pos.x);

    let st = sin(ray.theta); let ct = cos(ray.theta);
    let sp = sin(ray.phi);   let cp = cos(ray.phi);
    ray.dr     = st * cp * dir.x + st * sp * dir.y + ct * dir.z;
    ray.dtheta = (ct * cp * dir.x + ct * sp * dir.y - st * dir.z) / ray.r;
    ray.dphi   = (-sp * dir.x + cp * dir.y) / (ray.r * st);

    ray.L = ray.r * ray.r * st * ray.dphi;
    let f = 1.0 - SAGA_RS / ray.r;
    let dt_dl = sqrt((ray.dr * ray.dr) / f + ray.r * ray.r * (ray.dtheta * ray.dtheta + st * st * ray.dphi * ray.dphi));
    ray.E = f * dt_dl;
    return ray;
}

fn intercept_object(ray: Ray) -> bool {
    let p = vec3<f32>(ray.x, ray.y, ray.z);
    for (var i = 0u; i < objs.num_objects; i++) {
        let center = objs.pos_radius[i].xyz;
        if (distance(p, center) <= objs.pos_radius[i].w) {
            object_color = objs.color[i];
            hit_center = center;
            return true;
        }
    }
    return false;
}

// d1 = (dr, dtheta, dphi), d2 = segunda derivada de (r, theta, phi)
fn geodesic_rhs(ray: Ray, d1: ptr<function, vec3<f32>>, d2: ptr<function, vec3<f32>>) {
    let r = ray.r; let theta = ray.theta;
    let dr = ray.dr; let dtheta = ray.dtheta; let dphi = ray.dphi;
    let f = 1.0 - SAGA_RS / r;
    let dt_dl = ray.E / f;
    let s = sin(theta);

    *d1 = vec3<f32>(dr, dtheta, dphi);
    *d2 = vec3<f32>(
        -(SAGA_RS / (2.0 * r * r)) * f * dt_dl * dt_dl
            + (SAGA_RS / (2.0 * r * r * f)) * dr * dr
            + r * (dtheta * dtheta + s * s * dphi * dphi),
        -2.0 * dr * dtheta / r + s * cos(theta) * dphi * dphi,
        -2.0 * dr * dphi / r - 2.0 * cos(theta) / s * dtheta * dphi,
    );
}

// O original chama isto de rk4Step, mas é um passo de Euler; mantido igual.
fn rk4_step(ray: ptr<function, Ray>, dl: f32) {
    var k1a: vec3<f32>;
    var k1b: vec3<f32>;
    geodesic_rhs(*ray, &k1a, &k1b);

    (*ray).r      += dl * k1a.x;
    (*ray).theta  += dl * k1a.y;
    (*ray).phi    += dl * k1a.z;
    (*ray).dr     += dl * k1b.x;
    (*ray).dtheta += dl * k1b.y;
    (*ray).dphi   += dl * k1b.z;

    (*ray).x = (*ray).r * sin((*ray).theta) * cos((*ray).phi);
    (*ray).y = (*ray).r * sin((*ray).theta) * sin((*ray).phi);
    (*ray).z = (*ray).r * cos((*ray).theta);
}

fn crosses_equatorial_plane(old_pos: vec3<f32>, new_pos: vec3<f32>) -> bool {
    let crossed = old_pos.y * new_pos.y < 0.0;
    let r = length(vec2<f32>(new_pos.x, new_pos.z));
    return crossed && r >= disk.r1 && r <= disk.r2;
}

@compute @workgroup_size(16, 16)
fn main(@builtin(global_invocation_id) gid: vec3<u32>) {
    let dims = textureDimensions(out_image);
    if (gid.x >= dims.x || gid.y >= dims.y) { return; }
    let w = f32(dims.x);
    let h = f32(dims.y);

    let tan_half_fov = cam.params.x;
    let aspect = cam.params.y;
    let u = (2.0 * (f32(gid.x) + 0.5) / w - 1.0) * aspect * tan_half_fov;
    let v = (1.0 - 2.0 * (f32(gid.y) + 0.5) / h) * tan_half_fov;
    let dir = normalize(u * cam.right.xyz - v * cam.up.xyz + cam.forward.xyz);
    var ray = init_ray(cam.pos.xyz, dir);

    var color = vec4<f32>(0.0);
    var prev_pos = vec3<f32>(ray.x, ray.y, ray.z);
    var hit_black_hole = false;
    var hit_disk = false;
    var hit_object = false;

    for (var i = 0; i < STEPS; i++) {
        if (ray.r <= SAGA_RS) { hit_black_hole = true; break; }
        rk4_step(&ray, D_LAMBDA);

        let new_pos = vec3<f32>(ray.x, ray.y, ray.z);
        if (crosses_equatorial_plane(prev_pos, new_pos)) { hit_disk = true; break; }
        if (intercept_object(ray)) { hit_object = true; break; }
        prev_pos = new_pos;
        if (ray.r > ESCAPE_R) { break; }
    }

    if (hit_disk) {
        let r = length(vec3<f32>(ray.x, ray.y, ray.z)) / disk.r2;
        color = vec4<f32>(1.0, r, 0.2, r);
    } else if (hit_black_hole) {
        color = vec4<f32>(0.0, 0.0, 0.0, 1.0);
    } else if (hit_object) {
        let p = vec3<f32>(ray.x, ray.y, ray.z);
        let n = normalize(p - hit_center);
        let view = normalize(cam.pos.xyz - p);
        let ambient = 0.1;
        let intensity = ambient + (1.0 - ambient) * max(dot(n, view), 0.0);
        color = vec4<f32>(object_color.rgb * intensity, object_color.a);
    }

    textureStore(out_image, vec2<i32>(gid.xy), color);
}
