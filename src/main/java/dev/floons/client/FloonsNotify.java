package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Info-Pillen unten rechts: jede neue Info fliegt von rechts herein, bleibt kurz stehen und fliegt wieder raus.
 * Wird ueber dem Spiel und ueber allen Menues gezeichnet.
 */
public final class FloonsNotify {
    private record T(String title, String msg, long at, int color) {}
    private static final List<T> LIST = new ArrayList<>();
    private static final long LIFE = 4200, IN = 320, OUT = 280;

    private FloonsNotify() {}

    public static synchronized void push(String title, String msg, int color) {
        LIST.add(new T(title, msg == null ? "" : msg, System.currentTimeMillis(), color));
        while (LIST.size() > 6) LIST.remove(0);
    }

    private static float easeOut(float t) { t = Math.max(0, Math.min(1, t)); return 1 - (1 - t) * (1 - t) * (1 - t); }

    public static synchronized void render(DrawContext ctx, TextRenderer tr, MinecraftClient mc) {
        long now = System.currentTimeMillis();
        LIST.removeIf(t -> now - t.at() > LIFE);
        if (LIST.isEmpty()) return;
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int h = 22;
        float y = sh - 8;
        for (int i = LIST.size() - 1; i >= 0; i--) {
            T t = LIST.get(i);
            long el = now - t.at();
            String m = tr.trimToWidth(t.msg(), 220);
            String ti = t.title();
            boolean two = !m.isEmpty() && !m.equals(ti);
            int tw = two ? tr.getWidth(ti) + 8 + tr.getWidth(m) : tr.getWidth(ti);
            int w = 30 + tw + 12;
            float in = easeOut(el / (float) IN);
            float out = el > LIFE - OUT ? easeOut((LIFE - el) / (float) OUT) : 1f;
            float vis = Math.min(in, out);
            int x = Math.round(sw + 4 - (w + 10) * vis);
            int yy = Math.round(y - h);
            int a = Math.max(16, Math.round(255 * vis));
            // Pille: voll rund, dunkel, Rand in der Info-Farbe
            FloonsTheme.roundRectExact(ctx, x, yy, w, h, h / 2, FloonsTheme.alpha(0xFF0A111A, Math.min(0xE8, a)));
            FloonsTheme.roundRectExact(ctx, x + 1, yy + 1, w - 2, h / 2 - 1, h / 2 - 1, FloonsTheme.alpha(0xFFFFFFFF, Math.min(0x14, a / 12)));
            FloonsTheme.roundOutlineExact(ctx, x, yy, w, h, h / 2, FloonsTheme.alpha(t.color(), Math.min(0xD0, a)));
            // Fortschritt der Anzeigezeit als duenner Strich unten
            float life = Math.min(1f, el / (float) LIFE);
            int bw = Math.max(0, Math.round((w - h) * (1f - life)));
            if (bw > 0) ctx.fill(x + h / 2, yy + h - 2, x + h / 2 + bw, yy + h - 1, FloonsTheme.alpha(t.color(), Math.min(0x90, a)));
            FloonsLogo.draw(ctx, x + 6, yy + 6, 10);
            ctx.drawTextWithShadow(tr, ti, x + 22, yy + 7, FloonsTheme.alpha(t.color(), a));
            if (two) ctx.drawTextWithShadow(tr, m, x + 22 + tr.getWidth(ti) + 8, yy + 7, FloonsTheme.alpha(FloonsTheme.TEXT, a));
            y -= (h + 4) * Math.max(0.3f, vis);
        }
    }
}
