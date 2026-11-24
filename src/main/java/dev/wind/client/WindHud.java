package dev.wind.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Live-HUD der laufenden Auktion. */
public final class WindHud {
    private WindHud() {}

    static final int BG = 0xB0101820, LINE = 0x4060D0F0, ACCENT = 0xFF5FD7F5, TEXT = 0xFFEAF6FF,
        MUTED = 0xFF9FB4C4, GOOD = 0xFF6EE7A8, WARN = 0xFFFF8A5C, BAR_BG = 0x60FFFFFF;

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        WindConfig c = WindConfig.get();
        if (!c.hud || !WindAuction.active) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;

        long left = WindAuction.left();
        boolean urgent = left < 10_000;
        List<String[]> rows = new ArrayList<>();
        rows.add(new String[]{"Zeit", WindAuction.paused ? "Pause" : WindAuction.mmss(left)});
        rows.add(new String[]{"Höchstgebot", WindAuction.bestName == null ? WindAuction.fmt(WindAuction.startBid) + " (Start)" : WindAuction.fmt(WindAuction.best)});
        rows.add(new String[]{"Meistbietender", WindAuction.bestName == null ? "–" : WindAuction.bestName});
        List<Map.Entry<String, Long>> top = WindAuction.top();
        for (int i = 0; i < Math.min(3, top.size()); i++)
            rows.add(new String[]{(i + 1) + ". " + top.get(i).getKey(), WindAuction.fmt(top.get(i).getValue())});
        rows.add(new String[]{"Gesamt", WindAuction.fmt(WindAuction.total()) + "  (" + WindAuction.bids.size() + " Bieter)"});

        String title = "Auktion: " + WindAuction.item;
        int w = Math.min(220, Math.max(120, tr.getWidth(title)));
        for (String[] r : rows) w = Math.max(w, tr.getWidth(r[0]) + 14 + tr.getWidth(r[1]));
        int pad = 6;
        int h = pad + 10 + 6 + 4 + rows.size() * 11 + pad - 2;
        int bw = w + pad * 2;

        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        float s = c.hudScale, fw = bw * s, fh = h * s;
        float x, y;
        switch (c.hudCorner) {
            case "top_left" -> { x = 6; y = 6; }
            case "bottom_right" -> { x = sw - fw - 6; y = sh - fh - 40; }
            case "mid_left" -> { x = 6; y = (sh - fh) / 2; }
            case "mid_right" -> { x = sw - fw - 6; y = (sh - fh) / 2; }
            default -> { x = sw - fw - 6; y = 6; }
        }

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(s, s);

        boolean flash = System.currentTimeMillis() < WindAuction.flashUntil;
        ctx.fill(0, 0, bw, h, BG);
        int edge = flash ? GOOD : (urgent && !WindAuction.paused ? WARN : LINE);
        ctx.fill(0, 0, bw, 1, edge);
        ctx.fill(0, h - 1, bw, h, edge);
        ctx.fill(0, 0, 1, h, edge);
        ctx.fill(bw - 1, 0, bw, h, edge);

        String t = tr.getWidth(title) > w ? tr.trimToWidth(title, w - 6) + "…" : title;
        ctx.drawTextWithShadow(tr, t, pad, pad, ACCENT);

        // Fortschrittsbalken (verbleibende Zeit)
        int by = pad + 12;
        ctx.fill(pad, by, pad + w, by + 3, BAR_BG);
        float frac = WindAuction.durationMs <= 0 ? 0 : Math.min(1f, left / (float) WindAuction.durationMs);
        ctx.fill(pad, by, pad + Math.round(w * frac), by + 3, urgent ? WARN : ACCENT);

        int ry = by + 8;
        for (String[] r : rows) {
            int vc = TEXT;
            if (r[0].equals("Zeit") && urgent) vc = WARN;
            if (r[0].equals("Höchstgebot") || r[0].equals("Meistbietender") || r[0].startsWith("1. ")) vc = GOOD;
            ctx.drawTextWithShadow(tr, r[0], pad, ry, MUTED);
            ctx.drawTextWithShadow(tr, r[1], pad + w - tr.getWidth(r[1]), ry, vc);
            ry += 11;
        }
        ctx.getMatrices().popMatrix();
    }
}
