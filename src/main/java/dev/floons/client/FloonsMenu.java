package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Mod-Menü (Rechte Shift-Taste oder /floons menu): Reiter Auktion, Gamble, Allgemein. */
public class FloonsMenu extends FloonsScreen {
    private static final int W = 360, H = 256;
    private static int tab = 0;
    private static String fItem = "", fStart = "", fDur = "2m";
    private final Screen parent;
    private int left, top;
    private long resetArmed;

    public FloonsMenu(Screen parent) {
        super(Text.literal("Floons Client"));
        this.parent = parent;
    }

    // ---------- Hilfen ----------
    private static String onOff(boolean b) { return b ? "an" : "aus"; }

    private void toggle(String label, Supplier<Boolean> get, Consumer<Boolean> set, int x, int y, int w) {
        button(() -> label + ": " + onOff(get.get()), () -> { set.accept(!get.get()); FloonsConfig.get().save(); }, x, y, w, 18);
    }

    /** Wert per Klick durch feste Stufen schalten. */
    private void cycleLong(String label, long[] vals, Supplier<Long> get, Consumer<Long> set, java.util.function.LongFunction<String> show, int x, int y, int w) {
        button(() -> label + ": " + show.apply(get.get()), () -> {
            long cur = get.get(); long nx = vals[0];
            for (int i = 0; i < vals.length; i++) if (vals[i] == cur) { nx = vals[(i + 1) % vals.length]; break; }
            set.accept(nx); FloonsConfig.get().save();
        }, x, y, w, 18);
    }

    private TextFieldWidget field(int x, int y, int w, String value, int max, Consumer<String> onChange) {
        TextFieldWidget f = new TextFieldWidget(textRenderer, x + 5, y + 5, w - 10, 12, Text.empty());
        f.setDrawsBackground(false);
        addDrawable((dc, mx, my, dl) -> FloonsTheme.fieldBg(dc, x, y, w, 18, f.isFocused()));
        f.setMaxLength(max);
        f.setText(value);
        f.setChangedListener(onChange);
        addDrawableChild(f);
        return f;
    }

    private void switchTab(int t) { tab = t; clearAndInit(); }

    // ---------- Aufbau ----------
    @Override
    protected void build() {
        left = width / 2 - W / 2;
        top = height / 2 - H / 2;
        String[] tabs = {"Auktion", "Gamble", "Allgemein"};
        int tw = (W - 16) / 3;
        for (int i = 0; i < tabs.length; i++) {
            final int t = i;
            button(() -> tabs[t], () -> switchTab(t), left + 8 + i * tw, top + 24, tw - 4, 18, tab == t);
        }
        int y = top + 50;
        switch (tab) {
            case 0 -> buildAuction(y);
            case 1 -> buildGamble(y);
            default -> buildGeneral(y);
        }
        button(() -> "Schließen", this::close, left + W / 2 - 50, top + H - 26, 100, 18);
    }

