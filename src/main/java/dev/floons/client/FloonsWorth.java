package dev.floons.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
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

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ender-Wert: zeigt, wie viel die Endertruhe nach WindSMP-Preisen wert ist.
 * Es gibt keine eingebaute Preisliste: Preise kommen nur vom Server selbst
 * (beim Durchblättern von /ah bzw. /orders aus dem Item-Text gelesen) oder werden per /ewert set gesetzt.
 * Gespeichert in config/floonsclient-prices.json.
 */
public final class FloonsWorth {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("floonsclient-prices.json");
    /** AH-Preise älter als das werden beim nächsten Sehen ersetzt statt mit dem Minimum verglichen. */
    private static final long AH_FRESH_MS = 6 * 3600_000L;

    public static class Price {
        public String name = "";
        public double ah = 0, order = 0, manual = 0; // jeweils pro Stück
        public long ahTime = 0, orderTime = 0, manualTime = 0;
    }

    private static Map<String, Price> prices;
    private static boolean dirty;
    private static int saveTimer;

    // letzte gesehene Endertruhe
    private static final List<ItemStack> lastEc = new ArrayList<>();
    private static long lastEcTime;
    private static Result lastResult;

    private static final Pattern MONEY = Pattern.compile("\\$\\s*([0-9][0-9.,]*)\\s*([KkMmBbTt])?\\b|([0-9][0-9.,]*)\\s*([KkMmBbTt])?\\s*\\$");
    private static final Pattern UNIT = Pattern.compile("(?i)(each|per item|per piece|/\\s*stk|/\\s*item|pro st(ü|ue)ck|je st(ü|ue)ck|pro item|stückpreis|unit)");
    private static final Pattern SKIP = Pattern.compile("(?i)(balance|guthaben|kontostand|total earned|verdient|bezahlt|paid|delivered|geliefert|tax|steuer|gebühr|fee)");
    private static final Pattern PRICEWORD = Pattern.compile("(?i)(price|preis|kost|cost|buy|kauf|sofort|bin|bietet|pays?|zahlt|reward|belohnung)");

    public record Result(double total, int stacks, int items, int priced, List<String> missing, List<Map.Entry<String, Double>> top) {}

