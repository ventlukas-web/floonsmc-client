package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Mod-Menü (Rechte Shift oder /floons menu).
 * Reiter: General (Design, FPS), Gamble, Auktion, Visuals (alle HUDs + Visuals), Tools, Commands.
 * Mehrseitige Reiter blättert man mit &lt; &gt;.
 */
public class FloonsMenu extends FloonsScreen {
    private static final int W = 384, H = 280, PW = W - 20;
    private static final String[] TABS = {"General", "Gamble", "Auktion", "Visuals", "Tools", "Commands"};
    private static final String[][] PAGES = {
        {"Design", "FPS"},
        {"Gamble"},
        {"Auktion"},
        {"HUD", "Mehr HUDs", "PvP-Anzeigen", "Extras", "Crosshair", "Scoreboard", "Eigenes HUD"},
        {"Wegpunkte", "Notizen", "Timer & Rechner"},
        {"Commands"}
    };
    private static final String[][] PRESETS = {
        {"Floons", "#38BDF8", "#5EEAD4"},
        {"Lila-Blau", "#A855F7", "#7DD3FC"},
        {"Sunset", "#FB7185", "#FBBF24"},
        {"Mint", "#34D399", "#7DD3FC"},
        {"Neon", "#F472B6", "#22D3EE"},
        {"Feuer", "#EF4444", "#F97316"},
        {"Gold", "#F59E0B", "#FDE68A"},
        {"Rot", "#DC2626", "#FCA5A5"},
    };
    private static int tab = 0;
    private static final int[] page = new int[TABS.length];
    private static int presetIdx = 0, cmdPage = 0;
    private static String fItem = "", fStart = "", fDur = "2m", fWp = "", fNote = "";

    private record Label(String text, int x, int y, int color) {}
    private final List<Label> labels = new ArrayList<>();
    private final Screen parent;
    private int left, top;
    private long resetArmed;

    public FloonsMenu(Screen parent) {
        super(Text.literal("Floons Client"));
        this.parent = parent;
    }

    // ---------- Hilfen ----------
    private static String onOff(boolean b) { return b ? "an" : "aus"; }
    private void label(String t, int x, int y) { labels.add(new Label(t, x, y, FloonsTheme.MUTED)); }

    private void toggle(String label, Supplier<Boolean> get, Consumer<Boolean> set, int x, int y, int w) {
        button(() -> label + ": " + onOff(get.get()), () -> { set.accept(!get.get()); FloonsConfig.get().save(); }, x, y, w, 18);
    }

    /** Zwei Schalter pro Zeile; gibt die naechste y-Position zurueck. */
    private int toggles(int y, Object[][] list) {
        int half = (PW - 6) / 2;
        for (int i = 0; i < list.length; i++) {
            @SuppressWarnings("unchecked") Supplier<Boolean> g = (Supplier<Boolean>) list[i][1];
            @SuppressWarnings("unchecked") Consumer<Boolean> s = (Consumer<Boolean>) list[i][2];
            toggle((String) list[i][0], g, s, left + (i % 2) * (half + 6), y, half);
            if (i % 2 == 1 || i == list.length - 1) y += 21;
        }
        return y;
    }

    private static Object[] t(String n, Supplier<Boolean> g, Consumer<Boolean> s) { return new Object[]{n, g, s}; }

    private void cycleLong(String label, long[] vals, Supplier<Long> get, Consumer<Long> set, java.util.function.LongFunction<String> show, int x, int y, int w) {
        button(() -> label + ": " + show.apply(get.get()), () -> {
            long cur = get.get(); long nx = vals[0];
            for (int i = 0; i < vals.length; i++) if (vals[i] == cur) { nx = vals[(i + 1) % vals.length]; break; }
            set.accept(nx); FloonsConfig.get().save();
        }, x, y, w, 18);
    }