    private void buildAuction(int y) {
        FloonsConfig c = FloonsConfig.get();
        int x = left + 8, half = (W - 20) / 2, x2 = x + half + 4;
        if (!FloonsAuction.active) {
            y += 10;
            field(x, y, half + 40, fItem, 60, s -> fItem = s);
            field(x + half + 44, y, 70, fStart, 16, s -> fStart = s);
            field(x + half + 118, y, W - 16 - half - 110, fDur, 8, s -> fDur = s);
            y += 22;
            button(() -> "Auktion starten", () -> {
                long st = FloonsAuction.parseInput(fStart.isBlank() ? "0" : fStart);
                int sec = FloonsAuction.parseDuration(fDur);
                if (fItem.isBlank()) { FloonsAuction.info("Erst ein Item eintragen."); return; }
                if (st < 0) { FloonsAuction.info("Startgebot ungültig (z. B. 500k, 1.5m, 2000)."); return; }
                if (sec < 0) { FloonsAuction.info("Dauer ungültig (z. B. 90, 90s, 2m, 1m30s)."); return; }
                String err = FloonsAuction.start(fItem.trim(), st, sec);
                if (err != null) FloonsAuction.info(err); else clearAndInit();
            }, x, y, W - 16, 18, true);
        } else {
            y += 22;
            int bw = (W - 16 - 12) / 4;
            button(() -> "Beenden", () -> { FloonsAuction.end(false); clearAndInit(); }, x, y, bw, 18, true);
            button(() -> "Abbrechen", () -> { FloonsAuction.end(true); clearAndInit(); }, x + (bw + 4), y, bw, 18);
            button(() -> "+30s", () -> FloonsAuction.extend(30), x + 2 * (bw + 4), y, bw, 18);
            button(() -> "+60s", () -> FloonsAuction.extend(60), x + 3 * (bw + 4), y, bw, 18);
        }
        y += 26;
        toggle("Ansagen", () -> c.announce, v -> c.announce = v, x, y, half);
        toggle("Countdown", () -> c.countdown, v -> c.countdown = v, x2, y, half);
        y += 21;
        toggle("Ton", () -> c.sound, v -> c.sound = v, x, y, half);
        toggle("Auktions-HUD", () -> c.hud, v -> c.hud = v, x2, y, half);
        y += 21;
        cycleLong("Mindest-Erhöhung", new long[]{1, 1_000, 10_000, 100_000, 1_000_000, 10_000_000},
            () -> c.minRaise, v -> c.minRaise = v, FloonsAuction::fmt, x, y, half);
        cycleLong("Anti-Snipe", new long[]{0, 5, 10, 15, 30, 60},
            () -> (long) c.antiSnipe, v -> c.antiSnipe = (int) (long) v, v -> v > 0 ? v + "s" : "aus", x2, y, half);
        y += 21;
        String[] corners = {"top_right", "top_left", "mid_right", "mid_left", "bottom_right"};
        String[] names = {"oben rechts", "oben links", "Mitte rechts", "Mitte links", "unten rechts"};
        button(() -> {
            for (int i = 0; i < corners.length; i++) if (corners[i].equals(c.hudCorner)) return "HUD: " + names[i];
            return "HUD: oben rechts";
        }, () -> {
            int i = 0;
            for (int k = 0; k < corners.length; k++) if (corners[k].equals(c.hudCorner)) i = k;
            c.hudCorner = corners[(i + 1) % corners.length]; c.save();
        }, x, y, half, 18);
        button(() -> "Rückzahlungen / Verlauf", () -> { FloonsAuction.refundList(); close(); }, x2, y, half, 18);
    }

