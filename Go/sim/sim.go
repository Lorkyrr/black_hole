// Package sim: física e câmera, porte de C++/sim.h. Sem nenhuma chamada de API gráfica.
package sim

import (
	"fmt"
	"math"

	"github.com/go-gl/mathgl/mgl32"
)

const (
	C          = 299792458.0
	G          = 6.67430e-11
	GridSize   = 25
	MaxObjects = 16
	sagaMass   = float32(8.54e36) // o C++ guardava a massa num float
)

// SchwarzschildRadius: 2GM/c².
func SchwarzschildRadius(mass float64) float64 { return 2 * G * mass / (C * C) }

// SagaRs é o raio de Schwarzschild de Sagittarius A*.
func SagaRs() float64 { return SchwarzschildRadius(float64(sagaMass)) }

type Object struct {
	PosRadius mgl32.Vec4 // xyz = posição, w = raio
	Color     mgl32.Vec4
	Mass      float32
	Velocity  mgl32.Vec3
}

func DefaultObjects() []Object {
	return []Object{
		{PosRadius: mgl32.Vec4{4e11, 0, 0, 4e10}, Color: mgl32.Vec4{1, 1, 0, 1}, Mass: 1.98892e30},
		{PosRadius: mgl32.Vec4{0, 0, 4e11, 4e10}, Color: mgl32.Vec4{1, 0, 0, 1}, Mass: 1.98892e30},
		{PosRadius: mgl32.Vec4{0, 0, 0, float32(SagaRs())}, Color: mgl32.Vec4{0, 0, 0, 1}, Mass: sagaMass},
	}
}

type Camera struct {
	Radius, Azimuth, Elevation float32
	Dragging                   bool
	lastX, lastY               float64
}

const (
	minRadius  = 1e10
	maxRadius  = 1e12
	orbitSpeed = 0.01
	zoomSpeed  = 25e9
	// Target: a câmera sempre olha para o buraco negro, na origem.
)

var Target = mgl32.Vec3{}

func NewCamera() *Camera {
	return &Camera{Radius: 6.34194e10, Elevation: math.Pi / 2}
}

func clamp32(v, lo, hi float32) float32 {
	return float32(math.Max(float64(lo), math.Min(float64(hi), float64(v))))
}

func (c *Camera) Position() mgl32.Vec3 {
	e := float64(clamp32(c.Elevation, 0.01, math.Pi-0.01))
	az := float64(c.Azimuth)
	r := float64(c.Radius)
	return mgl32.Vec3{
		float32(r * math.Sin(e) * math.Cos(az)),
		float32(r * math.Cos(e)),
		float32(r * math.Sin(e) * math.Sin(az)),
	}
}

func (c *Camera) MouseMove(x, y float64) {
	if c.Dragging {
		c.Azimuth += float32(x-c.lastX) * orbitSpeed
		c.Elevation = clamp32(c.Elevation-float32(y-c.lastY)*orbitSpeed, 0.01, math.Pi-0.01)
	}
	c.lastX, c.lastY = x, y
}

// SetDragging: cursor é a posição atual do mouse (o C++ lia com glfwGetCursorPos).
func (c *Camera) SetDragging(on bool, x, y float64) {
	c.Dragging = on
	if on {
		c.lastX, c.lastY = x, y
	}
}

func (c *Camera) Scroll(dy float64) {
	c.Radius = float32(math.Max(minRadius, math.Min(maxRadius, float64(c.Radius)-dy*zoomSpeed)))
}

// StepGravity: um passo de gravidade newtoniana (sem dt, como no original). Só mexe nos objetos se gravity.
func StepGravity(objs []Object, gravity bool) {
	if !gravity {
		return
	}
	for i := range objs {
		for j := range objs {
			if i == j {
				continue
			}
			d := objs[j].PosRadius.Vec3().Sub(objs[i].PosRadius.Vec3())
			dist := float64(d.Len())
			if dist <= 0 {
				continue
			}
			force := G * float64(objs[i].Mass) * float64(objs[j].Mass) / (dist * dist)
			acc := force / float64(objs[i].Mass)
			o := &objs[i]
			for k := 0; k < 3; k++ {
				o.Velocity[k] += float32(float64(d[k]) / dist * acc)
				o.PosRadius[k] += o.Velocity[k]
			}
		}
	}
}

// BuildGrid devolve os vértices da grade deformada (geometria de Schwarzschild) e os índices das linhas.
func BuildGrid(objs []Object) ([]mgl32.Vec3, []uint32) {
	const spacing = 1e10
	n := GridSize
	verts := make([]mgl32.Vec3, 0, (n+1)*(n+1))
	for z := 0; z <= n; z++ {
		for x := 0; x <= n; x++ {
			wx := float32(x-n/2) * spacing
			wz := float32(z-n/2) * spacing
			var y float32
			for _, o := range objs {
				rs := SchwarzschildRadius(float64(o.Mass))
				dx := float64(wx) - float64(o.PosRadius[0])
				dz := float64(wz) - float64(o.PosRadius[2])
				dist := math.Sqrt(dx*dx + dz*dz)
				if dist > rs {
					y += float32(2*math.Sqrt(rs*(dist-rs))) - 3e10
				} else {
					y += float32(2*rs) - 3e10
				}
			}
			verts = append(verts, mgl32.Vec3{wx, y, wz})
		}
	}
	idx := make([]uint32, 0, n*n*4)
	for z := 0; z < n; z++ {
		for x := 0; x < n; x++ {
			i := uint32(z*(n+1) + x)
			idx = append(idx, i, i+1, i, i+uint32(n)+1)
		}
	}
	return verts, idx
}

func (o Object) String() string { return fmt.Sprintf("%v m=%g", o.PosRadius, o.Mass) }
