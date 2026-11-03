package sim

import (
	"math"
	"testing"

	"github.com/go-gl/mathgl/mgl32"
)

func TestSagittariusRadiusMatchesShaderConstant(t *testing.T) {
	// geodesic.comp usa SagA_rs = 1.269e10
	if math.Abs(SagaRs()-1.269e10)/1.269e10 > 1e-3 {
		t.Fatalf("SagaRs = %g", SagaRs())
	}
}

func TestCameraStartsOnXAxis(t *testing.T) {
	p := NewCamera().Position()
	if math.Abs(float64(p.X())-6.34194e10) > 1e6 || math.Abs(float64(p.Y())) > 1e7 || math.Abs(float64(p.Z())) > 1e6 {
		t.Fatalf("pos = %v", p)
	}
}

func TestCameraZoomIsClamped(t *testing.T) {
	c := NewCamera()
	c.Scroll(1000)
	if c.Radius != 1e10 {
		t.Fatalf("min: %g", c.Radius)
	}
	c.Scroll(-1000)
	if c.Radius != 1e12 {
		t.Fatalf("max: %g", c.Radius)
	}
}

func TestOrbitOnlyWhileDragging(t *testing.T) {
	c := NewCamera()
	c.MouseMove(10, 10)
	if c.Azimuth != 0 {
		t.Fatal("orbitou sem arrastar")
	}
	c.SetDragging(true, 10, 10)
	c.MouseMove(20, 10)
	if math.Abs(float64(c.Azimuth)-0.1) > 1e-6 {
		t.Fatalf("azimuth = %g", c.Azimuth)
	}
}

func TestGravityOffDoesNothingOnAttracts(t *testing.T) {
	objs := DefaultObjects()
	before := objs[0].PosRadius
	StepGravity(objs, false)
	if objs[0].PosRadius != before {
		t.Fatal("moveu com gravidade desligada")
	}
	// f32 perde passos pequenos perto de 4e11, então precisa acumular velocidade
	for i := 0; i < 20; i++ {
		StepGravity(objs, true)
	}
	if objs[0].PosRadius.X() >= before.X() {
		t.Fatal("não foi puxado para o buraco negro")
	}
}

func TestGridSizes(t *testing.T) {
	v, idx := BuildGrid(DefaultObjects())
	if len(v) != (GridSize+1)*(GridSize+1) || len(idx) != GridSize*GridSize*4 {
		t.Fatalf("v=%d idx=%d", len(v), len(idx))
	}
	for _, k := range idx {
		if int(k) >= len(v) {
			t.Fatal("índice fora do range")
		}
	}
}

var _ = mgl32.Vec3{}
