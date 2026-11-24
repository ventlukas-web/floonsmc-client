package dev.floons.client;

/** Treffer-Zaehler fuer das Combo-HUD. */
public final class FloonsMisc {
    private FloonsMisc() {}
    public static int combo, maxCombo, hits;
    public static long lastHit;

    public static void register() {
        net.fabricmc.fabric.api.event.player.AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (entity instanceof net.minecraft.entity.LivingEntity && world.isClient()) {
                long now = System.currentTimeMillis();
                if (now - lastHit > 2500) combo = 0;
                combo++; hits++; lastHit = now;
                maxCombo = Math.max(maxCombo, combo);
            }
            return net.minecraft.util.ActionResult.PASS;
        });
    }
}
