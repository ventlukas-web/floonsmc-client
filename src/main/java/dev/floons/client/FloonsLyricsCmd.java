package dev.floons.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/** /lyrics – Sky Lyrics wie im Toasty Client. */
public final class FloonsLyricsCmd {
    private FloonsLyricsCmd() {}

    static String modeName(String m) {
        return switch (m) { case "front" -> "vor dir"; case "block" -> "feste Stelle"; case "hud" -> "HUD-Kasten"; default -> "im Himmel"; };
    }

    static int mode(String m) {
        FloonsConfig k = FloonsConfig.get(); k.lyricsMode = m; k.lyricsHud = true; k.save(); FloonsSkyLyrics.clear();
        FloonsAuction.info("Sky Lyrics: " + modeName(m)); return 1;
    }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("lyrics")
            .executes(c -> { FloonsConfig k = FloonsConfig.get(); k.lyricsHud = !k.lyricsHud; k.save();
                if (!k.lyricsHud) FloonsSkyLyrics.clear();
                FloonsAuction.info("Sky Lyrics: " + (k.lyricsHud ? "an (" + modeName(k.lyricsMode) + ")" : "aus")); return 1; })
            .then(ClientCommandManager.literal("sky").executes(c -> mode("sky")))
            .then(ClientCommandManager.literal("front").executes(c -> mode("front")))
            .then(ClientCommandManager.literal("hud").executes(c -> mode("hud")))
            .then(ClientCommandManager.literal("font").then(ClientCommandManager.argument("schrift", StringArgumentType.word())
                .suggests((c, b) -> { for (String f : FloonsSkyLyrics.FONTS) b.suggest(f); return b.buildFuture(); })
                .executes(c -> { String f = StringArgumentType.getString(c, "schrift").toLowerCase();
                    if (!java.util.Arrays.asList(FloonsSkyLyrics.FONTS).contains(f)) { FloonsAuction.info("Schrift: clean, bold oder vanilla"); return 0; }
                    FloonsConfig k = FloonsConfig.get(); k.lyricsFont = f; k.save(); FloonsAuction.info("Sky Lyrics Schrift: " + FloonsSkyLyrics.fontName(f)); return 1; })))
            .then(ClientCommandManager.literal("color").then(ClientCommandManager.argument("hex", StringArgumentType.greedyString())
                .executes(c -> { String[] h = StringArgumentType.getString(c, "hex").trim().split("\\s+");
                    Integer a = FloonsTheme.parseHex(h[0]); Integer b = h.length > 1 ? FloonsTheme.parseHex(h[1]) : null;
                    if (a == null) { FloonsAuction.info("Farbe als #RRGGBB, optional zweite für die nächste Zeile"); return 0; }
                    FloonsConfig k = FloonsConfig.get(); k.lyricsColor = FloonsTheme.toHex(a); if (b != null) k.lyricsColor2 = FloonsTheme.toHex(b); k.save();
                    FloonsAuction.info("Sky Lyrics Farbe: " + k.lyricsColor + (b != null ? " / " + k.lyricsColor2 : "")); return 1; })))
            .then(ClientCommandManager.literal("here").executes(c -> { FloonsConfig.get().lyricsHud = true; FloonsAuction.info(FloonsSkyLyrics.here()); return 1; }))
            .then(ClientCommandManager.literal("size").then(ClientCommandManager.argument("faktor", FloatArgumentType.floatArg(0.3f, 4f))
                .executes(c -> { FloonsConfig k = FloonsConfig.get(); k.lyricsSize = FloatArgumentType.getFloat(c, "faktor"); k.save(); FloonsSkyLyrics.clear();
                    FloonsAuction.info("Sky Lyrics Größe: " + k.lyricsSize); return 1; })))
            .then(ClientCommandManager.literal("offset").then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(-5000, 5000))
                .executes(c -> { FloonsConfig k = FloonsConfig.get(); k.lyricsOffsetMs = IntegerArgumentType.getInteger(c, "ms"); k.save();
                    FloonsAuction.info("Sky Lyrics " + Math.abs(k.lyricsOffsetMs) + " ms " + (k.lyricsOffsetMs >= 0 ? "früher" : "später")); return 1; }))));
    }
}
