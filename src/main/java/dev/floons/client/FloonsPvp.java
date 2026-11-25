package dev.floons.client;

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.ActionResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayDeque;

/** PvP-Helfer: CPS, Reach, Item-Zaehler, Treffer-Marker/-Sound, Low-HP-Warnung, Cooldown-Balken. Alles nur Anzeige. */
public final class FloonsPvp {
    private static final ArrayDeque<Long> L = new ArrayDeque<>(), R = new ArrayDeque<>();
    private static boolean pl, pr;
    public static double reach = -1;
    private static long markerUntil;
    public static final int[] COUNTS = new int[6];
    public static final String[] NAMES = {"Totems", "G-Äpfel", "Perlen", "Kristalle", "Obsidian", "Tränke"};

    /** {Name, Sound-ID, Lautstaerke, Tonhoehe} */
    public static final Object[][] SOUNDS = {
        {"Ding", "entity.arrow.hit_player", 0.8f, 1.35f},
        {"Pling", "block.note_block.pling", 0.8f, 1.8f},
        {"Klick", "block.note_block.hat", 0.9f, 1.6f},
        {"Orb", "entity.experience_orb.pickup", 0.7f, 1.4f},
        {"Glocke", "block.note_block.bell", 0.8f, 1.6f},
        {"Kritisch", "entity.player.attack.crit", 0.8f, 1.2f},
        {"Schlag", "entity.player.attack.strong", 0.7f, 1.3f},
        {"Stoß", "entity.player.attack.knockback", 0.7f, 1.4f},
        {"Bogen", "entity.arrow.hit", 0.8f, 1.5f},
        {"Level", "entity.player.levelup", 0.5f, 1.8f},
        {"Harfe", "block.note_block.harp", 0.9f, 1.7f},
        {"Flöte", "block.note_block.flute", 0.9f, 1.8f},
        {"Gitarre", "block.note_block.guitar", 0.9f, 1.6f},
        {"Banjo", "block.note_block.banjo", 0.9f, 1.7f},
        {"Xylophon", "block.note_block.xylophone", 0.9f, 1.9f},
        {"Eisen-Xylo", "block.note_block.iron_xylophone", 0.9f, 1.7f},
        {"Kuhglocke", "block.note_block.cow_bell", 0.9f, 1.5f},
        {"Chime", "block.note_block.chime", 0.9f, 1.8f},
        {"Bass", "block.note_block.bass", 1.0f, 1.2f},
        {"Bit", "block.note_block.bit", 0.8f, 1.7f},
        {"Amethyst", "block.amethyst_block.chime", 0.9f, 1.5f},
        {"Amboss", "block.anvil.land", 0.35f, 1.8f},
        {"Dreizack", "item.trident.hit", 0.7f, 1.4f},
        {"Plop", "entity.item.pickup", 0.8f, 1.2f},
        {"Enderman", "entity.enderman.teleport", 0.5f, 1.6f},
        {"Glas", "block.glass.break", 0.5f, 1.8f},
    };

    public static void playHit() {
        Object[] s = SOUNDS[Math.floorMod(FloonsConfig.get().hitSoundIdx, SOUNDS.length)];
        FloonsGamble.playId((String) s[1], (Float) s[2], (Float) s[3]);
    }

    private FloonsPvp() {}

