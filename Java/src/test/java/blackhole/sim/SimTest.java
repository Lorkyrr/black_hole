package blackhole.sim;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class SimTest {
    @Test
    void sagittariusRadiusMatchesShaderConstant() {
        // geodesic_vk.comp usa SagA_rs = 1.269e10
        assertEquals(1.269e10, Sim.sagaRs(), 1.269e10 * 1e-3);
    }

    @Test
    void cameraStartsOnXAxis() {
        var p = new Camera().position();
        assertEquals(6.34194e10, p.x, 1e6);
        assertEquals(0, p.y, 1e7);
        assertEquals(0, p.z, 1e6);
    }

    @Test
    void cameraZoomIsClamped() {
        var c = new Camera();
        c.scroll(1000);
        assertEquals(1e10f, c.radius);
        c.scroll(-1000);
        assertEquals(1e12f, c.radius);
    }

    @Test
    void orbitOnlyWhileDragging() {
        var c = new Camera();
        c.mouseMove(10, 10);
        assertEquals(0f, c.azimuth);
        c.setDragging(true, 10, 10);
        c.mouseMove(20, 10);
        assertEquals(0.1f, c.azimuth, 1e-6f);
    }

    @Test
    void gravityOffDoesNothingAndOnAttracts() {
        List<Obj> objs = Sim.defaultObjects();
        float before = objs.get(0).posRadius.x;
        Sim.stepGravity(objs, false);
        assertEquals(before, objs.get(0).posRadius.x);
        // float perde passos pequenos perto de 4e11, então precisa acumular velocidade
        for (int i = 0; i < 20; i++) Sim.stepGravity(objs, true);
        assertTrue(objs.get(0).posRadius.x < before); // puxado para o buraco negro na origem
    }

    @Test
    void gridHasExpectedSizes() {
        var g = Sim.buildGrid(Sim.defaultObjects());
        int n = Sim.GRID_SIZE;
        assertEquals((n + 1) * (n + 1), g.vertexCount());
        assertEquals(n * n * 4, g.indices().length);
        for (int k : g.indices()) assertTrue(k >= 0 && k < g.vertexCount());
    }
}
