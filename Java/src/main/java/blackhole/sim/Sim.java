package blackhole.sim;

import java.util.List;
import org.joml.Vector4f;

/** Física e malha do grid: porte de C++/sim.h. Nenhuma chamada de API gráfica. */
public final class Sim {
    private Sim() {}

    public static final double C = 299_792_458.0;
    public static final double G = 6.674_30e-11;
    public static final int GRID_SIZE = 25;
    public static final int MAX_OBJECTS = 16;
    /** Sagittarius A*. O C++ guardava a massa num {@code float}, então o valor exato é o do float. */
    public static final float SAGA_MASS = 8.54e36f;

    /** Raio de Schwarzschild: 2GM/c². */
    public static double schwarzschildRadius(double mass) {
        return 2 * G * mass / (C * C);
    }

    public static double sagaRs() {
        return schwarzschildRadius(SAGA_MASS);
    }

    public static List<Obj> defaultObjects() {
        return List.of(
                new Obj(new Vector4f(4e11f, 0, 0, 4e10f), new Vector4f(1, 1, 0, 1), 1.98892e30f),
                new Obj(new Vector4f(0, 0, 4e11f, 4e10f), new Vector4f(1, 0, 0, 1), 1.98892e30f),
                new Obj(new Vector4f(0, 0, 0, (float) sagaRs()), new Vector4f(0, 0, 0, 1), SAGA_MASS));
    }

    /** Um passo de gravidade newtoniana (sem dt, como no original). Só mexe nos objetos se {@code gravity}. */
    public static void stepGravity(List<Obj> objs, boolean gravity) {
        if (!gravity) return;
        for (Obj a : objs) {
            for (Obj b : objs) {
                if (a == b) continue;
                float dx = b.posRadius.x - a.posRadius.x;
                float dy = b.posRadius.y - a.posRadius.y;
                float dz = b.posRadius.z - a.posRadius.z;
                double dist = Math.sqrt((double) (dx * dx + dy * dy + dz * dz));
                if (dist <= 0) continue;
                double acc = G * b.mass / (dist * dist); // força/massa de a: a massa de a se cancela
                a.velocity.x += (float) (dx / dist * acc);
                a.velocity.y += (float) (dy / dist * acc);
                a.velocity.z += (float) (dz / dist * acc);
                a.posRadius.x += a.velocity.x;
                a.posRadius.y += a.velocity.y;
                a.posRadius.z += a.velocity.z;
            }
        }
    }

    /** Vértices (x,y,z intercalados) e índices de linha da grade deformada pela geometria de Schwarzschild. */
    public record Grid(float[] vertices, int[] indices) {
        public int vertexCount() {
            return vertices.length / 3;
        }
    }

    public static Grid buildGrid(List<Obj> objs) {
        final float spacing = 1e10f;
        int n = GRID_SIZE;
        float[] v = new float[(n + 1) * (n + 1) * 3];
        int k = 0;
        for (int z = 0; z <= n; z++) {
            for (int x = 0; x <= n; x++) {
                float wx = (x - n / 2) * spacing;
                float wz = (z - n / 2) * spacing;
                float y = 0;
                for (Obj o : objs) {
                    double rs = schwarzschildRadius(o.mass);
                    double dx = wx - (double) o.posRadius.x;
                    double dz = wz - (double) o.posRadius.z;
                    double dist = Math.sqrt(dx * dx + dz * dz);
                    y += (float) (dist > rs ? 2 * Math.sqrt(rs * (dist - rs)) : 2 * rs) - 3e10f;
                }
                v[k++] = wx;
                v[k++] = y;
                v[k++] = wz;
            }
        }
        int[] idx = new int[n * n * 4];
        k = 0;
        for (int z = 0; z < n; z++) {
            for (int x = 0; x < n; x++) {
                int i = z * (n + 1) + x;
                idx[k++] = i;
                idx[k++] = i + 1;
                idx[k++] = i;
                idx[k++] = i + n + 1;
            }
        }
        return new Grid(v, idx);
    }
}
