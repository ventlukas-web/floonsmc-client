package dev.floons.client;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.text.Text;

/** Hauptmenü: großer Beitreten-Button (Name/IP im Mod-Menü, Reiter Server), Mod-Menü-Knopf, Vanilla-Buttons im Floons-Design. */
public final class FloonsTitle {
    private FloonsTitle() {}

    private static ButtonWidget joinBtn;
    public static final String TIKTOK = "https://www.tiktok.com/@floonsmc";
    private static final java.util.Map<Object, Float> HOVER = new java.util.WeakHashMap<>();

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof TitleScreen)) return;
            joinBtn = null;
            FloonsConfig c = FloonsConfig.get();
            var vanilla = new java.util.ArrayList<>(Screens.getButtons(screen));
            int minY = screen.height, maxY = 0;
            for (var vb : vanilla) { minY = Math.min(minY, vb.getY()); maxY = Math.max(maxY, vb.getY() + vb.getHeight()); }
            if (vanilla.isEmpty()) { minY = screen.height / 4 + 48; maxY = minY; }
            if (c.joinButton) {
                int bw = Math.min(220, screen.width - 20), bh = 26, gap = 5;
                int top = 78;                                    // unter Minecraft-Logo + Spruch
                int by = minY - bh - gap;
                if (by < top) {
                    int need = top - by;
                    int free = Math.max(0, (screen.height - 58) - maxY); // Platz bis zu den Knoepfen unten rechts
                    int shift = Math.min(need, free);
                    if (shift > 0) for (var vb : vanilla) vb.setY(vb.getY() + shift);
                    minY += shift; maxY += shift;
                    int rest = need - shift;
                    bh = Math.max(20, bh - rest);                // notfalls etwas flacher
                    by = Math.max(4, minY - bh - gap);
                }
                joinBtn = ButtonWidget.builder(Text.literal(joinLabel()), b -> joinServer(client, screen))
                    .dimensions(screen.width / 2 - bw / 2, by, bw, bh).build();
                Screens.getButtons(screen).add(joinBtn);
            }
            // Floons Menue + TikTok: unten rechts, aber nie ueber anderen Buttons
            var taken = new java.util.ArrayList<int[]>();
            for (var vb : Screens.getButtons(screen)) taken.add(new int[]{vb.getX(), vb.getY(), vb.getWidth(), vb.getHeight()});
            int ew = 110;
            int[][] spots = {
                {screen.width - ew - 8, screen.height - 54, screen.width - ew - 8, screen.height - 30},   // unten rechts
                {8, screen.height - 54, 8, screen.height - 30},                                           // unten links
                {8, 6, 8, 30},                                                                            // oben links
            };
            int[] pick = spots[spots.length - 1];
            for (int[] sp : spots) {
                if (!hits(taken, sp[0], sp[1], ew, 20) && !hits(taken, sp[2], sp[3], ew, 20)) { pick = sp; break; }
            }
            Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("Floons Menü"), b -> client.setScreen(new FloonsMenu(screen)))
                .dimensions(pick[0], pick[1], ew, 20).build());
            Screens.getButtons(screen).add(ButtonWidget.builder(Text.literal("TikTok @floonsmc"), b -> {
                try { net.minecraft.util.Util.getOperatingSystem().open(TIKTOK); } catch (Throwable ignored) { }
            }).dimensions(pick[2], pick[3], ew, 20).build());
        });
    }

    /** Ueberschneidet das Rechteck (mit 2 px Abstand) einen vorhandenen Button? */
    private static boolean hits(java.util.List<int[]> taken, int x, int y, int w, int h) {
        for (int[] r : taken)
            if (x < r[0] + r[2] + 2 && x + w + 2 > r[0] && y < r[1] + r[3] + 2 && y + h + 2 > r[1]) return true;
        return false;
    }

    public static String joinLabel() {
        FloonsConfig c = FloonsConfig.get();
        String nm = c.serverName == null || c.serverName.isBlank() ? "Server" : c.serverName.trim();
        return nm + " beitreten";
    }

    /** Tritt dem eingetragenen Server bei (Standard: WindSMP / windsmp.net). */
    public static void joinServer(MinecraftClient mc, net.minecraft.client.gui.screen.Screen from) {
        FloonsConfig cfg = FloonsConfig.get();
        String ip = cfg.serverIp == null || cfg.serverIp.isBlank() ? "windsmp.net" : cfg.serverIp.trim();
        String name = cfg.serverName == null || cfg.serverName.isBlank() ? "Server" : cfg.serverName.trim();
        net.minecraft.client.network.ServerAddress addr = net.minecraft.client.network.ServerAddress.parse(ip);
        net.minecraft.client.network.ServerInfo info = new net.minecraft.client.network.ServerInfo(name, ip, net.minecraft.client.network.ServerInfo.ServerType.OTHER);
        net.minecraft.client.gui.screen.multiplayer.ConnectScreen.connect(from, mc, addr, info, false, null);
    }

    /** Vanilla-Button im Floons-Design (vom Mixin aufgerufen). Der Beitreten-Button bekommt eine Verlaufs-Pille mit Logo. */
    public static void styledButton(DrawContext ctx, MinecraftClient mc, ClickableWidget b, int mx, int my) {
        if (!b.visible) return;
        boolean hov = b.active && b.isMouseOver(mx, my);
        float cur = HOVER.getOrDefault(b, 0f);
        cur += ((hov ? 1f : 0f) - cur) * 0.25f;
        HOVER.put(b, cur);
        int x = b.getX(), y = b.getY(), w = b.getWidth(), h = b.getHeight();
        String label = b.getMessage().getString();
        int textX = 0;
        if (b == joinBtn) {
            int cA = FloonsTheme.PURPLE, cB = FloonsTheme.BLUE;
            int[] st = {FloonsTheme.lerp(cA, 0xFFFFFFFF, 0.10f), FloonsTheme.lerp(cA, cB, 0.33f), FloonsTheme.lerp(cA, cB, 0.66f), FloonsTheme.lerp(cB, 0xFFFFFFFF, 0.10f)};
            if (cur > 0.05f) for (int i = 0; i < st.length; i++) st[i] = FloonsTheme.lerp(st[i], 0xFFFFFFFF, 0.18f * cur);
            FloonsTheme.roundRectExact(ctx, x, y, h, h, h / 2, st[0]);
            FloonsTheme.roundRectExact(ctx, x + w - h, y, h, h, h / 2, st[3]);
            int bx = x + h / 2, bw2 = w - h, seg = bw2 / 3;
            for (int i = 0; i < 3; i++) {
                int sx = bx + i * seg, sw = i == 2 ? bw2 - 2 * seg : seg;
                FloonsTheme.hGradient(ctx, sx, y, sw, h, st[i], st[i + 1]);
            }
            // wandernder Glanz
            int gw = Math.max(24, bw2 / 5);
            int gx = bx + (int) (((System.currentTimeMillis() % 2600) / 2600.0) * (bw2 + gw)) - gw;
            int g0 = Math.max(bx, gx), g1 = Math.min(bx + bw2, gx + gw);
            if (g1 > g0) FloonsTheme.hGradient(ctx, g0, y + 2, g1 - g0, h - 4, FloonsTheme.alpha(0xFFFFFFFF, 0x22), FloonsTheme.alpha(0xFFFFFFFF, 0x22));
            FloonsTheme.roundOutlineExact(ctx, x, y, w, h, h / 2, FloonsTheme.alpha(0xFFFFFFFF, 0xB0));
            FloonsLogo.draw(ctx, x + h / 2 - 2, y + (h - 12) / 2, 12);
            label = joinLabel();
            textX = 6;
        } else {
            // Floons-Pille: dunkel-durchsichtig, Rand in den Theme-Farben
            // fast schwarz, beim Drueberfahren leicht in den Theme-Farben
            int base = FloonsTheme.lerp(0xFF03060D, FloonsTheme.PURPLE, 0.06f + 0.24f * cur);
            FloonsTheme.roundRectExact(ctx, x, y, w, h, h / 2, FloonsTheme.alpha(base, b.active ? 0xEC : 0xA0));
            FloonsTheme.roundOutlineExact(ctx, x, y, w, h, h / 2,
                FloonsTheme.alpha(FloonsTheme.lerp(FloonsTheme.PURPLE, FloonsTheme.BLUE, cur), b.active ? 0x70 + (int) (0x70 * cur) : 0x40));
        }
        int maxW = w - 10 - textX * 2;
        String t = label;
        if (mc.textRenderer.getWidth(t) > maxW) { while (t.length() > 1 && mc.textRenderer.getWidth(t + "…") > maxW) t = t.substring(0, t.length() - 1); t = t + "…"; }
        int tw = mc.textRenderer.getWidth(t);
        ctx.drawTextWithShadow(mc.textRenderer, t, x + textX + (w - tw) / 2, y + (h - 8) / 2, b.active ? FloonsTheme.TEXT : 0xFF8A93A8);
    }
}