    /** "-  Name: Wert  +" */
    private void stepper(Supplier<String> label, Runnable minus, Runnable plus, int y) {
        button(() -> "-", minus, left, y, 24, 18);
        button(label, () -> {}, left + 28, y, PW - 56, 18);
        button(() -> "+", plus, left + PW - 24, y, 24, 18);
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

    private void rebuild() { clearAndInit(); }
    private void msg(String err) { if (err != null) FloonsGamble.chat(err); }

    // ---------- Aufbau ----------
    @Override
    protected void build() {
        labels.clear();
        left = width / 2 - W / 2 + 10;
        top = Math.max(4, height / 2 - H / 2);
        int tw = PW / TABS.length;
        for (int i = 0; i < TABS.length; i++) {
            final int k = i;
            button(() -> TABS[k], () -> { tab = k; rebuild(); }, left + i * tw, top + 22, tw - 3, 18, tab == k);
        }
        int y = top + 46;
        String[] ps = PAGES[tab];
        int pg = Math.floorMod(page[tab], ps.length);
        page[tab] = pg;
        if (ps.length > 1) {
            button(() -> "<", () -> { page[tab] = pg - 1; rebuild(); }, left, y, 24, 16);
            button(() -> ps[pg] + "  (" + (pg + 1) + "/" + ps.length + ")", () -> { page[tab] = pg + 1; rebuild(); }, left + 28, y, PW - 56, 16);
            button(() -> ">", () -> { page[tab] = pg + 1; rebuild(); }, left + PW - 24, y, 24, 16);
            y += 22;
        }
        switch (ps[pg]) {
            case "Design" -> design(y);
            case "FPS" -> fps(y);
            case "Gamble" -> gamble(y);
            case "Auktion" -> auction(y);
            case "HUD" -> hud(y);
            case "Mehr HUDs" -> moreHud(y);
            case "PvP-Anzeigen" -> pvp(y);
            case "Extras" -> extras(y);
            case "Crosshair" -> crosshair(y);
            case "Scoreboard" -> scoreboard(y);
            case "Eigenes HUD" -> custom(y);
            case "Wegpunkte" -> waypoints(y);
            case "Notizen" -> notes(y);
            case "Timer & Rechner" -> timers(y);
            case "Commands" -> commands(y);
            default -> {}
        }
        button(() -> "Schließen", this::close, left + PW / 2 - 50, top + H - 24, 100, 18);
    }

    // ---------- General ----------
    private void design(int y) {
        FloonsConfig c = FloonsConfig.get();
        int half = (PW - 6) / 2;
        label("Farbe 1 (#RRGGBB)", left, y);
        label("Farbe 2 (#RRGGBB)", left + half + 6, y);
        field(left, y + 10, half, c.color1, 7, s -> { Integer v = FloonsTheme.parseHex(s); if (v != null) { c.color1 = FloonsTheme.toHex(v); c.save(); FloonsTheme.reload(c); } });
        field(left + half + 6, y + 10, half, c.color2, 7, s -> { Integer v = FloonsTheme.parseHex(s); if (v != null) { c.color2 = FloonsTheme.toHex(v); c.save(); FloonsTheme.reload(c); } });
        y += 32;
        button(() -> "Vorlage: " + PRESETS[presetIdx % PRESETS.length][0], () -> {
            presetIdx = (presetIdx + 1) % PRESETS.length;
            String[] p = PRESETS[presetIdx];
            c.color1 = p[1]; c.color2 = p[2]; c.save(); FloonsTheme.reload(c); rebuild();
        }, left, y, half, 18, true);
        cycleLong("Deckkraft", new long[]{96, 128, 160, 192, 208, 232, 255},
            () -> (long) c.bgOpacity, v -> { c.bgOpacity = (int) (long) v; FloonsTheme.reload(c); }, v -> (v * 100 / 255) + " %", left + half + 6, y, half);
        y += 21;
        y = toggles(y, new Object[][]{
            t("Wasserzeichen", () -> c.watermark, v -> c.watermark = v),
            t("Sound", () -> c.sound, v -> c.sound = v),
            t("Overlay-Effekte", () -> c.effects, v -> c.effects = v),
            t("Konfetti", () -> c.confetti, v -> c.confetti = v),
            t("Toasts", () -> c.notify, v -> c.notify = v),
            t("Streamer-Modus", () -> c.streamer, v -> c.streamer = v),
        });
        button(() -> "HUD verschieben", () -> client.setScreen(new FloonsHudEditor(this)), left, y, half, 18, true);
        button(() -> "HUD-Größe: " + String.format(Locale.ROOT, "%.1f", c.hudScale), () -> {
            c.hudScale = c.hudScale >= 2.0f ? 0.5f : Math.round((c.hudScale + 0.1f) * 10) / 10f; c.save();
        }, left + half + 6, y, half, 18);
        y += 21;
        button(() -> "Lite-Modus (HUDs + Effekte aus)", () -> {
            c.gambleHud = false; c.keystrokes = false; c.coords = false; c.moduleList = false; c.payHud = false;
            c.watermark = false; c.effects = false; c.confetti = false; c.save();
        }, left, y, PW, 18);
    }

    private void fps(int y) {
        FloonsConfig c = FloonsConfig.get();
        Object[][] l = {
            t("Partikel minimal", () -> c.perfParticles, v -> c.perfParticles = v),
            t("Entity-Schatten aus", () -> c.perfShadows, v -> c.perfShadows = v),
            t("Weniger Distanz (8/6)", () -> c.perfDistance, v -> c.perfDistance = v),
            t("Wolken aus", () -> c.perfClouds, v -> c.perfClouds = v),
            t("Biom-Blend 0", () -> c.perfBlend, v -> c.perfBlend = v),
            t("Weiches Licht aus", () -> c.perfAo, v -> c.perfAo = v),
            t("Kein FPS-Limit", () -> c.perfFps, v -> c.perfFps = v),
        };
        int half = (PW - 6) / 2;
        for (int i = 0; i < l.length; i++) {
            @SuppressWarnings("unchecked") Supplier<Boolean> g = (Supplier<Boolean>) l[i][1];
            @SuppressWarnings("unchecked") Consumer<Boolean> s = (Consumer<Boolean>) l[i][2];
            String n = (String) l[i][0];
            button(() -> n + ": " + onOff(g.get()), () -> { s.accept(!g.get()); FloonsPerf.apply(); },
                left + (i % 2) * (half + 6), y, half, 18);
            if (i % 2 == 1 || i == l.length - 1) y += 21;
        }
        y += 6;
        label("Ändert nur normale Spieloptionen. Beim Ausschalten", left, y);
        label("werden deine alten Werte wiederhergestellt.", left, y + 11);
        label("Analyse: /floons lag  ·  Messung: /floons perf", left, y + 28);
    }

    // ---------- Gamble ----------
    private void gamble(int y) {
        FloonsConfig c = FloonsConfig.get();
        int half = (PW - 6) / 2, bw = (PW - 12) / 4;
        y += 22;
        button(() -> "Win", () -> msg(FloonsGamble.settle(null, true, -1)), left, y, bw, 18, true);
        button(() -> "Lose", () -> msg(FloonsGamble.settle(null, false, -1)), left + (bw + 4), y, bw, 18);
        button(() -> "Payback", () -> msg(FloonsGamble.payback(null)), left + 2 * (bw + 4), y, bw, 18);
        button(() -> "Alle zurück", () -> msg(FloonsGamble.payback("all")), left + 3 * (bw + 4), y, bw, 18);
        y += 23;
        button(() -> "Multiplikator: " + String.format(Locale.ROOT, "%.2fx", c.winMultiplier).replace(".00x", "x"), () -> {
            double[] v = {1.5, 1.8, 2.0, 2.5, 3.0, 5.0, 10.0};
            double nx = v[0];
            for (int i = 0; i < v.length; i++) if (Math.abs(v[i] - c.winMultiplier) < 1e-9) { nx = v[(i + 1) % v.length]; break; }
            c.winMultiplier = nx; c.save();
        }, left, y, half, 18);
        toggle("Auto-Pay", () -> c.autoPay, v -> c.autoPay = v, left + half + 6, y, half);
        y += 21;
        cycleLong("Pause /pay", new long[]{0, 250, 500, 1000, 2000},
            () -> (long) c.payDelayMs, v -> c.payDelayMs = (int) (long) v, v -> v + " ms", left, y, half);
        cycleLong("Warte-Warnung", new long[]{0, 15, 30, 60, 120},
            () -> (long) c.waitWarnSec, v -> c.waitWarnSec = (int) (long) v, v -> v > 0 ? v + "s" : "aus", left + half + 6, y, half);
        y += 23;
        int fw = (PW - 8) / 3;
        label("Min-Einsatz", left, y); label("Max-Einsatz", left + fw + 4, y); label("Verlust-Limit", left + 2 * (fw + 4), y);
        y += 10;
        field(left, y, fw, c.minBet > 0 ? String.valueOf(c.minBet) : "", 16, s -> {
            long v = s.isBlank() ? 0 : FloonsAuction.parseInput(s); if (v >= 0) { c.minBet = v; c.save(); } });
        field(left + fw + 4, y, fw, c.maxBet == Long.MAX_VALUE ? "" : String.valueOf(c.maxBet), 16, s -> {
            long v = s.isBlank() ? Long.MAX_VALUE : FloonsAuction.parseInput(s); if (v >= 0) { c.maxBet = v; c.save(); } });
        field(left + 2 * (fw + 4), y, fw, c.lossLimit > 0 ? String.valueOf(c.lossLimit) : "", 16, s -> {
            long v = s.isBlank() ? 0 : FloonsAuction.parseInput(s); if (v >= 0) { c.lossLimit = v; c.save(); } });
        y += 24;
        y = toggles(y, new Object[][]{
            t("Stats-HUD", () -> c.gambleHud, v -> c.gambleHud = v),
            t("Zahlungs-HUD", () -> c.payHud, v -> c.payHud = v),
            t("Gewinn-Graph", () -> c.graphHud, v -> c.graphHud = v),
            t("Top-Spieler", () -> c.topHud, v -> c.topHud = v),
        });
        button(() -> System.currentTimeMillis() < resetArmed ? "Wirklich zurücksetzen?" : "Statistik zurücksetzen", () -> {
            if (System.currentTimeMillis() < resetArmed) { FloonsStats.reset(); resetArmed = 0; FloonsGamble.chat("Statistik zurückgesetzt."); }
            else resetArmed = System.currentTimeMillis() + 3000;
        }, left, y, half, 18);
        button(() -> "Roulette", () -> client.setScreen(new FloonsRouletteScreen(this)), left + half + 6, y, half, 18, true);
    }

    // ---------- Auktion ----------
    private void auction(int y) {
        FloonsConfig c = FloonsConfig.get();
        int half = (PW - 6) / 2, x2 = left + half + 6;
        if (!FloonsAuction.active) {
            label("Item", left, y); label("Start", left + half + 44, y); label("Dauer", left + half + 120, y);
            y += 10;
            field(left, y, half + 40, fItem, 60, s -> fItem = s);
            field(left + half + 44, y, 72, fStart, 16, s -> fStart = s);
            field(left + half + 120, y, PW - half - 120, fDur, 8, s -> fDur = s);
            y += 22;
            button(() -> "Auktion starten", () -> {
                long st = FloonsAuction.parseInput(fStart.isBlank() ? "0" : fStart);
                int sec = FloonsAuction.parseDuration(fDur);
                if (fItem.isBlank()) { FloonsAuction.info("Erst ein Item eintragen."); return; }
                if (st < 0) { FloonsAuction.info("Startgebot ungültig (z. B. 500k, 1.5m, 2000)."); return; }
                if (sec < 0) { FloonsAuction.info("Dauer ungültig (z. B. 90, 90s, 2m, 1m30s)."); return; }
                String err = FloonsAuction.start(fItem.trim(), st, sec);
                if (err != null) FloonsAuction.info(err); else rebuild();
            }, left, y, PW, 18, true);
        } else {
            y += 22;
            int bw = (PW - 12) / 4;
            button(() -> "Beenden", () -> { FloonsAuction.end(false); rebuild(); }, left, y, bw, 18, true);
            button(() -> "Abbrechen", () -> { FloonsAuction.end(true); rebuild(); }, left + (bw + 4), y, bw, 18);
            button(() -> "+30s", () -> FloonsAuction.extend(30), left + 2 * (bw + 4), y, bw, 18);
            button(() -> "+60s", () -> FloonsAuction.extend(60), left + 3 * (bw + 4), y, bw, 18);
        }
        y += 25;
        y = toggles(y, new Object[][]{
            t("Ansagen", () -> c.announce, v -> c.announce = v),
            t("Countdown", () -> c.countdown, v -> c.countdown = v),
            t("Ton", () -> c.sound, v -> c.sound = v),
            t("Auktions-HUD", () -> c.hud, v -> c.hud = v),
        });
        cycleLong("Mindest-Erhöhung", new long[]{1, 1_000, 10_000, 100_000, 1_000_000, 10_000_000},
            () -> c.minRaise, v -> c.minRaise = v, FloonsAuction::fmt, left, y, half);
        cycleLong("Anti-Snipe", new long[]{0, 5, 10, 15, 30, 60},
            () -> (long) c.antiSnipe, v -> c.antiSnipe = (int) (long) v, v -> v > 0 ? v + "s" : "aus", x2, y, half);
        y += 21;
        String[] corners = {"mid_right", "top_right", "top_left", "mid_left", "bottom_right"};
        String[] names = {"Mitte rechts", "oben rechts", "oben links", "Mitte links", "unten rechts"};
        button(() -> {
            for (int i = 0; i < corners.length; i++) if (corners[i].equals(c.hudCorner)) return "HUD: " + names[i];
            return "HUD: Mitte rechts";
        }, () -> {
            int i = 0;
            for (int k = 0; k < corners.length; k++) if (corners[k].equals(c.hudCorner)) i = k;
            c.hudCorner = corners[(i + 1) % corners.length]; c.save();
        }, left, y, half, 18);
        button(() -> "Rückzahlungen im Chat", () -> { FloonsAuction.refundList(); close(); }, x2, y, half, 18);
    }

    // ---------- Visuals ----------
    private void hud(int y) {
        FloonsConfig c = FloonsConfig.get();
        y = toggles(y, new Object[][]{
            t("Stats-HUD", () -> c.gambleHud, v -> c.gambleHud = v),
            t("Keystrokes", () -> c.keystrokes, v -> c.keystrokes = v),
            t("Koordinaten", () -> c.coords, v -> c.coords = v),
            t("FPS", () -> c.showFps, v -> c.showFps = v),
            t("Ping", () -> c.ping, v -> c.ping = v),
            t("Speed", () -> c.speed, v -> c.speed = v),
            t("Uhrzeit", () -> c.clock, v -> c.clock = v),
            t("Modul-Liste", () -> c.moduleList, v -> c.moduleList = v),
            t("Zahlungs-HUD", () -> c.payHud, v -> c.payHud = v),
            t("Auktions-HUD", () -> c.hud, v -> c.hud = v),
        });
        int half = (PW - 6) / 2;
        y += 4;
        button(() -> "HUD verschieben", () -> client.setScreen(new FloonsHudEditor(this)), left, y, half, 18, true);
        button(() -> "HUD-Größe: " + String.format(Locale.ROOT, "%.1f", c.hudScale), () -> {
            c.hudScale = c.hudScale >= 2.0f ? 0.5f : Math.round((c.hudScale + 0.1f) * 10) / 10f; c.save();
        }, left + half + 6, y, half, 18);
    }

    private void moreHud(int y) {
        FloonsConfig c = FloonsConfig.get();
        y = toggles(y, new Object[][]{
            t("Combo-Zähler", () -> c.comboHud, v -> c.comboHud = v),
            t("System (FPS/RAM)", () -> c.memHud, v -> c.memHud = v),
            t("Status (Leben/Hunger)", () -> c.vitalsHud, v -> c.vitalsHud = v),
            t("Kompass", () -> c.compassHud, v -> c.compassHud = v),
            t("Armor Durability", () -> c.armorHud, v -> c.armorHud = v),
            t("Effekte-HUD", () -> c.potionHud, v -> c.potionHud = v),
            t("Session-HUD", () -> c.sessionHud, v -> c.sessionHud = v),
            t("Notizen-HUD", () -> c.notesHud, v -> c.notesHud = v),
            t("Wegpunkte-HUD", () -> c.wpOn, v -> c.wpOn = v),
            t("Armor-Warnung", () -> c.armorWarn, v -> c.armorWarn = v),
            t("Top-Spieler", () -> c.topHud, v -> c.topHud = v),
            t("Gewinn-Graph", () -> c.graphHud, v -> c.graphHud = v),
        });
        cycleLong("Armor-Warnung bei", new long[]{5, 10, 20, 30},
            () -> (long) c.armorWarnPct, v -> c.armorWarnPct = (int) (long) v, v -> v + " %", left, y, PW);
    }

    private void pvp(int y) {
        FloonsConfig c = FloonsConfig.get();
        label("Nur Anzeigen & Komfort – keine Kampfhilfen.", left, y);
        y += 12;
        y = toggles(y, new Object[][]{
            t("CPS-Anzeige", () -> c.cpsHud, v -> c.cpsHud = v),
            t("Reach-Anzeige", () -> c.reachHud, v -> c.reachHud = v),
            t("Item-Zähler", () -> c.pvpItems, v -> c.pvpItems = v),
            t("Treffer-Marker", () -> c.hitMarker, v -> c.hitMarker = v),
            t("Treffer-Sound", () -> c.hitSound, v -> c.hitSound = v),
            t("Low-HP-Warnung", () -> c.lowHpWarn, v -> c.lowHpWarn = v),
            t("Cooldown-Balken", () -> c.cooldownBar, v -> c.cooldownBar = v),
            t("Saturation-Rand", () -> c.satBorder, v -> c.satBorder = v),
        });
        button(() -> "Treffer-Sound: " + FloonsPvp.SOUNDS[Math.floorMod(c.hitSoundIdx, FloonsPvp.SOUNDS.length)][0], () -> {
            c.hitSoundIdx = (c.hitSoundIdx + 1) % FloonsPvp.SOUNDS.length; c.save(); FloonsPvp.playHit();
        }, left, y, PW, 18);
    }

    private void extras(int y) {
        FloonsConfig c = FloonsConfig.get();
        int half = (PW - 6) / 2;
        y = toggles(y, new Object[][]{
            t("Fullbright", () -> c.fullbright, v -> c.fullbright = v),
            t("NoFog", () -> c.noFog, v -> c.noFog = v),
            t("Auto-Sprint", () -> c.autoSprint, v -> c.autoSprint = v),
        });
        button(() -> "Anti-Leak: " + onOff(c.antiLeak), () -> {
            c.antiLeak = !c.antiLeak; c.save();
            if (!FloonsGlass.setCloak(c.antiLeak)) FloonsGamble.chat("Anti-Leak-Pack nicht gefunden.");
        }, left, y, half, 18);
        button(() -> "Unsichtbares Glas: " + onOff(FloonsGlass.isOn()), () -> {
            if (!FloonsGlass.toggle()) FloonsGamble.chat("Glas-Pack nicht gefunden.");
        }, left + half + 6, y, half, 18);
        y += 21;
        label("Anti-Leak zeigt jeden Block als Netherite und filtert den Chat.", left, y);
        label("Packs laden Texturen neu (kurzer Ruckler). Zoom: Taste C.", left, y + 11);
        y += 26;
        label("Anti-Leak: gesperrte Wörter (Komma getrennt)", left, y);
        field(left, y + 10, PW, c.leakWords, 300, s -> { c.leakWords = s; c.save(); });
        label("Sperrt auch Koordinaten, IPs und deinen PC-Namen.", left, y + 32);
    }

    private void crosshair(int y) {
        FloonsConfig c = FloonsConfig.get();
        int half = (PW - 6) / 2;
        toggle("Crosshair", () -> c.chOn, v -> c.chOn = v, left, y, half);
        button(() -> "Stil: " + FloonsCrosshair.STYLES[Math.floorMod(c.chStyle, FloonsCrosshair.STYLES.length)],
            () -> { FloonsCrosshair.applyStyle(c, c.chStyle + 1); c.save(); }, left + half + 6, y, half, 18, true);
        y += 21;
        stepper(() -> "Länge: " + c.chLen, () -> { c.chLen = Math.max(0, c.chLen - 1); c.chStyle = 5; c.save(); },
            () -> { c.chLen = Math.min(24, c.chLen + 1); c.chStyle = 5; c.save(); }, y);
        y += 21;
        stepper(() -> "Abstand: " + c.chGap, () -> { c.chGap = Math.max(0, c.chGap - 1); c.chStyle = 5; c.save(); },
            () -> { c.chGap = Math.min(16, c.chGap + 1); c.chStyle = 5; c.save(); }, y);
        y += 21;
        stepper(() -> "Dicke: " + c.chThick, () -> { c.chThick = Math.max(1, c.chThick - 1); c.chStyle = 5; c.save(); },
            () -> { c.chThick = Math.min(5, c.chThick + 1); c.chStyle = 5; c.save(); }, y);
        y += 21;
        toggle("Mittelpunkt", () -> c.chDot, v -> { c.chDot = v; c.chStyle = 5; }, left, y, half);
        toggle("Kontur", () -> c.chOutline, v -> c.chOutline = v, left + half + 6, y, half);
        y += 21;
        button(() -> "Farbe: " + FloonsCrosshair.COLORS[Math.floorMod(c.chColorIdx, FloonsCrosshair.COLORS.length)] + (c.chHex != null && !c.chHex.isBlank() ? " (Hex)" : ""),
            () -> { c.chColorIdx = (c.chColorIdx + 1) % FloonsCrosshair.COLORS.length; c.chHex = ""; c.save(); rebuild(); }, left, y, half, 18);
        field(left + half + 6, y, half, c.chHex == null ? "" : c.chHex, 7, s -> { c.chHex = s; c.save(); });
    }

    private void scoreboard(int y) {
        FloonsConfig c = FloonsConfig.get();
        y = toggles(y, new Object[][]{
            t("Eigenes Scoreboard", () -> c.sbCustom, v -> c.sbCustom = v),
            t("Zahlen rechts", () -> c.sbNumbers, v -> c.sbNumbers = v),
            t("Rand", () -> c.sbBorderOn, v -> c.sbBorderOn = v),
            t("Design-Farben", () -> c.sbTheme, v -> c.sbTheme = v),
        });
        stepper(() -> "Größe: " + c.sbScale, () -> { c.sbScale = Math.max(0.5f, Math.round((c.sbScale - 0.1f) * 10) / 10f); c.save(); },
            () -> { c.sbScale = Math.min(2.5f, Math.round((c.sbScale + 0.1f) * 10) / 10f); c.save(); }, y);
        y += 21;
        stepper(() -> "Rundung: " + c.sbRadius, () -> { c.sbRadius = Math.max(0, c.sbRadius - 1); c.save(); },
            () -> { c.sbRadius = Math.min(12, c.sbRadius + 1); c.save(); }, y);
        y += 21;
        stepper(() -> "Deckkraft: " + (c.sbAlpha * 100 / 255) + " %", () -> { c.sbAlpha = Math.max(0, c.sbAlpha - 15); c.save(); },
            () -> { c.sbAlpha = Math.min(255, c.sbAlpha + 15); c.save(); }, y);
        y += 23;
        int fw = (PW - 8) / 3;
        label("Hintergrund", left, y); label("Rand", left + fw + 4, y); label("Text", left + 2 * (fw + 4), y);
        y += 10;
        field(left, y, fw, c.sbBg, 7, s -> { if (FloonsTheme.parseHex(s) != null) { c.sbBg = s; c.save(); } });
        field(left + fw + 4, y, fw, c.sbBorder, 7, s -> { if (FloonsTheme.parseHex(s) != null) { c.sbBorder = s; c.save(); } });
        field(left + 2 * (fw + 4), y, fw, c.sbText, 7, s -> { if (FloonsTheme.parseHex(s) != null) { c.sbText = s; c.save(); } });
    }

    private void custom(int y) {
        FloonsConfig c = FloonsConfig.get();
        while (c.customLines.size() < 5) c.customLines.add("");
        int half = (PW - 6) / 2;
        toggle("Eigenes HUD", () -> c.customHud, v -> c.customHud = v, left, y, half);
        button(() -> "Platzhalter (Chat)", () -> FloonsGamble.chat("Platzhalter: " + String.join(" ", FloonsPanels.TOKENS)), left + half + 6, y, half, 18, true);
        y += 22;
        label("Titel", left, y);
        field(left, y + 10, PW, c.customTitle, 40, s -> { c.customTitle = s; c.save(); });
        y += 32;
        label("Zeilen (leer = ausgeblendet)", left, y);
        y += 10;
        for (int i = 0; i < 5; i++) {
            final int idx = i;
            field(left, y + i * 20, PW, c.customLines.get(i), 80, s -> { c.customLines.set(idx, s); c.save(); });
        }
    }

    // ---------- Tools ----------
    private void waypoints(int y) {
        FloonsConfig c = FloonsConfig.get();
        label("Neuer Wegpunkt an deiner Position", left, y);
        field(left, y + 10, PW - 100, fWp, 24, s -> fWp = s);
        button(() -> "Setzen", () -> {
            String n = fWp.trim().replace(' ', '_');
            if (n.isEmpty()) return;
            FloonsGamble.chat(FloonsWaypoints.addHere(n) ? "Wegpunkt \"" + n + "\" gesetzt." : "Nicht möglich.");
            fWp = ""; rebuild();
        }, left + PW - 96, y + 10, 96, 18, true);
        y += 34;
        toggle("Wegpunkte-HUD", () -> c.wpOn, v -> c.wpOn = v, left, y, PW);
        y += 23;
        int shown = 0;
        for (var w : new ArrayList<>(c.waypoints)) {
            if (shown++ >= 7) { label("+ " + (c.waypoints.size() - 7) + " weitere (/waypoint list)", left, y + 4); break; }
            final String n = w.name;
            label(n + "   " + w.x + " " + w.y + " " + w.z + "   " + w.dim.replace("minecraft:", ""), left, y + 5);
            button(() -> "Löschen", () -> { FloonsWaypoints.remove(n); rebuild(); }, left + PW - 70, y, 70, 18);
            y += 20;
        }
        if (c.waypoints.isEmpty()) label("Noch keine Wegpunkte.", left, y);
    }

    private void notes(int y) {
        FloonsConfig c = FloonsConfig.get();
        label("Neue Notiz", left, y);
        field(left, y + 10, PW - 100, fNote, 120, s -> fNote = s);
        button(() -> "Hinzufügen", () -> {
            if (!fNote.isBlank()) { c.notes.add(fNote.trim()); fNote = ""; c.save(); rebuild(); }
        }, left + PW - 96, y + 10, 96, 18, true);
        y += 34;
        toggle("Notizen-HUD", () -> c.notesHud, v -> c.notesHud = v, left, y, PW);
        y += 23;
        for (int i = 0; i < Math.min(7, c.notes.size()); i++) {
            final int idx = i;
            String n = c.notes.get(i);
            label((i + 1) + ". " + (textRenderer.getWidth(n) > PW - 90 ? textRenderer.trimToWidth(n, PW - 96) + "…" : n), left, y + 5);
            button(() -> "Löschen", () -> { if (idx < c.notes.size()) { c.notes.remove(idx); c.save(); } rebuild(); }, left + PW - 70, y, 70, 18);
            y += 20;
        }
        if (c.notes.isEmpty()) label("Noch keine Notizen.", left, y);
    }

    private void timers(int y) {
        int half = (PW - 6) / 2;
        button(() -> FloonsTimers.swRunning() ? "Stoppuhr stoppen" : "Stoppuhr starten",
            () -> FloonsGamble.chat(FloonsTimers.stopwatch(FloonsTimers.swRunning() ? "stop" : "start")), left, y, half, 18, true);
        button(() -> "Stoppuhr: Runde / Reset", () -> FloonsGamble.chat(FloonsTimers.stopwatch(FloonsTimers.swRunning() ? "lap" : "reset")),
            left + half + 6, y, half, 18);
        y += 21;
        button(() -> "Timer: 1 Minute", () -> { FloonsTimers.addTimer(60, "1 Minute"); FloonsGamble.chat("Timer gestartet: 60 s"); }, left, y, half, 18);
        button(() -> "Timer: 5 Minuten", () -> { FloonsTimers.addTimer(300, "5 Minuten"); FloonsGamble.chat("Timer gestartet: 300 s"); }, left + half + 6, y, half, 18);
        y += 21;
        button(() -> "Timer löschen", () -> FloonsGamble.chat(FloonsTimers.cancelTimers() + " Timer gelöscht."), left, y, half, 18);
        button(() -> "Rechner", () -> client.setScreen(new FloonsCalculatorScreen(this)), left + half + 6, y, half, 18, true);
        y += 21;
        button(() -> "Roulette", () -> client.setScreen(new FloonsRouletteScreen(this)), left, y, half, 18);
        button(() -> "Münzwurf", () -> runCommand("/coinflip"), left + half + 6, y, half, 18);
    }

    // ---------- Commands ----------
    private static final String[][] CMDS = {
        {"Gamble", "/win"}, {"Gamble", "/win Spieler"}, {"Gamble", "/win Spieler 3"}, {"Gamble", "/win all"},
        {"Gamble", "/lose"}, {"Gamble", "/lose Spieler"}, {"Gamble", "/lose all"},
        {"Gamble", "/payback"}, {"Gamble", "/payback Spieler"}, {"Gamble", "/payback all"},
        {"Gamble", "/gamble"}, {"Gamble", "/gamble list"}, {"Gamble", "/gamble reset"}, {"Gamble", "/gamble min 1k"},
        {"Gamble", "/gamble max 1b"}, {"Gamble", "/gamble multiplier 2"}, {"Gamble", "/gamble autopay"}, {"Gamble", "/gamble delay 500"},
        {"Gamble", "/gamble warn 30"}, {"Gamble", "/gamble limit 250k"}, {"Gamble", "/gamble hud"}, {"Gamble", "/gamble effects"}, {"Gamble", "/gamble confetti"},
        {"Auktion", "/wa start 500k 2m Item"}, {"Auktion", "/wa end"}, {"Auktion", "/wa cancel"}, {"Auktion", "/wa extend 30"},
        {"Auktion", "/wa status"}, {"Auktion", "/wa refund"}, {"Auktion", "/wa history"}, {"Auktion", "/wa test Spieler 1k"},
        {"Einstellungen", "/floons"}, {"Einstellungen", "/floons menu"}, {"Einstellungen", "/floons hud"}, {"Einstellungen", "/floons watermark"},
        {"Einstellungen", "/floons announce"}, {"Einstellungen", "/floons countdown"}, {"Einstellungen", "/floons sound"},
        {"Einstellungen", "/floons minraise 10k"}, {"Einstellungen", "/floons antisnipe 15"}, {"Einstellungen", "/floons scale 1"},
        {"Einstellungen", "/floons corner mid_right"}, {"Einstellungen", "/floons pattern <regex>"}, {"Einstellungen", "/floons debug"},
        {"Einstellungen", "/floons lag"}, {"Einstellungen", "/floons perf"}, {"Einstellungen", "/floons perf reset"}, {"Einstellungen", "/floons lite"},
        {"Tools", "/roll 100"}, {"Tools", "/coinflip"}, {"Tools", "/roulette"}, {"Tools", "/calc"}, {"Tools", "/fullbright"}, {"Tools", "/glass"},
        {"Tools", "/stopwatch start"}, {"Tools", "/stopwatch stop"}, {"Tools", "/stopwatch lap"}, {"Tools", "/stopwatch reset"},
        {"Tools", "/timer 60 Name"}, {"Tools", "/timer cancel"},
        {"Notizen", "/note add Text"}, {"Notizen", "/note list"}, {"Notizen", "/note remove 1"}, {"Notizen", "/note clear"},
        {"Wegpunkte", "/waypoint add Name"}, {"Wegpunkte", "/waypoint add Name 0 64 0"}, {"Wegpunkte", "/waypoint list"},
        {"Wegpunkte", "/waypoint remove Name"}, {"Wegpunkte", "/waypoint clear"},
    };
    private static final String[] cmdText = new String[CMDS.length];
    static { for (int i = 0; i < CMDS.length; i++) cmdText[i] = CMDS[i][1]; }
    private static final int PER_PAGE = 8;

    private void runCommand(String text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.player.networkHandler == null) return;
        String c = text.trim();
        if (c.startsWith("/")) c = c.substring(1);
        if (c.isEmpty()) return;
        mc.player.networkHandler.sendChatCommand(c);
    }

