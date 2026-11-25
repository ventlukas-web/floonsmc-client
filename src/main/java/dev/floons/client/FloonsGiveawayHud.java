package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.List;

/**
 * Giveaway-HUD: Karte mit Logo, Preis, Zeitbalken, großer Teilnehmerzahl, Stichwort und Live-Beitritten.
 * Beim Ziehen laufen die Namen wie ein Glücksrad durch (Mitte oben), danach wird der Gewinner gezeigt.
 */
public final class FloonsGiveawayHud {
    private FloonsGiveawayHud() {}

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        FloonsConfig c = FloonsConfig.get();
        if (mc.options.hudHidden || !c.giveawayHud) return;
        if (FloonsClient.hideHud()) return;
        TextRenderer tr = mc.textRenderer;
        boolean showWinners = !FloonsStreamer.gwOn && FloonsStreamer.revealAt > 0
            && System.currentTimeMillis() - FloonsStreamer.revealAt < 15_000 && !FloonsStreamer.lastWinners.isEmpty();
        if (FloonsStreamer.gwOn || FloonsStreamer.drawing() || showWinners) card(ctx, tr, mc, c, showWinners);
        if (FloonsStreamer.drawing()) wheel(ctx, tr, mc);
    }

    private static void card(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig c, boolean showWinners) {
        long now = System.currentTimeMillis();
        boolean on = FloonsStreamer.gwOn;
        long left = FloonsStreamer.left();
        boolean urgent = on && left < 10_000;
        int pad = 7, w = 170;
        String prize = FloonsStreamer.prize;
        List<FloonsStreamer.Join> feed = FloonsStreamer.joinFeed;
        int feedN = on ? Math.min(5, feed.size()) : 0;
        int winN = showWinners ? Math.min(5, FloonsStreamer.lastWinners.size()) : 0;
        int h = pad + 12 + 4 + 12 + 10 + 24 + 12 + (feedN > 0 ? 14 + feedN * 10 : 0) + (winN > 0 ? 14 + winN * 11 : 0) + pad - 4;
        int bw = w + pad * 2;
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        float s = c.hudScale;
        float[] pos = FloonsHud.place("giveaway", sw - bw * s - 6, sh * 0.16f, bw * s, h * s, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(s, s);

        FloonsTheme.panel(ctx, 0, 0, bw, h);
        // pulsierender Rand
        float pulse = (float) (0.5 + 0.5 * Math.sin(now / 300.0));
        int edge = urgent ? FloonsTheme.BAD : FloonsTheme.lerp(FloonsTheme.PURPLE, FloonsTheme.BLUE, pulse);
        FloonsTheme.roundOutline(ctx, 0, 0, bw, h, 6, FloonsTheme.alpha(edge, 0xC0));

        FloonsLogo.draw(ctx, pad, pad, 10);
        FloonsTheme.gradientText(ctx, tr, "GIVEAWAY", pad + 14, pad + 1);
        String time = on ? FloonsAuction.mmss(left) : FloonsStreamer.drawing() ? "ZIEHE …" : "ENDE";
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pad + w, pad - 1);
        ctx.getMatrices().scale(1.5f, 1.5f);
        ctx.drawTextWithShadow(tr, time, -tr.getWidth(time), 0, urgent ? FloonsTheme.BAD : FloonsTheme.TEXT);
        ctx.getMatrices().popMatrix();

        int y = pad + 16;
        ctx.drawTextWithShadow(tr, tr.getWidth(prize) > w ? tr.trimToWidth(prize, w - 6) + "…" : prize, pad, y, FloonsTheme.BLUE);
        y += 12;
        float frac = on && FloonsStreamer.gwDuration > 0 ? Math.min(1f, left / (float) FloonsStreamer.gwDuration) : 0f;
        FloonsTheme.roundRectExact(ctx, pad, y, w, 4, 2, FloonsTheme.alpha(0xFFFFFFFF, 0x30));
        if (frac > 0) FloonsTheme.roundRectExact(ctx, pad, y, Math.max(4, Math.round(w * frac)), 4, 2,
            urgent ? FloonsTheme.BAD : FloonsTheme.lerp(FloonsTheme.BLUE, FloonsTheme.PURPLE, 1f - frac));
        y += 10;

        // grosse Teilnehmerzahl
        String cnt = String.valueOf(on ? FloonsStreamer.entrants.size() : FloonsStreamer.drawPool().size());
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pad, y);
        ctx.getMatrices().scale(2f, 2f);
        ctx.drawTextWithShadow(tr, cnt, 0, 0, FloonsTheme.GOOD);
        ctx.getMatrices().popMatrix();
        ctx.drawTextWithShadow(tr, "Teilnehmer", pad + tr.getWidth(cnt) * 2 + 6, y + 5, FloonsTheme.MUTED);
        String win = FloonsStreamer.winnersWanted > 1 ? FloonsStreamer.winnersWanted + " Gewinner" : "1 Gewinner";
        ctx.drawTextWithShadow(tr, win, pad + w - tr.getWidth(win), y + 5, FloonsTheme.MUTED);
        y += 22;
        String kw = "Schreib »" + c.giveawayKeyword + "«" + (c.giveawayTwitch && c.twitch ? " (Chat + Twitch)" : " in den Chat");
        ctx.drawTextWithShadow(tr, tr.trimToWidth(kw, w), pad, y, FloonsTheme.TEXT);
        y += 12;

        if (feedN > 0) {
            ctx.fill(pad, y, pad + w, y + 1, FloonsTheme.alpha(0xFFFFFFFF, 0x30));
            y += 4;
            ctx.drawTextWithShadow(tr, "Neu dabei", pad, y, FloonsTheme.MUTED);
            y += 10;
            for (int i = 0; i < feedN; i++) {
                FloonsStreamer.Join j = feed.get(i);
                String ago = ((now - j.at()) / 1000) + "s";
                ctx.drawTextWithShadow(tr, tr.trimToWidth(j.name(), w - 30), pad, y, j.twitch() ? FloonsTheme.PURPLE : FloonsTheme.TEXT);
                ctx.drawTextWithShadow(tr, ago, pad + w - tr.getWidth(ago), y, FloonsTheme.MUTED);
                y += 10;
            }
        }
        if (winN > 0) {
            ctx.fill(pad, y, pad + w, y + 1, FloonsTheme.alpha(0xFFFFFFFF, 0x30));
            y += 4;
            FloonsTheme.gradientText(ctx, tr, "Gewinner", pad, y);
            y += 10;
            for (int i = 0; i < winN; i++) {
                String n = FloonsStreamer.display(FloonsStreamer.lastWinners.get(FloonsStreamer.lastWinners.size() - winN + i));
                ctx.drawTextWithShadow(tr, "★ " + n, pad, y, FloonsTheme.GOOD);
                y += 11;
            }
        }
        ctx.getMatrices().popMatrix();
    }

    private static int lastTick = -1;

    /** Echtes Glücksrad in der Bildschirmmitte: dreht sich, wird langsamer und bleibt beim Gewinner unter dem Zeiger stehen. */
    private static void wheel(DrawContext ctx, TextRenderer tr, MinecraftClient mc) {
        List<String> slots = FloonsStreamer.wheelSlots;
        int n = slots.size();
        if (n == 0) return;
        long el = System.currentTimeMillis() - FloonsStreamer.drawStart;
        double t = Math.min(1.0, el / (double) FloonsStreamer.SPIN_MS);
        double ease = 1 - Math.pow(1 - t, 4);                 // stark abbremsen
        double seg = Math.PI * 2 / n;
        double pointer = -Math.PI / 2;                          // Zeiger oben
        double endTheta = pointer - (FloonsStreamer.wheelWin + 0.5) * seg - Math.PI * 2 * FloonsStreamer.wheelTurns;
        double theta = endTheta * ease;                         // von 0 bis zum Endwinkel
        boolean stopped = t >= 1.0;

        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        int R = (int) Math.max(48, Math.min(100, Math.min(sw, sh) * 0.30));
        int cx = sw / 2, cy = sh / 2 - 6;

        // Hintergrund-Glow
        FloonsTheme.roundRectExact(ctx, cx - R - 14, cy - R - 26, 2 * R + 28, 2 * R + 52, 16, FloonsTheme.alpha(0xFF03060D, 0xC8));
        FloonsTheme.roundOutlineExact(ctx, cx - R - 14, cy - R - 26, 2 * R + 28, 2 * R + 52, 16, FloonsTheme.alpha(FloonsTheme.BLUE, 0x90));

        // Segmente per Scanline zeichnen (Laeufe gleicher Farbe zusammengefasst)
        int step = R > 70 ? 2 : 1;
        for (int yy = -R; yy <= R; yy += step) {
            int runStart = Integer.MIN_VALUE, runColor = 0;
            for (int xx = -R; xx <= R + step; xx += step) {
                int col = 0;
                double d2 = (double) xx * xx + (double) yy * yy;
                if (xx <= R && d2 <= (double) R * R) {
                    double d = Math.sqrt(d2);
                    if (d >= R - 3) col = 0xFFF2F7FF;                         // Rand
                    else {
                        double a = Math.atan2(yy, xx) - theta;
                        int idx = (int) Math.floor(((a % (Math.PI * 2)) + Math.PI * 2) % (Math.PI * 2) / seg) % n;
                        col = segColor(idx, n);
                        boolean win = stopped && idx == FloonsStreamer.wheelWin;
                        if (win) col = FloonsTheme.lerp(col, 0xFFFFFFFF, 0.25f + 0.15f * (float) Math.sin(System.currentTimeMillis() / 120.0));
                    }
                }
                if (col != runColor) {
                    if (runColor != 0) ctx.fill(cx + runStart, cy + yy, cx + xx, cy + yy + step, runColor);
                    runStart = xx; runColor = col;
                }
            }
        }
        // Namen entlang der Segmente
        float textScale = n > 16 ? 0.6f : n > 10 ? 0.75f : 0.9f;
        int maxLen = (int) ((R - 26) / textScale);
        for (int i = 0; i < n; i++) {
            double ang = theta + (i + 0.5) * seg;
            String name = FloonsStreamer.display(slots.get(i));
            if (tr.getWidth(name) > maxLen) name = tr.trimToWidth(name, maxLen - 4) + "…";
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(cx, cy);
            ctx.getMatrices().rotate((float) ang);
            ctx.getMatrices().translate(20, 0);
            ctx.getMatrices().scale(textScale, textScale);
            ctx.drawTextWithShadow(tr, name, 0, -4, 0xFFFFFFFF);
            ctx.getMatrices().popMatrix();
        }
        // Nabe mit Logo
        FloonsTheme.roundRectExact(ctx, cx - 11, cy - 11, 22, 22, 11, 0xFF03060D);
        FloonsTheme.roundOutlineExact(ctx, cx - 11, cy - 11, 22, 22, 11, 0xFFF2F7FF);
        FloonsLogo.draw(ctx, cx - 7, cy - 7, 14);
        // Zeiger oben (Dreieck nach unten)
        int py = cy - R - 9;
        for (int k = 0; k < 10; k++) ctx.fill(cx - (10 - k), py + k, cx + (10 - k), py + k + 1, k == 0 ? 0xFFFFFFFF : FloonsTheme.BAD);
        // Titel + Ergebnis
        String title = "GIVEAWAY  ·  " + FloonsStreamer.prize;
        title = tr.getWidth(title) > 2 * R + 20 ? tr.trimToWidth(title, 2 * R + 14) + "…" : title;
        FloonsTheme.gradientText(ctx, tr, title, cx - tr.getWidth(title) / 2, cy - R - 22);
        if (stopped) {
            String w = FloonsStreamer.display(slots.get(FloonsStreamer.wheelWin)) + " gewinnt!";
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(cx, cy + R + 8);
            ctx.getMatrices().scale(1.4f, 1.4f);
            ctx.drawTextWithShadow(tr, w, -tr.getWidth(w) / 2, 0, FloonsTheme.GOOD);
            ctx.getMatrices().popMatrix();
        } else {
            String s = FloonsStreamer.drawPool().size() + " Teilnehmer";
            ctx.drawTextWithShadow(tr, s, cx - tr.getWidth(s) / 2, cy + R + 9, FloonsTheme.MUTED);
        }
        // Tick-Ton beim Ueberqueren einer Segmentgrenze unter dem Zeiger
        int under = (int) Math.floor(((pointer - theta) % (Math.PI * 2) + Math.PI * 2) % (Math.PI * 2) / seg);
        if (!stopped && under != lastTick) {
            lastTick = under;
            if (FloonsConfig.get().sound) FloonsGamble.playId("block.note_block.hat", 0.7f, 1.0f + (float) t * 0.8f);
        }
        if (stopped && lastTick != -2) {
            lastTick = -2;
            if (FloonsConfig.get().sound) FloonsGamble.playId("block.note_block.bell", 1f, 1.2f);
        }
    }

    private static int segColor(int i, int n) {
        int a = FloonsTheme.PURPLE, b = FloonsTheme.BLUE;
        int base = switch (i % 4) {
            case 0 -> a;
            case 1 -> b;
            case 2 -> FloonsTheme.lerp(a, b, 0.5f);
            default -> FloonsTheme.lerp(b, 0xFF03060D, 0.35f);
        };
        if (n % 4 == 1 && i == n - 1) base = FloonsTheme.lerp(a, 0xFFFFFFFF, 0.3f); // letztes Feld nicht gleich wie das erste
        return 0xFF000000 | (FloonsTheme.lerp(base, 0xFF03060D, (i % 2) * 0.12f) & 0xFFFFFF);
    }
}
