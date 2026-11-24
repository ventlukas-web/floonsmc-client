package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Auktions-HUD: Karte mit Logo, Item, Zeitbalken, Höchstgebot, Mindestgebot, Sofortkauf/Mindestpreis,
 * Top 3, Live-Gebotsverlauf und Warteschlange. Verschiebbar im HUD-Editor. Dazu großer Countdown in der Mitte.
 */
public final class FloonsAuctionHud {
    private FloonsAuctionHud() {}

    private record Row(String l, String r, int color) {}

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        FloonsConfig c = FloonsConfig.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden || !c.hud) return;
        if (FloonsClient.hideHud()) return;
        TextRenderer tr = mc.textRenderer;
        if (FloonsAuction.active) {
            card(ctx, tr, mc, c);
            if (c.bigCountdown) bigCountdown(ctx, tr, mc);
        } else if (!FloonsAuction.queue.isEmpty()) {
            queueCard(ctx, tr, mc, c);
        }
    }

    private static float[] defaultPos(MinecraftClient mc, FloonsConfig c, float fw, float fh) {
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        return switch (c.hudCorner) {
            case "top_left" -> new float[]{6, 6};
            case "top_right" -> new float[]{sw - fw - 6, 6};
            case "bottom_right" -> new float[]{sw - fw - 6, sh - fh - 40};
            case "mid_left" -> new float[]{6, (sh - fh) / 2};
            default -> new float[]{sw - fw - 6, (sh - fh) / 2};
        };
    }

    private static void card(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig c) {
        long now = System.currentTimeMillis();
        long left = FloonsAuction.left();
        boolean urgent = left < 10_000 && !FloonsAuction.paused;
        boolean hasBid = FloonsAuction.bestName != null;
        long need = hasBid ? FloonsAuction.best + Math.max(1, c.minRaise) : FloonsAuction.startBid;

        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Höchstgebot", hasBid ? FloonsAuction.fmt(FloonsAuction.best) : FloonsAuction.fmt(FloonsAuction.startBid) + " (Start)", FloonsTheme.GOOD));
        rows.add(new Row("Meistbietender", hasBid ? FloonsAuction.bestName : "–", hasBid ? FloonsTheme.GOOD : FloonsTheme.MUTED));
        rows.add(new Row("Nächstes Gebot ab", FloonsAuction.fmt(need), FloonsTheme.TEXT));
        if (FloonsAuction.buyNow > 0) rows.add(new Row("Sofortkauf", FloonsAuction.fmt(FloonsAuction.buyNow), FloonsTheme.BLUE));
        if (FloonsAuction.reserve > 0) {
            boolean met = hasBid && FloonsAuction.best >= FloonsAuction.reserve;
            rows.add(new Row("Mindestpreis", met ? "erreicht" : "noch nicht", met ? FloonsTheme.GOOD : FloonsTheme.BAD));
        }
        if (c.antiSnipeOn) {
            boolean hit = now < FloonsAuction.snipeFlashUntil;
            rows.add(new Row("Anti-Snipe", hit ? "+ Zeit! wieder " + c.antiSnipeTo + "s" : "letzte " + c.antiSnipeWindow + "s → " + c.antiSnipeTo + "s",
                hit ? FloonsTheme.BAD : FloonsTheme.MUTED));
        }
        List<Map.Entry<String, Long>> top = FloonsAuction.top();
        int n = Math.min(3, top.size());
        for (int i = 0; i < n; i++)
            rows.add(new Row((i + 1) + ". " + top.get(i).getKey(), FloonsAuction.fmt(top.get(i).getValue()), i == 0 ? FloonsTheme.GOOD : FloonsTheme.TEXT));
        rows.add(new Row("Gesamt", FloonsAuction.fmt(FloonsAuction.total()) + "  ·  " + FloonsAuction.bids.size() + " Bieter", FloonsTheme.MUTED));

        List<Row> feed = new ArrayList<>();
        if (c.bidFeed) {
            for (int i = 0; i < Math.min(4, FloonsAuction.feed.size()); i++) {
                FloonsAuction.Bid b = FloonsAuction.feed.get(i);
                long ago = (now - b.at()) / 1000;
                String r = "+" + FloonsAuction.fmt(b.amount()) + (b.total() != b.amount() ? " = " + FloonsAuction.fmt(b.total()) : "") + "  ·  " + ago + "s";
                feed.add(new Row(b.name(), r, b.top() ? FloonsTheme.GOOD : FloonsTheme.MUTED));
            }
        }
        String next = !FloonsAuction.queue.isEmpty() ? "Danach: " + FloonsAuction.queue.get(0).item() + (FloonsAuction.queue.size() > 1 ? " (+" + (FloonsAuction.queue.size() - 1) + ")" : "") : null;

        String title = FloonsAuction.item;
        String time = FloonsAuction.paused ? "PAUSE" : FloonsAuction.mmss(left);
        int pad = 7;
        int w = Math.max(150, Math.min(240, tr.getWidth(title) + 12 + tr.getWidth(time) * 2 + 16));
        for (Row r : rows) w = Math.max(w, Math.min(260, tr.getWidth(r.l()) + 14 + tr.getWidth(r.r())));
        for (Row r : feed) w = Math.max(w, Math.min(260, tr.getWidth(r.l()) + 14 + tr.getWidth(r.r())));
        int h = pad + 12 + 4 + 16 + 6 + rows.size() * 11 + (feed.isEmpty() ? 0 : 14 + feed.size() * 10) + (next == null ? 0 : 13) + pad - 2;
        int bw = w + pad * 2;

        float s = c.hudScale;
        float[] d = defaultPos(mc, c, bw * s, h * s);
        float[] pos = FloonsHud.place("auction", d[0], d[1], bw * s, h * s, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(s, s);

        FloonsTheme.panel(ctx, 0, 0, bw, h);
        boolean flash = now < FloonsAuction.flashUntil;
        int edge = flash ? FloonsTheme.GOOD : urgent ? FloonsTheme.BAD : FloonsTheme.alpha(FloonsTheme.PURPLE, 0x90);
        FloonsTheme.roundOutline(ctx, 0, 0, bw, h, 6, edge);

        // Kopfzeile: Logo + "AUKTION" + Zeit gross rechts
        FloonsLogo.draw(ctx, pad, pad, 10);
        FloonsTheme.gradientText(ctx, tr, "AUKTION", pad + 14, pad + 1);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pad + w, pad - 1);
        ctx.getMatrices().scale(1.5f, 1.5f);
        ctx.drawTextWithShadow(tr, time, -tr.getWidth(time), 0, urgent ? FloonsTheme.BAD : FloonsAuction.paused ? FloonsTheme.MUTED : FloonsTheme.TEXT);
        ctx.getMatrices().popMatrix();

        // Item-Name
        int y = pad + 16;
        String t = tr.getWidth(title) > w ? tr.trimToWidth(title, w - 6) + "…" : title;
        ctx.drawTextWithShadow(tr, t, pad, y, FloonsTheme.BLUE);
        y += 12;

        // Zeitbalken
        float frac = FloonsAuction.durationMs <= 0 ? 0 : Math.min(1f, left / (float) FloonsAuction.durationMs);
        FloonsTheme.roundRectExact(ctx, pad, y, w, 4, 2, FloonsTheme.alpha(0xFFFFFFFF, 0x30));
        int fill = Math.max(4, Math.round(w * frac));
        int barCol = urgent ? FloonsTheme.BAD : FloonsTheme.lerp(FloonsTheme.BLUE, FloonsTheme.PURPLE, 1f - frac);
        if (frac > 0) FloonsTheme.roundRectExact(ctx, pad, y, fill, 4, 2, barCol);
        y += 10;

        for (Row r : rows) {
            ctx.drawTextWithShadow(tr, r.l(), pad, y, FloonsTheme.MUTED);
            ctx.drawTextWithShadow(tr, r.r(), pad + w - tr.getWidth(r.r()), y, r.color());
            y += 11;
        }
        if (!feed.isEmpty()) {
            y += 3;
            ctx.fill(pad, y, pad + w, y + 1, FloonsTheme.alpha(0xFFFFFFFF, 0x30));
            y += 4;
            ctx.drawTextWithShadow(tr, "Letzte Gebote", pad, y, FloonsTheme.MUTED);
            y += 10;
            for (Row r : feed) {
                ctx.drawTextWithShadow(tr, r.l(), pad, y, r.color());
                ctx.drawTextWithShadow(tr, r.r(), pad + w - tr.getWidth(r.r()), y, FloonsTheme.TEXT);
                y += 10;
            }
        }
        if (next != null) {
            y += 3;
            ctx.drawTextWithShadow(tr, tr.trimToWidth(next, w), pad, y, FloonsTheme.MUTED);
        }
        ctx.getMatrices().popMatrix();
    }

    private static void queueCard(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig c) {
        long in = FloonsAuction.queueStartsIn();
        String l1 = "Nächste Auktion: " + FloonsAuction.queue.get(0).item();
        String l2 = (in >= 0 ? "startet in " + FloonsAuction.mmss(in) : "/action queue next zum Starten") + "  ·  " + FloonsAuction.queue.size() + " in der Warteschlange";
        int pad = 6, w = Math.min(240, Math.max(tr.getWidth(l1) + 14, tr.getWidth(l2))), h = pad * 2 + 20, bw = w + pad * 2;
        float s = c.hudScale;
        float[] d = defaultPos(mc, c, bw * s, h * s);
        float[] pos = FloonsHud.place("auction", d[0], d[1], bw * s, h * s, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(s, s);
        FloonsTheme.panel(ctx, 0, 0, bw, h);
        FloonsLogo.draw(ctx, pad, pad, 9);
        ctx.drawTextWithShadow(tr, tr.trimToWidth(l1, w - 12), pad + 12, pad, FloonsTheme.BLUE);
        ctx.drawTextWithShadow(tr, tr.trimToWidth(l2, w), pad, pad + 11, FloonsTheme.MUTED);
        ctx.getMatrices().popMatrix();
    }

    /** Letzte 5 Sekunden: große Zahl in der Bildschirmmitte, oben. */
    private static void bigCountdown(DrawContext ctx, TextRenderer tr, MinecraftClient mc) {
        if (FloonsAuction.paused) return;
        long left = FloonsAuction.left();
        if (left > 5000 || left <= 0) return;
        int sec = (int) ((left + 999) / 1000);
        float part = (left % 1000) / 1000f;              // 1 -> 0 innerhalb jeder Sekunde
        float scale = 3.0f + 1.5f * part;
        int alpha = (int) (110 + 145 * part);
        String s = String.valueOf(sec);
        int sw = mc.getWindow().getScaledWidth();
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(sw / 2f, 38);
        ctx.getMatrices().scale(scale, scale);
        ctx.drawTextWithShadow(tr, s, -tr.getWidth(s) / 2, 0, FloonsTheme.alpha(sec <= 3 ? FloonsTheme.BAD : FloonsTheme.BLUE, alpha));
        ctx.getMatrices().popMatrix();
    }
}
