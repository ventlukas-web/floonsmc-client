package dev.wind.client;

import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.util.Identifier;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Wind Client – WindSMP-Mod. Bewusst OHNE Auto-Update und ohne jede Netzwerkverbindung:
 * neue Versionen kommen nur, wenn du die .jar selbst austauschst.
 */
public class WindClient implements ClientModInitializer {
    public static final String ID = "windclient";
    private static boolean debug;
    private static Pattern compiled;
    private static String compiledSrc;

    @Override
    public void onInitializeClient() {
        WindConfig.get();
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.of(ID, "auction"), WindHud::render);
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.of(ID, "gamble"), WindGambleHud::render);
        ClientTickEvents.END_CLIENT_TICK.register(mc -> { WindAuction.tick(); WindGamble.tick(mc); });
        ClientSendMessageEvents.COMMAND.register(WindGamble::onOwnCommand);
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) onChat(msg.getString()); });
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> WindAuction.pause());
        ClientPlayConnectionEvents.JOIN.register((h, s, mc) -> mc.execute(WindAuction::resume));
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> {
            d.register(auctionCommand());
            d.register(settingsCommand());
            WindGamble.register(d);
        });
    }

    // ---------- Zahlungen erkennen ----------
    private static void onChat(String text) {
        WindConfig c = WindConfig.get();
        try {
            if (compiled == null || !c.pattern.equals(compiledSrc)) { compiled = Pattern.compile(c.pattern); compiledSrc = c.pattern; }
        } catch (Exception e) { return; }
        Matcher m = compiled.matcher(text);
        if (!m.find()) {
            if (debug && text.contains("$")) WindAuction.info("Nicht erkannt: " + text);
            return;
        }
        long amount;
        try {
            String suf;
            try { suf = m.group("suffix"); } catch (Exception e) { suf = ""; }
            amount = WindAuction.parseAmount(m.group("amount"), suf);
        } catch (Exception e) { return; }
        if (amount <= 0) return;
        String player = m.group("player");
        // laeuft eine Auktion, ist jede Zahlung ein Gebot; sonst ein Gamble-Einsatz
        if (!WindAuction.onPayment(player, amount)) WindGamble.onPayment(player, amount);
    }

    // ---------- /wa ----------
    private static int ok(String err) {
        if (err != null) { WindAuction.info(err); return 0; }
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> auctionCommand() {
        return ClientCommandManager.literal("wa")
            .executes(c -> { help(); return 1; })
            .then(ClientCommandManager.literal("start")
                .then(ClientCommandManager.argument("startgebot", StringArgumentType.word())
                    .then(ClientCommandManager.argument("dauer", StringArgumentType.word())
                        .then(ClientCommandManager.argument("item", StringArgumentType.greedyString()).executes(c -> {
                            long st = WindAuction.parseInput(StringArgumentType.getString(c, "startgebot"));
                            int sec = WindAuction.parseDuration(StringArgumentType.getString(c, "dauer"));
                            if (st < 0) return ok("Startgebot ungültig (z. B. 500k, 1.5m, 2000).");
                            if (sec < 0) return ok("Dauer ungültig (z. B. 90, 90s, 2m, 1m30s).");
                            return ok(WindAuction.start(StringArgumentType.getString(c, "item"), st, sec));
                        })))))
            .then(ClientCommandManager.literal("end").executes(c -> ok(WindAuction.end(false))))
            .then(ClientCommandManager.literal("cancel").executes(c -> ok(WindAuction.end(true))))
            .then(ClientCommandManager.literal("extend")
                .then(ClientCommandManager.argument("sekunden", IntegerArgumentType.integer(1, 3600))
                    .executes(c -> ok(WindAuction.extend(IntegerArgumentType.getInteger(c, "sekunden"))))))
            .then(ClientCommandManager.literal("refund").executes(c -> { WindAuction.refundList(); return 1; }))
            .then(ClientCommandManager.literal("status").executes(c -> { status(); return 1; }))
            .then(ClientCommandManager.literal("history").executes(c -> { history(); return 1; }))
            .then(ClientCommandManager.literal("test")
                .then(ClientCommandManager.argument("spieler", StringArgumentType.word())
                    .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(c -> {
                        long v = WindAuction.parseInput(StringArgumentType.getString(c, "betrag"));
                        if (v <= 0) return ok("Betrag ungültig.");
                        if (!WindAuction.onPayment(StringArgumentType.getString(c, "spieler"), v)) return ok("Keine Auktion aktiv.");
                        return 1;
                    }))));
    }

    private static void help() {
        WindAuction.info("/wa start <startgebot> <dauer> <item>  – z. B. /wa start 500k 2m Elytra");
        WindAuction.info("/wa end | cancel | extend <s> | status | refund | history");
        WindAuction.info("/wa test <spieler> <betrag> – Gebot simulieren (kein Geld)");
        WindAuction.info("Gamble: /win /lose /payback [spieler|all] · /gamble · /roll · /coinflip · /roulette · /calc");
        WindAuction.info("/wind – Auktions-Einstellungen");
    }

    private static void status() {
        if (!WindAuction.active) { WindAuction.info("Keine Auktion aktiv."); return; }
        WindAuction.info(WindAuction.item + " | noch " + WindAuction.mmss(WindAuction.left()) + (WindAuction.paused ? " (Pause)" : "")
            + " | " + (WindAuction.bestName == null ? "kein Gebot" : WindAuction.fmt(WindAuction.best) + " von " + WindAuction.bestName)
            + " | " + WindAuction.bids.size() + " Bieter");
    }

    private static void history() {
        List<WindAuction.Entry> h = WindAuction.history();
        if (h.isEmpty()) { WindAuction.info("Noch keine Auktionen."); return; }
        SimpleDateFormat f = new SimpleDateFormat("dd.MM. HH:mm");
        long sum = 0;
        for (WindAuction.Entry e : h) sum += e.amount;
        WindAuction.info("Letzte Auktionen (" + h.size() + ", Erlös gesamt " + WindAuction.fmt(sum) + "):");
        for (int i = 0; i < Math.min(10, h.size()); i++) {
            WindAuction.Entry e = h.get(i);
            WindAuction.info(f.format(new Date(e.time)) + "  " + e.item + " → "
                + (e.winner == null ? "keine Gebote" : e.winner + " " + WindAuction.fmt(e.amount)) + "  (" + e.bidders + " Bieter)");
        }
    }

    // ---------- /wind ----------
    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, String label, java.util.function.Supplier<Boolean> get, Consumer<Boolean> set) {
        return ClientCommandManager.literal(name).executes(c -> {
            boolean v = !get.get(); set.accept(v); WindConfig.get().save();
            WindAuction.info(label + ": " + (v ? "an" : "aus")); return 1;
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> settingsCommand() {
        WindConfig k = WindConfig.get();
        var corner = ClientCommandManager.literal("corner");
        for (String s : new String[]{"top_left", "top_right", "bottom_right", "mid_left", "mid_right"})
            corner.then(ClientCommandManager.literal(s).executes(c -> { k.hudCorner = s; k.save(); WindAuction.info("HUD-Position: " + s); return 1; }));
        return ClientCommandManager.literal("wind")
            .executes(c -> {
                WindAuction.info("Ansagen " + onOff(k.announce) + " | Countdown " + onOff(k.countdown) + " | Ton " + onOff(k.sound) + " | HUD " + onOff(k.hud));
                WindAuction.info("Mindest-Erhöhung " + WindAuction.fmt(k.minRaise) + " | Anti-Snipe " + (k.antiSnipe > 0 ? k.antiSnipe + "s" : "aus")
                    + " | HUD " + k.hudCorner + " x" + k.hudScale);
                WindAuction.info("/wind announce|countdown|sound|hud|debug  ·  minraise <betrag>  ·  antisnipe <s>  ·  scale <0.5-3>  ·  corner <pos>  ·  pattern <regex>");
                return 1;
            })
            .then(toggle("announce", "Öffentliche Ansagen", () -> k.announce, v -> k.announce = v))
            .then(toggle("countdown", "Countdown", () -> k.countdown, v -> k.countdown = v))
            .then(toggle("sound", "Ton", () -> k.sound, v -> k.sound = v))
            .then(toggle("hud", "HUD", () -> k.hud, v -> k.hud = v))
            .then(ClientCommandManager.literal("debug").executes(c -> {
                debug = !debug; WindAuction.info("Debug (nicht erkannte $-Nachrichten zeigen): " + onOff(debug)); return 1;
            }))
            .then(ClientCommandManager.literal("minraise")
                .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(c -> {
                    long v = WindAuction.parseInput(StringArgumentType.getString(c, "betrag"));
                    if (v < 1) return ok("Betrag ungültig.");
                    k.minRaise = v; k.save(); WindAuction.info("Mindest-Erhöhung: " + WindAuction.fmt(v)); return 1;
                })))
            .then(ClientCommandManager.literal("antisnipe")
                .then(ClientCommandManager.argument("sekunden", IntegerArgumentType.integer(0, 300)).executes(c -> {
                    k.antiSnipe = IntegerArgumentType.getInteger(c, "sekunden"); k.save();
                    WindAuction.info("Anti-Snipe: " + (k.antiSnipe > 0 ? k.antiSnipe + "s" : "aus")); return 1;
                })))
            .then(ClientCommandManager.literal("scale")
                .then(ClientCommandManager.argument("wert", FloatArgumentType.floatArg(0.5f, 3f)).executes(c -> {
                    k.hudScale = FloatArgumentType.getFloat(c, "wert"); k.save(); WindAuction.info("HUD-Größe: " + k.hudScale); return 1;
                })))
            .then(corner)
            .then(ClientCommandManager.literal("pattern")
                .then(ClientCommandManager.argument("regex", StringArgumentType.greedyString()).executes(c -> {
                    String r = StringArgumentType.getString(c, "regex");
                    try { Pattern.compile(r); } catch (Exception e) { return ok("Ungültige Regex."); }
                    if (!r.contains("(?<amount>") || !r.contains("(?<player>")) return ok("Regex braucht die Gruppen (?<amount>…) und (?<player>…).");
                    k.pattern = r; k.save(); WindAuction.info("Zahlungsmuster gesetzt."); return 1;
                })));
    }

    private static String onOff(boolean b) { return b ? "an" : "aus"; }
}