    public static void register() {
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (world.isClient() && player == mc.player && entity != null) {
                Vec3d eye = player.getEyePos();
                Box b = entity.getBoundingBox();
                Vec3d c = new Vec3d(clamp(eye.x, b.minX, b.maxX), clamp(eye.y, b.minY, b.maxY), clamp(eye.z, b.minZ, b.maxZ));
                reach = eye.distanceTo(c);
                long now = System.currentTimeMillis();
                markerUntil = now + 260;
                if (FloonsConfig.get().hitSound && !FloonsClient.off()) playHit();
            }
            return ActionResult.PASS;
        });
        ClientTickEvents.END_CLIENT_TICK.register(FloonsPvp::tick);
    }

    private static double clamp(double v, double lo, double hi) { return Math.max(lo, Math.min(hi, v)); }

    private static void tick(MinecraftClient mc) {
        if (mc.player == null) return;
        long now = System.currentTimeMillis();
        if (mc.currentScreen == null) {
            long win = mc.getWindow().getHandle();
            boolean l = GLFW.glfwGetMouseButton(win, 0) == GLFW.GLFW_PRESS, r = GLFW.glfwGetMouseButton(win, 1) == GLFW.GLFW_PRESS;
            if (l && !pl) L.add(now);
            if (r && !pr) R.add(now);
            pl = l; pr = r;
        } else { pl = false; pr = false; }
        while (!L.isEmpty() && now - L.peekFirst() > 1000) L.pollFirst();
        while (!R.isEmpty() && now - R.peekFirst() > 1000) R.pollFirst();
        if ((mc.world != null && (mc.world.getTime() % 5) == 0) && FloonsConfig.get().pvpItems) count(mc);
    }

    private static void count(MinecraftClient mc) {
        java.util.Arrays.fill(COUNTS, 0);
        var inv = mc.player.getInventory();
        for (int i = 0; i < inv.size(); i++) {
            ItemStack s = inv.getStack(i);
            if (s.isEmpty()) continue;
            Item it = s.getItem();
            int n = s.getCount();
            if (it == Items.TOTEM_OF_UNDYING) COUNTS[0] += n;
            else if (it == Items.GOLDEN_APPLE || it == Items.ENCHANTED_GOLDEN_APPLE) COUNTS[1] += n;
            else if (it == Items.ENDER_PEARL) COUNTS[2] += n;
            else if (it == Items.END_CRYSTAL) COUNTS[3] += n;
            else if (it == Items.OBSIDIAN) COUNTS[4] += n;
            else if (it == Items.SPLASH_POTION) COUNTS[5] += n;
        }
    }

    public static int cpsL() { return L.size(); }
    public static int cpsR() { return R.size(); }
    /** Treffer-Marker, Cooldown-Balken, Low-HP-Warnung. */
    public static void overlay(DrawContext ctx, MinecraftClient mc, FloonsConfig cfg) {
        if (mc.player == null || mc.options.hudHidden || mc.currentScreen != null) return;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        long now = System.currentTimeMillis();
        int cx = sw / 2, cy = sh / 2;

        if (cfg.hitMarker && now < markerUntil) {
            int a = (int) (255 * (markerUntil - now) / 260.0);
            int col = FloonsTheme.alpha(0xFFFFFFFF, Math.max(40, a));
            for (int d = 4; d <= 8; d++) {
                ctx.fill(cx - d, cy - d, cx - d + 1, cy - d + 1, col);
                ctx.fill(cx + d - 1, cy - d, cx + d, cy - d + 1, col);
                ctx.fill(cx - d, cy + d - 1, cx - d + 1, cy + d, col);
                ctx.fill(cx + d - 1, cy + d - 1, cx + d, cy + d, col);
            }
        }
        if (cfg.cooldownBar) {
            float p = mc.player.getAttackCooldownProgress(0f);
            if (p < 0.999f) {
                int w = 26, x = cx - w / 2, y = cy + 11;
                FloonsTheme.roundRectExact(ctx, x, y, w, 5, 2, FloonsTheme.bgA(0xC0));
                int fw = Math.max(3, (int) ((w - 2) * p));
                int col = FloonsTheme.lerp(FloonsTheme.PURPLE, FloonsTheme.BLUE, p);
                FloonsTheme.roundRectExact(ctx, x + 1, y + 1, fw, 3, 1, FloonsTheme.alpha(col, 0xFF));
                FloonsTheme.roundOutlineExact(ctx, x, y, w, 5, 2, FloonsTheme.alpha(FloonsTheme.lerp(FloonsTheme.PURPLE, FloonsTheme.BLUE, 0.5f), 0xA0));
            }
        }
        if (cfg.lowHpWarn) {
            float hp = mc.player.getHealth() + mc.player.getAbsorptionAmount();
            if (hp > 0 && hp <= 6f) {
                double pulse = 0.5 + 0.5 * Math.sin(now / 220.0);
                int a = (int) (0x28 + 0x40 * pulse * (1f - hp / 8f));
                int red = 0xFFDC2626;
                ctx.fillGradient(0, 0, sw, 46, FloonsTheme.alpha(red, a), 0x00DC2626);
                ctx.fillGradient(0, sh - 46, sw, sh, 0x00DC2626, FloonsTheme.alpha(red, a));
            }
        }
    }
}
