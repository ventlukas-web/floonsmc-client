package dev.floons.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/** Einstellungen in config/floonsclient.json. */
public final class FloonsConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("floonsclient.json");
    private static FloonsConfig inst;

    /** Zahlungsnachricht von WindSMP (gleiches Muster wie im Toasty Client). Gruppen: amount, suffix, player. */
    public String pattern = "^You received \\$(?<amount>[0-9][0-9.,]*)\\s*(?<suffix>[KkMmBbTt]?) from (?<player>\\.?[A-Za-z0-9_]{1,16})\\.?$";
    /** Start, Höchstgebote, Countdown und Gewinner öffentlich in den Chat schreiben. */
    public boolean announce = true;
    /** Öffentlicher Countdown bei 60/30/10/5 Sekunden. */
    public boolean countdown = true;
    /** Mindest-Erhöhung über dem aktuellen Höchstgebot. */
    public long minRaise = 1;
    /** Anti-Snipe: Höchstgebot in den letzten X Sekunden verlängert auf X Sekunden (0 = aus). */
    /** Anti-Snipe: kommt in den letzten antiSnipeWindow Sekunden mehr Geld, geht die Zeit wieder auf antiSnipeTo Sekunden. */
    public boolean antiSnipeOn = true;
    public int antiSnipeWindow = 10;
    public int antiSnipeTo = 15;
    /** Ton bei neuem Höchstgebot. */
    public boolean sound = true;
    public boolean hud = true;
    /** Wasserzeichen "Floons Client" oben rechts in allen Menüs. */
    public boolean watermark = true;
    /** Hauptmenue: Beitreten-Button + Vanilla-Buttons im Floons-Design. */
    public boolean joinButton = true, niceButtons = true;
    public String serverName = "WindSMP";
    public String serverIp = "windsmp.net";

    // ---------- Gamble (wie Toasty Client) ----------
    /** Auszahlungs-Faktor bei /win (2.0 = doppelter Einsatz). */
    public double winMultiplier = 2.0;
    /** Gewinn automatisch per /pay auszahlen. */
    public boolean autoPay = true;
    /** Pause zwischen automatischen /pay-Befehlen (Anti-Spam), in ms. */
    public int payDelayMs = 0;
    public long minBet = 0;
    public long maxBet = Long.MAX_VALUE;
    /** Warnung, wenn eine Zahlung so lange offen ist (0 = aus), in Sekunden. */
    public int waitWarnSec = 30;
    /** Warnung bei diesem Session-Verlust (0 = aus). */
    public long lossLimit = 0;
    /** Großes GEWONNEN/VERLOREN-Banner. */
    public boolean effects = true;
    public boolean confetti = true;
    /** Gamble-HUD (aktuelles Gamble, Warteschlange, Profit). */
    public boolean gambleHud = true;
    public float hudScale = 1.0f;
    /** top_left, top_right, bottom_right, mid_left, mid_right */
    public String hudCorner = "mid_right";
    /** Warteschlange: nächste Auktion automatisch starten, mit Pause dazwischen (Sekunden). */
    public boolean autoQueue = true;
    public int queueGapSec = 10;
    /** Große Countdown-Zahl in der Bildschirmmitte in den letzten Sekunden. */
    public boolean bigCountdown = true;
    /** Live-Gebotsverlauf im Auktions-HUD. */
    public boolean bidFeed = true;

    // ---------- Design ----------
    public String color1 = "#38BDF8";
    public String color2 = "#5EEAD4";
    public int bgOpacity = 208;
    public String themeBase = "#0B1620";

    // ---------- HUDs (wie Toasty Client) ----------
    public boolean keystrokes = true, coords = true, showFps = false, ping = false, speed = false, clock = false, moduleList = true;
    public boolean payHud = true, notesHud = true, notify = true, satBorder = true;
    public java.util.Map<String, float[]> hudPos = new java.util.HashMap<>();
    public static class Wp { public String name = "", dim = ""; public int x, y, z; }
    public boolean wpOn = true;
    public java.util.List<Wp> waypoints = new java.util.ArrayList<>();
    public java.util.List<String> notes = new java.util.ArrayList<>();
    public boolean customHud = false;
    public String customTitle = "Mein HUD";
    public java.util.List<String> customLines = new java.util.ArrayList<>(java.util.List.of("XYZ: {x} {y} {z}", "FPS: {fps}", "Profit: {profit}"));
    public boolean comboHud = false, memHud = false, vitalsHud = false;
    public boolean topHud = false, graphHud = false;
    public boolean compassHud = false, armorWarn = true;
    public int armorWarnPct = 10;
    public boolean armorHud = false, potionHud = false, sessionHud = false;
    public boolean cpsHud = false, reachHud = false, pvpItems = false;
    public int hitSoundIdx = 0;
    public boolean hitMarker = false, hitSound = false, lowHpWarn = false, cooldownBar = false;
    public boolean streamer = false, streamerHideXyz = true;
    public String streamerName = "Floons";
    // Twitch & Giveaway
    public boolean twitch = false;
    public String twitchChannel = "";
    public int twitchLines = 8;
    public String adChannel = "";
    public String adText = "Schaut bei mir auf Twitch vorbei: twitch.tv/{kanal}";
    public String giveawayKeyword = "join";
    public int giveawayWinners = 1;
    public boolean giveawayTwitch = true;
    public boolean giveawayHud = true;
    // Scoreboard (rechte Seite)
    public boolean sbCustom = false, sbNumbers = false, sbBorderOn = true, sbTheme = true;
    public int sbRadius = 6, sbAlpha = 170;
    public float sbScale = 1.0f;
    public String sbBg = "#0B1620", sbBorder = "#38BDF8", sbText = "#FFFFFF";
    // Visuals
    public boolean fullbright = false, noFog = false, antiLeak = false, autoSprint = false;
    public String leakWords = "";
    // FPS (aendert nur normale Spieloptionen, alte Werte werden gemerkt)
    public boolean perfParticles = false, perfShadows = false, perfDistance = false;
    public boolean perfClouds = false, perfBlend = false, perfAo = false, perfFps = false;
    public java.util.Map<String, String> orig = new java.util.HashMap<>();
    public String origParticles = null;
    public Boolean origShadows = null;
    public Integer origView = null, origSim = null;
    public Double origScaling = null;
    // Crosshair
    public boolean chOn = false, chDot = true, chOutline = true;
    public int chStyle = 1, chLen = 6, chGap = 3, chThick = 1, chColorIdx = 1;
    public String chHex = "";

    public static FloonsConfig get() {
        if (inst == null) {
            try {
                if (Files.exists(FILE)) inst = GSON.fromJson(Files.readString(FILE), FloonsConfig.class);
            } catch (Exception ignored) {}
            if (inst == null) inst = new FloonsConfig();
            if (inst.orig == null) inst.orig = new java.util.HashMap<>();
            inst.save();
        }
        return inst;
    }

    public void save() {
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(this));
        } catch (Exception ignored) {}
    }
}
