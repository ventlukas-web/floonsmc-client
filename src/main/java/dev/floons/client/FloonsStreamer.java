package dev.floons.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Streamer-Funktionen: Streamer-Modus (Name ersetzen), Werbung, Giveaway.
 * Giveaway-Teilnahme per Stichwort im Spiel-Chat und optional im Twitch-Chat.
 */
public final class FloonsStreamer {
    private FloonsStreamer() {}

    // ---------- Giveaway-Zustand ----------
    public static boolean gwOn;
    public static String prize = "";
    public static long gwEnd, gwDuration, gwStarted;
    public static int winnersWanted = 1;
    /** Teilnehmer; Twitch-Zuschauer bekommen ein "tw:"-Präfix intern, angezeigt mit [T]. */
    public static final LinkedHashSet<String> entrants = new LinkedHashSet<>();
    public record Join(String name, boolean twitch, long at) {}
    public static final List<Join> joinFeed = new ArrayList<>();
    public static final List<String> lastWinners = new ArrayList<>();
    private static final List<String> lastPool = new ArrayList<>();
    private static final Random RND = new Random();
    private static int lastCountdown = -1;
    private static final int[] COUNTDOWN = {60, 30, 10, 5};

    /** Zieh-Animation: Namen rotieren im HUD, bevor der Gewinner feststeht. */
    public static long drawStart = 0, revealAt = 0;
    public static final long DRAW_MS = 3200;
    private static List<String> pendingWinners = List.of();
    private static long twitchSeen = 0;

    public static boolean drawing() { return drawStart > 0 && System.currentTimeMillis() - drawStart < DRAW_MS; }
    public static List<String> drawPool() { return lastPool; }
    /** Index des (ersten) Gewinners im Pool, damit das Glücksrad genau dort stehen bleibt. */
    public static int wheelTarget() { return pendingWinners.isEmpty() ? 0 : Math.max(0, lastPool.indexOf(pendingWinners.get(0))); }

    public static String display(String e) { return e.startsWith("tw:") ? e.substring(3) + " [T]" : e; }

