package dev.floons.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Sky Lyrics: zeigt den synchronen Songtext des gerade laufenden Titels (Spotify, Apple Music, Browser ...).
 * Windows: ein dauerhaft laufender PowerShell-Prozess liest die System-Mediensteuerung (nur im Hintergrund-Thread).
 * Lyrics kommen von LRCLIB. Im Render-Thread wird nur Text gezeichnet, es gibt keine Netzwerk- oder Prozessarbeit.
 */
public final class FloonsLyrics {
    private FloonsLyrics() {}

    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).build();
    private static final Pattern LRC = Pattern.compile("\\[(\\d+):(\\d+)(?:[.:](\\d+))?]\\s*(.*)");

    private static final String PS = String.join("\n",
        "[Console]::OutputEncoding = [System.Text.Encoding]::UTF8",
        "Add-Type -AssemblyName System.Runtime.WindowsRuntime",
        "$asTask = ([System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object { $_.Name -eq 'AsTask' -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1' })[0]",
        "function Await($op, $t) { $m = $asTask.MakeGenericMethod($t); $task = $m.Invoke($null, @($op)); $task.Wait(-1) | Out-Null; $task.Result }",
        "$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager, Windows.Media.Control, ContentType = WindowsRuntime]",
        "$null = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties, Windows.Media.Control, ContentType = WindowsRuntime]",
        "$mgr = Await ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager]::RequestAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager])",
        "while ($true) {",
        "  try {",
        "    $s = $mgr.GetCurrentSession()",
        "    if ($s) {",
        "      $p = Await ($s.TryGetMediaPropertiesAsync()) ([Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties])",
        "      $tl = $s.GetTimelineProperties(); $pi = $s.GetPlaybackInfo()",
        "      $age = [int64]([DateTimeOffset]::UtcNow - $tl.LastUpdatedTime.ToUniversalTime()).TotalMilliseconds",
        "      $pos = [int64]$tl.Position.TotalMilliseconds",
        "      if ($pi.PlaybackStatus -eq 4 -and $age -gt 0 -and $age -lt 600000) { $pos += $age }",
        "      Write-Output ($p.Title + \"`t\" + $p.Artist + \"`t\" + $pos + \"`t\" + [int]$pi.PlaybackStatus)",
        "    } else { Write-Output \"`t`t0`t0\" }",
        "  } catch { Write-Output \"`t`t0`t0\" }",
        "  Start-Sleep -Milliseconds 200",
        "}");

    private static volatile Process proc;
    private static volatile boolean running = false;
    private static volatile String title = "", artist = "";
    private static volatile long posMs = 0, posAt = 0;
    private static volatile boolean playing = false;
    private static volatile long[] times = new long[0];
    private static volatile String[] lines = new String[0];
    private static volatile String state = ""; // "", "lädt", "keine Lyrics"
    private static String fetched = "";
    private static int idx = 0;

    public static void update(FloonsConfig cfg) {
        if (cfg.lyricsHud && !running) start();
        else if (!cfg.lyricsHud && running) stop();
    }

    private static void start() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("win")) return;
        running = true;
        Thread t = new Thread(() -> {
            try {
                String enc = Base64.getEncoder().encodeToString(PS.getBytes(StandardCharsets.UTF_16LE));
                Process p = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-WindowStyle", "Hidden", "-EncodedCommand", enc)
                    .redirectErrorStream(true).start();
                proc = p;
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                    String ln;
                    while (running && (ln = r.readLine()) != null) parse(ln);
                }
            } catch (Exception ignored) {
            } finally { running = false; Process p = proc; if (p != null) p.destroyForcibly(); proc = null; }
        }, "floons-lyrics");
        t.setDaemon(true);
        t.start();
    }

    private static void stop() {
        running = false;
        Process p = proc;
        if (p != null) p.destroyForcibly();
        proc = null;
        title = artist = ""; lines = new String[0]; times = new long[0]; state = ""; fetched = "";
    }

    private static void parse(String ln) {
        String[] f = ln.split("\t", -1);
        if (f.length < 4) return;
        try {
            String nt = f[0].trim(), na = f[1].trim();
            long np = Long.parseLong(f[2].trim()), now = System.currentTimeMillis();
            boolean npl = f[3].trim().equals("4");
            // Glätten: Windows meldet die Position nur grob – nur neu setzen bei Songwechsel, Pause/Play oder echtem Sprung (Spulen)
            long predicted = posMs + (playing ? now - posAt : 0);
            boolean sameSong = nt.equals(title) && na.equals(artist);
            if (!sameSong || npl != playing || Math.abs(np - predicted) > 700) { posMs = np; posAt = now; }
            else if (npl && np > predicted) { posMs = predicted + (np - predicted) / 4; posAt = now; } // sanft nachziehen
            title = nt; artist = na; playing = npl;
        } catch (NumberFormatException e) { return; }
        String key = artist + "|" + title;
        if (!title.isEmpty() && !key.equals(fetched)) { fetched = key; fetch(title, artist); }
    }

    private static void fetch(String t, String a) {
        state = "lädt"; lines = new String[0]; times = new long[0]; idx = 0;
        final String key = a + "|" + t;
        Thread th = new Thread(() -> {
            try {
                String clean = t.replaceAll("\\s*[\\(\\[].*?[\\)\\]]", "").replaceAll("\\s*-\\s*(Remaster|Live|Radio|Single|Version).*$", "").trim();
                String syn = get("https://lrclib.net/api/get?track_name=" + URLEncoder.encode(clean, StandardCharsets.UTF_8) + "&artist_name=" + URLEncoder.encode(a, StandardCharsets.UTF_8));
                String lrc = null;
                if (syn != null) lrc = syncedOf(JsonParser.parseString(syn));
                // wie FWM: Titel/Interpret über Deezer bzw. iTunes nachschlagen und mit dem offiziellen Namen + Länge erneut suchen
                if (lrc == null) for (String[] m : catalog(a, clean)) {
                    String r2 = get("https://lrclib.net/api/get?track_name=" + URLEncoder.encode(m[0], StandardCharsets.UTF_8)
                        + "&artist_name=" + URLEncoder.encode(m[1], StandardCharsets.UTF_8) + (m[2].isEmpty() ? "" : "&duration=" + m[2]));
                    if (r2 != null) lrc = syncedOf(JsonParser.parseString(r2));
                    if (lrc == null) {
                        String s2 = get("https://lrclib.net/api/search?track_name=" + URLEncoder.encode(m[0], StandardCharsets.UTF_8) + "&artist_name=" + URLEncoder.encode(m[1], StandardCharsets.UTF_8));
                        if (s2 != null) { JsonElement e2 = JsonParser.parseString(s2); if (e2.isJsonArray()) for (JsonElement x : (JsonArray) e2) { lrc = syncedOf(x); if (lrc != null) break; } }
                    }
                    if (lrc != null) break;
                }
                if (lrc == null) {
                    String s = get("https://lrclib.net/api/search?q=" + URLEncoder.encode(a + " " + clean, StandardCharsets.UTF_8));
                    if (s != null) {
                        JsonElement e = JsonParser.parseString(s);
                        if (e.isJsonArray()) for (JsonElement x : (JsonArray) e) { lrc = syncedOf(x); if (lrc != null) break; }
                    }
                }
                if (!key.equals(fetched)) return; // Song hat gewechselt
                if (lrc == null) { state = "keine Lyrics"; return; }
                List<Long> ts = new ArrayList<>(); List<String> ls = new ArrayList<>();
                for (String l : lrc.split("\n")) {
                    Matcher m = LRC.matcher(l.trim());
                    if (!m.matches()) continue;
                    long ms = Long.parseLong(m.group(1)) * 60000L + Long.parseLong(m.group(2)) * 1000L;
                    if (m.group(3) != null) { String fr = (m.group(3) + "00").substring(0, 3); ms += Long.parseLong(fr); }
                    ts.add(ms); ls.add(m.group(4).trim());
                }
                if (ls.isEmpty()) { state = "keine Lyrics"; return; }
                long[] ta = new long[ts.size()]; for (int i = 0; i < ta.length; i++) ta[i] = ts.get(i);
                times = ta; lines = ls.toArray(new String[0]); state = "";
            } catch (Exception e) { state = "keine Lyrics"; }
        }, "floons-lyrics-fetch");
        th.setDaemon(true);
        th.start();
    }

    /** Offizieller Titel, Interpret und Länge (Sekunden) aus Deezer und iTunes. */
    private static List<String[]> catalog(String artist, String title) {
        List<String[]> out = new ArrayList<>();
        String q = URLEncoder.encode(artist + " " + title, StandardCharsets.UTF_8);
        try {
            String d = get("https://api.deezer.com/search?limit=1&q=" + q);
            if (d != null) {
                JsonArray arr = JsonParser.parseString(d).getAsJsonObject().getAsJsonArray("data");
                if (arr != null && !arr.isEmpty()) {
                    JsonObject o = arr.get(0).getAsJsonObject();
                    out.add(new String[]{o.get("title_short").getAsString(), o.getAsJsonObject("artist").get("name").getAsString(),
                        o.has("duration") ? String.valueOf(o.get("duration").getAsInt()) : ""});
                }
            }
        } catch (Exception ignored) {}
        try {
            String i = get("https://itunes.apple.com/search?entity=song&limit=1&term=" + q);
            if (i != null) {
                JsonArray arr = JsonParser.parseString(i).getAsJsonObject().getAsJsonArray("results");
                if (arr != null && !arr.isEmpty()) {
                    JsonObject o = arr.get(0).getAsJsonObject();
                    out.add(new String[]{o.get("trackName").getAsString(), o.get("artistName").getAsString(),
                        o.has("trackTimeMillis") ? String.valueOf(o.get("trackTimeMillis").getAsLong() / 1000) : ""});
                }
            }
        } catch (Exception ignored) {}
        return out;
    }

    private static String syncedOf(JsonElement e) {
        if (!e.isJsonObject()) return null;
        JsonObject o = e.getAsJsonObject();
        JsonElement s = o.get("syncedLyrics");
        return s != null && !s.isJsonNull() && !s.getAsString().isBlank() ? s.getAsString() : null;
    }

    private static String get(String url) {
        try {
            HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(8))
                .header("User-Agent", "FloonsClient (github.com/ventlukas-web/floonsmc-client)").GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            return r.statusCode() == 200 ? r.body() : null;
        } catch (Exception e) { return null; }
    }

    /** {Kopfzeile, aktuelle Zeile, nächste Zeile, Zeilen-Nummer} oder null, wenn kein Song läuft. */
    public static String[] current() {
        if (title.isEmpty()) return null;
        long pos = posMs + (playing ? System.currentTimeMillis() - posAt : 0) + FloonsConfig.get().lyricsOffsetMs;
        String[] ls = lines; long[] ts = times;
        String cur, next = "";
        int at = -1;
        if (ls.length == 0) { cur = state.isEmpty() ? "" : state; }
        else {
            int i = 0;
            while (i + 1 < ts.length && ts[i + 1] <= pos) i++;
            at = ts[i] <= pos ? i : -1;
            cur = at >= 0 ? ls[i] : "";
            if (cur.isBlank()) cur = "♪";
            if (at >= 0 && i + 1 < ls.length) next = ls[i + 1];
            else if (at < 0) next = ls[0];
        }
        return new String[]{artist.isEmpty() ? title : artist + " – " + title, cur, next, String.valueOf(at)};
    }

    // ---------- Anzeige (nur Text, kein I/O) ----------
    public static void render(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        if (!cfg.lyricsHud || title.isEmpty() || !"hud".equals(cfg.lyricsMode)) return;
        long pos = posMs + (playing ? System.currentTimeMillis() - posAt : 0);
        String[] ls = lines; long[] ts = times;
        String cur, next = "";
        if (ls.length == 0) { cur = state.isEmpty() ? "" : state; }
        else {
            if (idx >= ts.length || (idx > 0 && ts[idx] > pos)) idx = 0;
            while (idx + 1 < ts.length && ts[idx + 1] <= pos) idx++;
            cur = ts[idx] <= pos ? ls[idx] : "";
            if (cur.isBlank()) cur = "♪";
            if (idx + 1 < ls.length && ts[idx] <= pos) next = ls[idx + 1];
            else if (ts[0] > pos) next = ls[0];
        }
        String head = (artist.isEmpty() ? title : artist + " – " + title);
        int sw = mc.getWindow().getScaledWidth();
        int maxW = (int) (sw * 0.55f);
        head = tr.trimToWidth(head, maxW); cur = tr.trimToWidth(cur, maxW); next = tr.trimToWidth(next, maxW);
        int pad = 8;
        int w = Math.max(Math.max(tr.getWidth(head), tr.getWidth(cur)), Math.max(tr.getWidth(next), 120));
        int h = 14 + 12 + (next.isEmpty() ? 0 : 11) + 8;
        float[] pos0 = FloonsHud.place("lyrics", (sw - (w + pad * 2) * cfg.hudScale) / 2f, 6, (w + pad * 2) * cfg.hudScale, h * cfg.hudScale, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos0[0], pos0[1]);
        ctx.getMatrices().scale(cfg.hudScale, cfg.hudScale);
        FloonsTheme.panel(ctx, 0, 0, w + pad * 2, h);
        ctx.drawTextWithShadow(tr, head, pad + (w - tr.getWidth(head)) / 2, 5, FloonsTheme.MUTED);
        ctx.drawTextWithShadow(tr, cur, pad + (w - tr.getWidth(cur)) / 2, 18, FloonsTheme.TEXT);
        if (!next.isEmpty()) ctx.drawTextWithShadow(tr, next, pad + (w - tr.getWidth(next)) / 2, 30, FloonsTheme.alpha(FloonsTheme.MUTED, 0xA0));
        ctx.getMatrices().popMatrix();
    }
}
