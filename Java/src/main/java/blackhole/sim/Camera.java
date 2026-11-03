package blackhole.sim;

import org.joml.Vector3f;

/** Câmera orbital em volta do buraco negro (origem). Porte de {@code struct Camera} em C++/sim.h, sem API gráfica. */
public final class Camera {
    static final float MIN_RADIUS = 1e10f;
    static final float MAX_RADIUS = 1e12f;
    static final float ORBIT_SPEED = 0.01f;
    static final double ZOOM_SPEED = 25e9;
    private static final float MIN_ELEV = 0.01f;
    private static final float MAX_ELEV = (float) Math.PI - 0.01f;

    /** A câmera sempre olha para o buraco negro, na origem. Não modificar. */
    public static final Vector3f TARGET = new Vector3f();

    public float radius = 6.34194e10f;
    public float azimuth = 0f;
    public float elevation = (float) (Math.PI / 2);
    public boolean dragging = false;
    private double lastX, lastY;

    public Vector3f position() {
        double e = Math.clamp(elevation, MIN_ELEV, MAX_ELEV);
        return new Vector3f(
                (float) (radius * Math.sin(e) * Math.cos(azimuth)),
                (float) (radius * Math.cos(e)),
                (float) (radius * Math.sin(e) * Math.sin(azimuth)));
    }

    public void mouseMove(double x, double y) {
        if (dragging) {
            azimuth += (float) (x - lastX) * ORBIT_SPEED;
            elevation = Math.clamp(elevation - (float) (y - lastY) * ORBIT_SPEED, MIN_ELEV, MAX_ELEV);
        }
        lastX = x;
        lastY = y;
    }

    /** {@code x, y} é a posição atual do cursor (o C++ lia com glfwGetCursorPos). */
    public void setDragging(boolean on, double x, double y) {
        dragging = on;
        if (on) {
            lastX = x;
            lastY = y;
        }
    }

    public void scroll(double dy) {
        radius = (float) Math.clamp(radius - dy * ZOOM_SPEED, MIN_RADIUS, MAX_RADIUS);
    }
}
