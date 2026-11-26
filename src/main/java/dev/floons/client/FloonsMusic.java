package dev.floons.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.sun.net.httpserver.HttpServer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.util.Util;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Musik ingame. Die Musik läuft weiter in Spotify bzw. Apple Music, der Client steuert nur.
 * Spotify: eigene Playlists laden, Lied wählen, Play/Pause, Weiter/Zurück, Lautstärke (Spotify Web API, Login per PKCE).
 * System (Apple Music und alle anderen Player unter Windows): Play/Pause, Weiter/Zurück und Windows-Lautstärke per Medientasten.
 * Netzwerk nur, wenn du die Funktion benutzt.
 */
public final class FloonsMusic {
    private FloonsMusic() {}

    public static final int PORT = 43821;
    public static final String REDIRECT = "http://127.0.0.1:" + PORT + "/callback";
    private static final String SCOPES = "playlist-read-private playlist-read-collaborative user-read-playback-state user-modify-playback-state";
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).build();
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "floons-music"); t.setDaemon(true); return t; });

    /** {id, name, anzahl} */
    public static volatile List<String[]> playlists = new ArrayList<>();
    /** {titel, interpret} */
    public static volatile List<String[]> tracks = new ArrayList<>();
    public static volatile String openPlaylist = null, openPlaylistName = "";
    public static volatile String status = "", nowTitle = "", nowArtist = "";
    public static volatile boolean playing = false;
    public static volatile int volume = -1;

    private static String access = null;
    private static long accessUntil = 0;
    private static HttpServer server;

    public static boolean spotify() { return !"system".equals(FloonsConfig.get().musicSource); }
    public static boolean loggedIn() { return !FloonsConfig.get().spotifyRefresh.isBlank(); }

    // ---------------------------------------------------------------- Steuerung (beide Quellen)

    public static void toggle() {
        if (!spotify()) { key(179); playing = !playing; return; }
        run(() -> { if (playing) call("PUT", "/me/player/pause", null); else call("PUT", "/me/player/play", null); playing = !playing; refreshNow(); });
    }

    public static void next() {
        if (!spotify()) { key(176); return; }
        run(() -> { call("POST", "/me/player/next", null); sleep(350); refreshNow(); });
    }

    public static void prev() {
        if (!spotify()) { key(177); return; }
        run(() -> { call("POST", "/me/player/previous", null); sleep(350); refreshNow(); });
    }

    /** Spotify: Lautstärke in Prozent. System: Windows-Lautstärke um +/- 2 Stufen pro Klick. */
    public static void volume(int delta) {
        if (!spotify()) { int k = delta > 0 ? 175 : 174; for (int i = 0; i < 2; i++) key(k); return; }
        run(() -> {
            if (volume < 0) refreshNow();
            int v = Math.max(0, Math.min(100, (volume < 0 ? 50 : volume) + delta));
            setVolumeNow(v);
        });
    }

    public static void setVolume(int v) {
        if (!spotify()) { status = "Feste Lautstärke geht nur mit Spotify, nutze − und +."; return; }
        run(() -> setVolumeNow(Math.max(0, Math.min(100, v))));
    }

    private static void setVolumeNow(int v) {
        if (call("PUT", "/me/player/volume?volume_percent=" + v, null) != null) { volume = v; status = "Lautstärke " + v + " %"; }
    }

    // ---------------------------------------------------------------- Spotify: Playlists

    public static void loadPlaylists() {
        run(() -> {
            List<String[]> out = new ArrayList<>();
            String next = "/me/playlists?limit=50";
            while (next != null && out.size() < 200) {
                JsonObject j = call("GET", next, null);
                if (j == null) return;
                for (JsonElement e : j.getAsJsonArray("items")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject p = e.getAsJsonObject();
                    int n = 0;
                    for (String k : new String[]{"items", "tracks"}) if (p.has(k) && p.get(k).isJsonObject() && p.getAsJsonObject(k).has("total")) n = p.getAsJsonObject(k).get("total").getAsInt();
                    out.add(new String[]{p.get("id").getAsString(), str(p, "name"), String.valueOf(n)});
                }
                next = j.has("next") && !j.get("next").isJsonNull() ? j.get("next").getAsString().replace("https://api.spotify.com/v1", "") : null;
            }
            playlists = out;
            status = out.isEmpty() ? "Keine Playlists gefunden." : out.size() + " Playlists importiert.";
        });
    }

    public static void openPlaylist(String id, String name) {
        openPlaylist = id; openPlaylistName = name; tracks = new ArrayList<>();
        run(() -> {
            List<String[]> out = new ArrayList<>();
            String next = "/playlists/" + id + "/items?limit=100";
            while (next != null && out.size() < 1000) {
                JsonObject j = call("GET", next, null);
                if (j == null) return;
                for (JsonElement e : j.getAsJsonArray("items")) {
                    if (!e.isJsonObject()) continue;
                    JsonObject w = e.getAsJsonObject();
                    JsonElement t = w.has("item") ? w.get("item") : w.get("track");
                    if (t == null || !t.isJsonObject()) { out.add(new String[]{"(nicht verfügbar)", ""}); continue; }
                    out.add(new String[]{str(t.getAsJsonObject(), "name"), artists(t.getAsJsonObject())});
                }
                next = j.has("next") && !j.get("next").isJsonNull() ? j.get("next").getAsString().replace("https://api.spotify.com/v1", "") : null;
            }
            if (id.equals(openPlaylist)) tracks = out;
        });
    }

    public static void closePlaylist() { openPlaylist = null; tracks = new ArrayList<>(); }

    /** Spielt die Playlist ab Lied Nummer index (0 = von vorne). */
    public static void play(String playlistId, int index) {
        run(() -> {
            JsonObject b = new JsonObject();
            b.addProperty("context_uri", "spotify:playlist:" + playlistId);
            JsonObject off = new JsonObject(); off.addProperty("position", Math.max(0, index)); b.add("offset", off);
            if (call("PUT", "/me/player/play", b) != null) { playing = true; sleep(400); refreshNow(); }
        });
    }

    public static void refresh() { if (spotify() && loggedIn()) run(FloonsMusic::refreshNow); }

    private static void refreshNow() {
        JsonObject j = call("GET", "/me/player", null);
        if (j == null) return;
        playing = j.has("is_playing") && j.get("is_playing").getAsBoolean();
        if (j.has("device") && j.get("device").isJsonObject() && j.getAsJsonObject("device").has("volume_percent")
            && !j.getAsJsonObject("device").get("volume_percent").isJsonNull()) volume = j.getAsJsonObject("device").get("volume_percent").getAsInt();
        if (j.has("item") && j.get("item").isJsonObject()) {
            JsonObject it = j.getAsJsonObject("item");
            nowTitle = str(it, "name"); nowArtist = artists(it);
        }
    }

    // ---------------------------------------------------------------- Spotify: Login (PKCE, ohne Secret)

    public static void login() {
        String cid = FloonsConfig.get().spotifyClientId.trim();
        if (cid.isEmpty()) { status = "Erst die Client-ID deiner Spotify-App eintragen."; return; }
        try {
            String verifier = rand(64);
            String challenge = b64(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
            String state = rand(16);
            stopServer();
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", PORT), 0);
            server.createContext("/callback", ex -> {
                String q = ex.getRequestURI().getRawQuery();
                String code = param(q, "code"), st = param(q, "state");
                boolean ok = code != null && state.equals(st);
                byte[] page = ("<html><body style='font-family:sans-serif;text-align:center;margin-top:80px'><h2>"
                    + (ok ? "Spotify verbunden. Du kannst zurück ins Spiel." : "Anmeldung abgebrochen.") + "</h2></body></html>").getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
                ex.sendResponseHeaders(200, page.length);
                try (OutputStream o = ex.getResponseBody()) { o.write(page); }
                if (ok) run(() -> exchange(cid, code, verifier)); else status = "Spotify-Anmeldung abgebrochen.";
                POOL.execute(FloonsMusic::stopServer);
            });
            server.start();
            String url = "https://accounts.spotify.com/authorize?response_type=code&client_id=" + enc(cid)
                + "&scope=" + enc(SCOPES) + "&redirect_uri=" + enc(REDIRECT) + "&state=" + state
                + "&code_challenge_method=S256&code_challenge=" + challenge;
            Util.getOperatingSystem().open(URI.create(url));
            status = "Browser geöffnet: bei Spotify anmelden.";
        } catch (Exception e) {
            status = "Login nicht möglich (Port " + PORT + " belegt?).";
        }
    }

    public static void logout() {
        FloonsConfig c = FloonsConfig.get(); c.spotifyRefresh = ""; c.save();
        access = null; playlists = new ArrayList<>(); tracks = new ArrayList<>(); openPlaylist = null;
        nowTitle = nowArtist = ""; status = "Von Spotify abgemeldet.";
    }

    private static void stopServer() { if (server != null) { server.stop(0); server = null; } }

    private static void exchange(String cid, String code, String verifier) {
        JsonObject j = token("grant_type=authorization_code&code=" + enc(code) + "&redirect_uri=" + enc(REDIRECT)
            + "&client_id=" + enc(cid) + "&code_verifier=" + enc(verifier));
        if (j == null) return;
        status = "Spotify verbunden.";
        loadPlaylists();
        refreshNow();
    }

    private static boolean ensureToken() {
        if (access != null && System.currentTimeMillis() < accessUntil) return true;
        FloonsConfig c = FloonsConfig.get();
        if (c.spotifyRefresh.isBlank()) { status = "Nicht mit Spotify verbunden."; return false; }
        return token("grant_type=refresh_token&refresh_token=" + enc(c.spotifyRefresh) + "&client_id=" + enc(c.spotifyClientId.trim())) != null;
    }

    private static JsonObject token(String form) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create("https://accounts.spotify.com/api/token"))
                .timeout(Duration.ofSeconds(10)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            JsonObject j = JsonParser.parseString(r.body()).getAsJsonObject();
            if (r.statusCode() != 200 || !j.has("access_token")) {
                status = "Spotify-Login fehlgeschlagen: " + (j.has("error_description") ? j.get("error_description").getAsString() : "HTTP " + r.statusCode());
                if (r.statusCode() == 400) { FloonsConfig c = FloonsConfig.get(); c.spotifyRefresh = ""; c.save(); }
                return null;
            }
            access = j.get("access_token").getAsString();
            accessUntil = System.currentTimeMillis() + (j.has("expires_in") ? j.get("expires_in").getAsLong() : 3600) * 1000L - 60_000L;
            if (j.has("refresh_token")) { FloonsConfig c = FloonsConfig.get(); c.spotifyRefresh = j.get("refresh_token").getAsString(); c.save(); }
            return j;
        } catch (Exception e) { status = "Keine Verbindung zu Spotify."; return null; }
    }

    // ---------------------------------------------------------------- Spotify: API

    /** Gibt die Antwort zurück (leeres Objekt bei 204), oder null bei Fehler (status wird gesetzt). */
    private static JsonObject call(String method, String path, JsonObject body) {
        if (!ensureToken()) return null;
        JsonObject r = send(method, path, body);
        if (r == NO_DEVICE) {
            String dev = device();
            if (dev == null) { status = "Kein Spotify-Gerät aktiv: Spotify einmal am PC oder Handy öffnen."; return null; }
            r = send(method, path + (path.contains("?") ? "&" : "?") + "device_id=" + enc(dev), body);
            if (r == NO_DEVICE) { status = "Kein Spotify-Gerät aktiv: Spotify einmal am PC oder Handy öffnen."; return null; }
        }
        return r;
    }

    private static final JsonObject NO_DEVICE = new JsonObject();

    private static JsonObject send(String method, String path, JsonObject body) {
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create("https://api.spotify.com/v1" + path)).timeout(Duration.ofSeconds(10))
                .header("Authorization", "Bearer " + access);
            String data = body == null ? "" : body.toString();
            if (body != null) b.header("Content-Type", "application/json");
            b.method(method, method.equals("GET") ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(data, StandardCharsets.UTF_8));
            HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            int sc = r.statusCode();
            if (sc == 204 || (sc >= 200 && sc < 300 && r.body().isBlank())) return new JsonObject();
            if (sc >= 200 && sc < 300) { JsonElement e = JsonParser.parseString(r.body()); return e.isJsonObject() ? e.getAsJsonObject() : new JsonObject(); }
            if (sc == 404 && r.body().contains("NO_ACTIVE_DEVICE")) return NO_DEVICE;
            if (sc == 401) { access = null; status = "Spotify-Sitzung abgelaufen, bitte nochmal klicken."; return null; }
            if (sc == 403) { status = path.startsWith("/me/player") ? "Spotify Premium nötig (oder Account nicht in der Spotify-App freigeschaltet)." : "Spotify erlaubt das nicht (403)."; return null; }
            if (sc == 429) { status = "Zu viele Anfragen an Spotify, kurz warten."; return null; }
            status = "Spotify-Fehler " + sc + ".";
            return null;
        } catch (Exception e) { status = "Keine Verbindung zu Spotify."; return null; }
    }

    private static String device() {
        JsonObject j = send("GET", "/me/player/devices", null);
        if (j == null || j == NO_DEVICE || !j.has("devices")) return null;
        String any = null;
        for (JsonElement e : j.getAsJsonArray("devices")) {
            JsonObject d = e.getAsJsonObject();
            if (d.get("id") == null || d.get("id").isJsonNull()) continue;
            String id = d.get("id").getAsString();
            if ("Computer".equalsIgnoreCase(str(d, "type"))) return id;
            if (any == null) any = id;
        }
        return any;
    }

    // ---------------------------------------------------------------- System-Medientasten (Windows)

    private static void key(int vk) {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) { status = "Medien-Steuerung geht nur unter Windows."; return; }
        POOL.execute(() -> {
            try {
                String ps = "$s=New-Object -ComObject WScript.Shell; $s.SendKeys([char]" + vk + ")";
                new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-Command", ps)
                    .redirectErrorStream(true).start().waitFor();
            } catch (Exception e) { status = "Medientaste konnte nicht gesendet werden."; }
        });
    }

    // ---------------------------------------------------------------- Befehle

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(ClientCommandManager.literal("music")
            .executes(c -> { FloonsMenu.openMusicTab(); FloonsGamble.pendingScreen = 1; FloonsMusic.refresh(); return 1; })
            .then(ClientCommandManager.literal("play").executes(c -> { if (!playing) toggle(); return 1; }))
            .then(ClientCommandManager.literal("pause").executes(c -> { if (playing || !spotify()) toggle(); return 1; }))
            .then(ClientCommandManager.literal("toggle").executes(c -> { toggle(); return 1; }))
            .then(ClientCommandManager.literal("next").executes(c -> { next(); return 1; }))
            .then(ClientCommandManager.literal("prev").executes(c -> { prev(); return 1; }))
            .then(ClientCommandManager.literal("vol").then(ClientCommandManager.argument("prozent", IntegerArgumentType.integer(0, 100))
                .executes(c -> { setVolume(IntegerArgumentType.getInteger(c, "prozent")); return 1; }))));
    }

    // ---------------------------------------------------------------- Hilfen

    private static void run(Runnable r) { POOL.execute(() -> { try { r.run(); } catch (Exception e) { status = "Fehler: " + e.getMessage(); } }); }
    private static void sleep(long ms) { try { Thread.sleep(ms); } catch (InterruptedException ignored) {} }
    private static String str(JsonObject o, String k) { return o.has(k) && !o.get(k).isJsonNull() ? o.get(k).getAsString() : ""; }
    private static String artists(JsonObject t) {
        if (!t.has("artists") || !t.get("artists").isJsonArray()) return "";
        List<String> a = new ArrayList<>();
        for (JsonElement e : (JsonArray) t.get("artists")) if (e.isJsonObject()) a.add(str(e.getAsJsonObject(), "name"));
        return String.join(", ", a);
    }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8); }
    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }
    private static String rand(int n) {
        String ab = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        SecureRandom r = new SecureRandom(); StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) sb.append(ab.charAt(r.nextInt(ab.length())));
        return sb.toString();
    }
    private static String param(String q, String k) {
        if (q == null) return null;
        for (String p : q.split("&")) { int i = p.indexOf('='); if (i > 0 && p.substring(0, i).equals(k)) return java.net.URLDecoder.decode(p.substring(i + 1), StandardCharsets.UTF_8); }
        return null;
    }
}
