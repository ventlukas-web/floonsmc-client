package dev.floons.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Auktion hosten: Gebote sind erkannte Zahlungen an dich.
 * Mehrfachzahlungen derselben Person addieren sich zu einem Gesamtgebot.
 */
public final class FloonsAuction {
    private FloonsAuction() {}

    // ---------- Zustand ----------
    public static boolean active;
    public static boolean paused;          // Verbindung getrennt: Zeit eingefroren
    public static String item = "";
    public static long startBid, best;
    public static String bestName;
    public static long endAt, durationMs, pausedLeft;
    public static long flashUntil;
    public static final Map<String, Long> bids = new LinkedHashMap<>();
    private static int lastCountdown = -1;
    /** Sofortkauf-Preis und Mindestpreis der laufenden Auktion (0 = aus). */
    public static long buyNow, reserve;
    /** Für die nächste Auktion vorgemerkt (per /action buynow / reserve ohne laufende Auktion). */
    public static long nextBuyNow, nextReserve;
    public static boolean manualPause;
    public static long startedAt;

    /** Live-Gebotsverlauf (neueste zuerst). */
    public record Bid(String name, long amount, long total, long at, boolean top) {}
    public static final List<Bid> feed = new ArrayList<>();

    /** Warteschlange: nächste Auktionen. */
    public record Queued(String item, long start, int sec, long buyNow, long reserve) {}
    public static final List<Queued> queue = new ArrayList<>();
    private static long queueStartAt;
    private static final int[] COUNTDOWN = {60, 30, 10, 5};

    /** Letzte Auktion (für /action refund nach dem Ende). */
    private static final Map<String, Long> lastBids = new LinkedHashMap<>();
    private static String lastWinner;

    // ---------- Verlauf ----------
    public static final class Entry {
        public String item, winner;
        public long amount, total, time;
        public int bidders;
    }
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path HIST = FabricLoader.getInstance().getConfigDir().resolve("floonsclient-history.json");
    private static List<Entry> history;

    public static List<Entry> history() {
        if (history == null) {
            try {
                if (Files.exists(HIST)) history = GSON.fromJson(Files.readString(HIST), new TypeToken<List<Entry>>() {}.getType());
            } catch (Exception ignored) {}
            if (history == null) history = new ArrayList<>();
        }
        return history;
    }

    private static void saveHistory() {
        try { Files.writeString(HIST, GSON.toJson(history())); } catch (Exception ignored) {}
    }

