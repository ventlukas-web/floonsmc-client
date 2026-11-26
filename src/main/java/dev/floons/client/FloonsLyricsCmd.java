package dev.floons.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.FloatArgumentType;
import com.mojang.brigadier.arguments.IntegerArgumentType;
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
            .then(ClientCommandManager.literal("here").executes(c -> { FloonsConfig.get().lyricsHud = true; FloonsAuction.info(FloonsSkyLyrics.here()); return 1; }))
            .then(ClientCommandManager.literal("size").then(ClientCommandManager.argument("faktor", FloatArgumentType.floatArg(0.3f, 4f))
                .executes(c -> { FloonsConfig k = FloonsConfig.get(); k.lyricsSize = FloatArgumentType.getFloat(c, "faktor"); k.save(); FloonsSkyLyrics.clear();
                    FloonsAuction.info("Sky Lyrics Größe: " + k.lyricsSize); return 1; })))
            .then(ClientCommandManager.literal("offset").then(ClientCommandManager.argument("ms", IntegerArgumentType.integer(-5000, 5000))
                .executes(c -> { FloonsConfig k = FloonsConfig.get(); k.lyricsOffsetMs = IntegerArgumentType.getInteger(c, "ms"); k.save();
                    FloonsAuction.info("Sky Lyrics " + Math.abs(k.lyricsOffsetMs) + " ms " + (k.lyricsOffsetMs >= 0 ? "früher" : "später")); return 1; }))));
    }
}
