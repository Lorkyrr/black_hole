package blackhole.sim;

import org.joml.Vector3f;
import org.joml.Vector4f;

/** Um corpo da cena. Mutável de propósito: a gravidade move {@code posRadius} e {@code velocity} a cada frame. */
public final class Obj {
    public final Vector4f posRadius; // xyz = posição, w = raio
    public final Vector4f color;
    public final float mass;
    public final Vector3f velocity = new Vector3f();

    public Obj(Vector4f posRadius, Vector4f color, float mass) {
        this.posRadius = posRadius;
        this.color = color;
        this.mass = mass;
    }
}
