package dev.floons.client;

import com.mojang.brigadier.arguments.DoubleArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Gamble-Funktionen wie im Toasty Client: Zahlungen werden als Einsatz erfasst (Warteschlange je Spieler),
 * /win zahlt Einsatz x Multiplikator aus, /lose behält ihn, /payback zahlt den Einsatz zurück.
 */
public final class FloonsGamble {
    private FloonsGamble() {}

    static int pendingScreen = 0; // 1 = Menü, 2 = Rechner, 8 = Roulette (nach dem Schließen des Chats öffnen)

    // ---------- Hilfen ----------
    public static void chat(String text) { FloonsAuction.info(text); }

    public static void playId(String id, float vol, float pitch) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        SoundEvent ev = Registries.SOUND_EVENT.get(Identifier.of("minecraft", id));
        if (ev != null) try { mc.player.playSound(ev, vol, pitch); } catch (Throwable ignored) {}
    }

    public static void playId(String id) { playId(id, 1.0f, 1.0f); }

    private static int run(String err) {
        if (err != null) { chat(err); return 0; }
        return 1;
    }

    // ---------- Einsatz erfassen ----------
    public static void onPayment(String player, long amount) {
        FloonsConfig cfg = FloonsConfig.get();
        if (amount < cfg.minBet || amount > cfg.maxBet) {
            chat(player + " hat " + FloonsStats.fmt(amount) + " gezahlt – außerhalb Min/Max, ignoriert.");
            return;
        }
        if (FloonsStats.pending.containsKey(player)) {
            FloonsStats.queued.computeIfAbsent(player, k -> new java.util.ArrayDeque<>()).add(amount);
            chat(player + " hat " + FloonsStats.fmt(amount) + " gezahlt – in der Warteschlange (" + FloonsStats.queued.get(player).size() + ").");
        } else {
            FloonsStats.pending.put(player, amount);
            FloonsStats.pendingAt.put(player, System.currentTimeMillis());
        }
        FloonsStats.income += amount;
        FloonsStats.lastPayer = player;
        FloonsHud.flash(FloonsTheme.PURPLE);
    }

    // ---------- /win /lose /payback ----------
    private static String resolve(String player) {
        String p = player != null ? player : FloonsStats.pending.keySet().stream().findFirst().orElse(null); // ohne Namen: der Älteste
        if (p != null) for (String k : FloonsStats.pending.keySet())
            if (k.equalsIgnoreCase(p) || k.equalsIgnoreCase("." + p) || ("." + k).equalsIgnoreCase(p)) { p = k; break; }
        return p;
    }

    private static boolean isAll(String p) { return p != null && (p.equalsIgnoreCase("all") || p.equalsIgnoreCase("alle")); }

    public static String settle(String player, boolean win, double mult) {
        if (isAll(player)) {
            int n = FloonsStats.pending.size() + FloonsStats.queuedCount();
            if (n == 0) return "Keine offene Zahlung.";
            for (int guard = 0; guard < 10000 && !FloonsStats.pending.isEmpty(); guard++)
                settle(FloonsStats.pending.keySet().iterator().next(), win, mult);
            chat(n + " Zahlungen abgeschlossen.");
            return null;
        }
        String p = resolve(player);
        Long bet = p == null ? null : FloonsStats.pending.remove(p);
        if (bet == null) return "Keine offene Zahlung" + (p != null ? " von " + p : "") + ".";
        FloonsConfig cfg = FloonsConfig.get();
        FloonsStats.rounds++;
        if (win) {
            long payout = Math.round(bet * (mult > 0 ? mult : cfg.winMultiplier));
            FloonsStats.payouts += payout;
            FloonsStats.wins++;
            FloonsStats.recordRound(p, bet, true, payout);
            FloonsStats.finish(p, payout, true);
            effect(true, p, payout);
            if (cfg.autoPay && payout > 0) sendPay(p, payout);
            else chat(p + " gewinnt " + FloonsStats.fmt(payout) + " – Auto-Pay aus, selbst auszahlen.");
        } else {
            FloonsStats.losses++;
            FloonsStats.recordRound(p, bet, false, 0);
            FloonsStats.finish(p, bet, false);
            effect(false, p, bet);
        }
        promote(p);
        fixLast(p);
        return null;
    }

    public static String payback(String player) {
        if (isAll(player)) {
            int n = FloonsStats.pending.size() + FloonsStats.queuedCount();
            if (n == 0) return "Keine offene Zahlung.";
            for (int guard = 0; guard < 10000 && !FloonsStats.pending.isEmpty(); guard++)
                payback(FloonsStats.pending.keySet().iterator().next());
            chat(n + " Zahlungen werden zurückgezahlt.");
            return null;
        }
        String p = resolve(player);
        Long bet = p == null ? null : FloonsStats.pending.remove(p);
        if (bet == null) return "Keine offene Zahlung" + (p != null ? " von " + p : "") + ".";
        FloonsStats.recordPayout(p, bet);
        sendPay(p, bet);
        promote(p);
        fixLast(p);
        chat("Zurückgezahlt: " + FloonsStats.fmt(bet) + " an " + p);
        return null;
    }

    private static void promote(String p) {
        var q = FloonsStats.queued.get(p);
        if (q == null) return;
        Long nx = q.poll();
        if (q.isEmpty()) FloonsStats.queued.remove(p);
        if (nx != null) {
            FloonsStats.pending.put(p, nx);
            FloonsStats.pendingAt.put(p, System.currentTimeMillis());
            chat("Nächste Zahlung von " + p + ": " + FloonsStats.fmt(nx));
        }
    }

    private static void fixLast(String p) {
        if (p.equals(FloonsStats.lastPayer))
            FloonsStats.lastPayer = FloonsStats.pending.keySet().stream().reduce((a, b) -> b).orElse(null);
    }

    private static void effect(boolean win, String player, long amount) {
        FloonsConfig cfg = FloonsConfig.get();
        FloonsHud.flash(win ? FloonsTheme.GOOD : FloonsTheme.BAD);
        if (cfg.effects) FloonsHud.overlay(win, win ? "GEWONNEN" : "VERLOREN", player + "  ·  " + FloonsStats.fmt(amount));
        if (cfg.sound) playId(win ? "entity.player.levelup" : "entity.villager.no");
    }

    // ---------- Auszahlen ----------
    private static final java.util.ArrayDeque<Object[]> PAYQ = new java.util.ArrayDeque<>();
    private static long nextPay;
    private static boolean autoPaying;

    private static void sendPay(String player, long amount) {
        PAYQ.add(new Object[]{player, amount});
        payTick();
    }

    public static void payTick() {
        if (PAYQ.isEmpty()) return;
        long now = System.currentTimeMillis();
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.player.networkHandler == null) { PAYQ.clear(); return; }
        int delay = Math.max(0, FloonsConfig.get().payDelayMs);
        while (!PAYQ.isEmpty() && now >= nextPay) {
            Object[] e = PAYQ.poll();
            String pl = (String) e[0]; long amount = (Long) e[1];
            autoPaying = true;
            try { mc.player.networkHandler.sendChatCommand("pay " + pl + " " + amount); } finally { autoPaying = false; }
            chat("Auszahlung: /pay " + pl + " " + FloonsStats.fmt(amount));
            nextPay = now + delay;
            if (delay > 0) break;
        }
    }

    private static final Pattern PAY_CMD = Pattern.compile(
        "^pay\\s+(\\.?[A-Za-z0-9_]{1,16})\\s+\\$?([0-9][0-9.,]*\\s*[KkMmBbTt]?)\\s*$", Pattern.CASE_INSENSITIVE);

    /** Eigenes "/pay <name> <betrag>" von Hand: schließt die offene Zahlung als Gewinn ab bzw. zählt als Auszahlung. */
    public static void onOwnCommand(String cmd) {
        if (autoPaying) return;
        Matcher m = PAY_CMD.matcher(cmd.trim());
        if (!m.matches()) return;
        long amount;
        try {
            String raw = m.group(2).replaceAll("\\s", "");
            amount = FloonsAuction.parseAmount(raw.replaceAll("[KkMmBbTt]", ""), raw.replaceAll("[^KkMmBbTt]", ""));
        } catch (Exception e) { return; }
        if (FloonsAuction.isRefund(m.group(1), amount)) return; // Auktions-Rückzahlung, kein Gamble
        String p = m.group(1);
        for (String k : FloonsStats.pending.keySet())
            if (k.replaceFirst("^\\.", "").equalsIgnoreCase(p.replaceFirst("^\\.", ""))) { p = k; break; }
        Long bet = FloonsStats.pending.remove(p);
        if (bet != null) {
            FloonsStats.payouts += amount;
            FloonsStats.rounds++; FloonsStats.wins++;
            FloonsStats.recordRound(p, bet, true, amount);
            FloonsStats.finish(p, amount, true);
            promote(p);
            fixLast(p);
            effect(true, p, amount);
        } else {
            FloonsStats.recordPayout(p, amount);
            FloonsHud.flash(FloonsTheme.GOOD);
        }
    }

    // ---------- Zufall ----------
    private static void roll(int max) {
        int v = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, max + 1);
        int col = v == max ? 0xFF4ADE80 : v == 1 ? 0xFFFB7185 : 0xFF7DD3FC;
        FloonsNotify.push("Roll 1–" + max, String.valueOf(v), col);
        chat("Roll (1–" + max + "): " + v);
    }

    public static void tick(MinecraftClient mc) {
        FloonsStats.checkWait();
        FloonsStats.checkLimit();
        payTick();
        if (pendingScreen == 1) { pendingScreen = 0; mc.setScreen(new FloonsMenu(null)); }
        if (pendingScreen == 4) { pendingScreen = 0; mc.setScreen(new FloonsHudEditor(null)); }
        if (pendingScreen == 2) { pendingScreen = 0; mc.setScreen(new FloonsCalculatorScreen(null)); }
        if (pendingScreen == 8) { pendingScreen = 0; mc.setScreen(new FloonsRouletteScreen(null)); }
    }

    // ---------- Befehle ----------
    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("win")
            .executes(c -> run(settle(null, true, -1)))
            .then(ClientCommandManager.argument("spieler", StringArgumentType.word())
                .executes(c -> run(settle(StringArgumentType.getString(c, "spieler"), true, -1)))
                .then(ClientCommandManager.argument("multiplikator", DoubleArgumentType.doubleArg(0.01, 1000))
                    .executes(c -> run(settle(StringArgumentType.getString(c, "spieler"), true, DoubleArgumentType.getDouble(c, "multiplikator")))))));
        d.register(ClientCommandManager.literal("lose")
            .executes(c -> run(settle(null, false, -1)))
            .then(ClientCommandManager.argument("spieler", StringArgumentType.word())
                .executes(c -> run(settle(StringArgumentType.getString(c, "spieler"), false, -1)))));
        d.register(ClientCommandManager.literal("payback")
            .executes(c -> run(payback(null)))
            .then(ClientCommandManager.argument("spieler", StringArgumentType.word())
                .executes(c -> run(payback(StringArgumentType.getString(c, "spieler"))))));
        d.register(ClientCommandManager.literal("roll")
            .executes(c -> { roll(100); return 1; })
            .then(ClientCommandManager.argument("max", IntegerArgumentType.integer(2, 1000000))
                .executes(c -> { roll(IntegerArgumentType.getInteger(c, "max")); return 1; })));
        d.register(ClientCommandManager.literal("coinflip").executes(c -> {
            boolean heads = java.util.concurrent.ThreadLocalRandom.current().nextBoolean();
            FloonsNotify.push("Münzwurf", heads ? "KOPF" : "ZAHL", heads ? 0xFF7DD3FC : 0xFFFBBF24);
            chat("Münzwurf: " + (heads ? "Kopf" : "Zahl"));
            return 1;
        }));
        d.register(ClientCommandManager.literal("roulette")
            .executes(c -> { FloonsRouletteScreen.target = null; pendingScreen = 8; return 1; })
            .then(ClientCommandManager.literal("auto").executes(c -> {
                FloonsConfig k2 = FloonsConfig.get(); k2.rouletteSettle = !k2.rouletteSettle; k2.save();
                chat("Roulette rechnet offene Einsätze ab: " + (k2.rouletteSettle ? "an" : "aus")); return 1;
            }))
            .then(ClientCommandManager.argument("spieler", StringArgumentType.word()).executes(c -> {
                FloonsRouletteScreen.target = StringArgumentType.getString(c, "spieler"); pendingScreen = 8; return 1;
            })));
        d.register(ClientCommandManager.literal("calc").executes(c -> { pendingScreen = 2; return 1; }));

        FloonsConfig k = FloonsConfig.get();
        d.register(ClientCommandManager.literal("gamble")
            .executes(c -> { status(); return 1; })
            .then(ClientCommandManager.literal("stats").executes(c -> { status(); return 1; }))
            .then(ClientCommandManager.literal("list").executes(c -> { list(); return 1; }))
            .then(ClientCommandManager.literal("reset").executes(c -> { FloonsStats.reset(); chat("Statistik zurückgesetzt."); return 1; }))
            .then(ClientCommandManager.literal("min")
                .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(c -> {
                    long v = FloonsAuction.parseInput(StringArgumentType.getString(c, "betrag"));
                    if (v < 0) return run("Betrag ungültig.");
                    k.minBet = v; k.save(); chat("Mindesteinsatz " + FloonsStats.fmt(v)); return 1;
                })))
            .then(ClientCommandManager.literal("max")
                .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(c -> {
                    String s = StringArgumentType.getString(c, "betrag");
                    long v = s.equalsIgnoreCase("off") || s.equalsIgnoreCase("aus") ? Long.MAX_VALUE : FloonsAuction.parseInput(s);
                    if (v < 0) return run("Betrag ungültig.");
                    k.maxBet = v; k.save(); chat("Höchsteinsatz " + (v == Long.MAX_VALUE ? "aus" : FloonsStats.fmt(v))); return 1;
                })))
            .then(ClientCommandManager.literal("multiplier")
                .then(ClientCommandManager.argument("wert", DoubleArgumentType.doubleArg(0)).executes(c -> {
                    k.winMultiplier = DoubleArgumentType.getDouble(c, "wert"); k.save(); chat("Gewinn-Multiplikator " + k.winMultiplier); return 1;
                })))
            .then(ClientCommandManager.literal("autopay").executes(c -> {
                k.autoPay = !k.autoPay; k.save(); chat("Auto-Pay: " + (k.autoPay ? "an" : "aus")); return 1;
            }))
            .then(ClientCommandManager.literal("delay")
                .then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(0, 10000)).executes(c -> {
                    k.payDelayMs = IntegerArgumentType.getInteger(c, "ms"); k.save(); chat("Pause zwischen Auszahlungen: " + k.payDelayMs + " ms"); return 1;
                })))
            .then(ClientCommandManager.literal("warn")
                .then(ClientCommandManager.argument("sekunden", IntegerArgumentType.integer(0, 3600)).executes(c -> {
                    k.waitWarnSec = IntegerArgumentType.getInteger(c, "sekunden"); k.save();
                    chat("Warte-Warnung: " + (k.waitWarnSec > 0 ? k.waitWarnSec + " s" : "aus")); return 1;
                })))
            .then(ClientCommandManager.literal("limit")
                .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(c -> {
                    String s = StringArgumentType.getString(c, "betrag");
                    long v = s.equalsIgnoreCase("off") || s.equalsIgnoreCase("aus") ? 0 : FloonsAuction.parseInput(s);
                    if (v < 0) return run("Betrag ungültig.");
                    k.lossLimit = v; k.save(); chat("Verlust-Limit: " + (v > 0 ? FloonsStats.fmt(v) : "aus")); return 1;
                })))
            .then(ClientCommandManager.literal("hud").executes(c -> {
                k.gambleHud = !k.gambleHud; k.save(); chat("Gamble-HUD: " + (k.gambleHud ? "an" : "aus")); return 1;
            }))
            .then(ClientCommandManager.literal("effects").executes(c -> {
                k.effects = !k.effects; k.save(); chat("Effekte: " + (k.effects ? "an" : "aus")); return 1;
            }))
            .then(ClientCommandManager.literal("confetti").executes(c -> {
                k.confetti = !k.confetti; k.save(); chat("Konfetti: " + (k.confetti ? "an" : "aus")); return 1;
            })));
    }

    private static void status() {
        FloonsConfig k = FloonsConfig.get();
        chat("Profit " + FloonsStats.fmt(FloonsStats.profit()) + " | Einnahmen " + FloonsStats.fmt(FloonsStats.income)
            + " | Auszahlung " + FloonsStats.fmt(FloonsStats.payouts) + " | Runden " + FloonsStats.rounds
            + String.format(Locale.ROOT, " (W%d/L%d, %.1f%%)", FloonsStats.wins, FloonsStats.losses, FloonsStats.winrate()));
        chat("Multiplikator " + k.winMultiplier + " | Auto-Pay " + (k.autoPay ? "an" : "aus") + " | Min " + FloonsStats.fmt(k.minBet)
            + " | Max " + (k.maxBet == Long.MAX_VALUE ? "aus" : FloonsStats.fmt(k.maxBet)) + " | Offen " + (FloonsStats.pending.size() + FloonsStats.queuedCount()));
        chat("/gamble list|reset|min|max|multiplier|autopay|delay|warn|limit|hud|effects|confetti");
    }

    private static void list() {
        if (FloonsStats.pending.isEmpty()) { chat("Keine offenen Zahlungen."); return; }
        for (var e : FloonsStats.pending.entrySet()) {
            var q = FloonsStats.queued.get(e.getKey());
            chat(e.getKey() + "  " + FloonsStats.fmt(e.getValue()) + "  (" + FloonsStats.clock(FloonsStats.waitedSec(e.getKey())) + ")"
                + (q == null || q.isEmpty() ? "" : "  +" + q.size() + " wartend"));
        }
    }
}
