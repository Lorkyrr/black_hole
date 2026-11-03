package blackhole;

import static org.lwjgl.glfw.GLFW.*;

import blackhole.sim.Camera;
import blackhole.sim.Obj;
import blackhole.sim.Sim;
import java.util.List;

/** Janela GLFW (sem contexto OpenGL), callbacks de entrada e laço principal. Equivale a main() em C++/black_hole_vk.cpp. */
public final class Main {
    public static void main(String[] args) {
        // Os drivers listam centenas de extensões; a pilha padrão do LWJGL (64 KB) estoura em vkCreateInstance. Precisa vir antes de usar o LWJGL.
        System.setProperty("org.lwjgl.system.stackSize", "1024");
        if (!glfwInit()) throw new IllegalStateException("GLFW init failed");
        if (!org.lwjgl.glfw.GLFWVulkan.glfwVulkanSupported())
            throw new IllegalStateException("GLFW: Vulkan loader/driver not found");
        glfwWindowHint(GLFW_CLIENT_API, GLFW_NO_API);
        long window = glfwCreateWindow(VulkanRenderer.WIDTH, VulkanRenderer.HEIGHT, "Black Hole (Vulkan, Java)", 0, 0);
        if (window == 0) throw new IllegalStateException("Failed to create GLFW window");
        glfwSetFramebufferSizeCallback(window, (w, x, y) -> VulkanRenderer.resized = true);

        Camera cam = new Camera();
        List<Obj> objs = Sim.defaultObjects();
        boolean[] gravity = {false};

        glfwSetCursorPosCallback(window, (w, x, y) -> cam.mouseMove(x, y));
        glfwSetScrollCallback(window, (w, dx, dy) -> cam.scroll(dy));
        glfwSetMouseButtonCallback(window, (w, button, action, mods) -> {
            if (button == GLFW_MOUSE_BUTTON_LEFT || button == GLFW_MOUSE_BUTTON_MIDDLE) {
                double[] x = new double[1], y = new double[1];
                glfwGetCursorPos(w, x, y);
                cam.setDragging(action == GLFW_PRESS, x[0], y[0]);
            } else if (button == GLFW_MOUSE_BUTTON_RIGHT) {
                gravity[0] = action == GLFW_PRESS;
            }
        });
        glfwSetKeyCallback(window, (w, key, scancode, action, mods) -> {
            if (action != GLFW_PRESS) return;
            if (key == GLFW_KEY_G) {
                gravity[0] = !gravity[0];
                System.out.println("[INFO] Gravity turned " + (gravity[0] ? "ON" : "OFF"));
            } else if (key == GLFW_KEY_ESCAPE) {
                glfwSetWindowShouldClose(w, true);
            }
        });

        VulkanRenderer r = new VulkanRenderer(window);
        while (!glfwWindowShouldClose(window)) {
            glfwPollEvents();
            Sim.stepGravity(objs, gravity[0]);
            r.frame(cam, objs);
        }
        r.waitIdle();
        // shortcut: sem vkDestroy* explícito no encerramento, o processo termina e o SO recupera tudo (como no C++).
        glfwDestroyWindow(window);
        glfwTerminate();
    }
}
