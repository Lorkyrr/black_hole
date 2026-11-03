mod gpu;
mod sim;

use std::sync::Arc;
use winit::application::ApplicationHandler;
use winit::event::{ElementState, KeyEvent, MouseButton, MouseScrollDelta, WindowEvent};
use winit::event_loop::{ActiveEventLoop, ControlFlow, EventLoop};
use winit::keyboard::{Key, NamedKey};
use winit::window::{Window, WindowId};

struct App {
    window: Option<Arc<Window>>,
    renderer: Option<gpu::Renderer>,
    camera: sim::Camera,
    objects: Vec<sim::Object>,
    gravity: bool,
    cursor: (f64, f64),
}

impl ApplicationHandler for App {
    fn resumed(&mut self, el: &ActiveEventLoop) {
        if self.window.is_some() {
            return;
        }
        let attrs = Window::default_attributes()
            .with_title("Black Hole")
            .with_inner_size(winit::dpi::LogicalSize::new(800, 600));
        let window = Arc::new(el.create_window(attrs).expect("create_window"));
        self.renderer = Some(gpu::Renderer::new(window.clone()));
        self.window = Some(window);
    }

    fn window_event(&mut self, el: &ActiveEventLoop, _: WindowId, event: WindowEvent) {
        match event {
            WindowEvent::CloseRequested => el.exit(),
            WindowEvent::Resized(s) => {
                if let Some(r) = &mut self.renderer {
                    r.resize(s.width, s.height);
                }
            }
            WindowEvent::CursorMoved { position, .. } => {
                self.cursor = (position.x, position.y);
                self.camera.mouse_move(position.x, position.y);
            }
            WindowEvent::MouseInput { state, button, .. } => {
                let pressed = state == ElementState::Pressed;
                match button {
                    MouseButton::Left | MouseButton::Middle => self.camera.set_dragging(pressed, self.cursor),
                    MouseButton::Right => self.gravity = pressed,
                    _ => {}
                }
            }
            WindowEvent::MouseWheel { delta, .. } => {
                let dy = match delta {
                    MouseScrollDelta::LineDelta(_, y) => y as f64,
                    MouseScrollDelta::PixelDelta(p) => p.y / 20.0, // aproxima "linhas" do GLFW
                };
                self.camera.scroll(dy);
            }
            WindowEvent::KeyboardInput {
                event: KeyEvent { logical_key: Key::Character(c), state: ElementState::Pressed, repeat: false, .. },
                ..
            } if c.eq_ignore_ascii_case("g") => {
                self.gravity = !self.gravity;
                println!("[INFO] Gravity turned {}", if self.gravity { "ON" } else { "OFF" });
            }
            WindowEvent::KeyboardInput {
                event: KeyEvent { logical_key: Key::Named(NamedKey::Escape), state: ElementState::Pressed, .. },
                ..
            } => el.exit(),
            WindowEvent::RedrawRequested => {
                sim::step_gravity(&mut self.objects, self.gravity);
                if let Some(r) = &mut self.renderer {
                    r.render(&self.camera, &self.objects);
                }
            }
            _ => {}
        }
    }

    fn about_to_wait(&mut self, _: &ActiveEventLoop) {
        if let Some(w) = &self.window {
            w.request_redraw();
        }
    }
}

fn main() {
    let event_loop = EventLoop::new().expect("event loop");
    event_loop.set_control_flow(ControlFlow::Poll);
    let mut app = App {
        window: None,
        renderer: None,
        camera: sim::Camera::new(),
        objects: sim::default_objects(),
        gravity: false,
        cursor: (0.0, 0.0),
    };
    event_loop.run_app(&mut app).expect("run_app");
}