    // ---------- Start ----------
    public static void register() {
        load();
        ItemTooltipCallback.EVENT.register((stack, ctx, type, lines) -> {
            if (!FloonsConfig.get().worthTooltip || stack.isEmpty()) return;
            double unit = unitPrice(stack);
            double inside = containerValue(stack);
            if (unit <= 0 && inside <= 0) return;
            if (unit > 0) {
                String s = "Wert: " + money(unit * stack.getCount());
                if (stack.getCount() > 1) s += "  (" + money(unit) + "/Stk)";
                lines.add(Text.literal(s + "  " + source(key(stack))).formatted(Formatting.GOLD));
            }
            if (inside > 0) lines.add(Text.literal("Inhalt: " + money(inside)).formatted(Formatting.GOLD));
        });
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> {
            if (!(screen instanceof GenericContainerScreen gs) || !isEnderChest(screen)) return;
            ScreenEvents.afterRender(screen).register((s, ctx, mx, my, delta) -> renderOverlay(gs, ctx));
        });
    }

    /** Jeden Tick: offene Markt-GUIs auslesen, offene Endertruhe merken. */
    public static void tick(MinecraftClient mc) {
        if (++saveTimer >= 200) { saveTimer = 0; if (dirty) save(); }
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return;
        String title = hs.getTitle().getString();
        if (hs instanceof GenericContainerScreen gs && isEnderChest(hs)) { snapshot(gs); return; }
        FloonsConfig c = FloonsConfig.get();
        if (!c.worthLearn) return;
        Source src = marketSource(title);
        if (src == null) return;
        for (Slot slot : hs.getScreenHandler().slots) {
            if (slot.inventory instanceof PlayerInventory) continue;
            learn(slot.getStack(), src);
        }
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
        lastEcTime = System.currentTimeMillis();
        lastResult = evaluate(lastEc);
    }

    public static Result evaluate(List<ItemStack> stacks) {
        double total = 0;
        int items = 0, priced = 0;
        Map<String, Double> byItem = new HashMap<>();
        Set<String> missing = new LinkedHashSet<>();
        for (ItemStack st : stacks) {
            items += st.getCount();
            double unit = unitPrice(st);
            if (unit > 0) {
                double v = unit * st.getCount();
                total += v; priced++;
                byItem.merge(display(st), v, Double::sum);
            } else if (!isContainer(st)) missing.add(display(st));
            // Shulker & Co.: Inhalt mitzählen
            ContainerComponent cc = st.get(DataComponentTypes.CONTAINER);
            if (cc != null) for (ItemStack in : cc.iterateNonEmpty()) {
                items += in.getCount();
                double u = unitPrice(in);
                if (u > 0) { double v = u * in.getCount(); total += v; byItem.merge(display(in), v, Double::sum); }
                else missing.add(display(in));
            }
        }
        List<Map.Entry<String, Double>> top = new ArrayList<>(byItem.entrySet());
        top.sort((a, b) -> Double.compare(b.getValue(), a.getValue()));
        return new Result(total, stacks.size(), items, priced, new ArrayList<>(missing), top);
    }

    private static void renderOverlay(GenericContainerScreen gs, DrawContext ctx) {
        if (!FloonsConfig.get().worthOverlay || lastResult == null) return;
        var tr = MinecraftClient.getInstance().textRenderer;
        int rows = gs.getScreenHandler().getRows();
        int bgW = 176, bgH = 114 + rows * 18;
        int gx = (gs.width - bgW) / 2, gy = (gs.height - bgH) / 2;
        String head = "Ender-Wert: " + money(lastResult.total());
        String sub = lastResult.missing().isEmpty() ? "alle Items mit Preis"
            : lastResult.missing().size() + " Item-Arten ohne Preis (/ewert missing)";
        int w = Math.max(tr.getWidth(head), tr.getWidth(sub)) + 16, h = 28;
        int x = gx + (bgW - w) / 2, y = gy - h - 4;
        if (y < 2) { x = gx + bgW + 6; y = gy; } // kein Platz oben -> rechts daneben
        FloonsTheme.panel(ctx, x, y, w, h);
        FloonsTheme.gradientText(ctx, tr, head, x + 8, y + 5);
        ctx.drawTextWithShadow(tr, sub, x + 8, y + 16, lastResult.missing().isEmpty() ? FloonsTheme.GOOD : FloonsTheme.MUTED);
    }

    // ---------- Preise lernen ----------
    private enum Source { AH, ORDER }

    private static Source marketSource(String title) {
        String t = title.toLowerCase(Locale.ROOT);
        if (t.contains("order") || t.contains("auftr")) return Source.ORDER;
        try {
            if (Pattern.compile(FloonsConfig.get().worthMarketTitle, Pattern.CASE_INSENSITIVE).matcher(title).find()) return Source.AH;
        } catch (Exception ignored) {}
        return null;
    }

    private static void learn(ItemStack st, Source src) {
        if (st.isEmpty()) return;
        LoreComponent lore = st.get(DataComponentTypes.LORE);
        if (lore == null) return;
        double best = -1; boolean bestUnit = false; boolean bestWord = false;
        for (Text line : lore.lines()) {
            String s = line.getString();
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
            // Stückpreis-Zeile schlägt alles, danach Zeilen mit "Preis"-Wort, sonst die erste $-Zeile
            if (best < 0 || (unit && !bestUnit) || (!bestUnit && word && !bestWord)) { best = v; bestUnit = unit; bestWord = word; }
        }
        if (best <= 0) return;
        double perItem = bestUnit || src == Source.ORDER ? best : best / Math.max(1, st.getCount());
        String k = key(st);
        Price p = prices.computeIfAbsent(k, x -> new Price());
        p.name = display(st);
        long now = System.currentTimeMillis();
        if (src == Source.AH) {
            if (p.ah <= 0 || now - p.ahTime > AH_FRESH_MS || perItem < p.ah) { if (p.ah != perItem) dirty = true; p.ah = perItem; }
            p.ahTime = now;
        } else {
            if (p.order <= 0 || now - p.orderTime > AH_FRESH_MS || perItem > p.order) { if (p.order != perItem) dirty = true; p.order = perItem; }
            p.orderTime = now;
        }
    }

    // ---------- Preise abfragen ----------
    /** Preis pro Stück nach eingestelltem Modus; 0 = unbekannt. */
    public static double unitPrice(ItemStack st) {
        if (st.isEmpty()) return 0;
        Price p = prices.get(key(st));
        if (p == null) return 0;
        if (p.manual > 0) return p.manual;
        boolean order = "order".equals(FloonsConfig.get().worthMode);
        double a = order ? p.order : p.ah, b = order ? p.ah : p.order;
        return a > 0 ? a : b;
    }

    private static String source(String k) {
        Price p = prices.get(k);
        if (p == null) return "";
        if (p.manual > 0) return "[eigener Preis]";
        boolean order = "order".equals(FloonsConfig.get().worthMode);
        boolean useOrder = order ? p.order > 0 : p.ah <= 0;
        return "[" + (useOrder ? "Orders" : "AH") + ", " + ago(useOrder ? p.orderTime : p.ahTime) + "]";
    }

    private static double containerValue(ItemStack st) {
        ContainerComponent cc = st.get(DataComponentTypes.CONTAINER);
        if (cc == null) return 0;
        double v = 0;
        for (ItemStack in : cc.iterateNonEmpty()) v += unitPrice(in) * in.getCount();
        return v;
    }

    private static boolean isContainer(ItemStack st) { return st.get(DataComponentTypes.CONTAINER) != null; }

    /** Item-ID + eigener Name (z. B. Spawner-Namen) + gespeicherte Verzauberungen (Bücher). */
    public static String key(ItemStack st) {
        StringBuilder b = new StringBuilder(baseKey(st));
        Text name = st.get(DataComponentTypes.CUSTOM_NAME);
        if (name == null) { // vom Server gesetzter Item-Name (z. B. "Zombie Spawner")
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

    private static String baseKey(ItemStack st) { return Registries.ITEM.getId(st.getItem()).toString(); }

    private static String display(ItemStack st) { return st.getName().getString().replaceAll("§.", "").trim(); }

    // ---------- Befehle ----------
    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> d) {
        d.register(command("ewert"));
        d.register(command("ecvalue"));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> command(String name) {
        return ClientCommandManager.literal(name)
            .executes(c -> { show(); return 1; })
            .then(ClientCommandManager.literal("missing").executes(c -> { missing(); return 1; }))
            .then(ClientCommandManager.literal("hand").executes(c -> { hand(); return 1; }))
            .then(ClientCommandManager.literal("set")
                .then(ClientCommandManager.argument("preis", StringArgumentType.word()).executes(c -> {
                    setHand(StringArgumentType.getString(c, "preis")); return 1;
                })))
            .then(ClientCommandManager.literal("del").executes(c -> { delHand(); return 1; }))
            .then(ClientCommandManager.literal("mode")
                .then(ClientCommandManager.literal("ah").executes(c -> { mode("ah"); return 1; }))
                .then(ClientCommandManager.literal("order").executes(c -> { mode("order"); return 1; })))
            .then(ClientCommandManager.literal("learn").executes(c -> {
                FloonsConfig k = FloonsConfig.get(); k.worthLearn = !k.worthLearn; k.save();
                FloonsAuction.info("Preise lernen: " + (k.worthLearn ? "an" : "aus")); return 1;
            }))
            .then(ClientCommandManager.literal("list").executes(c -> { list(); return 1; }))
            .then(ClientCommandManager.literal("clear").executes(c -> {
                int n = prices.size(); prices.clear(); save();
                FloonsAuction.info("Ender-Wert: " + n + " Preise gelöscht."); return 1;
            }))
            .then(ClientCommandManager.literal("help").executes(c -> { help(); return 1; }));
    }

    private static void show() {
        if (lastResult == null) {
            FloonsAuction.info("Ender-Wert: Öffne einmal deine Endertruhe, dann wird sie bewertet.");
            help(); return;
        }
        Result r = evaluate(lastEc); // mit aktuellen Preisen neu rechnen
        lastResult = r;
        FloonsAuction.info(Text.literal("Endertruhe (" + ago(lastEcTime) + "): ").formatted(Formatting.AQUA)
            .append(Text.literal(money(r.total())).formatted(Formatting.GOLD))
            .append(Text.literal("  ·  " + r.items() + " Items, " + r.missing().size() + " Arten ohne Preis").formatted(Formatting.GRAY)));
        for (int i = 0; i < Math.min(5, r.top().size()); i++) {
            var e = r.top().get(i);
            FloonsAuction.info(Text.literal("  " + (i + 1) + ". " + e.getKey() + " – ").formatted(Formatting.GRAY)
                .append(Text.literal(money(e.getValue())).formatted(Formatting.GOLD)));
        }
        if (prices.isEmpty()) FloonsAuction.info(Text.literal("Noch keine Preise: blättere durch /ah oder /orders, oder nutze /ewert set <preis>.").formatted(Formatting.GRAY));
    }

    private static void missing() {
        if (lastResult == null) { FloonsAuction.info("Ender-Wert: Öffne erst deine Endertruhe."); return; }
        Result r = evaluate(lastEc);
        if (r.missing().isEmpty()) { FloonsAuction.info("Ender-Wert: alle Items haben einen Preis."); return; }
        FloonsAuction.info(Text.literal("Ohne Preis (in /ah suchen oder /ewert set <preis> mit Item in der Hand):").formatted(Formatting.AQUA));
        FloonsAuction.info(Text.literal(String.join(", ", r.missing())).formatted(Formatting.GRAY));
    }

    private static void hand() {
        ItemStack st = held();
        if (st == null) return;
        double u = unitPrice(st), in = containerValue(st);
        if (u <= 0 && in <= 0) { FloonsAuction.info("Ender-Wert: kein Preis für " + display(st) + " bekannt."); return; }
        FloonsAuction.info(display(st) + ": " + (u > 0 ? money(u * st.getCount()) + " (" + money(u) + "/Stk) " + source(key(st)) : "")
            + (in > 0 ? "  Inhalt " + money(in) : ""));
    }

    private static void setHand(String raw) {
        ItemStack st = held();
        if (st == null) return;
        boolean stack = raw.toLowerCase(Locale.ROOT).endsWith("/stack");
        String r = raw.replaceAll("(?i)/stack$", "");
        double v;
        try {
            String t = r.trim().replace("$", "");
            v = amount(t.replaceAll("[KkMmBbTt]", ""), t.replaceAll("[^KkMmBbTt]", ""));
        } catch (Exception e) { FloonsAuction.info("Ender-Wert: Preis ungültig (z. B. 500, 1.5k, 2m – pro Stück)."); return; }
        if (stack) v /= Math.max(1, st.getCount());
        if (v <= 0) { FloonsAuction.info("Ender-Wert: Preis ungültig."); return; }
        Price p = prices.computeIfAbsent(key(st), x -> new Price());
        p.name = display(st); p.manual = v; p.manualTime = System.currentTimeMillis();
        save();
        FloonsAuction.info("Ender-Wert: Preis gesetzt – " + display(st) + " " + money(v) + "/Stk");
    }

    private static void delHand() {
        ItemStack st = held();
        if (st == null) return;
        Price p = prices.remove(key(st));
        save();
        FloonsAuction.info(p == null ? "Ender-Wert: kein Preis gespeichert." : "Ender-Wert: Preis für " + display(st) + " gelöscht.");
    }

    private static void mode(String m) {
        FloonsConfig k = FloonsConfig.get(); k.worthMode = m; k.save();
        FloonsAuction.info("Ender-Wert: rechnet mit " + (m.equals("order") ? "Order-Preisen (Sofortverkauf)" : "AH-Preisen (günstigstes Angebot)"));
    }

    private static void list() {
        if (prices.isEmpty()) { FloonsAuction.info("Ender-Wert: noch keine Preise gespeichert."); return; }
        List<Price> ps = new ArrayList<>(prices.values());
        ps.sort(Comparator.comparing(p -> p.name.toLowerCase(Locale.ROOT)));
        FloonsAuction.info(Text.literal(ps.size() + " Preise (pro Stück):").formatted(Formatting.AQUA));
        int shown = 0;
        for (Price p : ps) {
            if (shown++ >= 20) { FloonsAuction.info(Text.literal("… " + (ps.size() - 20) + " weitere in config/floonsclient-prices.json").formatted(Formatting.GRAY)); break; }
            FloonsAuction.info(Text.literal("  " + p.name + ": "
                + (p.manual > 0 ? "eigen " + money(p.manual) + "  " : "")
                + (p.ah > 0 ? "AH " + money(p.ah) + "  " : "")
                + (p.order > 0 ? "Orders " + money(p.order) : "")).formatted(Formatting.GRAY));
        }
    }

    private static void help() {
        FloonsAuction.info(Text.literal("/ewert – Wert der Endertruhe · missing – Items ohne Preis · hand – Wert in der Hand").formatted(Formatting.GRAY));
        FloonsAuction.info(Text.literal("/ewert set <preis> (pro Stück, oder z. B. 64k/stack) · del · mode ah|order · learn · list · clear").formatted(Formatting.GRAY));
        FloonsAuction.info(Text.literal("Preise lernt der Client beim Durchblättern von /ah und /orders auf WindSMP.").formatted(Formatting.GRAY));
    }

    private static ItemStack held() {
        var p = MinecraftClient.getInstance().player;
        if (p == null || p.getMainHandStack().isEmpty()) { FloonsAuction.info("Ender-Wert: Halte ein Item in der Hand."); return null; }
        return p.getMainHandStack();
    }

    // ---------- Infos fürs Menü ----------
    public static int priceCount() { return prices == null ? 0 : prices.size(); }
    public static String lastSummary() {
        if (lastResult == null) return "Endertruhe noch nicht geöffnet";
        Result r = evaluate(lastEc);
        return "Endertruhe: " + money(r.total()) + "  ·  " + r.missing().size() + " Arten ohne Preis  ·  " + ago(lastEcTime);
    }

    // ---------- Hilfen ----------
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

    private static void load() {
        try {
            if (Files.exists(FILE)) prices = GSON.fromJson(Files.readString(FILE), new TypeToken<HashMap<String, Price>>() {}.getType());
        } catch (Exception ignored) {}
        if (prices == null) prices = new HashMap<>();
    }

    public static void save() {
        dirty = false;
        try {
            Files.createDirectories(FILE.getParent());
            Files.writeString(FILE, GSON.toJson(prices));
        } catch (Exception ignored) {}
    }
}
