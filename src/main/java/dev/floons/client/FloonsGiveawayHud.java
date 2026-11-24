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

    /** Glücksrad: Namen laufen durch und werden langsamer, oben in der Mitte. */
    private static void wheel(DrawContext ctx, TextRenderer tr, MinecraftClient mc) {
        List<String> pool = FloonsStreamer.drawPool();
        if (pool.isEmpty()) return;
        long el = System.currentTimeMillis() - FloonsStreamer.drawStart;
        float t = Math.min(1f, el / (float) FloonsStreamer.DRAW_MS);
        // Position wird mit der Zeit langsamer (ease-out)
        double pos = (1 - Math.pow(1 - t, 3)) * 40;
        int off = Math.floorMod(FloonsStreamer.wheelTarget() - 40, pool.size());
        int idx = Math.floorMod((int) Math.floor(pos) + off, pool.size());
        float sub = (float) (pos - Math.floor(pos));
        int sw = mc.getWindow().getScaledWidth();
        int cx = sw / 2, cy = 52;
        int bw = 200, bh = 46;
        FloonsTheme.panel(ctx, cx - bw / 2, cy - bh / 2, bw, bh);
        FloonsTheme.roundOutline(ctx, cx - bw / 2, cy - bh / 2, bw, bh, 8, FloonsTheme.alpha(FloonsTheme.BLUE, 0xD0));
        for (int k = -1; k <= 1; k++) {
            String n = FloonsStreamer.display(pool.get(Math.floorMod(idx + k, pool.size())));
            float yy = cy - 4 + (k - sub) * 14;
            boolean mid = k == 0;
            int col = mid ? FloonsTheme.TEXT : FloonsTheme.alpha(FloonsTheme.MUTED, 0x90);
            float sc = mid ? 1.5f : 1f;
            ctx.getMatrices().pushMatrix();
            ctx.getMatrices().translate(cx, yy);
            ctx.getMatrices().scale(sc, sc);
            ctx.drawTextWithShadow(tr, n, -tr.getWidth(n) / 2, 0, col);
            ctx.getMatrices().popMatrix();
        }
        int tickN = (int) Math.floor(pos);
        if (tickN != lastTick) {
            lastTick = tickN;
            if (FloonsConfig.get().sound) FloonsGamble.playId("block.note_block.hat", 0.6f, 1.2f + t * 0.6f);
        }
    }

    private static int lastTick = -1;
}
