package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.particle.DustColorTransitionParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;

/** Effekte in Floons-Farben: Luftpartikel um dich, Jumpringe beim Abspringen, Block-Kontur. Reine Client-Partikel. */
public final class FloonsFx {
    private FloonsFx() {}

    private static boolean jumpPrevGround = true;
    private static int jumpRing = -1;
    private static Vec3d jumpPos = Vec3d.ZERO;

    static ParticleEffect effect(float size) {
        // Uebergang Lila -> Blau (Floons-Farben); Farbe ohne Alpha
        return new DustColorTransitionParticleEffect(FloonsTheme.PURPLE & 0xFFFFFF, FloonsTheme.BLUE & 0xFFFFFF, size);
    }

    public static void tick(MinecraftClient mc) {
        FloonsConfig cfg = FloonsConfig.get();
        if ((!cfg.fxOrbs && !cfg.fxJump && !cfg.blockOutline) || mc.player == null || mc.world == null || mc.isPaused()) { return; }
        var p = mc.player;
        Vec3d pos = new Vec3d(p.getX(), p.getY(), p.getZ());
        long tk = mc.world.getTime();

        // Luft-Partikel (Ambience): viele weiche Lichtpunkte in verschiedenen Groessen treiben in der Luft um dich, dazu Glitzer
        if (cfg.fxOrbs) {
            int dens = Math.max(1, Math.min(3, cfg.airDensity));
            int n = dens * 2;
            for (int i = 0; i < n; i++) {
                double ang = Math.random() * Math.PI * 2, dist = 1.5 + Math.random() * (7.0 + dens * 3.0);
                double sz = Math.random() < 0.18 ? 2.6 + Math.random() * 1.2 : 0.8 + Math.random() * 1.4; // wenige grosse "Bokeh"-Punkte
                mc.particleManager.addParticle(effect((float) sz), pos.x + Math.cos(ang) * dist, pos.y - 0.5 + Math.random() * 5.5, pos.z + Math.sin(ang) * dist,
                    (Math.random() - 0.5) * 0.02, 0.01 + Math.random() * 0.025, (Math.random() - 0.5) * 0.02);
            }
            if (tk % 4 == 0) for (int i = 0; i < dens; i++) {
                double ang = Math.random() * Math.PI * 2, dist = 1.0 + Math.random() * 9.0;
                mc.particleManager.addParticle(net.minecraft.particle.ParticleTypes.END_ROD, pos.x + Math.cos(ang) * dist, pos.y + Math.random() * 4.0, pos.z + Math.sin(ang) * dist,
                    0, 0.01, 0);
            }
        }
        // Jump Circles: Ring aus Licht am Boden, wo du abspringst
        boolean gnd = p.isOnGround();
        if (cfg.fxJump && jumpPrevGround && !gnd && p.getVelocity().y > 0.05) { jumpRing = 0; jumpPos = pos; }
        jumpPrevGround = gnd;
        if (jumpRing >= 0) {
            if (!cfg.fxJump) jumpRing = -1;
            else {
                double r = 0.4 + jumpRing * 0.2;
                for (int i = 0; i < 24; i++) {
                    double a3 = i * Math.PI * 2 / 24;
                    mc.particleManager.addParticle(effect(1.1f), jumpPos.x + Math.cos(a3) * r, jumpPos.y + 0.05, jumpPos.z + Math.sin(a3) * r, 0, 0.005, 0);
                }
                if (++jumpRing > 9) jumpRing = -1;
            }
        }
        // Block-Kontur: lila Partikel entlang der 12 Kanten des anvisierten Blocks
        if (cfg.blockOutline && tk % 2 == 0 && mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK) {
            var bp = bhr.getBlockPos();
            for (int a = 0; a < 2; a++) for (int b = 0; b < 2; b++) for (int s = 0; s <= 4; s++) {
                double u = s / 4.0;
                mc.particleManager.addParticle(effect(0.45f), bp.getX() + u, bp.getY() + a, bp.getZ() + b, 0, 0, 0);
                mc.particleManager.addParticle(effect(0.45f), bp.getX() + a, bp.getY() + u, bp.getZ() + b, 0, 0, 0);
                mc.particleManager.addParticle(effect(0.45f), bp.getX() + a, bp.getY() + b, bp.getZ() + u, 0, 0, 0);
            }
        }
    }
}