    private void commands(int y) {
        int pages = (CMDS.length + PER_PAGE - 1) / PER_PAGE;
        cmdPage = Math.floorMod(cmdPage, pages);
        label("Text anpassen und Ausführen klicken (z. B. Spieler eintragen).", left, y);
        y += 12;
        for (int i = 0; i < PER_PAGE; i++) {
            int idx = cmdPage * PER_PAGE + i;
            if (idx >= CMDS.length) break;
            field(left, y, PW - 154, cmdText[idx], 120, s -> cmdText[idx] = s);
            labels.add(new Label(CMDS[idx][0], left + PW - 148, y + 5, FloonsTheme.BLUE));
            button(() -> "Ausführen", () -> runCommand(cmdText[idx]), left + PW - 80, y, 80, 18, true);
            y += 21;
        }
        y += 2;
        button(() -> "<", () -> { cmdPage--; rebuild(); }, left, y, 30, 18);
        button(() -> "Seite " + (cmdPage + 1) + "/" + pages + "  ·  " + CMDS.length + " Befehle", () -> {}, left + 34, y, PW - 154, 18);
        button(() -> ">", () -> { cmdPage++; rebuild(); }, left + PW - 116, y, 30, 18);
        button(() -> "Reset", () -> { for (int i = 0; i < CMDS.length; i++) cmdText[i] = CMDS[i][1]; rebuild(); }, left + PW - 80, y, 80, 18);
    }

