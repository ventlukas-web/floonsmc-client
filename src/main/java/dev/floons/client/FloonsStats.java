package dev.floons.client;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Session-Statistik aus Sicht des Hauses. */
public class FloonsStats {
    public static long income, payouts;
    public static int rounds, wins, losses;
    public static final Map<String, Long> pending = new LinkedHashMap<>();
    public static String lastPayer;
    /** Weitere Zahlungen desselben Spielers: kommen der Reihe nach dran, sobald die vorherige abgeschlossen ist. */
    public static final Map<String, java.util.ArrayDeque<Long>> queued = new LinkedHashMap<>();
    public static int queuedCount() { int n = 0; for (var q : queued.values()) n += q.size(); return n; }
    public static final Map<String, Long> pendingAt = new java.util.HashMap<>();
    private static final Map<String, Long> lastWarn = new java.util.HashMap<>();

    public static long waitedSec(String name) {
        Long t = pendingAt.get(name);
        return t == null ? 0 : (System.currentTimeMillis() - t) / 1000;
    }

    public static String clock(long sec) { return String.format(Locale.ROOT, "%d:%02d", sec / 60, sec % 60); }

    /** Meldet Zahlungen, die schon lange warten (Toast + Ton), im eingestellten Abstand. */
    public static void checkWait() {
        FloonsConfig cfg = FloonsConfig.get();
        long now = System.currentTimeMillis();
        pendingAt.keySet().removeIf(k -> !pending.containsKey(k));
        lastWarn.keySet().removeIf(k -> !pending.containsKey(k));
        for (String n : pending.keySet()) pendingAt.putIfAbsent(n, now);
        if (cfg.waitWarnSec <= 0) return;
        for (String n : pending.keySet()) {
            long since = Math.max(pendingAt.get(n), lastWarn.getOrDefault(n, 0L));
            if (now - since < cfg.waitWarnSec * 1000L) continue;
            lastWarn.put(n, now);
            FloonsNotify.push("Wartet seit " + clock(waitedSec(n)), n + " · " + fmt(pending.get(n)), 0xFFFBBF24);
            if (cfg.sound) FloonsGamble.playId("block.note_block.bell");
        }
    }
    /** Abgeschlossene Zahlungen (result 1 = bezahlt/gewonnen, 2 = verloren). */
    public record Done(String name, long amount, int result, long at) {}
    public static final java.util.List<Done> done = new java.util.ArrayList<>();
    public static void finish(String name, long amount, boolean win) {
        done.add(new Done(name, amount, win ? 1 : 2, System.currentTimeMillis()));
        if (done.size() > 20) done.remove(0);
    }

    /** Pro Spieler: {Runden, Siege, Einsatz gesamt, Gewinn fuers Haus}. */
    public static final Map<String, long[]> players = new LinkedHashMap<>();
    /** Ergebnis je Runde aus Sicht des Hauses (+ = Haus gewinnt). */
    public static final java.util.List<Long> roundNet = new java.util.ArrayList<>();
    private static boolean limitHit;

    public static void recordRound(String name, long bet, boolean win, long payout) {
        long[] p = players.computeIfAbsent(name.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""), k -> new long[4]);
        p[0]++; if (win) p[1]++; p[2] += bet;
        long net = win ? bet - payout : bet;
        p[3] += net;
        roundNet.add(net);
        if (roundNet.size() > 60) roundNet.remove(0);
    }

    /** Freie Auszahlung ohne offene Zahlung (z.B. manuelles /pay): senkt Profit, Spieler-Bilanz und Diagramm. */
    public static void recordPayout(String name, long amount) {
        long[] p = players.computeIfAbsent(name.toLowerCase(Locale.ROOT).replaceFirst("^\\.", ""), k -> new long[4]);
        p[3] -= amount;
        payouts += amount;
        roundNet.add(-amount);
        if (roundNet.size() > 60) roundNet.remove(0);
        done.add(new Done(name, amount, 1, System.currentTimeMillis()));
        if (done.size() > 20) done.remove(0);
    }

    /** Warnt einmal, wenn der Session-Verlust das eingestellte Limit erreicht. */
    public static void checkLimit() {
        FloonsConfig cfg = FloonsConfig.get();
        if (cfg.lossLimit <= 0) { limitHit = false; return; }
        boolean over = profit() <= -cfg.lossLimit;
        if (over && !limitHit) {
            limitHit = true;
            FloonsNotify.push("Verlust-Limit erreicht", "Session: " + fmt(profit()), 0xFFFB7185);
            if (cfg.sound) FloonsGamble.playId("block.anvil.land");
        } else if (!over) limitHit = false;
    }

    public static long profit() { return income - payouts; }
    public static double winrate() { return rounds == 0 ? 0 : 100.0 * wins / rounds; }

    public static void reset() {
        income = payouts = 0; rounds = wins = losses = 0;
        players.clear(); roundNet.clear(); limitHit = false;
        pending.clear(); queued.clear(); pendingAt.clear(); lastWarn.clear(); done.clear(); lastPayer = null;
    }

    private static String trim(double d) {
        return String.format(Locale.ROOT, "%.2f", d).replaceAll("0+$", "").replaceAll("\\.$", "");
    }

    /** 1500 -> 1.5k */
    public static String fmt(long v) {
        double a = Math.abs((double) v);
        if (a >= 1e12) return trim(v / 1e12) + "T";
        if (a >= 1e9) return trim(v / 1e9) + "B";
        if (a >= 1e6) return trim(v / 1e6) + "M";
        if (a >= 1e3) return trim(v / 1e3) + "k";
        return Long.toString(v);
    }
}