    private void buildGamble(int y) {
        FloonsConfig c = FloonsConfig.get();
        int x = left + 8, half = (W - 20) / 2, x2 = x + half + 4;
        y += 22;
        int bw = (W - 16 - 12) / 4;
        button(() -> "Win", () -> msg(FloonsGamble.settle(null, true, -1)), x, y, bw, 18, true);
        button(() -> "Lose", () -> msg(FloonsGamble.settle(null, false, -1)), x + (bw + 4), y, bw, 18);
        button(() -> "Payback", () -> msg(FloonsGamble.payback(null)), x + 2 * (bw + 4), y, bw, 18);
        button(() -> "Alle zurück", () -> msg(FloonsGamble.payback("all")), x + 3 * (bw + 4), y, bw, 18);
        y += 24;
        button(() -> "Multiplikator: " + String.format(Locale.ROOT, "%.2fx", c.winMultiplier).replace(".00x", "x"), () -> {
            double[] v = {1.5, 1.8, 2.0, 2.5, 3.0, 5.0, 10.0};
            double nx = v[0];
            for (int i = 0; i < v.length; i++) if (Math.abs(v[i] - c.winMultiplier) < 1e-9) { nx = v[(i + 1) % v.length]; break; }
            c.winMultiplier = nx; c.save();
        }, x, y, half, 18);
        toggle("Auto-Pay", () -> c.autoPay, v -> c.autoPay = v, x2, y, half);
        y += 21;
        cycleLong("Pause /pay", new long[]{0, 250, 500, 1000, 2000},
            () -> (long) c.payDelayMs, v -> c.payDelayMs = (int) (long) v, v -> v + " ms", x, y, half);
        cycleLong("Warte-Warnung", new long[]{0, 15, 30, 60, 120},
            () -> (long) c.waitWarnSec, v -> c.waitWarnSec = (int) (long) v, v -> v > 0 ? v + "s" : "aus", x2, y, half);
        y += 21;
        // Min / Max / Verlust-Limit als Eingabefelder (leer = aus)
        int fw = (W - 16 - 8) / 3;
        field(x, y, fw, c.minBet > 0 ? String.valueOf(c.minBet) : "", 16, s -> {
            long v = s.isBlank() ? 0 : FloonsAuction.parseInput(s); if (v >= 0) { c.minBet = v; c.save(); } });
        field(x + fw + 4, y, fw, c.maxBet == Long.MAX_VALUE ? "" : String.valueOf(c.maxBet), 16, s -> {
            long v = s.isBlank() ? Long.MAX_VALUE : FloonsAuction.parseInput(s); if (v >= 0) { c.maxBet = v; c.save(); } });
        field(x + 2 * (fw + 4), y, fw, c.lossLimit > 0 ? String.valueOf(c.lossLimit) : "", 16, s -> {
            long v = s.isBlank() ? 0 : FloonsAuction.parseInput(s); if (v >= 0) { c.lossLimit = v; c.save(); } });
        y += 26;
        toggle("Gamble-HUD", () -> c.gambleHud, v -> c.gambleHud = v, x, y, half);
        toggle("Effekte", () -> c.effects, v -> c.effects = v, x2, y, half);
        y += 21;
        toggle("Konfetti", () -> c.confetti, v -> c.confetti = v, x, y, half);
        button(() -> System.currentTimeMillis() < resetArmed ? "Wirklich zurücksetzen?" : "Statistik zurücksetzen", () -> {
            if (System.currentTimeMillis() < resetArmed) { FloonsStats.reset(); resetArmed = 0; FloonsGamble.chat("Statistik zurückgesetzt."); }
            else resetArmed = System.currentTimeMillis() + 3000;
        }, x2, y, half, 18);
        y += 21;
        button(() -> "Roulette", () -> client.setScreen(new FloonsRouletteScreen(this)), x, y, half, 18);
        button(() -> "Rechner", () -> client.setScreen(new FloonsCalculatorScreen(this)), x2, y, half, 18);
    }

    private void buildGeneral(int y) {
        FloonsConfig c = FloonsConfig.get();
        int x = left + 8, half = (W - 20) / 2, x2 = x + half + 4;
        toggle("Wasserzeichen", () -> c.watermark, v -> c.watermark = v, x, y, half);
        button(() -> "HUD-Größe: " + String.format(Locale.ROOT, "%.2f", c.hudScale), () -> {
            float[] v = {0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f};
            float nx = v[0];
            for (int i = 0; i < v.length; i++) if (Math.abs(v[i] - c.hudScale) < 1e-4) { nx = v[(i + 1) % v.length]; break; }
            c.hudScale = nx; c.save();
        }, x2, y, half, 18);
    }

    private void msg(String err) { if (err != null) FloonsGamble.chat(err); }

