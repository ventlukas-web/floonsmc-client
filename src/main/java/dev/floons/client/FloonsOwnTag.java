package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.util.math.Vec3d;

/**
 * Eigener Nametag in der 3. Person. Der Mixin-Weg haengt an Minecraft-Methodennamen; zusaetzlich zeichnet dieser Fallback den Namen selbst
 * ueber dem Kopf (Welt-zu-Bildschirm-Projektion), sobald der Mixin nicht gegriffen hat.
 */
public final class FloonsOwnTag {
    private FloonsOwnTag() {}

    /** Wird vom Mixin gesetzt, sobald er selbst einen Nametag erzeugt hat. */
    public static volatile long mixinAt = 0;

    public static void render(DrawContext ctx, MinecraftClient mc, FloonsConfig cfg) {
        if (!cfg.ownNametag || mc.player == null || mc.options.hudHidden) return;
        if (mc.options.getPerspective().isFirstPerson()) return;
        if (System.currentTimeMillis() - mixinAt < 1500) return;   // Vanilla-Nametag laeuft, nichts doppelt zeichnen
        var cam = mc.gameRenderer.getCamera();
        Vec3d c = cam.getCameraPos();
        Vec3d p = mc.player.getLerpedPos(mc.getRenderTickCounter().getTickProgress(false)).add(0, mc.player.getHeight() + 0.55, 0);
        Vec3d v = p.subtract(c);
        double yaw = Math.toRadians(cam.getYaw()), pit = Math.toRadians(cam.getPitch());
        double fx = -Math.sin(yaw) * Math.cos(pit), fy = -Math.sin(pit), fz = Math.cos(yaw) * Math.cos(pit);
        double rx = -Math.cos(yaw), ry = 0, rz = -Math.sin(yaw);
        double ux = ry * fz - rz * fy, uy = rz * fx - rx * fz, uz = rx * fy - ry * fx;   // up = right x forward
        double d = v.x * fx + v.y * fy + v.z * fz;
        if (d < 0.1) return;
        double fov = Math.toRadians(mc.options.getFov().getValue());
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        double k = (sh / 2.0) / Math.tan(fov / 2.0) / d;
        int x = (int) (sw / 2.0 + (v.x * rx + v.y * ry + v.z * rz) * k);
        int y = (int) (sh / 2.0 - (v.x * ux + v.y * uy + v.z * uz) * k);
        TextRenderer tr = mc.textRenderer;
        String name = mc.player.getName().getString();
        int w = tr.getWidth(name);
        FloonsTheme.roundRectExact(ctx, x - w / 2 - 4, y - 5, w + 8, 12, 6, 0x66000000);
        ctx.drawTextWithShadow(tr, name, x - w / 2, y - 3, 0xFFFFFFFF);
    }
}
