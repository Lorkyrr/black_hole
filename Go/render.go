package main

import (
	"embed"
	"fmt"
	"math"
	"strings"
	"unsafe"

	"blackhole/sim"

	"github.com/go-gl/gl/v4.3-core/gl"
	"github.com/go-gl/mathgl/mgl32"
)

//go:embed shaders/*
var shaderFS embed.FS

const (
	computeW = 200 // resolução do ray tracer (o shader também a tem fixa)
	computeH = 150
	fovDeg   = 60.0
	aspect   = 800.0 / 600.0 // como no C++: constante, independente do tamanho real da janela
)

const quadVert = `#version 330 core
layout (location = 0) in vec2 aPos;
layout (location = 1) in vec2 aTexCoord;
out vec2 TexCoord;
void main() {
    gl_Position = vec4(aPos, 0.0, 1.0);
    TexCoord = aTexCoord;
}`

const quadFrag = `#version 330 core
in vec2 TexCoord;
out vec4 FragColor;
uniform sampler2D screenTexture;
void main() {
    FragColor = texture(screenTexture, TexCoord);
}`

type renderer struct {
	gridProg, quadProg, computeProg uint32
	cameraBuf, diskBuf, objectsBuf  uint32
	gridVAO, gridVBO, gridEBO       uint32
	gridIndexCount                  int32
	quadVAO, texture                uint32
	viewProjLoc, screenTexLoc       int32
}

func newRenderer() *renderer {
	r := &renderer{}
	r.quadProg = mustProgram(shader(gl.VERTEX_SHADER, quadVert), shader(gl.FRAGMENT_SHADER, quadFrag))
	r.gridProg = mustProgram(embedded(gl.VERTEX_SHADER, "grid.vert"), embedded(gl.FRAGMENT_SHADER, "grid.frag"))
	r.computeProg = mustProgram(embedded(gl.COMPUTE_SHADER, "geodesic.comp"))
	r.viewProjLoc = gl.GetUniformLocation(r.gridProg, gl.Str("viewProj\x00"))
	r.screenTexLoc = gl.GetUniformLocation(r.quadProg, gl.Str("screenTexture\x00"))

	// binding = 1/2/3 casa com o shader; tamanhos std140.
	r.cameraBuf = newUBO(1, 80)
	r.diskBuf = newUBO(2, 16)
	r.objectsBuf = newUBO(3, 16+16*16*2+16*16) // int+pad, 16×vec4 ×2, float[16] com stride 16
	r.initQuad()
	gl.GenVertexArrays(1, &r.gridVAO)
	gl.GenBuffers(1, &r.gridVBO)
	gl.GenBuffers(1, &r.gridEBO)
	return r
}

func newUBO(binding uint32, size int) uint32 {
	var b uint32
	gl.GenBuffers(1, &b)
	gl.BindBuffer(gl.UNIFORM_BUFFER, b)
	gl.BufferData(gl.UNIFORM_BUFFER, size, nil, gl.DYNAMIC_DRAW)
	gl.BindBufferBase(gl.UNIFORM_BUFFER, binding, b)
	return b
}

func (r *renderer) initQuad() {
	quad := []float32{ // pos, texcoord
		-1, 1, 0, 1,
		-1, -1, 0, 0,
		1, -1, 1, 0,
		-1, 1, 0, 1,
		1, -1, 1, 0,
		1, 1, 1, 1,
	}
	var vbo uint32
	gl.GenVertexArrays(1, &r.quadVAO)
	gl.GenBuffers(1, &vbo)
	gl.BindVertexArray(r.quadVAO)
	gl.BindBuffer(gl.ARRAY_BUFFER, vbo)
	gl.BufferData(gl.ARRAY_BUFFER, len(quad)*4, gl.Ptr(quad), gl.STATIC_DRAW)
	gl.VertexAttribPointerWithOffset(0, 2, gl.FLOAT, false, 16, 0)
	gl.EnableVertexAttribArray(0)
	gl.VertexAttribPointerWithOffset(1, 2, gl.FLOAT, false, 16, 8)
	gl.EnableVertexAttribArray(1)

	gl.GenTextures(1, &r.texture)
	gl.BindTexture(gl.TEXTURE_2D, r.texture)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_MIN_FILTER, gl.LINEAR)
	gl.TexParameteri(gl.TEXTURE_2D, gl.TEXTURE_MAG_FILTER, gl.LINEAR)
	gl.TexImage2D(gl.TEXTURE_2D, 0, gl.RGBA8, computeW, computeH, 0, gl.RGBA, gl.UNSIGNED_BYTE, nil)
}