    // ---------- Zeichnen ----------
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fillGradient(0, 0, width, height,
            FloonsTheme.alpha(FloonsTheme.lerp(0xFF05030C, FloonsTheme.PURPLE, 0.15f), 0xC0),
            FloonsTheme.alpha(FloonsTheme.lerp(0xFF05030C, FloonsTheme.BLUE, 0.18f), 0xC0));
        FloonsTheme.panel(ctx, left, top, W, H);
        FloonsTheme.gradientText(ctx, textRenderer, "Floons Client", left + 8, top + 8);
        String v = "v1.0.0 · kein Auto-Update";
        ctx.drawTextWithShadow(textRenderer, v, left + W - 8 - textRenderer.getWidth(v), top + 8, FloonsTheme.MUTED);
    }

    @Override
    protected void extra(DrawContext ctx, int mouseX, int mouseY) {
        int x = left + 10, y = top + 55;
        if (tab == 0) {
            if (!FloonsAuction.active) {
                int half = (W - 20) / 2;
                ctx.drawTextWithShadow(textRenderer, "Item", x, y - 4, FloonsTheme.MUTED);
                ctx.drawTextWithShadow(textRenderer, "Start", left + 8 + half + 46, y - 4, FloonsTheme.MUTED);
                ctx.drawTextWithShadow(textRenderer, "Dauer", left + 8 + half + 120, y - 4, FloonsTheme.MUTED);
            } else {
                String l1 = FloonsAuction.item + "  ·  noch " + FloonsAuction.mmss(FloonsAuction.left()) + (FloonsAuction.paused ? " (Pause)" : "");
                String l2 = (FloonsAuction.bestName == null ? "kein Gebot (Start " + FloonsAuction.fmt(FloonsAuction.startBid) + ")"
                    : FloonsAuction.fmt(FloonsAuction.best) + " von " + FloonsAuction.bestName) + "  ·  " + FloonsAuction.bids.size() + " Bieter";
                ctx.drawTextWithShadow(textRenderer, l1, x, y - 5, FloonsTheme.TEXT);
                ctx.drawTextWithShadow(textRenderer, l2, x, y + 6, FloonsTheme.GOOD);
            }
        } else if (tab == 1) {
            String cur = FloonsStats.pending.keySet().stream().findFirst().orElse(null);
            String l1 = cur == null ? "Kein offener Einsatz" : "Aktuell: " + cur + "  " + FloonsStats.fmt(FloonsStats.pending.get(cur))
                + "  (" + (FloonsStats.pending.size() + FloonsStats.queuedCount()) + " offen)";
            String l2 = "Profit " + FloonsStats.fmt(FloonsStats.profit()) + "  ·  " + FloonsStats.rounds + " Runden  ·  "
                + String.format(Locale.ROOT, "%.1f%% Winrate", FloonsStats.winrate());
            ctx.drawTextWithShadow(textRenderer, l1, x, y - 5, cur == null ? FloonsTheme.MUTED : FloonsTheme.BLUE);
            ctx.drawTextWithShadow(textRenderer, l2, x, y + 6, FloonsStats.profit() >= 0 ? FloonsTheme.GOOD : FloonsTheme.BAD);
            int fy = top + 50 + 22 + 24 + 21 + 21 - 10;
            int fw = (W - 16 - 8) / 3;
            ctx.drawTextWithShadow(textRenderer, "Min-Einsatz", left + 8, fy, FloonsTheme.MUTED);
            ctx.drawTextWithShadow(textRenderer, "Max-Einsatz", left + 8 + fw + 4, fy, FloonsTheme.MUTED);
            ctx.drawTextWithShadow(textRenderer, "Verlust-Limit", left + 8 + 2 * (fw + 4), fy, FloonsTheme.MUTED);
        } else {
            int yy = top + 78;
            String[] help = {
                "Taste: Rechte Shift (änderbar unter Optionen > Steuerung)",
                "Auktion: /wa start <start> <dauer> <item>  ·  /wa end|cancel|extend",
                "Gamble: /win /lose /payback [spieler|all]  ·  /gamble",
                "Extras: /roll  /coinflip  /roulette  /calc",
                "Einstellungen per Befehl: /floons",
                "Die Mod aktualisiert sich nie selbst."
            };
            for (String h : help) { ctx.drawTextWithShadow(textRenderer, h, x, yy, FloonsTheme.MUTED); yy += 12; }
        }
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() { MinecraftClient.getInstance().setScreen(parent); }
}
