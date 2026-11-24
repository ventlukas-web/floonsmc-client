package dev.floons.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

/** Tools wie im Toasty Client: Notizen, Wegpunkte, Stoppuhr, Timer, Fullbright, Glas. */
public final class FloonsTools {
    private FloonsTools() {}

    private static void chat(String s) { FloonsGamble.chat(s); }

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
            d.register(ClientCommandManager.literal("note")
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("text", StringArgumentType.greedyString()).executes(c -> {
                        FloonsConfig cf = FloonsConfig.get(); cf.notes.add(StringArgumentType.getString(c, "text").trim()); cf.save();
                        chat("Notiz " + cf.notes.size() + " gespeichert."); return 1;
                    })))
                .then(ClientCommandManager.literal("remove")
                    .then(ClientCommandManager.argument("nr", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1)).executes(c -> {
                        FloonsConfig cf = FloonsConfig.get(); int i = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "nr") - 1;
                        if (i >= cf.notes.size()) { chat("Keine Notiz " + (i + 1) + "."); return 0; }
                        cf.notes.remove(i); cf.save(); chat("Notiz gelöscht."); return 1;
                    })))
                .then(ClientCommandManager.literal("clear").executes(c -> {
                    FloonsConfig cf = FloonsConfig.get(); cf.notes.clear(); cf.save(); chat("Alle Notizen gelöscht."); return 1;
                }))
                .then(ClientCommandManager.literal("list").executes(c -> {
                    FloonsConfig cf = FloonsConfig.get();
                    if (cf.notes.isEmpty()) { chat("Keine Notizen."); return 1; }
                    for (int i = 0; i < cf.notes.size(); i++) chat((i + 1) + ". " + cf.notes.get(i));
                    return 1;
                })));
            d.register(ClientCommandManager.literal("waypoint")
                .then(ClientCommandManager.literal("add")
                    .then(ClientCommandManager.argument("name", StringArgumentType.word())
                        .executes(c -> {
                            String n = StringArgumentType.getString(c, "name");
                            chat(FloonsWaypoints.addHere(n) ? "Wegpunkt \"" + n + "\" gesetzt." : "Nicht möglich."); return 1;
                        })
                        .then(ClientCommandManager.argument("x", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                            .then(ClientCommandManager.argument("y", com.mojang.brigadier.arguments.IntegerArgumentType.integer())
                                .then(ClientCommandManager.argument("z", com.mojang.brigadier.arguments.IntegerArgumentType.integer()).executes(c -> {
                                    String n = StringArgumentType.getString(c, "name");
                                    boolean ok = FloonsWaypoints.add(n, com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "x"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "y"),
                                        com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "z"));
                                    chat(ok ? "Wegpunkt \"" + n + "\" gesetzt." : "Nicht möglich."); return 1;
                                })))))
                )
                .then(ClientCommandManager.literal("remove").then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(c -> {
                    chat(FloonsWaypoints.remove(StringArgumentType.getString(c, "name")) ? "Wegpunkt gelöscht." : "Wegpunkt nicht gefunden."); return 1;
                })))
                .then(ClientCommandManager.literal("clear").executes(c -> {
                    FloonsConfig cf = FloonsConfig.get(); cf.waypoints.clear(); cf.save(); chat("Alle Wegpunkte gelöscht."); return 1;
                }))
                .then(ClientCommandManager.literal("list").executes(c -> {
                    FloonsConfig cf = FloonsConfig.get();
                    if (cf.waypoints.isEmpty()) { chat("Keine Wegpunkte."); return 1; }
                    for (var w : cf.waypoints) chat(w.name + ": " + w.x + " " + w.y + " " + w.z + " (" + w.dim + ")");
                    return 1;
                })));
            d.register(ClientCommandManager.literal("stopwatch")
                .executes(c -> { chat(FloonsTimers.stopwatch(FloonsTimers.swRunning() ? "stop" : "start")); return 1; })
                .then(ClientCommandManager.literal("start").executes(c -> { chat(FloonsTimers.stopwatch("start")); return 1; }))
                .then(ClientCommandManager.literal("stop").executes(c -> { chat(FloonsTimers.stopwatch("stop")); return 1; }))
                .then(ClientCommandManager.literal("lap").executes(c -> { chat(FloonsTimers.stopwatch("lap")); return 1; }))
                .then(ClientCommandManager.literal("reset").executes(c -> { chat(FloonsTimers.stopwatch("reset")); return 1; })));
            d.register(ClientCommandManager.literal("timer")
                .then(ClientCommandManager.literal("cancel").executes(c -> { chat(FloonsTimers.cancelTimers() + " Timer gelöscht."); return 1; }))
                .then(ClientCommandManager.argument("sekunden", com.mojang.brigadier.arguments.IntegerArgumentType.integer(1, 86400))
                    .executes(c -> { int s = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "sekunden"); FloonsTimers.addTimer(s, s + " s"); chat("Timer gestartet: " + s + " s"); return 1; })
                    .then(ClientCommandManager.argument("name", StringArgumentType.greedyString())
                        .executes(c -> { int s = com.mojang.brigadier.arguments.IntegerArgumentType.getInteger(c, "sekunden"); String n = StringArgumentType.getString(c, "name"); FloonsTimers.addTimer(s, n); chat("Timer \"" + n + "\": " + s + " s"); return 1; }))));
        d.register(ClientCommandManager.literal("fullbright").executes(c -> {
            FloonsConfig cf = FloonsConfig.get(); cf.fullbright = !cf.fullbright; cf.save();
            chat("Fullbright " + (cf.fullbright ? "an" : "aus")); return 1;
        }));
        d.register(ClientCommandManager.literal("glass").executes(c -> {
            boolean ok = FloonsGlass.toggle();
            chat(ok ? "Unsichtbares Glas wird umgeschaltet ..." : "Glas-Pack nicht gefunden.");
            return ok ? 1 : 0;
        }));
    }
}
