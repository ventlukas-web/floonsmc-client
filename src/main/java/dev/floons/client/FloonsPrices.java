package dev.floons.client;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.GenericContainerScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.ContainerComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.component.type.LoreComponent;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.text.Text;
import net.minecraft.text.TranslatableTextContent;
import net.minecraft.util.Formatting;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * WindSMP-Preise wie bei der FWM Mod: alle Clients schicken die Preise, die sie beim Durchblättern
 * von /ah und /orders sehen, an einen gemeinsamen Preis-Server (Cloudflare Worker, siehe price-server/).
 * Der Server liefert pro Item den Durchschnittspreis (Median) der letzten 7 Tage zurück.
 * Anzeige: Preis im Item-Tooltip und AH-/Order-Wert oben rechts in der Endertruhe.
 * Es gibt keine eingebauten Preise: ohne Server zählen nur selbst gesehene Preise.
 */
public final class FloonsPrices {
    private static final Gson GSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private static final Path CACHE = FabricLoader.getInstance().getConfigDir().resolve("floonsclient-prices-cache.json");
    private static final long REFRESH_MS = 5 * 60_000L, UPLOAD_MS = 20_000L;

    /** Preis pro Stück: ah = Ø billigstes Angebot, o = Ø Order. */
    public static final class P { public String name = ""; public double ah, o; public int ahN, oN; public long t; }

    private static volatile Map<String, P> server = new HashMap<>();
    private static final Map<String, P> local = new ConcurrentHashMap<>();
    private static volatile long serverUpdated;
    private static volatile String lastError = "";
    private static long nextRefresh, nextUpload;
    private static volatile boolean busy;
    private static final List<JsonObject> queue = Collections.synchronizedList(new ArrayList<>());
    private static final Set<String> sentThisHour = new HashSet<>();
    private static long sentHour;
    private static int learnTimer;

    private static final List<ItemStack> lastEc = new ArrayList<>();

    private static final Pattern MONEY = Pattern.compile("\\$\\s*([0-9][0-9.,]*)\\s*([KkMmBbTt])?\\b|([0-9][0-9.,]*)\\s*([KkMmBbTt])?\\s*\\$");
    private static final Pattern UNIT = Pattern.compile("(?i)(each|per item|per piece|/\\s*stk|/\\s*item|pro st(ü|ue)ck|je st(ü|ue)ck|pro item|stückpreis|unit)");
    private static final Pattern SKIP = Pattern.compile("(?i)(balance|guthaben|kontostand|total earned|verdient|bezahlt|paid|delivered|geliefert|tax|steuer|gebühr|fee)");
    private static final Pattern PRICEWORD = Pattern.compile("(?i)(price|preis|kost|cost|buy|kauf|sofort|bin|bietet|pays?|zahlt|reward|belohnung)");