func (r *renderer) frame(cam *sim.Camera, objs []sim.Object, fbW, fbH int) {
	gl.ClearColor(0, 0, 0, 1)
	gl.Clear(gl.COLOR_BUFFER_BIT | gl.DEPTH_BUFFER_BIT)
	gl.Viewport(0, 0, int32(fbW), int32(fbH))

	// grade deformada por baixo
	view := mgl32.LookAtV(cam.Position(), sim.Target, mgl32.Vec3{0, 1, 0})
	proj := mgl32.Perspective(mgl32.DegToRad(fovDeg), float32(computeW)/computeH, 1e9, 1e14)
	r.drawGrid(proj.Mul4(view), objs)

	// ray tracer -> textura -> quad
	r.dispatchCompute(cam, objs)
	gl.UseProgram(r.quadProg)
	gl.BindVertexArray(r.quadVAO)
	gl.ActiveTexture(gl.TEXTURE0)
	gl.BindTexture(gl.TEXTURE_2D, r.texture)
	gl.Uniform1i(r.screenTexLoc, 0)
	gl.Disable(gl.DEPTH_TEST)
	gl.DrawArrays(gl.TRIANGLES, 0, 6)
	gl.Enable(gl.DEPTH_TEST)
}

func (r *renderer) drawGrid(viewProj mgl32.Mat4, objs []sim.Object) {
	verts, idx := sim.BuildGrid(objs)
	gl.BindVertexArray(r.gridVAO)
	gl.BindBuffer(gl.ARRAY_BUFFER, r.gridVBO)
	gl.BufferData(gl.ARRAY_BUFFER, len(verts)*12, unsafe.Pointer(&verts[0]), gl.DYNAMIC_DRAW)
	gl.BindBuffer(gl.ELEMENT_ARRAY_BUFFER, r.gridEBO)
	gl.BufferData(gl.ELEMENT_ARRAY_BUFFER, len(idx)*4, gl.Ptr(idx), gl.STATIC_DRAW)
	gl.EnableVertexAttribArray(0)
	gl.VertexAttribPointerWithOffset(0, 3, gl.FLOAT, false, 12, 0)
	r.gridIndexCount = int32(len(idx))

	gl.UseProgram(r.gridProg)
	gl.UniformMatrix4fv(r.viewProjLoc, 1, false, &viewProj[0])
	gl.Disable(gl.DEPTH_TEST)
	gl.Enable(gl.BLEND)
	gl.BlendFunc(gl.SRC_ALPHA, gl.ONE_MINUS_SRC_ALPHA)
	gl.DrawElementsWithOffset(gl.LINES, r.gridIndexCount, gl.UNSIGNED_INT, 0)
	gl.Enable(gl.DEPTH_TEST)
}

func (r *renderer) dispatchCompute(cam *sim.Camera, objs []sim.Object) {
	gl.UseProgram(r.computeProg)
	r.uploadCamera(cam)
	r.uploadDisk()
	r.uploadObjects(objs)
	gl.BindImageTexture(0, r.texture, 0, false, 0, gl.WRITE_ONLY, gl.RGBA8)
	gl.DispatchCompute(uint32(math.Ceil(computeW/16.0)), uint32(math.Ceil(computeH/16.0)), 1)
	gl.MemoryBarrier(gl.SHADER_IMAGE_ACCESS_BARRIER_BIT)
}

