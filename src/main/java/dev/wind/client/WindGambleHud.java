package dev.wind.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/** Gamble-HUD (aktuelles Gamble, Warteschlange, Profit), GEWONNEN/VERLOREN-Banner, Konfetti und Benachrichtigungen. */
public final class WindGambleHud {
    private WindGambleHud() {}

    private static long flashUntil;
    private static int flashColor = WindTheme.BLUE;

    private static String ovTitle = "", ovSub = "";
    private static int ovColor;
    private static long ovStart = -100000;
    private static final long OV_MS = 2800;

    private static final class P { float x, y, vx, vy, size; int color; }
    private static final List<P> parts = new ArrayList<>();
    private static final Random RND = new Random();
    private static long lastNano = System.nanoTime();

    public static void flash(int argb) {
        flashColor = argb;
        flashUntil = System.currentTimeMillis() + 1500;
    }

    public static void overlay(boolean win, String title, String sub) {
        ovTitle = title; ovSub = sub;
        ovColor = win ? WindTheme.GOOD : WindTheme.BAD;
        ovStart = System.currentTimeMillis();
        if (win && WindConfig.get().confetti) {
            int w = MinecraftClient.getInstance().getWindow().getScaledWidth();
            for (int i = 0; i < 110; i++) {
                P p = new P();
                p.x = RND.nextFloat() * w; p.y = -10 - RND.nextFloat() * 60;
                p.vx = (RND.nextFloat() - 0.5f) * 40; p.vy = 60 + RND.nextFloat() * 90;
                p.size = 2 + RND.nextInt(3);
                p.color = WindTheme.lerp(WindTheme.PURPLE, WindTheme.BLUE, RND.nextFloat());
                parts.add(p);
            }
        }
    }

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;
        WindConfig cfg = WindConfig.get();
        long nowN = System.nanoTime();
        float dt = Math.min(0.1f, (nowN - lastNano) / 1e9f);
        lastNano = nowN;
        if (cfg.gambleHud) renderPanel(ctx, tr, cfg);
        renderConfetti(ctx, mc, dt);
        renderOverlay(ctx, tr, mc);
        WindNotify.render(ctx, tr, mc);
    }

    private static void renderPanel(DrawContext ctx, TextRenderer tr, WindConfig cfg) {
        List<String[]> rows = new ArrayList<>();
        String cur = WindStats.pending.keySet().stream().findFirst().orElse(null);
        if (cur != null) {
            rows.add(new String[]{"Aktuell", cur});
            rows.add(new String[]{"Einsatz", WindStats.fmt(WindStats.pending.get(cur)) + "  →  " + WindStats.fmt(Math.round(WindStats.pending.get(cur) * cfg.winMultiplier))});
            rows.add(new String[]{"Wartet", WindStats.clock(WindStats.waitedSec(cur))});
            int n = 0;
            for (var e : WindStats.pending.entrySet()) {
                if (e.getKey().equals(cur)) continue;
                if (n++ >= 4) break;
                rows.add(new String[]{"  " + e.getKey(), WindStats.fmt(e.getValue())});
            }
            int more = WindStats.pending.size() - 1 - Math.min(4, WindStats.pending.size() - 1) + WindStats.queuedCount();
            if (more > 0) rows.add(new String[]{"  + " + more + " weitere", ""});
        } else {
            rows.add(new String[]{"Aktuell", "–"});
        }
        rows.add(new String[]{"Profit", WindStats.fmt(WindStats.profit())});
        rows.add(new String[]{"Winrate", String.format(Locale.ROOT, "%.1f%%  (W%d/L%d)", WindStats.winrate(), WindStats.wins, WindStats.losses)});

        String title = "Gamble";
        int w = tr.getWidth(title);
        for (String[] r : rows) w = Math.max(w, tr.getWidth(r[0]) + 14 + tr.getWidth(r[1]));
        int pad = 6, h = 16 + rows.size() * 11 + pad;
        float s = cfg.hudScale;

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(6, 6);
        ctx.getMatrices().scale(s, s);
        WindTheme.panel(ctx, 0, 0, w + pad * 2, h);
        WindTheme.gradientText(ctx, tr, title, pad, 6);
        int y = 18;
        for (String[] r : rows) {
            int vc = WindTheme.TEXT;
            if (r[0].equals("Profit")) vc = WindStats.profit() >= 0 ? WindTheme.GOOD : WindTheme.BAD;
            if (r[0].equals("Aktuell") && cur != null) vc = WindTheme.BLUE;
            ctx.drawTextWithShadow(tr, r[0], pad, y, WindTheme.MUTED);
            ctx.drawTextWithShadow(tr, r[1], w + pad - tr.getWidth(r[1]), y, vc);
            y += 11;
        }
        if (System.currentTimeMillis() < flashUntil)
            WindTheme.roundOutline(ctx, 0, 0, w + pad * 2, h, 6, WindTheme.alpha(flashColor, 0xFF));
        ctx.getMatrices().popMatrix();
    }

    private static void renderConfetti(DrawContext ctx, MinecraftClient mc, float dt) {
        if (parts.isEmpty()) return;
        int h = mc.getWindow().getScaledHeight();
        parts.removeIf(p -> p.y > h + 10);
        for (P p : parts) {
            p.x += p.vx * dt; p.y += p.vy * dt;
            int sz = (int) p.size;
            WindTheme.roundRectExact(ctx, (int) p.x, (int) p.y, sz, sz, sz / 2, p.color);
        }
    }

    private static void renderOverlay(DrawContext ctx, TextRenderer tr, MinecraftClient mc) {
        long el = System.currentTimeMillis() - ovStart;
        if (el < 0 || el > OV_MS) return;
        float t = el / (float) OV_MS;
        float a = t < 0.1f ? t / 0.1f : (t > 0.7f ? (1f - t) / 0.3f : 1f);
        float pop = t < 0.15f ? 0.6f + 0.4f * (t / 0.15f) : 1f;
        int alpha = (int) (255 * Math.max(0, Math.min(1, a)));
        if (alpha < 8) return;

        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int cy = sh / 4, bandH = 58;
        int pw = Math.min(sw - 20, Math.max(tr.getWidth(ovTitle) * 3 + 70, tr.getWidth(ovSub) + 60));
        int px = (sw - pw) / 2, py = cy - bandH / 2;
        WindTheme.panel(ctx, px, py, pw, bandH, Math.max(1, alpha * 9 / 10));
        WindTheme.roundOutline(ctx, px, py, pw, bandH, 12, WindTheme.alpha(ovColor, alpha));

        float scale = 3f * pop;
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(sw / 2f, cy - 14);
        ctx.getMatrices().scale(scale, scale);
        ctx.drawTextWithShadow(tr, ovTitle, -tr.getWidth(ovTitle) / 2, 0, WindTheme.alpha(ovColor, alpha));
        ctx.getMatrices().popMatrix();
        ctx.drawTextWithShadow(tr, ovSub, (sw - tr.getWidth(ovSub)) / 2, cy + 12, WindTheme.alpha(WindTheme.TEXT, alpha));
    }
}