    // ---------- Zeichnen ----------
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fillGradient(0, 0, width, height,
            FloonsTheme.alpha(FloonsTheme.lerp(0xFF05030C, FloonsTheme.PURPLE, 0.15f), 0xC0),
            FloonsTheme.alpha(FloonsTheme.lerp(0xFF05030C, FloonsTheme.BLUE, 0.18f), 0xC0));
        FloonsTheme.panel(ctx, left - 10, top, W, H);
        FloonsLogo.draw(ctx, left, top + 6, 12);
        FloonsTheme.gradientText(ctx, textRenderer, "Floons Client", left + 16, top + 8);
        String v = "v1.0.0 · kein Auto-Update";
        ctx.drawTextWithShadow(textRenderer, v, left + PW - textRenderer.getWidth(v), top + 8, FloonsTheme.MUTED);
    }

    @Override
    protected void extra(DrawContext ctx, int mouseX, int mouseY) {
        for (Label l : labels) ctx.drawTextWithShadow(textRenderer, l.text(), l.x(), l.y(), l.color());
        String pgName = PAGES[tab][Math.floorMod(page[tab], PAGES[tab].length)];
        int y = top + 46;
        if (pgName.equals("Auktion") && FloonsAuction.active) {
            String l1 = FloonsAuction.item + "  ·  noch " + FloonsAuction.mmss(FloonsAuction.left()) + (FloonsAuction.paused ? " (Pause)" : "");
            String l2 = (FloonsAuction.bestName == null ? "kein Gebot (Start " + FloonsAuction.fmt(FloonsAuction.startBid) + ")"
                : FloonsAuction.fmt(FloonsAuction.best) + " von " + FloonsAuction.bestName) + "  ·  " + FloonsAuction.bids.size() + " Bieter";
            ctx.drawTextWithShadow(textRenderer, l1, left, y, FloonsTheme.TEXT);
            ctx.drawTextWithShadow(textRenderer, l2, left, y + 11, FloonsTheme.GOOD);
        } else if (pgName.equals("Gamble")) {
            String cur = FloonsStats.pending.keySet().stream().findFirst().orElse(null);
            String l1 = cur == null ? "Kein offener Einsatz" : "Aktuell: " + cur + "  " + FloonsStats.fmt(FloonsStats.pending.get(cur))
                + "  (" + (FloonsStats.pending.size() + FloonsStats.queuedCount()) + " offen)";
            String l2 = "Profit " + FloonsStats.fmt(FloonsStats.profit()) + "  ·  " + FloonsStats.rounds + " Runden  ·  "
                + String.format(Locale.ROOT, "%.1f%% Winrate", FloonsStats.winrate());
            ctx.drawTextWithShadow(textRenderer, l1, left, y, cur == null ? FloonsTheme.MUTED : FloonsTheme.BLUE);
            ctx.drawTextWithShadow(textRenderer, l2, left, y + 11, FloonsStats.profit() >= 0 ? FloonsTheme.GOOD : FloonsTheme.BAD);
        } else if (pgName.equals("Crosshair")) {
            FloonsCrosshair.draw(ctx, left + PW - 20, top + 30, FloonsConfig.get());
        }
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() { MinecraftClient.getInstance().setScreen(parent); }
}
