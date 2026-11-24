package dev.wind.client;

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
public final class WindGamble {
    private WindGamble() {}

    static int pendingScreen = 0; // 2 = Rechner, 8 = Roulette (nach dem Schließen des Chats öffnen)

    // ---------- Hilfen ----------
    public static void chat(String text) { WindAuction.info(text); }

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
        WindConfig cfg = WindConfig.get();
        if (amount < cfg.minBet || amount > cfg.maxBet) {
            chat(player + " hat " + WindStats.fmt(amount) + " gezahlt – außerhalb Min/Max, ignoriert.");
            return;
        }
        if (WindStats.pending.containsKey(player)) {
            WindStats.queued.computeIfAbsent(player, k -> new java.util.ArrayDeque<>()).add(amount);
            chat(player + " hat " + WindStats.fmt(amount) + " gezahlt – in der Warteschlange (" + WindStats.queued.get(player).size() + ").");
        } else {
            WindStats.pending.put(player, amount);
            WindStats.pendingAt.put(player, System.currentTimeMillis());
        }
        WindStats.income += amount;
        WindStats.lastPayer = player;
        WindGambleHud.flash(WindTheme.PURPLE);
    }

    // ---------- /win /lose /payback ----------
    private static String resolve(String player) {
        String p = player != null ? player : WindStats.pending.keySet().stream().findFirst().orElse(null); // ohne Namen: der Älteste
        if (p != null) for (String k : WindStats.pending.keySet())
            if (k.equalsIgnoreCase(p) || k.equalsIgnoreCase("." + p) || ("." + k).equalsIgnoreCase(p)) { p = k; break; }
        return p;
    }

    private static boolean isAll(String p) { return p != null && (p.equalsIgnoreCase("all") || p.equalsIgnoreCase("alle")); }

    public static String settle(String player, boolean win, double mult) {
        if (isAll(player)) {
            int n = WindStats.pending.size() + WindStats.queuedCount();
            if (n == 0) return "Keine offene Zahlung.";
            for (int guard = 0; guard < 10000 && !WindStats.pending.isEmpty(); guard++)
                settle(WindStats.pending.keySet().iterator().next(), win, mult);
            chat(n + " Zahlungen abgeschlossen.");
            return null;
        }
        String p = resolve(player);
        Long bet = p == null ? null : WindStats.pending.remove(p);
        if (bet == null) return "Keine offene Zahlung" + (p != null ? " von " + p : "") + ".";
        WindConfig cfg = WindConfig.get();
        WindStats.rounds++;
        if (win) {
            long payout = Math.round(bet * (mult > 0 ? mult : cfg.winMultiplier));
            WindStats.payouts += payout;
            WindStats.wins++;
            WindStats.recordRound(p, bet, true, payout);
            WindStats.finish(p, payout, true);
            effect(true, p, payout);
            if (cfg.autoPay && payout > 0) sendPay(p, payout);
            else chat(p + " gewinnt " + WindStats.fmt(payout) + " – Auto-Pay aus, selbst auszahlen.");
        } else {
            WindStats.losses++;
            WindStats.recordRound(p, bet, false, 0);
            WindStats.finish(p, bet, false);
            effect(false, p, bet);
        }
        promote(p);
        fixLast(p);
        return null;
    }

    public static String payback(String player) {
        if (isAll(player)) {
            int n = WindStats.pending.size() + WindStats.queuedCount();
            if (n == 0) return "Keine offene Zahlung.";
            for (int guard = 0; guard < 10000 && !WindStats.pending.isEmpty(); guard++)
                payback(WindStats.pending.keySet().iterator().next());
            chat(n + " Zahlungen werden zurückgezahlt.");
            return null;
        }
        String p = resolve(player);
        Long bet = p == null ? null : WindStats.pending.remove(p);
        if (bet == null) return "Keine offene Zahlung" + (p != null ? " von " + p : "") + ".";
        WindStats.recordPayout(p, bet);
        sendPay(p, bet);
        promote(p);
        fixLast(p);
        chat("Zurückgezahlt: " + WindStats.fmt(bet) + " an " + p);
        return null;
    }

    private static void promote(String p) {
        var q = WindStats.queued.get(p);
        if (q == null) return;
        Long nx = q.poll();
        if (q.isEmpty()) WindStats.queued.remove(p);
        if (nx != null) {
            WindStats.pending.put(p, nx);
            WindStats.pendingAt.put(p, System.currentTimeMillis());
            chat("Nächste Zahlung von " + p + ": " + WindStats.fmt(nx));
        }
    }

    private static void fixLast(String p) {
        if (p.equals(WindStats.lastPayer))
            WindStats.lastPayer = WindStats.pending.keySet().stream().reduce((a, b) -> b).orElse(null);
    }

    private static void effect(boolean win, String player, long amount) {
        WindConfig cfg = WindConfig.get();
        WindGambleHud.flash(win ? WindTheme.GOOD : WindTheme.BAD);
        if (cfg.effects) WindGambleHud.overlay(win, win ? "GEWONNEN" : "VERLOREN", player + "  ·  " + WindStats.fmt(amount));
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
        int delay = Math.max(0, WindConfig.get().payDelayMs);
        while (!PAYQ.isEmpty() && now >= nextPay) {
            Object[] e = PAYQ.poll();
            String pl = (String) e[0]; long amount = (Long) e[1];
            autoPaying = true;
            try { mc.player.networkHandler.sendChatCommand("pay " + pl + " " + amount); } finally { autoPaying = false; }
            chat("Auszahlung: /pay " + pl + " " + WindStats.fmt(amount));
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
            amount = WindAuction.parseAmount(raw.replaceAll("[KkMmBbTt]", ""), raw.replaceAll("[^KkMmBbTt]", ""));
        } catch (Exception e) { return; }
        if (WindAuction.isRefund(m.group(1), amount)) return; // Auktions-Rückzahlung, kein Gamble
        String p = m.group(1);
        for (String k : WindStats.pending.keySet())
            if (k.replaceFirst("^\\.", "").equalsIgnoreCase(p.replaceFirst("^\\.", ""))) { p = k; break; }
        Long bet = WindStats.pending.remove(p);
        if (bet != null) {
            WindStats.payouts += amount;
            WindStats.rounds++; WindStats.wins++;
            WindStats.recordRound(p, bet, true, amount);
            WindStats.finish(p, amount, true);
            promote(p);
            fixLast(p);
            effect(true, p, amount);
        } else {
            WindStats.recordPayout(p, amount);
            WindGambleHud.flash(WindTheme.GOOD);
        }
    }

    // ---------- Zufall ----------
    private static void roll(int max) {
        int v = java.util.concurrent.ThreadLocalRandom.current().nextInt(1, max + 1);
        int col = v == max ? 0xFF4ADE80 : v == 1 ? 0xFFFB7185 : 0xFF7DD3FC;
        WindNotify.push("Roll 1–" + max, String.valueOf(v), col);
        chat("Roll (1–" + max + "): " + v);
    }

    public static void tick(MinecraftClient mc) {
        WindStats.checkWait();
        WindStats.checkLimit();
        payTick();
        if (pendingScreen == 2) { pendingScreen = 0; mc.setScreen(new WindCalculatorScreen(null)); }
        if (pendingScreen == 8) { pendingScreen = 0; mc.setScreen(new WindRouletteScreen(null)); }
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
            WindNotify.push("Münzwurf", heads ? "KOPF" : "ZAHL", heads ? 0xFF7DD3FC : 0xFFFBBF24);
            chat("Münzwurf: " + (heads ? "Kopf" : "Zahl"));
            return 1;
        }));
        d.register(ClientCommandManager.literal("roulette").executes(c -> { pendingScreen = 8; return 1; }));
        d.register(ClientCommandManager.literal("calc").executes(c -> { pendingScreen = 2; return 1; }));

        WindConfig k = WindConfig.get();
        d.register(ClientCommandManager.literal("gamble")
            .executes(c -> { status(); return 1; })
            .then(ClientCommandManager.literal("stats").executes(c -> { status(); return 1; }))
            .then(ClientCommandManager.literal("list").executes(c -> { list(); return 1; }))
            .then(ClientCommandManager.literal("reset").executes(c -> { WindStats.reset(); chat("Statistik zurückgesetzt."); return 1; }))
            .then(ClientCommandManager.literal("min")
                .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(c -> {
                    long v = WindAuction.parseInput(StringArgumentType.getString(c, "betrag"));
                    if (v < 0) return run("Betrag ungültig.");
                    k.minBet = v; k.save(); chat("Mindesteinsatz " + WindStats.fmt(v)); return 1;
                })))
            .then(ClientCommandManager.literal("max")
                .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(c -> {
                    String s = StringArgumentType.getString(c, "betrag");
                    long v = s.equalsIgnoreCase("off") || s.equalsIgnoreCase("aus") ? Long.MAX_VALUE : WindAuction.parseInput(s);
                    if (v < 0) return run("Betrag ungültig.");
                    k.maxBet = v; k.save(); chat("Höchsteinsatz " + (v == Long.MAX_VALUE ? "aus" : WindStats.fmt(v))); return 1;
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
                    long v = s.equalsIgnoreCase("off") || s.equalsIgnoreCase("aus") ? 0 : WindAuction.parseInput(s);
                    if (v < 0) return run("Betrag ungültig.");
                    k.lossLimit = v; k.save(); chat("Verlust-Limit: " + (v > 0 ? WindStats.fmt(v) : "aus")); return 1;
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
        WindConfig k = WindConfig.get();
        chat("Profit " + WindStats.fmt(WindStats.profit()) + " | Einnahmen " + WindStats.fmt(WindStats.income)
            + " | Auszahlung " + WindStats.fmt(WindStats.payouts) + " | Runden " + WindStats.rounds
            + String.format(Locale.ROOT, " (W%d/L%d, %.1f%%)", WindStats.wins, WindStats.losses, WindStats.winrate()));
        chat("Multiplikator " + k.winMultiplier + " | Auto-Pay " + (k.autoPay ? "an" : "aus") + " | Min " + WindStats.fmt(k.minBet)
            + " | Max " + (k.maxBet == Long.MAX_VALUE ? "aus" : WindStats.fmt(k.maxBet)) + " | Offen " + (WindStats.pending.size() + WindStats.queuedCount()));
        chat("/gamble list|reset|min|max|multiplier|autopay|delay|warn|limit|hud|effects|confetti");
    }

    private static void list() {
        if (WindStats.pending.isEmpty()) { chat("Keine offenen Zahlungen."); return; }
        for (var e : WindStats.pending.entrySet()) {
            var q = WindStats.queued.get(e.getKey());
            chat(e.getKey() + "  " + WindStats.fmt(e.getValue()) + "  (" + WindStats.clock(WindStats.waitedSec(e.getKey())) + ")"
                + (q == null || q.isEmpty() ? "" : "  +" + q.size() + " wartend"));
        }
    }
}
