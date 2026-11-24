package dev.floons.client;

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
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Floons Client – WindSMP-Mod. Bewusst OHNE Auto-Update und ohne jede Netzwerkverbindung:
 * neue Versionen kommen nur, wenn du die .jar selbst austauschst.
 */
public class FloonsClient implements ClientModInitializer {
    public static final String ID = "floonsclient";
    private static boolean debug;
    public static KeyBinding menuKey;
    private static Pattern compiled;
    private static String compiledSrc;

    @Override
    public void onInitializeClient() {
        FloonsConfig.get();
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.of(ID, "auction"), FloonsHud::render);
        HudElementRegistry.attachElementAfter(VanillaHudElements.MISC_OVERLAYS, Identifier.of(ID, "gamble"), FloonsGambleHud::render);
        KeyBinding.Category cat = KeyBinding.Category.create(Identifier.of(ID, "main"));
        menuKey = KeyBindingHelper.registerKeyBinding(
            new KeyBinding("key.floonsclient.menu", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT, cat));
        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (menuKey.wasPressed()) if (mc.currentScreen == null) mc.setScreen(new FloonsMenu(null));
            FloonsAuction.tick();
            FloonsGamble.tick(mc);
        });
        ClientSendMessageEvents.COMMAND.register(FloonsGamble::onOwnCommand);
        ClientReceiveMessageEvents.GAME.register((msg, overlay) -> { if (!overlay) onChat(msg.getString()); });
        ClientPlayConnectionEvents.DISCONNECT.register((h, mc) -> FloonsAuction.pause());
        ClientPlayConnectionEvents.JOIN.register((h, s, mc) -> mc.execute(FloonsAuction::resume));
        // Wasserzeichen in allen Menues (oben rechts, damit der Chat unten links frei bleibt)
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) ->
            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> {
                if (!FloonsConfig.get().watermark) return;
                var tr = client.textRenderer;
                String label = "Floons Client";
                FloonsTheme.gradientText(ctx, tr, label, s.width - tr.getWidth(label) - 5, 5);
            }));
        ClientCommandRegistrationCallback.EVENT.register((d, reg) -> {
            d.register(auctionCommand());
            d.register(settingsCommand());
            FloonsGamble.register(d);
        });
    }

    // ---------- Zahlungen erkennen ----------
    private static void onChat(String text) {
        FloonsConfig c = FloonsConfig.get();
        try {
            if (compiled == null || !c.pattern.equals(compiledSrc)) { compiled = Pattern.compile(c.pattern); compiledSrc = c.pattern; }
        } catch (Exception e) { return; }
        Matcher m = compiled.matcher(text);
        if (!m.find()) {
            if (debug && text.contains("$")) FloonsAuction.info("Nicht erkannt: " + text);
            return;
        }
        long amount;
        try {
            String suf;
            try { suf = m.group("suffix"); } catch (Exception e) { suf = ""; }
            amount = FloonsAuction.parseAmount(m.group("amount"), suf);
        } catch (Exception e) { return; }
        if (amount <= 0) return;
        String player = m.group("player");
        // laeuft eine Auktion, ist jede Zahlung ein Gebot; sonst ein Gamble-Einsatz
        if (!FloonsAuction.onPayment(player, amount)) FloonsGamble.onPayment(player, amount);
    }

    // ---------- /wa ----------
    private static int ok(String err) {
        if (err != null) { FloonsAuction.info(err); return 0; }
        return 1;
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> auctionCommand() {
        return ClientCommandManager.literal("wa")
            .executes(c -> { help(); return 1; })
            .then(ClientCommandManager.literal("start")
                .then(ClientCommandManager.argument("startgebot", StringArgumentType.word())
                    .then(ClientCommandManager.argument("dauer", StringArgumentType.word())
                        .then(ClientCommandManager.argument("item", StringArgumentType.greedyString()).executes(c -> {
                            long st = FloonsAuction.parseInput(StringArgumentType.getString(c, "startgebot"));
                            int sec = FloonsAuction.parseDuration(StringArgumentType.getString(c, "dauer"));
                            if (st < 0) return ok("Startgebot ungültig (z. B. 500k, 1.5m, 2000).");
                            if (sec < 0) return ok("Dauer ungültig (z. B. 90, 90s, 2m, 1m30s).");
                            return ok(FloonsAuction.start(StringArgumentType.getString(c, "item"), st, sec));
                        })))))
            .then(ClientCommandManager.literal("end").executes(c -> ok(FloonsAuction.end(false))))
            .then(ClientCommandManager.literal("cancel").executes(c -> ok(FloonsAuction.end(true))))
            .then(ClientCommandManager.literal("extend")
                .then(ClientCommandManager.argument("sekunden", IntegerArgumentType.integer(1, 3600))
                    .executes(c -> ok(FloonsAuction.extend(IntegerArgumentType.getInteger(c, "sekunden"))))))
            .then(ClientCommandManager.literal("refund").executes(c -> { FloonsAuction.refundList(); return 1; }))
            .then(ClientCommandManager.literal("status").executes(c -> { status(); return 1; }))
            .then(ClientCommandManager.literal("history").executes(c -> { history(); return 1; }))
            .then(ClientCommandManager.literal("test")
                .then(ClientCommandManager.argument("spieler", StringArgumentType.word())
                    .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(c -> {
                        long v = FloonsAuction.parseInput(StringArgumentType.getString(c, "betrag"));
                        if (v <= 0) return ok("Betrag ungültig.");
                        if (!FloonsAuction.onPayment(StringArgumentType.getString(c, "spieler"), v)) return ok("Keine Auktion aktiv.");
                        return 1;
                    }))));
    }

    private static void help() {
        FloonsAuction.info("/wa start <startgebot> <dauer> <item>  – z. B. /wa start 500k 2m Elytra");
        FloonsAuction.info("/wa end | cancel | extend <s> | status | refund | history");
        FloonsAuction.info("/wa test <spieler> <betrag> – Gebot simulieren (kein Geld)");
        FloonsAuction.info("Gamble: /win /lose /payback [spieler|all] · /gamble · /roll · /coinflip · /roulette · /calc");
        FloonsAuction.info("/floons menu oder Rechte Shift – Mod-Menü  ·  /floons – Einstellungen per Befehl");
    }

    private static void status() {
        if (!FloonsAuction.active) { FloonsAuction.info("Keine Auktion aktiv."); return; }
        FloonsAuction.info(FloonsAuction.item + " | noch " + FloonsAuction.mmss(FloonsAuction.left()) + (FloonsAuction.paused ? " (Pause)" : "")
            + " | " + (FloonsAuction.bestName == null ? "kein Gebot" : FloonsAuction.fmt(FloonsAuction.best) + " von " + FloonsAuction.bestName)
            + " | " + FloonsAuction.bids.size() + " Bieter");
    }

    private static void history() {
        List<FloonsAuction.Entry> h = FloonsAuction.history();
        if (h.isEmpty()) { FloonsAuction.info("Noch keine Auktionen."); return; }
        SimpleDateFormat f = new SimpleDateFormat("dd.MM. HH:mm");
        long sum = 0;
        for (FloonsAuction.Entry e : h) sum += e.amount;
        FloonsAuction.info("Letzte Auktionen (" + h.size() + ", Erlös gesamt " + FloonsAuction.fmt(sum) + "):");
        for (int i = 0; i < Math.min(10, h.size()); i++) {
            FloonsAuction.Entry e = h.get(i);
            FloonsAuction.info(f.format(new Date(e.time)) + "  " + e.item + " → "
                + (e.winner == null ? "keine Gebote" : e.winner + " " + FloonsAuction.fmt(e.amount)) + "  (" + e.bidders + " Bieter)");
        }
    }

    // ---------- /floons ----------
    private static LiteralArgumentBuilder<FabricClientCommandSource> toggle(String name, String label, java.util.function.Supplier<Boolean> get, Consumer<Boolean> set) {
        return ClientCommandManager.literal(name).executes(c -> {
            boolean v = !get.get(); set.accept(v); FloonsConfig.get().save();
            FloonsAuction.info(label + ": " + (v ? "an" : "aus")); return 1;
        });
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> settingsCommand() {
        FloonsConfig k = FloonsConfig.get();
        var corner = ClientCommandManager.literal("corner");
        for (String s : new String[]{"top_left", "top_right", "bottom_right", "mid_left", "mid_right"})
            corner.then(ClientCommandManager.literal(s).executes(c -> { k.hudCorner = s; k.save(); FloonsAuction.info("HUD-Position: " + s); return 1; }));
        return ClientCommandManager.literal("floons")
            .then(ClientCommandManager.literal("menu").executes(c -> { FloonsGamble.pendingScreen = 1; return 1; }))
            .executes(c -> {
                FloonsAuction.info("Ansagen " + onOff(k.announce) + " | Countdown " + onOff(k.countdown) + " | Ton " + onOff(k.sound) + " | HUD " + onOff(k.hud));
                FloonsAuction.info("Mindest-Erhöhung " + FloonsAuction.fmt(k.minRaise) + " | Anti-Snipe " + (k.antiSnipe > 0 ? k.antiSnipe + "s" : "aus")
                    + " | HUD " + k.hudCorner + " x" + k.hudScale);
                FloonsAuction.info("/floons announce|countdown|sound|hud|watermark|debug  ·  minraise <betrag>  ·  antisnipe <s>  ·  scale <0.5-3>  ·  corner <pos>  ·  pattern <regex>");
                return 1;
            })
            .then(toggle("announce", "Öffentliche Ansagen", () -> k.announce, v -> k.announce = v))
            .then(toggle("countdown", "Countdown", () -> k.countdown, v -> k.countdown = v))
            .then(toggle("sound", "Ton", () -> k.sound, v -> k.sound = v))
            .then(toggle("hud", "HUD", () -> k.hud, v -> k.hud = v))
            .then(toggle("watermark", "Wasserzeichen", () -> k.watermark, v -> k.watermark = v))
            .then(ClientCommandManager.literal("debug").executes(c -> {
                debug = !debug; FloonsAuction.info("Debug (nicht erkannte $-Nachrichten zeigen): " + onOff(debug)); return 1;
            }))
            .then(ClientCommandManager.literal("minraise")
                .then(ClientCommandManager.argument("betrag", StringArgumentType.word()).executes(c -> {
                    long v = FloonsAuction.parseInput(StringArgumentType.getString(c, "betrag"));
                    if (v < 1) return ok("Betrag ungültig.");
                    k.minRaise = v; k.save(); FloonsAuction.info("Mindest-Erhöhung: " + FloonsAuction.fmt(v)); return 1;
                })))
            .then(ClientCommandManager.literal("antisnipe")
                .then(ClientCommandManager.argument("sekunden", IntegerArgumentType.integer(0, 300)).executes(c -> {
                    k.antiSnipe = IntegerArgumentType.getInteger(c, "sekunden"); k.save();
                    FloonsAuction.info("Anti-Snipe: " + (k.antiSnipe > 0 ? k.antiSnipe + "s" : "aus")); return 1;
                })))
            .then(ClientCommandManager.literal("scale")
                .then(ClientCommandManager.argument("wert", FloatArgumentType.floatArg(0.5f, 3f)).executes(c -> {
                    k.hudScale = FloatArgumentType.getFloat(c, "wert"); k.save(); FloonsAuction.info("HUD-Größe: " + k.hudScale); return 1;
                })))
            .then(corner)
            .then(ClientCommandManager.literal("pattern")
                .then(ClientCommandManager.argument("regex", StringArgumentType.greedyString()).executes(c -> {
                    String r = StringArgumentType.getString(c, "regex");
                    try { Pattern.compile(r); } catch (Exception e) { return ok("Ungültige Regex."); }
                    if (!r.contains("(?<amount>") || !r.contains("(?<player>")) return ok("Regex braucht die Gruppen (?<amount>…) und (?<player>…).");
                    k.pattern = r; k.save(); FloonsAuction.info("Zahlungsmuster gesetzt."); return 1;
                })));
    }

    private static String onOff(boolean b) { return b ? "an" : "aus"; }
}