    // ---------- Start ----------
    public static void register() {
        loadCache();
        ItemTooltipCallback.EVENT.register((stack, ctx, type, lines) -> {
            FloonsConfig c = FloonsConfig.get();
            if (!c.worthTooltip || c.allOff || stack.isEmpty()) return;
            P p = get(stack);
            if (p != null && (p.ah > 0 || p.o > 0)) {
                int n = stack.getCount();
                net.minecraft.text.MutableText line = Text.empty();
                boolean any = false;
                if (p.ah > 0) {
                    line.append(Text.literal("AH Ø ").formatted(Formatting.GRAY))
                        .append(Text.literal(money(p.ah * n)).formatted(Formatting.GREEN));
                    any = true;
                }
                if (p.o > 0) {
                    line.append(Text.literal((any ? "  ·  " : "") + "Orders Ø ").formatted(Formatting.GRAY))
                        .append(Text.literal(money(p.o * n)).formatted(Formatting.AQUA));
                }
                lines.add(line);
                String sub = (n > 1 ? (p.ah > 0 ? money(p.ah) : money(p.o)) + "/Stk  ·  " : "") + info(p);
                lines.add(Text.literal(sub).formatted(Formatting.DARK_GRAY));
            }
            double[] in = containerValue(stack);
            if (in[0] > 0 || in[1] > 0)
                lines.add(Text.literal("Inhalt: ").formatted(Formatting.GRAY)
                    .append(Text.literal("AH " + money(in[0])).formatted(Formatting.GREEN))
                    .append(Text.literal("  ·  ").formatted(Formatting.GRAY))
                    .append(Text.literal("Orders " + money(in[1])).formatted(Formatting.AQUA)));
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof GenericContainerScreen gs) || !isEnderChest(screen)) return;
            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> { if (!FloonsClient.off()) renderOverlay(gs, ctx); });
        });
    }

    public static void tick(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        FloonsConfig c = FloonsConfig.get();
        if (!c.priceServer.isBlank() && mc.player != null) {
            if (now >= nextRefresh) { nextRefresh = now + REFRESH_MS; refresh(false); }
            if (now >= nextUpload && c.priceShare) { nextUpload = now + UPLOAD_MS; upload(); }
        }
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return;
        if (hs instanceof GenericContainerScreen gs && isEnderChest(hs)) { snapshot(gs); return; }
        if (!c.worthLearn || ++learnTimer < 10) return;
        learnTimer = 0;
        String src = marketSource(hs.getTitle().getString());
        if (src == null) return;
        for (Slot slot : hs.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            learn(slot.getStack(), src);
        }
    }

    // ---------- Preise lernen (/ah, /orders) ----------
    private static String marketSource(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("order") || t.contains("auftr")) return "order";
        try {
            if (Pattern.compile(FloonsConfig.get().worthMarketTitle, Pattern.CASE_INSENSITIVE).matcher(title).find()) return "ah";
        } catch (Exception ignored) {}
        return null;
    }

    private static void learn(ItemStack st, String src) {
        if (st.isEmpty()) return;
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return;
        double best = -1; boolean bestUnit = false, bestWord = false;
        StringBuilder all = new StringBuilder();
        for (Text line : lore.lines()) {
            String s = line.getString();
            all.append(s).append('\n');
            if (SKIP.matcher(s).find()) continue;
            Matcher m = MONEY.matcher(s);
            if (!m.find()) continue;
            double v;
            try {
                String num = m.group(1) != null ? m.group(1) : m.group(3);
                String suf = m.group(1) != null ? m.group(2) : m.group(4);
                v = amount(num, suf);
            } catch (Exception e) { continue; }
            if (v <= 0) continue;
            boolean unit = UNIT.matcher(s).find(), word = PRICEWORD.matcher(s).find();
            if (best < 0 || (unit && !bestUnit) || (!bestUnit && word && !bestWord)) { best = v; bestUnit = unit; bestWord = word; }
        }
        if (best <= 0) return;
        double perItem = bestUnit || src.equals("order") ? best : best / Math.max(1, st.getCount());
        String k = key(st);
        long now = System.currentTimeMillis();

        // lokal merken (Fallback ohne Server): billigstes AH-Angebot / höchste Order der letzten Stunden
        P p = local.computeIfAbsent(k, x -> new P());
        p.name = display(st);
        boolean old = now - p.t > 6 * 3600_000L;
        if (src.equals("ah")) { if (p.ah <= 0 || old || perItem < p.ah) p.ah = perItem; p.ahN = 1; }
        else { if (p.o <= 0 || old || perItem > p.o) p.o = perItem; p.oN = 1; }
        p.t = now;

        // für den Server einreihen (jedes Angebot nur einmal pro Stunde)
        FloonsConfig c = FloonsConfig.get();
        if (c.priceServer.isBlank() || !c.priceShare) return;
        long hour = now / 3600_000L;
        if (hour != sentHour) { sentHour = hour; sentThisHour.clear(); }
        String fp = st.getCount() + ":" + Integer.toHexString(all.toString().hashCode());
        if (!sentThisHour.add(k + "|" + src + "|" + perItem + "|" + fp)) return;
        JsonObject o = new JsonObject();
        o.addProperty("item", k); o.addProperty("name", display(st)); o.addProperty("src", src);
        o.addProperty("price", perItem); o.addProperty("fp", fp);
        if (queue.size() < 2000) queue.add(o);
    }

    // ---------- Server ----------
    private static String base() { return FloonsConfig.get().priceServer.trim().replaceAll("/+$", ""); }

    private static void upload() {
        if (busy || queue.isEmpty()) return;
        JsonArray arr = new JsonArray();
        synchronized (queue) {
            Iterator<JsonObject> it = queue.iterator();
            while (it.hasNext() && arr.size() < 300) { arr.add(it.next()); it.remove(); }
        }
        JsonObject body = new JsonObject();
        body.add("obs", arr);
        busy = true;
        HttpRequest req = HttpRequest.newBuilder(URI.create(base() + "/report")).timeout(Duration.ofSeconds(15))
            .header("content-type", "application/json").POST(HttpRequest.BodyPublishers.ofString(GSON.toJson(body))).build();
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, e) -> {
            busy = false;
            if (e != null || r.statusCode() != 200) {
                lastError = e != null ? e.getClass().getSimpleName() : "HTTP " + r.statusCode();
                synchronized (queue) { for (JsonElement el : arr) if (queue.size() < 2000) queue.add(el.getAsJsonObject()); }
            } else lastError = "";
        });
    }

    public static void refresh(boolean chat) {
        if (base().isEmpty()) { if (chat) FloonsAuction.info("Preise: kein Preis-Server eingetragen (/prices server <url>)."); return; }
        HttpRequest req;
        try { req = HttpRequest.newBuilder(URI.create(base() + "/prices")).timeout(Duration.ofSeconds(20)).GET().build(); }
        catch (Exception e) { lastError = "URL ungültig"; if (chat) FloonsAuction.info("Preise: Server-URL ungültig."); return; }
        HTTP.sendAsync(req, HttpResponse.BodyHandlers.ofString()).whenComplete((r, e) -> {
            if (e != null || r.statusCode() != 200) {
                lastError = e != null ? e.getClass().getSimpleName() : "HTTP " + r.statusCode();
                if (chat) MinecraftClient.getInstance().execute(() -> FloonsAuction.info("Preise: Server nicht erreichbar (" + lastError + ")."));
                return;
            }
            try {
                Map<String, P> m = parse(r.body());
                server = m;
                serverUpdated = System.currentTimeMillis();
                lastError = "";
                try { Files.writeString(CACHE, r.body()); } catch (Exception ignored) {}
                if (chat) MinecraftClient.getInstance().execute(() -> FloonsAuction.info("Preise: " + m.size() + " Items vom Server geladen."));
            } catch (Exception ex) { lastError = "Antwort ungültig"; }
        });
    }

    private static Map<String, P> parse(String body) {
        JsonObject root = JsonParser.parseString(body).getAsJsonObject();
        JsonObject items = root.getAsJsonObject("items");
        Map<String, P> m = new HashMap<>();
        for (var e : items.entrySet()) m.put(e.getKey(), GSON.fromJson(e.getValue(), P.class));
        return m;
    }

    private static void loadCache() {
        try { if (Files.exists(CACHE)) server = parse(Files.readString(CACHE)); } catch (Exception ignored) {}
    }

    // ---------- Abfragen ----------
    /** Server-Preis, sonst selbst gesehener Preis; null = unbekannt. */
    public static P get(ItemStack st) {
        if (st.isEmpty()) return null;
        String k = key(st);
        P s = server.get(k), l = local.get(k);
        if (s == null) return l;
        if (l == null) return s;
        P m = new P(); m.name = s.name; m.t = Math.max(s.t, l.t);
        m.ah = s.ah > 0 ? s.ah : l.ah; m.ahN = s.ah > 0 ? s.ahN : l.ahN;
        m.o = s.o > 0 ? s.o : l.o; m.oN = s.o > 0 ? s.oN : l.oN;
        return m;
    }

    private static String info(P p) {
        int n = p.ahN + p.oN;
        return (server.isEmpty() ? "selbst gesehen" : n + " Preise, 7 Tage") + ", " + ago(p.t);
    }

    /** {AH-Wert, Order-Wert} des Inhalts einer Shulker o. Ä. */
    private static double[] containerValue(ItemStack st) {
        ContainerComponent cc = st.get(DataComponentTypes.CONTAINER);
        double[] v = {0, 0};
        if (cc == null) return v;
        for (ItemStack in : cc.iterateNonEmpty()) {
            P p = get(in);
            if (p == null) continue;
            v[0] += p.ah * in.getCount(); v[1] += p.o * in.getCount();
        }
        return v;
    }

    // ---------- Endertruhe ----------
    public static boolean isEnderChest(Screen s) {
        if (s.getTitle().getContent() instanceof TranslatableTextContent t && t.getKey().equals("container.enderchest")) return true;
        String t = s.getTitle().getString().toLowerCase(Locale.ROOT).replace(" ", "").replace("-", "");
        return t.contains("enderchest") || t.contains("endertruhe") || t.contains("enderkiste");
    }

    private static void snapshot(GenericContainerScreen gs) {
        GenericContainerScreenHandler h = gs.getScreenHandler();
        int n = h.getRows() * 9;
        lastEc.clear();
        for (int i = 0; i < n && i < h.slots.size(); i++) {
            ItemStack st = h.slots.get(i).getStack();
            if (!st.isEmpty()) lastEc.add(st.copy());
        }
    }

    /** {AH-Summe, Order-Summe, Item-Arten ohne AH-Preis, ohne Order-Preis} inkl. Shulker-Inhalt. */
    public static double[] ecTotals() {
        double ah = 0, od = 0;
        Set<String> missA = new HashSet<>(), missO = new HashSet<>();
        List<ItemStack> all = new ArrayList<>();
        for (ItemStack st : lastEc) {
            ContainerComponent cc = st.get(DataComponentTypes.CONTAINER);
            if (cc != null) for (ItemStack in : cc.iterateNonEmpty()) all.add(in);
            else all.add(st);
        }
        for (ItemStack st : all) {
            P p = get(st);
            if (p != null && p.ah > 0) ah += p.ah * st.getCount(); else missA.add(key(st));
            if (p != null && p.o > 0) od += p.o * st.getCount(); else missO.add(key(st));
        }
        return new double[]{ah, od, missA.size(), missO.size()};
    }

    /** Oben rechts in der Endertruhe (Titelzeile): AH-Wert und Order-Wert getrennt. */
    private static void renderOverlay(GenericContainerScreen gs, DrawContext ctx) {
        if (!FloonsConfig.get().worthOverlay || lastEc.isEmpty()) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        int rows = gs.getScreenHandler().getRows();
        int bgW = 176, bgH = 114 + rows * 18;
        int gx = (gs.width - bgW) / 2, gy = (gs.height - bgH) / 2;
        double[] t = ecTotals();
        Text line = Text.literal("AH ").withColor(0x404040)
            .append(Text.literal(money(t[0]).substring(1) + (t[2] > 0 ? "*" : "")).withColor(0x1B6E20))
            .append(Text.literal(" · O ").withColor(0x404040))
            .append(Text.literal(money(t[1]).substring(1) + (t[3] > 0 ? "*" : "")).withColor(0x0D47A1));
        ctx.drawText(tr, line, gx + bgW - 8 - tr.getWidth(line), gy + 6, 0xFFFFFFFF, false);
    }

    // ---------- Befehle ----------
    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
        for (String name : new String[]{"prices", "preise"}) d.register(ClientCommandManager.literal(name)
            .executes(c -> { status(); return 1; })
            .then(ClientCommandManager.literal("hand").executes(c -> { hand(); return 1; }))
            .then(ClientCommandManager.literal("ec").executes(c -> { ec(); return 1; }))
            .then(ClientCommandManager.literal("refresh").executes(c -> { refresh(true); return 1; }))
            .then(ClientCommandManager.literal("share").executes(c -> {
                FloonsConfig k = FloonsConfig.get(); k.priceShare = !k.priceShare; k.save();
                FloonsAuction.info("Preise: gesehene Preise an den Server senden: " + (k.priceShare ? "an" : "aus")); return 1; }))
            .then(ClientCommandManager.literal("server")
                .then(ClientCommandManager.argument("url", StringArgumentType.greedyString()).executes(c -> {
                    setServer(StringArgumentType.getString(c, "url")); return 1; }))));
    }

    public static void setServer(String url) {
        FloonsConfig k = FloonsConfig.get();
        String u = url.trim();
        if (u.equalsIgnoreCase("aus") || u.equalsIgnoreCase("off")) u = "";
        else if (!u.isEmpty() && !u.startsWith("http")) u = "https://" + u;
        k.priceServer = u; k.save();
        server = new HashMap<>(); nextRefresh = 0;
        FloonsAuction.info(u.isEmpty() ? "Preise: Server entfernt (nur selbst gesehene Preise)." : "Preise: Server " + u);
        if (!u.isEmpty()) refresh(true);
    }

    private static void status() {
        FloonsAuction.info(statusLine());
        FloonsAuction.info(Text.literal("/prices hand · ec · refresh · share · server <url|aus>").formatted(Formatting.GRAY));
    }

    public static String statusLine() {
        String s = FloonsConfig.get().priceServer;
        if (s.isBlank()) return "Kein Preis-Server · " + local.size() + " Preise selbst gesehen";
        return server.size() + " Items vom Server" + (serverUpdated > 0 ? " (" + ago(serverUpdated) + ")" : "")
            + (lastError.isEmpty() ? "" : " · Fehler: " + lastError) + (queue.isEmpty() ? "" : " · " + queue.size() + " zu senden");
    }

    private static void hand() {
        var pl = MinecraftClient.getInstance().player;
        if (pl == null || pl.getMainHandStack().isEmpty()) { FloonsAuction.info("Preise: Halte ein Item in der Hand."); return; }
        ItemStack st = pl.getMainHandStack();
        P p = get(st);
        if (p == null || (p.ah <= 0 && p.o <= 0)) { FloonsAuction.info("Preise: noch kein Preis für " + display(st) + "."); return; }
        FloonsAuction.info(display(st) + ": AH Ø " + (p.ah > 0 ? money(p.ah) : "–") + "/Stk · Orders Ø " + (p.o > 0 ? money(p.o) : "–") + "/Stk  (" + info(p) + ")");
    }

    private static void ec() {
        if (lastEc.isEmpty()) { FloonsAuction.info("Preise: Öffne erst deine Endertruhe."); return; }
        double[] t = ecTotals();
        FloonsAuction.info(Text.literal("Endertruhe: ").formatted(Formatting.AQUA)
            .append(Text.literal("AH " + money(t[0])).formatted(Formatting.GREEN))
            .append(Text.literal("  ·  ").formatted(Formatting.GRAY))
            .append(Text.literal("Orders " + money(t[1])).formatted(Formatting.AQUA))
            .append(Text.literal("  (" + (int) t[2] + " ohne AH-, " + (int) t[3] + " ohne Order-Preis)").formatted(Formatting.GRAY)));
    }

    // ---------- Hilfen ----------
    /** Item-ID + eigener Name (z. B. Spawner) + gespeicherte Verzauberungen (Bücher). */
    public static String key(ItemStack st) {
        StringBuilder b = new StringBuilder(Registries.ITEM.getId(st.getItem()).toString());
        Text name = st.get(DataComponentTypes.CUSTOM_NAME);
        if (name == null) {
            Text in = st.get(DataComponentTypes.ITEM_NAME), def = st.getItem().getComponents().get(DataComponentTypes.ITEM_NAME);
            if (in != null && !in.equals(def)) name = in;
        }
        if (name != null) b.append('#').append(name.getString().replaceAll("§.", "").trim().toLowerCase(Locale.ROOT));
        ItemEnchantmentsComponent ench = st.get(DataComponentTypes.STORED_ENCHANTMENTS);
        if (ench != null && !ench.isEmpty()) {
            List<String> es = new ArrayList<>();
            for (var e : ench.getEnchantmentEntries()) es.add(e.getKey().getIdAsString() + "=" + e.getIntValue());
            Collections.sort(es);
            b.append('@').append(String.join(",", es));
        }
        return b.toString();
    }

    private static String display(ItemStack st) { return st.getName().getString().replaceAll("§.", "").trim(); }

    private static double amount(String num, String suf) {
        double v = new java.math.BigDecimal(num.replace(",", "").replaceAll("\\.$", "")).doubleValue();
        return v * switch (suf == null ? "" : suf.toLowerCase(Locale.ROOT)) {
            case "k" -> 1e3; case "m" -> 1e6; case "b" -> 1e9; case "t" -> 1e12; default -> 1;
        };
    }

    public static String money(double v) {
        if (v > 0 && v < 1000) return "$" + (v < 10 && v != Math.floor(v) ? String.format(Locale.ROOT, "%.2f", v) : Long.toString(Math.round(v)));
        return "$" + FloonsAuction.fmt(Math.round(v));
    }

    private static String ago(long t) {
        if (t <= 0) return "nie";
        long m = (System.currentTimeMillis() - t) / 60_000;
        if (m < 1) return "gerade eben";
        if (m < 60) return "vor " + m + " min";
        if (m < 48 * 60) return "vor " + (m / 60) + " h";
        return "vor " + (m / 1440) + " Tagen";
    }
}