func (r *renderer) uploadCamera(cam *sim.Camera) {
	pos := cam.Position()
	fwd := sim.Target.Sub(pos).Normalize()
	right := fwd.Cross(mgl32.Vec3{0, 1, 0}).Normalize() // y é "para cima": o disco fica no plano x-z
	up := right.Cross(fwd)
	moving := int32(0)
	if cam.Dragging {
		moving = 1
	}
	data := struct {
		Pos     mgl32.Vec3
		_       float32
		Right   mgl32.Vec3
		_       float32
		Up      mgl32.Vec3
		_       float32
		Forward mgl32.Vec3
		_       float32
		TanHalf float32
		Aspect  float32
		Moving  int32
		_       int32
	}{Pos: pos, Right: right, Up: up, Forward: fwd,
		TanHalf: float32(math.Tan(float64(mgl32.DegToRad(fovDeg * 0.5)))), Aspect: aspect, Moving: moving}
	gl.BindBuffer(gl.UNIFORM_BUFFER, r.cameraBuf)
	gl.BufferSubData(gl.UNIFORM_BUFFER, 0, int(unsafe.Sizeof(data)), unsafe.Pointer(&data))
}

func (r *renderer) uploadDisk() {
	rs := float32(sim.SagaRs())
	disk := [4]float32{rs * 2.2, rs * 5.2, 2, 1e9} // r1, r2, num, thickness
	gl.BindBuffer(gl.UNIFORM_BUFFER, r.diskBuf)
	gl.BufferSubData(gl.UNIFORM_BUFFER, 0, 16, unsafe.Pointer(&disk))
}

func (r *renderer) uploadObjects(objs []sim.Object) {
	var data struct {
		Num       int32
		_         [3]int32
		PosRadius [sim.MaxObjects]mgl32.Vec4
		Color     [sim.MaxObjects]mgl32.Vec4
		Mass      [sim.MaxObjects][4]float32 // std140: cada float de um array ocupa 16 bytes
	}
	n := min(len(objs), sim.MaxObjects)
	data.Num = int32(n)
	for i := 0; i < n; i++ {
		data.PosRadius[i] = objs[i].PosRadius
		data.Color[i] = objs[i].Color
		data.Mass[i][0] = objs[i].Mass
	}
	gl.BindBuffer(gl.UNIFORM_BUFFER, r.objectsBuf)
	gl.BufferSubData(gl.UNIFORM_BUFFER, 0, int(unsafe.Sizeof(data)), unsafe.Pointer(&data))
}

// ---- shaders ----

func embedded(kind uint32, name string) uint32 {
	src, err := shaderFS.ReadFile("shaders/" + name)
	if err != nil {
		panic(err)
	}
	return shaderNamed(kind, string(src), name)
}

func shader(kind uint32, src string) uint32 { return shaderNamed(kind, src, "inline") }

func shaderNamed(kind uint32, src, name string) uint32 {
	s := gl.CreateShader(kind)
	csrc, free := gl.Strs(src + "\x00")
	gl.ShaderSource(s, 1, csrc, nil)
	free()
	gl.CompileShader(s)
	var ok int32
	gl.GetShaderiv(s, gl.COMPILE_STATUS, &ok)
	if ok == gl.FALSE {
		panic(fmt.Sprintf("shader compile error (%s):\n%s", name, infoLog(s, gl.GetShaderiv, gl.GetShaderInfoLog)))
	}
	return s
}

func mustProgram(shaders ...uint32) uint32 {
	p := gl.CreateProgram()
	for _, s := range shaders {
		gl.AttachShader(p, s)
	}
	gl.LinkProgram(p)
	var ok int32
	gl.GetProgramiv(p, gl.LINK_STATUS, &ok)
	if ok == gl.FALSE {
		panic("shader link error:\n" + infoLog(p, gl.GetProgramiv, gl.GetProgramInfoLog))
	}
	for _, s := range shaders {
		gl.DeleteShader(s)
	}
	return p
}

func infoLog(obj uint32, get func(uint32, uint32, *int32), getLog func(uint32, int32, *int32, *uint8)) string {
	var n int32
	get(obj, gl.INFO_LOG_LENGTH, &n)
	buf := strings.Repeat("\x00", int(n)+1)
	getLog(obj, n, nil, gl.Str(buf))
	return strings.TrimRight(buf, "\x00")
}
