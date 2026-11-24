package dev.floons.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import org.lwjgl.glfw.GLFW;

import java.util.Map;

/** Im offenen Chat lassen sich alle HUD-Elemente mit gedrückter linker Maustaste frei verschieben. */
public final class FloonsChatDrag {
    private FloonsChatDrag() {}

    private static String dragId;
    private static float grabDx, grabDy;
    private static boolean wasDown;

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof ChatScreen)) return;
            dragId = null; wasDown = true; // Klick, der den Chat geoeffnet hat, nicht als Griff werten
            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                if (!FloonsConfig.get().chatDrag) return;
                MinecraftClient mc = MinecraftClient.getInstance();
                boolean down = GLFW.glfwGetMouseButton(mc.getWindow().getHandle(), GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
                FloonsConfig cfg = FloonsConfig.get();
                int width = s.width, height = s.height;
                if (down && !wasDown) {
                    String hit = null;
                    for (Map.Entry<String, float[]> e : FloonsHud.BOUNDS.entrySet()) {
                        float[] b = e.getValue();
                        if (mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3]) {
                            hit = e.getKey(); grabDx = mx - b[0]; grabDy = my - b[1];
                        }
                    }
                    dragId = hit;
                } else if (down && dragId != null) {
                    float[] b = FloonsHud.BOUNDS.get(dragId);
                    if (b != null) {
                        float nx = Math.max(0, Math.min(width - b[2], mx - grabDx));
                        float ny = Math.max(0, Math.min(height - b[3], my - grabDy));
                        cfg.hudPos.put(dragId, new float[]{nx / width, ny / height});
                    }
                } else if (!down && wasDown && dragId != null) {
                    cfg.save();
                    dragId = null;
                }
                wasDown = down;
                // dezenter Rahmen um das Element unter der Maus bzw. das gezogene
                for (Map.Entry<String, float[]> e : FloonsHud.BOUNDS.entrySet()) {
                    float[] b = e.getValue();
                    boolean active = e.getKey().equals(dragId);
                    boolean hov = mx >= b[0] && mx <= b[0] + b[2] && my >= b[1] && my <= b[1] + b[3];
                    if (!active && !hov) continue;
                    FloonsTheme.roundOutline(ctx, (int) b[0] - 2, (int) b[1] - 2, (int) b[2] + 4, (int) b[3] + 4, 6,
                        active ? FloonsTheme.TEXT : FloonsTheme.BLUE);
                }
            });
        });
    }
}