    public static void register() {
        ClientReceiveMessageEvents.MODIFY_GAME.register((msg, overlay) -> alias(msg));
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) onChatLine(msg.getString()); });
        ClientReceiveMessageEvents.CHAT.register((msg, signed, sender, params, ts) -> onChatLine(msg.getString()));
    }

    // ---------- Streamer-Modus ----------
    private static Text alias(Text msg) {
        FloonsConfig cfg = FloonsConfig.get();
        if (!cfg.streamer || cfg.streamerName == null || cfg.streamerName.isBlank()) return msg;
        MinecraftClient mc = MinecraftClient.getInstance();
        String real = mc.player != null ? mc.player.getName().getString() : mc.getSession().getUsername();
        if (real == null || real.isBlank() || !msg.getString().toLowerCase().contains(real.toLowerCase())) return msg;
        Pattern pat = Pattern.compile(Pattern.quote(real), Pattern.CASE_INSENSITIVE);
        MutableText out = Text.empty();
        msg.visit((style, str) -> {
            out.append(Text.literal(pat.matcher(str).replaceAll(Matcher.quoteReplacement(cfg.streamerName))).setStyle(style));
            return java.util.Optional.empty();
        }, Style.EMPTY);
        return out;
    }

    // ---------- Werbung ----------
    public static void sendAd() {
        MinecraftClient mc = MinecraftClient.getInstance();
        FloonsConfig c = FloonsConfig.get();
        if (mc.player == null || mc.player.networkHandler == null) { FloonsGamble.chat("Nicht in einer Welt."); return; }
        String ch = c.adChannel != null && !c.adChannel.isBlank() ? c.adChannel.trim() : (c.twitchChannel == null ? "" : c.twitchChannel.trim());
        if (ch.isEmpty()) { FloonsGamble.chat("Erst einen Twitch-Kanal eintragen (Streamer-Tab)."); return; }
        String txt = (c.adText == null || c.adText.isBlank() ? "Schaut bei mir auf Twitch vorbei: twitch.tv/{kanal}" : c.adText).replace("{kanal}", ch);
        mc.player.networkHandler.sendChatMessage(txt);
    }

    // ---------- Giveaway ----------
    public static String start(int sec, String pr) {
        if (gwOn) return "Es läuft schon ein Giveaway (/giveaway end oder cancel).";
        if (sec < 5) return "Dauer mindestens 5 Sekunden.";
        FloonsConfig c = FloonsConfig.get();
        gwOn = true; prize = pr; entrants.clear(); joinFeed.clear(); lastWinners.clear();
        winnersWanted = Math.max(1, c.giveawayWinners);
        gwStarted = System.currentTimeMillis();
        gwDuration = sec * 1000L; gwEnd = gwStarted + gwDuration;
        lastCountdown = sec;
        twitchSeen = FloonsTwitch.seq();
        String how = "Schreib \"" + c.giveawayKeyword + "\" in den Chat" + (c.giveawayTwitch && c.twitch ? " (auch im Twitch-Chat)" : "");
        FloonsAuction.say("[Giveaway] " + prize + " | " + how + " | " + FloonsAuction.mmss(gwDuration)
            + (winnersWanted > 1 ? " | " + winnersWanted + " Gewinner" : ""));
        FloonsNotify.push("Giveaway gestartet", prize, FloonsTheme.BLUE);
        return null;
    }

    public static String cancel() {
        if (!gwOn) return "Kein Giveaway aktiv.";
        gwOn = false;
        FloonsAuction.say("[Giveaway] " + prize + " wurde abgebrochen.");
        return null;
    }

    /** Beendet das Giveaway und startet die Zieh-Animation. */
    public static String draw() {
        if (!gwOn) return "Kein Giveaway aktiv.";
        gwOn = false;
        lastPool.clear(); lastPool.addAll(entrants);
        if (lastPool.isEmpty()) { FloonsAuction.say("[Giveaway] " + prize + " beendet – keine Teilnehmer."); return null; }
        pendingWinners = pick(lastPool, winnersWanted);
        drawStart = System.currentTimeMillis();
        return null;
    }

    /** Neu ziehen aus dem letzten Teilnehmer-Pool (ohne die bisherigen Gewinner). */
    public static String reroll() {
        if (gwOn) return "Das Giveaway läuft noch.";
        List<String> pool = new ArrayList<>(lastPool);
        pool.removeAll(lastWinners);
        if (pool.isEmpty()) return "Keine weiteren Teilnehmer zum Neu-Ziehen.";
        pendingWinners = pick(pool, 1);
        drawStart = System.currentTimeMillis();
        return null;
    }

    private static List<String> pick(List<String> pool, int n) {
        List<String> p = new ArrayList<>(pool);
        java.util.Collections.shuffle(p, RND);
        return new ArrayList<>(p.subList(0, Math.min(n, p.size())));
    }

    private static void reveal() {
        drawStart = 0; revealAt = System.currentTimeMillis();
        List<String> names = new ArrayList<>();
        for (String w : pendingWinners) names.add(display(w));
        lastWinners.addAll(pendingWinners);
        String list = String.join(", ", names);
        FloonsAuction.say("[Giveaway] Gewinner" + (names.size() > 1 ? "" : "") + ": " + list + " (" + prize + ")! " + lastPool.size() + " Teilnehmer.");
        FloonsHud.overlay(true, names.size() > 1 ? "GEWINNER" : names.get(0), prize + "  ·  " + lastPool.size() + " Teilnehmer");
        if (FloonsConfig.get().sound) FloonsGamble.playId("ui.toast.challenge_complete", 0.8f, 1.0f);
    }

    private static void join(String key, boolean twitch) {
        if (!entrants.add(key)) return;
        joinFeed.add(0, new Join(display(key), twitch, System.currentTimeMillis()));
        while (joinFeed.size() > 6) joinFeed.remove(joinFeed.size() - 1);
    }

    /** Spiel-Chat: Spielername aus der Tab-Liste, dahinter das Stichwort. */
    public static void onChatLine(String text) {
        if (!gwOn) return;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return;
        String kw = FloonsConfig.get().giveawayKeyword.toLowerCase().trim();
        if (kw.isEmpty()) return;
        String low = text.toLowerCase();
        for (var e : mc.getNetworkHandler().getPlayerList()) {
            String n = e.getProfile().name();
            int i = low.indexOf(n.toLowerCase());
            if (i < 0) continue;
            if (low.indexOf(kw, i + n.length()) >= 0) { join(n, false); return; }
        }
    }

    public static void tick() {
        long now = System.currentTimeMillis();
        if (drawStart > 0 && now - drawStart >= DRAW_MS) reveal();
        if (!gwOn) return;
        FloonsConfig c = FloonsConfig.get();
        // Twitch-Chat: neue Nachrichten mit dem Stichwort
        if (c.giveawayTwitch && c.twitch) {
            long now2 = FloonsTwitch.seq();
            String kw = c.giveawayKeyword.toLowerCase().trim();
            for (FloonsTwitch.Msg m : FloonsTwitch.since(twitchSeen))
                if (!kw.isEmpty() && m.text().toLowerCase().contains(kw)) join("tw:" + m.user(), true);
            twitchSeen = now2;
        }
        long left = gwEnd - now;
        if (left <= 0) { draw(); return; }
        if (!c.countdown) return;
        int s = (int) ((left + 999) / 1000);
        for (int cd : COUNTDOWN) {
            if (s <= cd && lastCountdown > cd) {
                lastCountdown = cd;
                FloonsAuction.say("[Giveaway] Noch " + cd + "s | " + prize + " | " + entrants.size() + " Teilnehmer | Stichwort: " + c.giveawayKeyword);
                break;
            }
        }
    }

    public static long left() { return Math.max(0, gwEnd - System.currentTimeMillis()); }

    // ---------- Befehle ----------
    private static int ok(String err) { if (err != null) { FloonsGamble.chat(err); return 0; } return 1; }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
        FloonsConfig k = FloonsConfig.get();
        d.register(ClientCommandManager.literal("giveaway")
            .executes(c -> { status(); return 1; })
            .then(ClientCommandManager.literal("start")
                .then(ClientCommandManager.argument("dauer", StringArgumentType.word())
                    .then(ClientCommandManager.argument("preis", StringArgumentType.greedyString()).executes(c -> {
                        int sec = FloonsAuction.parseDuration(StringArgumentType.getString(c, "dauer"));
                        if (sec < 0) return ok("Dauer ungültig (z. B. 60, 90s, 2m).");
                        return ok(start(sec, StringArgumentType.getString(c, "preis")));
                    }))))
            .then(ClientCommandManager.literal("end").executes(c -> ok(draw())))
            .then(ClientCommandManager.literal("cancel").executes(c -> ok(cancel())))
            .then(ClientCommandManager.literal("reroll").executes(c -> ok(reroll())))
            .then(ClientCommandManager.literal("list").executes(c -> {
                if (entrants.isEmpty()) return ok("Keine Teilnehmer.");
                List<String> l = new ArrayList<>();
                for (String e : entrants) l.add(display(e));
                FloonsGamble.chat(entrants.size() + " Teilnehmer: " + String.join(", ", l)); return 1;
            }))
            .then(ClientCommandManager.literal("keyword")
                .then(ClientCommandManager.argument("wort", StringArgumentType.word()).executes(c -> {
                    k.giveawayKeyword = StringArgumentType.getString(c, "wort"); k.save();
                    return ok("Stichwort: " + k.giveawayKeyword);
                })))
            .then(ClientCommandManager.literal("winners")
                .then(ClientCommandManager.argument("anzahl", IntegerArgumentType.integer(1, 20)).executes(c -> {
                    k.giveawayWinners = IntegerArgumentType.getInteger(c, "anzahl"); k.save();
                    return ok("Gewinner pro Giveaway: " + k.giveawayWinners);
                })))
            .then(ClientCommandManager.literal("twitch").executes(c -> {
                k.giveawayTwitch = !k.giveawayTwitch; k.save();
                return ok("Teilnahme über Twitch-Chat: " + (k.giveawayTwitch ? "an" : "aus"));
            })));
        d.register(ClientCommandManager.literal("twitch")
            .then(ClientCommandManager.literal("off").executes(c -> { k.twitch = false; k.save(); return ok("Twitch-Chat aus."); }))
            .then(ClientCommandManager.argument("kanal", StringArgumentType.word()).executes(c -> {
                k.twitchChannel = StringArgumentType.getString(c, "kanal"); k.twitch = true; k.save();
                return ok("Twitch-Chat: " + k.twitchChannel);
            })));
        d.register(ClientCommandManager.literal("werbung").executes(c -> { sendAd(); return 1; }));
    }

    private static void status() {
        if (!gwOn) { FloonsGamble.chat("Kein Giveaway aktiv. /giveaway start <dauer> <preis>"); return; }
        FloonsGamble.chat(prize + " | noch " + FloonsAuction.mmss(left()) + " | " + entrants.size() + " Teilnehmer | Stichwort: " + FloonsConfig.get().giveawayKeyword);
    }
}
