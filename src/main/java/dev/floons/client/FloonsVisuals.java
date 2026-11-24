package dev.floons.client;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Visuals wie im Toasty Client: echtes Fullbright, Zoom, Auto-Sprint, Anti-Leak (Chat-Filter + Block-Pack), NoFog (Mixin), Glas. */
public final class FloonsVisuals {
    private FloonsVisuals() {}

    public static KeyBinding zoomKey, fullbrightKey, calcKey, shotKey;
    private static boolean zooming;
    private static int zoomOrig;
    private static double gammaOrig = -1;

    public static void register() {
        ClientSendMessageEvents.ALLOW_CHAT.register(FloonsVisuals::allowOut);
        ClientSendMessageEvents.ALLOW_COMMAND.register(cmd -> allowOut("/" + cmd));
    }

    public static void tick(MinecraftClient mc) {
        FloonsConfig cfg = FloonsConfig.get();
        fullbright(mc, cfg);
        shotTick(mc);
        while (calcKey != null && calcKey.wasPressed()) if (mc.currentScreen == null) mc.setScreen(new FloonsCalculatorScreen(null));
        while (shotKey != null && shotKey.wasPressed()) screenshot(true);
        if (mc.player == null || mc.world == null) return;
        while (fullbrightKey != null && fullbrightKey.wasPressed()) {
            cfg.fullbright = !cfg.fullbright; cfg.save();
            FloonsGamble.chat("Fullbright " + (cfg.fullbright ? "an" : "aus"));
        }
        if (cfg.autoSprint && mc.currentScreen == null && mc.options.forwardKey.isPressed() && !mc.player.isSneaking()
            && !mc.player.isUsingItem()) mc.player.setSprinting(true);
        boolean zk = zoomKey != null && zoomKey.isPressed() && mc.currentScreen == null;
        if (zk && !zooming) { zoomOrig = mc.options.getFov().getValue(); mc.options.getFov().setValue(30); zooming = true; }
        else if (!zk && zooming) { mc.options.getFov().setValue(zoomOrig); zooming = false; }
    }

    /** Echtes Fullbright: Gamma weit ueber das Menue-Maximum - kein Nachtsicht-Effekt. */
    private static void fullbright(MinecraftClient mc, FloonsConfig cfg) {
        try {
            net.minecraft.client.option.SimpleOption<Double> g = mc.options.getGamma();
            if (cfg.fullbright) {
                if (gammaOrig < 0) gammaOrig = g.getValue();
                if (g.getValue() < 15.0) ((dev.floons.client.mixin.SimpleOptionAccessor) (Object) g).floons$setRaw(16.0);
            } else if (gammaOrig >= 0) {
                ((dev.floons.client.mixin.SimpleOptionAccessor) (Object) g).floons$setRaw(Math.min(1.0, gammaOrig));
                gammaOrig = -1;
            }
        } catch (Throwable ignored) { }
    }

    // ---------- Screenshot-Tool ----------
    private static int shotWait = -1;
    private static boolean shotHudWas;

    /** Screenshot im Spiel: clean = ohne HUD. Schliesst offene Menues vorher, damit sie nicht im Bild sind. */
    public static void screenshot(boolean clean) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (shotWait >= 0 || mc.world == null) return;
        if (mc.currentScreen != null) mc.setScreen(null);
        shotHudWas = mc.options.hudHidden;
        if (clean) mc.options.hudHidden = true;
        shotWait = 5;
    }

    private static void shotTick(MinecraftClient mc) {
        if (shotWait > 0) { shotWait--; return; }
        if (shotWait != 0) return;
        shotWait = -1;
        try {
            net.minecraft.client.util.ScreenshotRecorder.saveScreenshot(mc.runDirectory, mc.getFramebuffer(),
                msg -> mc.execute(() -> mc.inGameHud.getChatHud().addMessage(msg)));
        } catch (Throwable t) {
            FloonsGamble.chat("Screenshot fehlgeschlagen: " + t.getClass().getSimpleName());
        }
        mc.options.hudHidden = shotHudWas;
    }

    // ---------- Anti-Leak (Chat) ----------
    private static final Pattern IP = Pattern.compile("\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b");
    private static final Pattern NUM = Pattern.compile("-?\\d{2,6}");
    private static final Pattern DM = Pattern.compile("^/(msg|w|tell|r|reply|m|whisper|t)\\b.*", Pattern.CASE_INSENSITIVE);

    /** @return Grund, warum die Nachricht gesperrt wuerde, sonst null */
    public static String leakReason(String text) {
        FloonsConfig cfg = FloonsConfig.get();
        MinecraftClient mc = MinecraftClient.getInstance();
        String low = text.toLowerCase();
        for (String w : cfg.leakWords.split(",")) {
            w = w.trim().toLowerCase();
            if (w.length() >= 2 && low.contains(w)) return "gesperrtes Wort \"" + w + "\"";
        }
        String user = System.getProperty("user.name", "");
        if (user.length() >= 4 && low.contains(user.toLowerCase())) return "dein PC-Benutzername";
        if (IP.matcher(text).find()) return "IP-Adresse";
        boolean chatLike = !text.startsWith("/") || DM.matcher(text).matches();
        if (chatLike && mc.player != null) {
            Matcher m = NUM.matcher(text);
            double px = mc.player.getX(), pz = mc.player.getZ();
            java.util.List<Integer> nums = new java.util.ArrayList<>();
            while (m.find()) { try { nums.add(Integer.parseInt(m.group())); } catch (Exception ignored) {} }
            for (int a : nums) {
                if (Math.abs(a) >= 50 && (Math.abs(a - px) <= 4 || Math.abs(a - pz) <= 4)) return "deine Koordinaten";
                for (int b : nums) if (Math.abs(a - px) < 150 && Math.abs(b - pz) < 150) return "deine Koordinaten (Gegend)";
            }
        }
        return null;
    }

    private static boolean allowOut(String text) {
        if (!FloonsConfig.get().antiLeak) return true;
        String why = leakReason(text);
        if (why == null) return true;
        FloonsNotify.push("Anti-Leak", "Blockiert: " + why, FloonsTheme.BAD);
        FloonsGamble.chat("Anti-Leak hat die Nachricht blockiert (" + why + ").");
        return false;
    }
}
