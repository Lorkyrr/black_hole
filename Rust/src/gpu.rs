//! Renderer wgpu: compute (geodésicas) -> textura 200x150 -> quad na tela, com a grade por baixo.
use crate::sim::{self, Camera, Object, MAX_OBJECTS};
use bytemuck::Zeroable;
use glam::camera::rh::{proj::directx::perspective, view::look_at_mat4};
use glam::Vec3;
use std::sync::Arc;
use wgpu::util::DeviceExt;
use winit::window::Window;

/// Resolução do ray tracer (no original os dois ramos de `moving` usavam o mesmo tamanho).
const COMPUTE_W: u32 = 200;
const COMPUTE_H: u32 = 150;
const FOV_DEG: f32 = 60.0;

// Uniforms: layout explícito em vec4 para bater com WGSL sem surpresas de padding.
#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct CameraU {
    pos: [f32; 4],
    right: [f32; 4],
    up: [f32; 4],
    forward: [f32; 4],
    params: [f32; 4], // tan(fov/2), aspect, moving, _
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct DiskU {
    r1: f32,
    r2: f32,
    num: f32,
    thickness: f32,
}

#[repr(C)]
#[derive(Clone, Copy, bytemuck::Pod, bytemuck::Zeroable)]
struct ObjectsU {
    num_objects: u32,
    _pad: [u32; 3],
    pos_radius: [[f32; 4]; MAX_OBJECTS],
    color: [[f32; 4]; MAX_OBJECTS],
    mass: [[f32; 4]; MAX_OBJECTS / 4],
}

pub struct Renderer {
    surface: wgpu::Surface<'static>,
    device: wgpu::Device,
    queue: wgpu::Queue,
    config: wgpu::SurfaceConfiguration,

    compute_pipe: wgpu::ComputePipeline,
    compute_bind: wgpu::BindGroup,
    camera_buf: wgpu::Buffer,
    objects_buf: wgpu::Buffer,

    grid_pipe: wgpu::RenderPipeline,
    grid_bind: wgpu::BindGroup,
    grid_uniform: wgpu::Buffer,
    grid_vertices: wgpu::Buffer,
    grid_indices: wgpu::Buffer,
    grid_index_count: u32,

    quad_pipe: wgpu::RenderPipeline,
    quad_bind: wgpu::BindGroup,
}

