package main

import (
	"fmt"
	"runtime"

	"blackhole/sim"

	"github.com/go-gl/gl/v4.3-core/gl"
	"github.com/go-gl/glfw/v3.3/glfw"
)

func init() { runtime.LockOSThread() } // GLFW e OpenGL exigem a thread principal

func main() {
	if err := glfw.Init(); err != nil {
		panic(err)
	}
	defer glfw.Terminate()
	glfw.WindowHint(glfw.ContextVersionMajor, 4)
	glfw.WindowHint(glfw.ContextVersionMinor, 3)
	glfw.WindowHint(glfw.OpenGLProfile, glfw.OpenGLCoreProfile)
	win, err := glfw.CreateWindow(800, 600, "Black Hole", nil, nil)
	if err != nil {
		panic(err)
	}
	win.MakeContextCurrent()
	glfw.SwapInterval(1)
	if err := gl.InitWithProcAddrFunc(glfw.GetProcAddress); err != nil { // EGL no Wayland: não usar glXGetProcAddress
		panic(err)
	}
	fmt.Println("OpenGL", gl.GoStr(gl.GetString(gl.VERSION)))

	r := newRenderer()
	cam := sim.NewCamera()
	objs := sim.DefaultObjects()
	gravity := false

	win.SetCursorPosCallback(func(_ *glfw.Window, x, y float64) { cam.MouseMove(x, y) })
	win.SetScrollCallback(func(_ *glfw.Window, _, dy float64) { cam.Scroll(dy) })
	win.SetMouseButtonCallback(func(w *glfw.Window, b glfw.MouseButton, a glfw.Action, _ glfw.ModifierKey) {
		switch b {
		case glfw.MouseButtonLeft, glfw.MouseButtonMiddle:
			x, y := w.GetCursorPos()
			cam.SetDragging(a == glfw.Press, x, y)
		case glfw.MouseButtonRight:
			gravity = a == glfw.Press
		}
	})
	win.SetKeyCallback(func(w *glfw.Window, k glfw.Key, _ int, a glfw.Action, _ glfw.ModifierKey) {
		if a != glfw.Press {
			return
		}
		switch k {
		case glfw.KeyG:
			gravity = !gravity
			fmt.Printf("[INFO] Gravity turned %s\n", map[bool]string{true: "ON", false: "OFF"}[gravity])
		case glfw.KeyEscape:
			w.SetShouldClose(true)
		}
	})

	for !win.ShouldClose() {
		sim.StepGravity(objs, gravity)
		w, h := win.GetFramebufferSize()
		r.frame(cam, objs, w, h)
		win.SwapBuffers()
		glfw.PollEvents()
	}
}