    // ---------- Chat ----------
    public static void say(String s) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        if (FloonsConfig.get().announce && mc.player.networkHandler != null) mc.player.networkHandler.sendChatMessage(s);
        else info(s);
    }

    /** Neue Info: als Pille unten rechts (reinfliegend); lange Texte zusätzlich im Chat. */
    public static void info(String s) {
        FloonsConfig c = FloonsConfig.get();
        if (c.notify && s.length() <= 90) { FloonsNotify.push(pillTitle(s), pillMsg(s), pillColor(s)); return; }
        info(Text.literal(s).formatted(Formatting.AQUA));
    }

    /** "Titel: Rest" wird zu Titel + Text, sonst "Floons" als Titel. */
    private static String pillTitle(String s) {
        int i = s.indexOf(": ");
        return i > 0 && i <= 28 ? s.substring(0, i) : "Floons";
    }

    private static String pillMsg(String s) {
        int i = s.indexOf(": ");
        return i > 0 && i <= 28 ? s.substring(i + 2) : s;
    }

    private static int pillColor(String s) {
        String l = s.toLowerCase();
        if (l.contains("ungültig") || l.contains("keine ") || l.contains("kein ") || l.contains("nicht") || l.contains("fehl")) return FloonsTheme.BAD;
        if (l.contains("gewinn") || l.contains("gesetzt") || l.contains("gespeichert") || l.contains("gestartet") || l.contains(": an")) return FloonsTheme.GOOD;
        return FloonsTheme.BLUE;
    }

    public static void info(Text t) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        mc.player.sendMessage(Text.empty().append(FloonsLogo.glyph()).append(Text.literal(" [Floons] ").formatted(Formatting.DARK_AQUA)).append(t), false);
    }

    // ---------- Steuerung ----------
    public static String start(String it, long start, int sec) {
        if (active) return "Es läuft schon eine Auktion (/action end oder /action cancel).";
        if (sec < 5) return "Dauer mindestens 5 Sekunden.";
        active = true; paused = false;
        item = it; startBid = start; best = start; bestName = null;
        bids.clear(); feed.clear();
        buyNow = nextBuyNow; reserve = nextReserve; nextBuyNow = 0; nextReserve = 0;
        manualPause = false;
        durationMs = sec * 1000L;
        startedAt = System.currentTimeMillis();
        endAt = startedAt + durationMs;
        lastCountdown = sec;
        queueStartAt = 0;
        say("[Auktion] " + item + " | Start " + fmt(start) + " | " + mmss(durationMs)
            + (buyNow > 0 ? " | Sofortkauf " + fmt(buyNow) : "") + (reserve > 0 ? " | Mindestpreis" : "")
            + " | Bieten: /pay an mich, Zahlungen addieren sich");
        FloonsNotify.push("Auktion gestartet", item, FloonsTheme.BLUE);
        return null;
    }

    public static String extend(int sec) {
        if (!active) return "Keine Auktion aktiv.";
        if (paused) pausedLeft += sec * 1000L; else endAt += sec * 1000L;
        long add = sec * 1000L;
        durationMs += add;
        lastCountdown = (int) (left() / 1000) + 1;
        say("[Auktion] +" + sec + "s für " + item + " | noch " + mmss(left()));
        return null;
    }

    public static String end(boolean cancel) {
        if (!active) return "Keine Auktion aktiv.";
        active = false; paused = false;
        lastBids.clear(); lastBids.putAll(bids);
        if (cancel) {
            lastWinner = null;
            say("[Auktion] " + item + " wurde abgebrochen. Alle Gebote werden zurückgezahlt.");
            refundList();
            return null;
        }
        lastWinner = bestName;
        long total = 0;
        for (long v : bids.values()) total += v;
        Entry e = new Entry();
        e.item = item; e.winner = bestName; e.amount = bestName == null ? 0 : best;
        e.total = total; e.bidders = bids.size(); e.time = System.currentTimeMillis();
        history().add(0, e);
        while (history().size() > 50) history().remove(history().size() - 1);
        saveHistory();
        if (bestName == null) {
            say("[Auktion] " + item + " beendet – keine Gebote.");
            refundList();
            scheduleNext();
            return null;
        }
        if (reserve > 0 && best < reserve) {
            e.winner = null; e.amount = 0; saveHistory();
            lastWinner = null;
            say("[Auktion] " + item + " nicht verkauft – Mindestpreis nicht erreicht. Alle Gebote werden zurückgezahlt.");
            FloonsHud.overlay(false, "NICHT VERKAUFT", item + "  ·  Mindestpreis " + fmt(reserve));
            refundList();
            scheduleNext();
            return null;
        }
        say("[Auktion] " + item + " geht an " + bestName + " für " + fmt(best) + "!");
        FloonsHud.overlay(true, "VERKAUFT", item + "  ·  " + bestName + "  ·  " + fmt(best));
        long over = bids.getOrDefault(bestName, 0L) - best;
        if (over > 0) info(bestName + " hat " + fmt(over) + " mehr gezahlt als nötig.");
        refundList();
        scheduleNext();
        return null;
    }

    /** Lokale Liste: jede Rückzahlung als Klick, der /pay nur in den Chat einträgt (du bestätigst selbst mit Enter). */
    public static void refundList() {
        List<Map.Entry<String, Long>> l = new ArrayList<>();
        for (var e : lastBids.entrySet()) if (!e.getKey().equals(lastWinner)) l.add(e);
        if (l.isEmpty()) { info("Keine Rückzahlungen offen."); return; }
        long sum = 0;
        for (var e : l) sum += e.getValue();
        info("Rückzahlungen: " + l.size() + " Spieler, " + fmt(sum) + " gesamt");
        for (var e : l) {
            String cmd = "/pay " + e.getKey() + " " + e.getValue();
            MutableText btn = Text.literal(" [Zurückzahlen]").styled(st -> st
                .withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent.SuggestCommand(cmd))
                .withHoverEvent(new HoverEvent.ShowText(Text.literal(cmd))));
            info(Text.literal(e.getKey() + "  " + fmt(e.getValue())).formatted(Formatting.WHITE).append(btn));
        }
    }

    /** Alles zurücksetzen: laufende Auktion still stoppen, alle Gebote/Zahler und die Rückzahlungsliste vergessen. */
    public static String reset() {
        boolean was = active;
        active = false; paused = false; manualPause = false;
        item = ""; bestName = null; best = 0; startBid = 0;
        bids.clear(); feed.clear(); lastBids.clear(); lastWinner = null;
        buyNow = 0; reserve = 0; nextBuyNow = 0; nextReserve = 0;
        queueStartAt = 0;
        info("Auktion zurückgesetzt" + (was ? " (laufende Auktion gestoppt)" : "") + ": alle Zahler und Gebote gelöscht.");
        return null;
    }

    /** Nur die Gebote zurücksetzen, die Auktion läuft weiter (Höchstgebot wieder auf Start). */
    public static String resetBids() {
        if (!active) return "Keine Auktion aktiv – /action reset löscht die alte Zahler-Liste.";
        bids.clear(); feed.clear(); bestName = null; best = startBid;
        say("[Auktion] Gebote für " + item + " wurden zurückgesetzt – neu bieten ab " + fmt(startBid) + ".");
        return null;
    }

    /** Gebot eines einzelnen Spielers entfernen (laufende Auktion oder Rückzahlungsliste). */
    public static String resetPlayer(String name) {
        Map<String, Long> m = active ? bids : lastBids;
        String key = null;
        for (String k : m.keySet()) if (k.replaceFirst("^\\.", "").equalsIgnoreCase(name.replaceFirst("^\\.", ""))) { key = k; break; }
        if (key == null) return name + " hat nichts gezahlt.";
        long v = m.remove(key);
        if (active && key.equals(bestName)) {
            bestName = null; best = startBid;
            for (var e : top()) { long need = bestName == null ? startBid : best; if (e.getValue() >= need) { bestName = e.getKey(); best = e.getValue(); } break; }
        }
        final String k2 = key;
        feed.removeIf(b -> b.name().equals(k2));
        info(key + " (" + fmt(v) + ") entfernt.");
        return null;
    }

    /** Eigenes /pay an einen Bieter der letzten Auktion mit genau seinem Gebot = Rückzahlung (zählt nicht fürs Gamble). */
    public static boolean isRefund(String player, long amount) {
        String key = null;
        for (String k : lastBids.keySet())
            if (k.replaceFirst("^\\.", "").equalsIgnoreCase(player.replaceFirst("^\\.", ""))) { key = k; break; }
        if (key == null || key.equals(lastWinner) || lastBids.get(key) != amount) return false;
        lastBids.remove(key);
        return true;
    }

    /** @return true, wenn die Zahlung als Gebot gewertet wurde. */
    public static boolean onPayment(String player, long amount) {
        if (!active) return false;
        long total = bids.merge(player, amount, Long::sum);
        FloonsConfig c = FloonsConfig.get();
        long need = bestName == null ? startBid : best + Math.max(1, c.minRaise);
        boolean top = player.equals(bestName) || total >= need;
        feed.add(0, new Bid(player, amount, total, System.currentTimeMillis(), top));
        while (feed.size() > 8) feed.remove(feed.size() - 1);
        if (buyNow > 0 && total >= buyNow) {
            best = total; bestName = player;
            say("[Auktion] SOFORTKAUF! " + player + " zahlt " + fmt(total) + " für " + item);
            end(false);
            return true;
        }
        if (player.equals(bestName)) {
            best = total;
            info(player + " erhöht sein eigenes Gebot auf " + fmt(total) + ".");
            flashUntil = System.currentTimeMillis() + 800;
            String snipe = antiSnipe();
            if (!snipe.isEmpty()) say("[Auktion] " + player + " erhöht auf " + fmt(total) + snipe);
            return true;
        }
        if (total >= need) {
            best = total; bestName = player;
            flashUntil = System.currentTimeMillis() + 800;
            String snipe = antiSnipe();
            say("[Auktion] Höchstgebot " + fmt(total) + " von " + player + snipe);
            MinecraftClient mc = MinecraftClient.getInstance();
            if (c.sound && mc.player != null) mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 0.6f, 1.4f);
        } else {
            info(player + " steht bei " + fmt(total) + " – zu wenig, nötig: " + fmt(need) + ".");
        }
        return true;
    }

    /** Mehr Geld in den letzten X Sekunden -> Restzeit wieder auf Y Sekunden. @return Zusatz für die Ansage oder "". */
    public static long snipeFlashUntil;
    private static String antiSnipe() {
        FloonsConfig c = FloonsConfig.get();
        if (!c.antiSnipeOn || paused) return "";
        long window = Math.max(1, c.antiSnipeWindow) * 1000L, to = Math.max(c.antiSnipeWindow + 1, c.antiSnipeTo) * 1000L;
        long l = left();
        if (l >= window) return "";
        long add = to - l;
        endAt += add; durationMs += add;
        lastCountdown = (int) (to / 1000) + 1;
        snipeFlashUntil = System.currentTimeMillis() + 2000;
        return " | Anti-Snipe: wieder " + (to / 1000) + "s";
    }

    // ---------- Zeit ----------
    public static long left() {
        return Math.max(0, paused ? pausedLeft : endAt - System.currentTimeMillis());
    }

    public static void pause() {
        if (!active || paused) return;
        pausedLeft = left(); paused = true;
    }

    /** /action pause: von Hand angehalten (Wiederbeitritt setzt nicht automatisch fort). */
    public static String pauseManual() {
        if (!active) return "Keine Auktion aktiv.";
        if (paused) return "Auktion ist schon pausiert.";
        pause(); manualPause = true;
        say("[Auktion] " + item + " pausiert (noch " + mmss(left()) + ").");
        return null;
    }

    public static String resumeManual() {
        if (!active) return "Keine Auktion aktiv.";
        if (!paused) return "Auktion läuft bereits.";
        manualPause = false;
        resume();
        say("[Auktion] " + item + " läuft weiter: noch " + mmss(left()) + ".");
        return null;
    }

    /** Beim Wiederbeitritt: nur fortsetzen, wenn nicht von Hand pausiert. */
    public static void resumeAfterJoin() { if (!manualPause) resume(); }

    // ---------- Warteschlange ----------
    private static void scheduleNext() {
        if (!queue.isEmpty() && FloonsConfig.get().autoQueue) {
            queueStartAt = System.currentTimeMillis() + Math.max(3, FloonsConfig.get().queueGapSec) * 1000L;
            info("Nächste Auktion in " + Math.max(3, FloonsConfig.get().queueGapSec) + "s: " + queue.get(0).item());
        }
    }

    public static String startNext() {
        if (active) return "Es läuft schon eine Auktion.";
        if (queue.isEmpty()) return "Warteschlange ist leer.";
        Queued q = queue.remove(0);
        nextBuyNow = q.buyNow(); nextReserve = q.reserve();
        return start(q.item(), q.start(), q.sec());
    }

    public static long queueStartsIn() { return queueStartAt == 0 ? -1 : Math.max(0, queueStartAt - System.currentTimeMillis()); }

    public static void resume() {
        if (!active || !paused) return;
        endAt = System.currentTimeMillis() + pausedLeft; paused = false;
        info("Auktion " + item + " läuft weiter: noch " + mmss(left()) + ".");
    }

    public static void tick() {
        if (!active && queueStartAt > 0 && System.currentTimeMillis() >= queueStartAt) {
            queueStartAt = 0;
            String err = startNext();
            if (err != null) info(err);
        }
        if (!active || paused) return;
        long l = left();
        if (l <= 0) { end(false); return; }
        if (!FloonsConfig.get().countdown) return;
        int s = (int) ((l + 999) / 1000);
        for (int c : COUNTDOWN) {
            if (s <= c && lastCountdown > c) {
                lastCountdown = c;
                say("[Auktion] Noch " + c + "s | " + item + " | " + (bestName == null ? "kein Gebot (Start " + fmt(startBid) + ")" : fmt(best) + " von " + bestName));
                break;
            }
        }
    }

    // ---------- Statistik ----------
    public static void stats() {
        List<Entry> h = history();
        long sold = 0, sum = 0, maxV = 0; String maxItem = "–";
        Map<String, Long> buyers = new LinkedHashMap<>();
        for (Entry e : h) {
            if (e.winner == null) continue;
            sold++; sum += e.amount;
            if (e.amount > maxV) { maxV = e.amount; maxItem = e.item; }
            buyers.merge(e.winner, e.amount, Long::sum);
        }
        info("Auktionen: " + h.size() + " | verkauft " + sold + " | Erlös " + fmt(sum) + (sold > 0 ? " | Schnitt " + fmt(sum / sold) : ""));
        if (sold > 0) info("Teuerstes: " + maxItem + " für " + fmt(maxV));
        String topB = null; long topV = 0;
        for (var b : buyers.entrySet()) if (b.getValue() > topV) { topV = b.getValue(); topB = b.getKey(); }
        if (topB != null) info("Bester Käufer: " + topB + " (" + fmt(topV) + ")");
    }

    // ---------- Anzeige-Daten ----------
    public static List<Map.Entry<String, Long>> top() {
        List<Map.Entry<String, Long>> t = new ArrayList<>(bids.entrySet());
        t.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
        return t;
    }

    public static long total() {
        long s = 0;
        for (long v : bids.values()) s += v;
        return s;
    }

    // ---------- Format ----------
    public static String fmt(long v) {
        double a = Math.abs((double) v);
        if (a >= 1e12) return trim(v / 1e12) + "T";
        if (a >= 1e9) return trim(v / 1e9) + "B";
        if (a >= 1e6) return trim(v / 1e6) + "M";
        if (a >= 1e3) return trim(v / 1e3) + "k";
        return Long.toString(v);
    }

    private static String trim(double d) {
        String s = String.format(Locale.ROOT, "%.2f", d);
        return s.replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    public static String mmss(long ms) {
        long s = Math.max(0, (ms + 999) / 1000);
        return (s / 60) + ":" + String.format(Locale.ROOT, "%02d", s % 60);
    }

    /** "1,500" / "2.5" + K/M/B/T -> long. Komma = Tausender, Punkt = Dezimal. */
    public static long parseAmount(String num, String suffix) {
        java.math.BigDecimal v = new java.math.BigDecimal(num.replace(",", "").replaceAll("\\.$", ""));
        long f = switch (suffix == null ? "" : suffix.toLowerCase(Locale.ROOT)) {
            case "k" -> 1_000L;
            case "m" -> 1_000_000L;
            case "b" -> 1_000_000_000L;
            case "t" -> 1_000_000_000_000L;
            default -> 1L;
        };
        return v.multiply(java.math.BigDecimal.valueOf(f)).longValue();
    }

    /** "2.5m", "500k", "1500" -> long; -1 bei Fehler. */
    public static long parseInput(String s) {
        try {
            String t = s.trim().replace("$", "");
            String suf = t.replaceAll("[^KkMmBbTt]", "");
            return parseAmount(t.replaceAll("[KkMmBbTt]", ""), suf);
        } catch (Exception e) { return -1; }
    }

    /** "90", "90s", "2m", "1m30s" -> Sekunden; -1 bei Fehler. */
    public static int parseDuration(String s) {
        String t = s.trim().toLowerCase(Locale.ROOT);
        if (t.matches("\\d+")) return Integer.parseInt(t);
        var m = java.util.regex.Pattern.compile("^(?:(\\d+)m)?(?:(\\d+)s)?$").matcher(t);
        if (!m.matches() || t.isEmpty()) return -1;
        int min = m.group(1) == null ? 0 : Integer.parseInt(m.group(1));
        int sec = m.group(2) == null ? 0 : Integer.parseInt(m.group(2));
        return min * 60 + sec;
    }
}
