package dev.floons.client;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Floons AI: KI-Assistent im Chat (/ai oder /ki), läuft über die Claude API mit dem eigenen API-Key.
 * Beantwortet allgemeine Fragen und Fragen zum Floons Client. Netzwerk nur, wenn eine Frage gestellt wird.
 */
public final class FloonsAI {
    private FloonsAI() {}

    static final String NAME = "Floons AI", CLIENT = "Floons Client";
    private static final String WISSEN = "/assets/floonsclient/ai/wissen.txt";
    private static final URI API = URI.create("https://api.anthropic.com/v1/messages");
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).build();
    static final String[] MODELS = {"claude-haiku-5-5", "claude-sonnet-5-5", "claude-opus-5-5"};

    private record Msg(String role, String text) {}
    private static final List<Msg> history = new ArrayList<>();
    private static volatile boolean busy = false;
    private static String knowledge;

    // ---------------------------------------------------------------- Befehle

    public static void register(CommandDispatcher<FabricClientCommandSource> d) {
        for (String name : new String[]{"ai", "ki"}) {
            d.register(ClientCommandManager.literal(name)
                .executes(c -> { help(); return 1; })
                .then(ClientCommandManager.literal("hilfe").executes(c -> { help(); return 1; }))
                .then(ClientCommandManager.literal("reset").executes(c -> { reset(); return 1; }))
                .then(ClientCommandManager.literal("key").then(ClientCommandManager.argument("key", StringArgumentType.greedyString())
                    .executes(c -> { setKey(StringArgumentType.getString(c, "key")); return 1; })))
                .then(ClientCommandManager.literal("model")
                    .executes(c -> { say("Modell: " + FloonsConfig.get().aiModel, Formatting.GRAY); return 1; })
                    .then(ClientCommandManager.argument("name", StringArgumentType.word()).executes(c -> {
                        FloonsConfig k = FloonsConfig.get(); k.aiModel = StringArgumentType.getString(c, "name"); k.save();
                        say("Modell: " + k.aiModel, Formatting.GRAY); return 1; })))
                .then(ClientCommandManager.argument("frage", StringArgumentType.greedyString())
                    .executes(c -> { ask(StringArgumentType.getString(c, "frage")); return 1; })));
        }
    }

    static void help() {
        say("Frag mich alles, auch zum " + CLIENT + ":", Formatting.GRAY);
        line("/ai <Frage>", "Frage stellen (auch /ki)");
        line("/ai reset", "neues Gespräch");
        line("/ai key <key>", "Claude-API-Key setzen");
        line("/ai model <name>", "Modell wechseln");
        if (!hasKey()) say("Noch kein API-Key: auf console.anthropic.com erstellen, dann /ai key <key>.", Formatting.YELLOW);
    }

    static boolean hasKey() { return !FloonsConfig.get().aiKey.isBlank(); }

    static void setKey(String key) {
        FloonsConfig k = FloonsConfig.get(); k.aiKey = key.trim(); k.save();
        FloonsNotify.push(NAME, k.aiKey.isEmpty() ? "API-Key entfernt" : "API-Key gespeichert", FloonsTheme.BLUE);
    }

    static void reset() {
        history.clear();
        say("Neues Gespräch gestartet.", Formatting.GRAY);
    }

    // ---------------------------------------------------------------- Fragen

    public static void ask(String q) {
        FloonsConfig cfg = FloonsConfig.get();
        if (!hasKey()) { say("Kein API-Key. Erstelle einen auf console.anthropic.com und gib /ai key <key> ein.", Formatting.RED); return; }
        if (busy) { say("Moment, ich denke noch über die letzte Frage nach.", Formatting.RED); return; }
        busy = true;
        MinecraftClient mc = MinecraftClient.getInstance();
        chat(Text.literal("Du: ").formatted(Formatting.GRAY).append(Text.literal(q).formatted(Formatting.WHITE)));
        say("denkt nach …", Formatting.DARK_GRAY);

        history.add(new Msg("user", q));
        trim();

        JsonObject body = new JsonObject();
        body.addProperty("model", cfg.aiModel);
        body.addProperty("max_tokens", 600);
        body.addProperty("system", system());
        JsonArray arr = new JsonArray();
        for (Msg m : history) { JsonObject o = new JsonObject(); o.addProperty("role", m.role()); o.addProperty("content", m.text()); arr.add(o); }
        body.add("messages", arr);

        HttpRequest req = HttpRequest.newBuilder(API).timeout(Duration.ofSeconds(60))
            .header("x-api-key", cfg.aiKey).header("anthropic-version", "2023-06-01").header("content-type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(body.toString(), StandardCharsets.UTF_8)).build();

        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)).whenComplete((res, err) -> {
            String answer = null, error = null;
            if (err != null) error = "Keine Verbindung zur KI.";
            else {
                try { answer = parse(res); } catch (RuntimeException e) { error = e.getMessage(); }
            }
            final String a = answer, e = error;
            mc.execute(() -> {
                busy = false;
                if (e != null) {
                    if (!history.isEmpty()) history.remove(history.size() - 1);
                    say(e, Formatting.RED);
                    return;
                }
                history.add(new Msg("assistant", a));
                trim();
                print(a);
            });
        });
    }

    private static String parse(HttpResponse<String> r) {
        JsonObject j;
        try { j = JsonParser.parseString(r.body()).getAsJsonObject(); }
        catch (Exception e) { throw new RuntimeException("Unerwartete Antwort (HTTP " + r.statusCode() + ")"); }
        if (r.statusCode() != 200) {
            if (r.statusCode() == 401) throw new RuntimeException("API-Key ungültig. Neu setzen mit /ai key <key>");
            if (r.statusCode() == 429) throw new RuntimeException("Zu viele Anfragen, kurz warten.");
            String m = "HTTP " + r.statusCode();
            if (j.has("error") && j.getAsJsonObject("error").has("message")) m = j.getAsJsonObject("error").get("message").getAsString();
            throw new RuntimeException(m);
        }
        StringBuilder sb = new StringBuilder();
        for (JsonElement el : j.getAsJsonArray("content")) {
            JsonObject b = el.getAsJsonObject();
            if ("text".equals(b.get("type").getAsString())) sb.append(b.get("text").getAsString());
        }
        return sb.toString().trim();
    }

    private static void trim() {
        while (history.size() > 12) history.remove(0);
        while (!history.isEmpty() && !"user".equals(history.get(0).role())) history.remove(0);
    }

    private static String system() {
        MinecraftClient mc = MinecraftClient.getInstance();
        String player = mc.getSession() != null ? mc.getSession().getUsername() : "unbekannt";
        ServerInfo s = mc.getCurrentServerEntry();
        return "Du bist " + NAME + ", ein KI-Assistent direkt im Minecraft-Chat (Java Edition 1.21.11, Fabric), eingebaut in den " + CLIENT + ".\n"
            + "Du beantwortest allgemeine Fragen zu jedem Thema, besonders zu Minecraft, und Fragen zum " + CLIENT + ".\n\n"
            + "Regeln:\n"
            + "- Antworte in der Sprache der Frage, standardmäßig Deutsch, locker und freundlich.\n"
            + "- Kurz halten: meist 1 bis 5 Sätze, die Antwort steht im Minecraft-Chat. Länger nur, wenn ausdrücklich gewünscht.\n"
            + "- Kein Markdown (keine **, #, Tabellen, Codeblöcke). Aufzählungen als einfache Zeilen mit \"- \".\n"
            + "- Du bist kein Programmier-Assistent; bei Code-Wünschen kurz erklären statt lange Programme zu schreiben.\n"
            + "- Erfinde keine Server-Daten (Preise, Ränge, Regeln, Spielerstände, Server-Befehle). Wenn du etwas nicht sicher weißt, sag das.\n"
            + "- Zum Client nur das Client-Wissen unten nutzen. Steht etwas nicht drin, sag ehrlich, dass du es nicht weißt, und verweise auf das Mod-Menü oder den Support.\n"
            + "- Keine Tipps zum Cheaten oder Umgehen von Server-Regeln.\n\n"
            + "Spieler: " + player + "\nAktueller Server: " + (s != null ? s.address : "keiner (Einzelspieler oder Menü)") + "\n\n"
            + "=== Client-Wissen ===\n" + knowledge() + "\n=== Ende Client-Wissen ===";
    }

    private static String knowledge() {
        if (knowledge != null) return knowledge;
        StringBuilder sb = new StringBuilder();
        try (InputStream in = FloonsAI.class.getResourceAsStream(WISSEN)) {
            if (in != null) sb.append(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception ignored) {}
        sb.append("\n\nAlle Befehle des Clients (aus dem Commands-Reiter):\n").append(String.join(", ", java.util.Arrays.stream(FloonsMenu.CMDS).map(r -> r[1]).distinct().toList()));
        return knowledge = sb.toString();
    }

    // ---------------------------------------------------------------- Ausgabe

    private static void print(String answer) {
        String clean = answer.replace("**", "").replace("__", "").replace("`", "")
            .replaceAll("(?m)^#{1,6}\\s*", "").replaceAll("(?m)^\\s*[*•]\\s+", "- ").trim();
        boolean first = true;
        for (String l : clean.split("\n")) {
            if (l.isBlank()) continue;
            MutableText t = Text.literal(l).formatted(Formatting.WHITE);
            chat(first ? prefix().append(t) : Text.literal("  ").append(t));
            first = false;
        }
    }

    private static MutableText prefix() {
        int col = FloonsTheme.BLUE & 0xFFFFFF;
        return Text.literal(NAME).styled(s -> s.withColor(col).withBold(true)).append(Text.literal(" » ").formatted(Formatting.DARK_GRAY));
    }

    private static void say(String s, Formatting f) { chat(prefix().append(Text.literal(s).formatted(f))); }

    private static void line(String cmd, String desc) {
        int col = FloonsTheme.BLUE & 0xFFFFFF;
        chat(Text.literal("  " + cmd).styled(s -> s.withColor(col)).append(Text.literal(" – " + desc).formatted(Formatting.GRAY)));
    }

    private static void chat(Text t) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) mc.inGameHud.getChatHud().addMessage(t);
    }
}
