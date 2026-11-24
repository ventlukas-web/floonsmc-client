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
    public int antiSnipe = 15;
    /** Ton bei neuem Höchstgebot. */
    public boolean sound = true;
    public boolean hud = true;

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
    public String hudCorner = "top_right";

    public static FloonsConfig get() {
        if (inst == null) {
            try {
                if (Files.exists(FILE)) inst = GSON.fromJson(Files.readString(FILE), FloonsConfig.class);
            } catch (Exception ignored) {}
            if (inst == null) inst = new FloonsConfig();
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