impl Renderer {
    pub fn new(window: Arc<Window>) -> Self {
        let size = window.inner_size();
        let instance = wgpu::Instance::new(wgpu::InstanceDescriptor::new_with_display_handle_from_env(Box::new(window.clone())));
        let surface = instance.create_surface(window).expect("create_surface");
        let adapter = pollster::block_on(instance.request_adapter(&wgpu::RequestAdapterOptions {
            compatible_surface: Some(&surface),
            // notebook híbrido: prefere a GPU dedicada em vez da integrada
            power_preference: wgpu::PowerPreference::HighPerformance,
            ..Default::default()
        }))
        .expect("nenhum adapter de GPU");
        println!("GPU: {}", adapter.get_info().name);
        let (device, queue) = pollster::block_on(adapter.request_device(&wgpu::DeviceDescriptor::default())).expect("request_device");

        let caps = surface.get_capabilities(&adapter);
        // O original escreve direto no framebuffer sem conversão sRGB.
        let format = caps.formats.iter().copied().find(|f| !f.is_srgb()).unwrap_or(caps.formats[0]);
        let config = wgpu::SurfaceConfiguration {
            usage: wgpu::TextureUsages::RENDER_ATTACHMENT,
            format,
            width: size.width.max(1),
            height: size.height.max(1),
            present_mode: wgpu::PresentMode::AutoVsync,
            desired_maximum_frame_latency: 2,
            alpha_mode: caps.alpha_modes[0],
            view_formats: vec![],
            color_space: Default::default(),
        };
        surface.configure(&device, &config);

        // --- compute ---
        let uniform = |label, size| {
            device.create_buffer(&wgpu::BufferDescriptor {
                label: Some(label),
                size,
                usage: wgpu::BufferUsages::UNIFORM | wgpu::BufferUsages::COPY_DST,
                mapped_at_creation: false,
            })
        };
        let camera_buf = uniform("camera", size_of::<CameraU>() as u64);
        let disk_buf = uniform("disk", size_of::<DiskU>() as u64);
        let objects_buf = uniform("objects", size_of::<ObjectsU>() as u64);

        let rs = sim::saga_rs() as f32;
        queue.write_buffer(
            &disk_buf,
            0,
            bytemuck::bytes_of(&DiskU { r1: rs * 2.2, r2: rs * 5.2, num: 2.0, thickness: 1e9 }),
        );

        let texture = device.create_texture(&wgpu::TextureDescriptor {
            label: Some("raytrace"),
            size: wgpu::Extent3d { width: COMPUTE_W, height: COMPUTE_H, depth_or_array_layers: 1 },
            mip_level_count: 1,
            sample_count: 1,
            dimension: wgpu::TextureDimension::D2,
            format: wgpu::TextureFormat::Rgba8Unorm,
            usage: wgpu::TextureUsages::STORAGE_BINDING | wgpu::TextureUsages::TEXTURE_BINDING,
            view_formats: &[],
        });
        let tex_view = texture.create_view(&Default::default());

        let compute_module = device.create_shader_module(wgpu::include_wgsl!("../shaders/geodesic.wgsl"));
        let compute_pipe = device.create_compute_pipeline(&wgpu::ComputePipelineDescriptor {
            label: Some("geodesic"),
            layout: None,
            module: &compute_module,
            entry_point: Some("main"),
            compilation_options: Default::default(),
            cache: None,
        });
        let compute_bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("compute"),
            layout: &compute_pipe.get_bind_group_layout(0),
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&tex_view) },
                wgpu::BindGroupEntry { binding: 1, resource: camera_buf.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 2, resource: disk_buf.as_entire_binding() },
                wgpu::BindGroupEntry { binding: 3, resource: objects_buf.as_entire_binding() },
            ],
        });

        // --- grade ---
        let (vertices, indices) = sim::build_grid(&sim::default_objects());
        let grid_vertices = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("grid vertices"),
            contents: bytemuck::cast_slice(&vertices),
            usage: wgpu::BufferUsages::VERTEX | wgpu::BufferUsages::COPY_DST,
        });
        let grid_indices = device.create_buffer_init(&wgpu::util::BufferInitDescriptor {
            label: Some("grid indices"),
            contents: bytemuck::cast_slice(&indices),
            usage: wgpu::BufferUsages::INDEX,
        });
        let grid_uniform = uniform("view_proj", 64);
        let grid_module = device.create_shader_module(wgpu::include_wgsl!("../shaders/grid.wgsl"));
        let grid_pipe = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("grid"),
            layout: None,
            vertex: wgpu::VertexState {
                module: &grid_module,
                entry_point: Some("vs_main"),
                compilation_options: Default::default(),
                buffers: &[Some(wgpu::VertexBufferLayout {
                    array_stride: 12,
                    step_mode: wgpu::VertexStepMode::Vertex,
                    attributes: &wgpu::vertex_attr_array![0 => Float32x3],
                })],
            },
            fragment: Some(wgpu::FragmentState {
                module: &grid_module,
                entry_point: Some("fs_main"),
                compilation_options: Default::default(),
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            primitive: wgpu::PrimitiveState { topology: wgpu::PrimitiveTopology::LineList, ..Default::default() },
            depth_stencil: None,
            multisample: Default::default(),
            multiview_mask: None,
            cache: None,
        });
        let grid_bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("grid"),
            layout: &grid_pipe.get_bind_group_layout(0),
            entries: &[wgpu::BindGroupEntry { binding: 0, resource: grid_uniform.as_entire_binding() }],
        });

        // --- quad ---
        let sampler = device.create_sampler(&wgpu::SamplerDescriptor {
            mag_filter: wgpu::FilterMode::Linear,
            min_filter: wgpu::FilterMode::Linear,
            ..Default::default()
        });
        let quad_module = device.create_shader_module(wgpu::include_wgsl!("../shaders/quad.wgsl"));
        let quad_pipe = device.create_render_pipeline(&wgpu::RenderPipelineDescriptor {
            label: Some("quad"),
            layout: None,
            vertex: wgpu::VertexState {
                module: &quad_module,
                entry_point: Some("vs_main"),
                compilation_options: Default::default(),
                buffers: &[],
            },
            fragment: Some(wgpu::FragmentState {
                module: &quad_module,
                entry_point: Some("fs_main"),
                compilation_options: Default::default(),
                // alpha 0 onde o raio escapa: a grade aparece por trás
                targets: &[Some(wgpu::ColorTargetState {
                    format,
                    blend: Some(wgpu::BlendState::ALPHA_BLENDING),
                    write_mask: wgpu::ColorWrites::ALL,
                })],
            }),
            primitive: Default::default(),
            depth_stencil: None,
            multisample: Default::default(),
            multiview_mask: None,
            cache: None,
        });
        let quad_bind = device.create_bind_group(&wgpu::BindGroupDescriptor {
            label: Some("quad"),
            layout: &quad_pipe.get_bind_group_layout(0),
            entries: &[
                wgpu::BindGroupEntry { binding: 0, resource: wgpu::BindingResource::TextureView(&tex_view) },
                wgpu::BindGroupEntry { binding: 1, resource: wgpu::BindingResource::Sampler(&sampler) },
            ],
        });

        Self {
            surface,
            device,
            queue,
            config,
            compute_pipe,
            compute_bind,
            camera_buf,
            objects_buf,
            grid_pipe,
            grid_bind,
            grid_uniform,
            grid_vertices,
            grid_indices,
            grid_index_count: indices.len() as u32,
            quad_pipe,
            quad_bind,
        }
    }

    pub fn resize(&mut self, width: u32, height: u32) {
        if width == 0 || height == 0 {
            return;
        }
        self.config.width = width;
        self.config.height = height;
        self.surface.configure(&self.device, &self.config);
    }

    pub fn render(&mut self, camera: &Camera, objects: &[Object]) {
        use wgpu::CurrentSurfaceTexture as S;
        let frame = match self.surface.get_current_texture() {
            S::Success(f) | S::Suboptimal(f) => f,
            S::Outdated | S::Lost => {
                self.surface.configure(&self.device, &self.config);
                return;
            }
            _ => return, // timeout/oclusão/validação: tenta de novo no próximo frame
        };
        let view = frame.texture.create_view(&Default::default());
        let aspect = self.config.width as f32 / self.config.height as f32;

        // câmera
        let pos = camera.position();
        let fwd = (Camera::TARGET - pos).normalize();
        let right = fwd.cross(Vec3::Y).normalize();
        let up = right.cross(fwd);
        let v4 = |v: Vec3| [v.x, v.y, v.z, 0.0];
        let cam = CameraU {
            pos: v4(pos),
            right: v4(right),
            up: v4(up),
            forward: v4(fwd),
            params: [(FOV_DEG.to_radians() * 0.5).tan(), aspect, camera.dragging as u32 as f32, 0.0],
        };
        self.queue.write_buffer(&self.camera_buf, 0, bytemuck::bytes_of(&cam));

        // objetos
        let mut obj = ObjectsU::zeroed();
        let count = objects.len().min(MAX_OBJECTS);
        obj.num_objects = count as u32;
        for (i, o) in objects.iter().take(count).enumerate() {
            obj.pos_radius[i] = o.pos_radius.to_array();
            obj.color[i] = o.color.to_array();
            obj.mass[i / 4][i % 4] = o.mass;
        }
        self.queue.write_buffer(&self.objects_buf, 0, bytemuck::bytes_of(&obj));

        // grade deformada pela posição atual dos objetos
        let (vertices, _) = sim::build_grid(objects);
        self.queue.write_buffer(&self.grid_vertices, 0, bytemuck::cast_slice(&vertices));
        let view_proj = perspective(FOV_DEG.to_radians(), aspect, 1e9, 1e14) * look_at_mat4(pos, Camera::TARGET, Vec3::Y);
        self.queue.write_buffer(&self.grid_uniform, 0, bytemuck::cast_slice(&view_proj.to_cols_array()));

        let mut enc = self.device.create_command_encoder(&Default::default());
        {
            let mut pass = enc.begin_compute_pass(&Default::default());
            pass.set_pipeline(&self.compute_pipe);
            pass.set_bind_group(0, &self.compute_bind, &[]);
            pass.dispatch_workgroups(COMPUTE_W.div_ceil(16), COMPUTE_H.div_ceil(16), 1);
        }
        {
            let mut pass = enc.begin_render_pass(&wgpu::RenderPassDescriptor {
                label: None,
                color_attachments: &[Some(wgpu::RenderPassColorAttachment {
                    view: &view,
                    depth_slice: None,
                    resolve_target: None,
                    ops: wgpu::Operations { load: wgpu::LoadOp::Clear(wgpu::Color::BLACK), store: wgpu::StoreOp::Store },
                })],
                depth_stencil_attachment: None,
                timestamp_writes: None,
                occlusion_query_set: None,
                multiview_mask: None,
            });
            pass.set_pipeline(&self.grid_pipe);
            pass.set_bind_group(0, &self.grid_bind, &[]);
            pass.set_vertex_buffer(0, self.grid_vertices.slice(..));
            pass.set_index_buffer(self.grid_indices.slice(..), wgpu::IndexFormat::Uint32);
            pass.draw_indexed(0..self.grid_index_count, 0, 0..1);

            pass.set_pipeline(&self.quad_pipe);
            pass.set_bind_group(0, &self.quad_bind, &[]);
            pass.draw(0..6, 0..1);
        }
        self.queue.submit([enc.finish()]);
        self.queue.present(frame);
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    // Se estes tamanhos mudarem, o layout deixa de bater com geodesic.wgsl.
    #[test]
    fn uniform_sizes_match_wgsl_layout() {
        assert_eq!(size_of::<CameraU>(), 80);
        assert_eq!(size_of::<DiskU>(), 16);
        assert_eq!(size_of::<ObjectsU>(), 16 + 256 + 256 + 64);
    }
}
