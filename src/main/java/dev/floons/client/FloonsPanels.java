package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Zusaetzliche HUD-Panels: Zahlungen, Auktion/Giveaway, Twitch-Chat, Toasts. */
public final class FloonsPanels {
    private FloonsPanels() {}

    /** Goldener Rand um die Hunger-Icons: zeigt die Saturation (je 2 Punkte = ein Icon, 1 Punkt = halber Rand). */
    private static void saturation(DrawContext ctx, MinecraftClient mc) {
        var pl = mc.player;
        if (pl == null || mc.options.hudHidden || pl.isCreative() || pl.isSpectator()) return;
        if (pl.getVehicle() instanceof net.minecraft.entity.LivingEntity) return; // Reittier: Hungerleiste ist ausgeblendet
        float sat = Math.min(pl.getHungerManager().getSaturationLevel(), pl.getHungerManager().getFoodLevel());
        if (sat <= 0.01f) return;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        int baseX = sw / 2 + 91 - 9, y = sh - 39;
        int full = (int) (sat / 2f);
        boolean half = sat - full * 2f >= 1f;
        int gold = 0xFFFFC832;
        for (int k = 0; k < 10 && k <= full; k++) {
            boolean f = k < full;
            if (!f && !(k == full && half)) break;
            int x = baseX - k * 8;
            int c = f ? gold : FloonsTheme.alpha(gold, 0x90);
            FloonsTheme.roundOutlineExact(ctx, x - 1, y - 1, 11, 11, 5, c);
        }
    }

    private static void pvpPanel(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        List<Row> rows = new ArrayList<>();
        if (cfg.cpsHud) rows.add(new Row("CPS", "", FloonsPvp.cpsL() + " | " + FloonsPvp.cpsR(), FloonsTheme.TEXT));
        if (cfg.reachHud) rows.add(new Row("Reach", "", FloonsPvp.reach < 0 ? "–" : String.format(java.util.Locale.ROOT, "%.2f", FloonsPvp.reach),
            FloonsPvp.reach > 3.0 ? FloonsTheme.BAD : FloonsTheme.TEXT));
        panel(ctx, tr, mc, cfg, "pvp", "PvP", rows, 6, mc.getWindow().getScaledHeight() / 2f - 20, 90);
    }

    private static void pvpItems(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < FloonsPvp.NAMES.length; i++)
            rows.add(new Row(FloonsPvp.NAMES[i], "", String.valueOf(FloonsPvp.COUNTS[i]), FloonsPvp.COUNTS[i] == 0 ? FloonsTheme.MUTED : FloonsTheme.TEXT));
        panel(ctx, tr, mc, cfg, "pvpitems", "PvP-Items", rows, 6, mc.getWindow().getScaledHeight() / 2f + 30, 90);
    }

    private record Row(String left, String mid, String right, int color) {}

    public static void render(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg, float delta) {
        if (cfg.payHud) pay(ctx, tr, mc, cfg);
        if (cfg.satBorder) saturation(ctx, mc);
        FloonsPvp.overlay(ctx, mc, cfg);
        if (cfg.cpsHud || cfg.reachHud) pvpPanel(ctx, tr, mc, cfg);
        if (cfg.pvpItems) pvpItems(ctx, tr, mc, cfg);
        if (cfg.wpOn && !cfg.waypoints.isEmpty()) waypoints(ctx, tr, mc, cfg);
        if (cfg.customHud) custom(ctx, tr, mc, cfg);
        if (cfg.comboHud) combo(ctx, tr, mc, cfg);
        if (cfg.memHud) mem(ctx, tr, mc, cfg);
        if (cfg.vitalsHud) vitals(ctx, tr, mc, cfg);
        if (cfg.armorHud) armor(ctx, tr, mc, cfg);
        if (cfg.compassHud) compass(ctx, tr, mc, cfg);
        if (cfg.topHud) topPlayers(ctx, tr, mc, cfg);
        if (cfg.graphHud) graph(ctx, tr, mc, cfg);
        if (cfg.potionHud) potions(ctx, tr, mc, cfg);
        if (cfg.sessionHud) session(ctx, tr, mc, cfg);
        if (cfg.notesHud) notes(ctx, tr, mc, cfg);
        if (cfg.twitch) twitch(ctx, tr, mc, cfg);
        if (cfg.notify) FloonsNotify.render(ctx, tr, mc);
    }

    private static void panel(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg, String id, String title,
                              List<Row> rows, float defX, float defY, int minW) {
        int w = Math.max(minW, tr.getWidth(title) + 4);
        for (Row r : rows) w = Math.max(w, tr.getWidth(r.left()) + 12 + tr.getWidth(r.mid()) + (r.mid().isEmpty() ? 0 : 10) + tr.getWidth(r.right()));
        int pad = 6, h = 17 + rows.size() * 11 + pad;
        float[] pos = FloonsHud.place(id, defX < 0 ? (mc.getWindow().getScaledWidth() - (w + pad * 2) * cfg.hudScale) / 2f : defX, defY,
            (w + pad * 2) * cfg.hudScale, h * cfg.hudScale, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(cfg.hudScale, cfg.hudScale);
        FloonsTheme.panel(ctx, 0, 0, w + pad * 2, h);
        FloonsTheme.gradientText(ctx, tr, title, pad, 5);
        int y = 17;
        for (Row r : rows) {
            ctx.drawTextWithShadow(tr, r.left(), pad, y, r.color());
            if (!r.mid().isEmpty()) ctx.drawTextWithShadow(tr, r.mid(), pad + tr.getWidth(r.left()) + 10, y, FloonsTheme.TEXT);
            ctx.drawTextWithShadow(tr, r.right(), w + pad - tr.getWidth(r.right()), y, r.color());
            y += 11;
        }
        ctx.getMatrices().popMatrix();
    }

    // Fertige Zahlungen oben (kurz sichtbar), darunter alle wartenden. Wird eine fertig, rutschen die naechsten nach oben.
    private static void pay(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        long now = System.currentTimeMillis();
        List<Row> rows = new ArrayList<>();
        List<FloonsStats.Done> d = FloonsStats.done;
        for (int i = d.size() - 1; i >= 0 && rows.size() < 4; i--) {
            FloonsStats.Done e = d.get(i);
            if (now - e.at() > 6000) break;
            boolean win = e.result() == 1;
            rows.add(new Row(e.name(), FloonsStats.fmt(e.amount()), win ? "bezahlt" : "verloren", win ? FloonsTheme.GOOD : FloonsTheme.BAD));
        }
        int shown = 0, total = FloonsStats.pending.size();
        for (var e : FloonsStats.pending.entrySet()) {
            if (shown++ >= 24) break;
            rows.add(new Row(e.getKey(), FloonsStats.fmt(e.getValue()), "wartend " + FloonsStats.clock(FloonsStats.waitedSec(e.getKey())), 0xFFFBBF24));
        }
        if (total > 24) rows.add(new Row("+" + (total - 24) + " weitere wartend", "", "", FloonsTheme.MUTED));
        if (rows.isEmpty()) rows.add(new Row("keine offenen Zahlungen", "", "", FloonsTheme.MUTED));
        panel(ctx, tr, mc, cfg, "pay", "Zahlungen", rows, -1, 6, 110);
    }

    private static void waypoints(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        if (mc.player == null || mc.world == null) return;
        String dim = FloonsWaypoints.dim(mc);
        List<FloonsConfig.Wp> ws = new ArrayList<>();
        for (var w : cfg.waypoints) if (dim.equals(w.dim)) ws.add(w);
        ws.sort((a, b) -> Double.compare(FloonsWaypoints.dist(mc, a), FloonsWaypoints.dist(mc, b)));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(6, ws.size()); i++) {
            var w = ws.get(i);
            rows.add(new Row(w.name, "", FloonsWaypoints.arrow(mc, w) + " " + Math.round(FloonsWaypoints.dist(mc, w)) + "m", FloonsTheme.TEXT));
        }
        if (rows.isEmpty()) rows.add(new Row("keine Wegpunkte hier", "", "", FloonsTheme.MUTED));
        panel(ctx, tr, mc, cfg, "waypoints", "Wegpunkte", rows, 6, mc.getWindow().getScaledHeight() / 2f + 60, 120);
    }

    public static final String[] TOKENS = {"{x}", "{y}", "{z}", "{fps}", "{ping}", "{profit}", "{income}", "{payout}", "{runden}",
        "{wins}", "{losses}", "{winrate}", "{offen}", "{zeit}", "{leben}", "{hunger}", "{combo}", "{name}", "{slots}"};

    private static String fill(String s, MinecraftClient mc) {
        var p = mc.player;
        if (p == null || !s.contains("{")) return s;
        int free = 0;
        for (int i = 0; i < 36; i++) if (p.getInventory().getStack(i).isEmpty()) free++;
        int ping = 0;
        if (mc.getNetworkHandler() != null) { var e = mc.getNetworkHandler().getPlayerListEntry(p.getUuid()); if (e != null) ping = e.getLatency(); }
        boolean hide = FloonsConfig.get().streamer && FloonsConfig.get().streamerHideXyz;
        String[] v = {hide ? "?" : String.valueOf((int) Math.floor(p.getX())), hide ? "?" : String.valueOf((int) Math.floor(p.getY())), hide ? "?" : String.valueOf((int) Math.floor(p.getZ())),
            String.valueOf(mc.getCurrentFps()), String.valueOf(ping), FloonsStats.fmt(FloonsStats.profit()), FloonsStats.fmt(FloonsStats.income),
            FloonsStats.fmt(FloonsStats.payouts), String.valueOf(FloonsStats.rounds), String.valueOf(FloonsStats.wins), String.valueOf(FloonsStats.losses),
            String.format(java.util.Locale.ROOT, "%.1f%%", FloonsStats.winrate()), String.valueOf(FloonsStats.pending.size()),
            java.time.LocalTime.now().format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")),
            String.format(java.util.Locale.ROOT, "%.1f", p.getHealth()), String.valueOf(p.getHungerManager().getFoodLevel()),
            String.valueOf(FloonsMisc.combo), FloonsConfig.get().streamer ? FloonsConfig.get().streamerName : p.getName().getString(), String.valueOf(free)};
        for (int i = 0; i < TOKENS.length; i++) s = s.replace(TOKENS[i], v[i]);
        return s;
    }

    private static void custom(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        List<Row> rows = new ArrayList<>();
        for (String l : cfg.customLines) if (l != null && !l.isBlank()) rows.add(new Row(fill(l, mc), "", "", FloonsTheme.TEXT));
        if (rows.isEmpty()) rows.add(new Row("Zeilen im Menü eintragen", "", "", FloonsTheme.MUTED));
        panel(ctx, tr, mc, cfg, "custom", cfg.customTitle == null || cfg.customTitle.isBlank() ? "Mein HUD" : cfg.customTitle, rows,
            mc.getWindow().getScaledWidth() - 190, 120, 130);
    }

    private static void combo(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        long now = System.currentTimeMillis();
        if (now - FloonsMisc.lastHit > 2500 || (mc.player != null && mc.player.hurtTime > 0)) FloonsMisc.combo = 0;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Combo", "", String.valueOf(FloonsMisc.combo), FloonsMisc.combo > 0 ? FloonsTheme.GOOD : FloonsTheme.MUTED));
        rows.add(new Row("Bester Combo", "", String.valueOf(FloonsMisc.maxCombo), FloonsTheme.TEXT));
        rows.add(new Row("Treffer", "", String.valueOf(FloonsMisc.hits), FloonsTheme.TEXT));
        panel(ctx, tr, mc, cfg, "combo", "Combo", rows, mc.getWindow().getScaledWidth() / 2f + 70, mc.getWindow().getScaledHeight() / 2f - 30, 110);
    }

    private static void mem(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        Runtime rt = Runtime.getRuntime();
        long used = (rt.totalMemory() - rt.freeMemory()) >> 20, max = rt.maxMemory() >> 20;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("FPS", "", String.valueOf(mc.getCurrentFps()), FloonsTheme.TEXT));
        rows.add(new Row("RAM", "", used + " / " + max + " MB", used * 10 > max * 9 ? FloonsTheme.BAD : FloonsTheme.TEXT));
        panel(ctx, tr, mc, cfg, "mem", "System", rows, 6, mc.getWindow().getScaledHeight() - 80, 110);
    }

    private static void vitals(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        var p = mc.player;
        if (p == null) return;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Leben", "", String.format(java.util.Locale.ROOT, "%.1f", p.getHealth() + p.getAbsorptionAmount()),
            FloonsTheme.lerp(FloonsTheme.BAD, FloonsTheme.GOOD, Math.min(1f, p.getHealth() / Math.max(1f, p.getMaxHealth())))));
        rows.add(new Row("Hunger", "", String.valueOf(p.getHungerManager().getFoodLevel()), FloonsTheme.TEXT));
        rows.add(new Row("XP-Level", "", String.valueOf(p.experienceLevel), FloonsTheme.TEXT));
        panel(ctx, tr, mc, cfg, "vitals", "Status", rows, mc.getWindow().getScaledWidth() / 2f - 160, mc.getWindow().getScaledHeight() - 70, 100);
    }

    private static final long START = System.currentTimeMillis();

    private static void armor(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        if (mc.player == null) return;
        net.minecraft.entity.EquipmentSlot[] sl = {net.minecraft.entity.EquipmentSlot.HEAD, net.minecraft.entity.EquipmentSlot.CHEST,
            net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET, net.minecraft.entity.EquipmentSlot.MAINHAND};
        List<net.minecraft.item.ItemStack> st = new ArrayList<>();
        for (var s : sl) { var is = mc.player.getEquippedStack(s); if (!is.isEmpty()) st.add(is); }
        int w = 112, rows = Math.max(1, st.size()), h = rows * 18 + 8;
        float[] pos = FloonsHud.place("armor", mc.getWindow().getScaledWidth() - w - 6, mc.getWindow().getScaledHeight() - h - 30,
            w * cfg.hudScale, h * cfg.hudScale, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(cfg.hudScale, cfg.hudScale);
        FloonsTheme.panel(ctx, 0, 0, w, h);
        int y = 4;
        if (st.isEmpty()) ctx.drawTextWithShadow(tr, "Armor Durability", 8, 8, FloonsTheme.MUTED);
        for (var is : st) {
            ctx.drawItem(is, 5, y);
            String t = is.isDamageable() ? Math.round(100f * (is.getMaxDamage() - is.getDamage()) / Math.max(1, is.getMaxDamage())) + " %  ·  " + (is.getMaxDamage() - is.getDamage()) : (is.getCount() > 1 ? "x" + is.getCount() : "");
            float frac = is.isDamageable() ? 1f - (float) is.getDamage() / Math.max(1, is.getMaxDamage()) : 1f;
            ctx.drawTextWithShadow(tr, t, 26, y + 5, FloonsTheme.lerp(FloonsTheme.BAD, FloonsTheme.GOOD, frac));
            y += 18;
        }
        ctx.getMatrices().popMatrix();
    }

    private static final boolean[] armorWarned = new boolean[4];
    private static final net.minecraft.entity.EquipmentSlot[] ARMOR_SLOTS = {net.minecraft.entity.EquipmentSlot.HEAD,
        net.minecraft.entity.EquipmentSlot.CHEST, net.minecraft.entity.EquipmentSlot.LEGS, net.minecraft.entity.EquipmentSlot.FEET};
    private static final String[] ARMOR_NAMES = {"Helm", "Brustplatte", "Hose", "Schuhe"};

    /** Warnt (Toast + Ton) einmal, wenn ein Rüstungsteil unter die eingestellte Haltbarkeit fällt. */
    public static void armorCheck() {
        MinecraftClient mc = MinecraftClient.getInstance();
        FloonsConfig cfg = FloonsConfig.get();
        if (mc.player == null) return;
        for (int i = 0; i < 4; i++) {
            var is = mc.player.getEquippedStack(ARMOR_SLOTS[i]);
            if (is.isEmpty() || !is.isDamageable()) { armorWarned[i] = false; continue; }
            int pct = Math.round(100f * (is.getMaxDamage() - is.getDamage()) / Math.max(1, is.getMaxDamage()));
            if (pct > cfg.armorWarnPct) { armorWarned[i] = false; continue; }
            if (!cfg.armorWarn || armorWarned[i]) continue;
            armorWarned[i] = true;
            FloonsNotify.push("Armor Durability niedrig", ARMOR_NAMES[i] + " nur noch " + pct + " %", 0xFFFB7185);
            if (cfg.sound) FloonsGamble.playId("block.anvil.land");
        }
    }

    private static void topPlayers(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        List<Map.Entry<String, long[]>> top = new ArrayList<>(FloonsStats.players.entrySet());
        top.sort((a, b) -> Long.compare(b.getValue()[2], a.getValue()[2]));
        List<Row> rows = new ArrayList<>();
        for (int i = 0; i < Math.min(5, top.size()); i++) {
            long[] p = top.get(i).getValue();
            rows.add(new Row(top.get(i).getKey(), "", FloonsStats.fmt(p[2]) + " · " + p[0] + "R · " + Math.round(100.0 * p[1] / Math.max(1, p[0])) + "%", FloonsTheme.TEXT));
        }
        if (rows.isEmpty()) rows.add(new Row("noch keine Runde", "", "", FloonsTheme.MUTED));
        panel(ctx, tr, mc, cfg, "top", "Top-Spieler", rows, 6, mc.getWindow().getScaledHeight() / 2f + 20, 130);
    }

    private static void graph(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        List<Long> v = new ArrayList<>(FloonsStats.roundNet);
        int n = Math.min(24, v.size());
        List<Long> last = v.subList(v.size() - n, v.size());
        int w = 130, h = 66, pad = 6;
        float[] pos = FloonsHud.place("graph", 6, mc.getWindow().getScaledHeight() / 2f + 90, w * cfg.hudScale, h * cfg.hudScale, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(cfg.hudScale, cfg.hudScale);
        FloonsTheme.panel(ctx, 0, 0, w, h);
        FloonsTheme.gradientText(ctx, tr, "Gewinn/Runde", pad, 5);
        String tot = FloonsStats.fmt(FloonsStats.profit());
        ctx.drawTextWithShadow(tr, tot, w - pad - tr.getWidth(tot), 5, FloonsStats.profit() >= 0 ? FloonsTheme.GOOD : FloonsTheme.BAD);
        int base = 43;
        ctx.fill(pad, base, w - pad, base + 1, FloonsTheme.alpha(FloonsTheme.MUTED, 110));
        if (n == 0) {
            ctx.drawTextWithShadow(tr, "noch keine Runde", pad, 34, FloonsTheme.MUTED);
        } else {
            long max = 1;
            for (long x : last) max = Math.max(max, Math.abs(x));
            int bw = Math.max(2, (w - pad * 2) / 24 - 1);
            for (int i = 0; i < n; i++) {
                long x = last.get(i);
                int bh = (int) Math.max(1, Math.round(Math.abs(x) * 20.0 / max));
                int bx = pad + i * (bw + 1);
                if (x >= 0) FloonsTheme.roundRectExact(ctx, bx, base - bh, bw, bh, Math.min(2, bw / 2), FloonsTheme.GOOD);
                else FloonsTheme.roundRectExact(ctx, bx, base + 1, bw, bh, Math.min(2, bw / 2), FloonsTheme.BAD);
            }
        }
        ctx.getMatrices().popMatrix();
    }

    private static final String[] DIRS = {"N", "NO", "O", "SO", "S", "SW", "W", "NW"};

    private static void compass(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        if (mc.player == null) return;
        int w = 200, h = 27;
        float[] pos = FloonsHud.place("compass", (mc.getWindow().getScaledWidth() - w * cfg.hudScale) / 2f,
            mc.getWindow().getScaledHeight() - 78, w * cfg.hudScale, h * cfg.hudScale, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(cfg.hudScale, cfg.hudScale);
        FloonsTheme.panel(ctx, 0, 0, w, h, 0xE0);
        double bear = ((mc.player.getYaw() + 180) % 360 + 360) % 360;
        String head = Math.round(bear) % 360 + "°";
        ctx.drawTextWithShadow(tr, head, (w - tr.getWidth(head)) / 2, 3, FloonsTheme.MUTED);
        double range = 90, half = w / 2.0 - 10;
        for (int b = -105; b <= 105; b += 15) {
            double abs = Math.floor(bear / 15) * 15 + b;
            double off = abs - bear;
            if (Math.abs(off) > range) continue;
            int px = (int) Math.round(w / 2.0 + off / range * half);
            int ab = (int) (((Math.round(abs) % 360) + 360) % 360);
            if (ab % 45 == 0) {
                String d = DIRS[ab / 45];
                int col = ab == 0 ? FloonsTheme.BAD : FloonsTheme.TEXT;
                ctx.drawTextWithShadow(tr, d, px - tr.getWidth(d) / 2, 13, col);
            } else {
                ctx.fill(px, 15, px + 1, 19, FloonsTheme.alpha(FloonsTheme.MUTED, 0xA0));
            }
        }
        if (cfg.wpOn) {
            String dim = FloonsWaypoints.dim(mc);
            for (var wp : cfg.waypoints) {
                if (!dim.equals(wp.dim)) continue;
                double dx = wp.x + 0.5 - mc.player.getX(), dz = wp.z + 0.5 - mc.player.getZ();
                double wb = ((Math.toDegrees(Math.atan2(-dx, dz)) + 180) % 360 + 360) % 360;
                double off = ((wb - bear + 540) % 360) - 180;
                boolean edge = Math.abs(off) > range;
                double o = Math.max(-range, Math.min(range, off));
                int px = (int) Math.round(w / 2.0 + o / range * half);
                ctx.fill(px - 1, 21, px + 2, 24, edge ? FloonsTheme.alpha(0xFFFBBF24, 0x70) : 0xFFFBBF24);
            }
        }
        ctx.fill(w / 2, 22, w / 2 + 1, 26, FloonsTheme.BLUE);
        ctx.getMatrices().popMatrix();
    }

    /** Effekte als Symbole (wie im Inventar) mit Restzeit darunter - kein Text zum Lesen noetig. */
    private static void potions(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        if (mc.player == null) return;
        List<net.minecraft.entity.effect.StatusEffectInstance> list = new ArrayList<>(mc.player.getStatusEffects());
        int n = Math.max(1, list.size()), perRow = Math.min(n, 6), rowsN = (n + perRow - 1) / perRow;
        int cellW = 28, cellH = 30, pad = 6;
        int w = Math.max(perRow * cellW, tr.getWidth("Effekte") + 4);
        if (list.isEmpty()) w = Math.max(w, tr.getWidth("keine Effekte") + 4);
        int h = 17 + (list.isEmpty() ? 11 : rowsN * cellH) + pad;
        float[] pos = FloonsHud.place("potions", mc.getWindow().getScaledWidth() - 140, 60,
            (w + pad * 2) * cfg.hudScale, h * cfg.hudScale, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(cfg.hudScale, cfg.hudScale);
        FloonsTheme.panel(ctx, 0, 0, w + pad * 2, h);
        FloonsTheme.gradientText(ctx, tr, "Effekte", pad, 5);
        if (list.isEmpty()) ctx.drawTextWithShadow(tr, "keine Effekte", pad, 17, FloonsTheme.MUTED);
        for (int i = 0; i < list.size(); i++) {
            var e = list.get(i);
            var type = e.getEffectType();
            int cx = pad + (i % perRow) * cellW, cy = 16 + (i / perRow) * cellH;
            try {
                var key = type.getKey();
                if (key.isPresent()) {
                    net.minecraft.util.Identifier id = key.get().getValue();
                    ctx.drawGuiTexture(net.minecraft.client.gl.RenderPipelines.GUI_TEXTURED,
                        net.minecraft.util.Identifier.of(id.getNamespace(), "mob_effect/" + id.getPath()), cx + 5, cy, 18, 18);
                }
            } catch (Throwable ignored) { }
            if (e.getAmplifier() > 0) {
                String lv = String.valueOf(e.getAmplifier() + 1);
                ctx.drawTextWithShadow(tr, lv, cx + 23 - tr.getWidth(lv), cy + 10, 0xFFFFFFFF);
            }
            ctx.fill(cx + 5, cy + 19, cx + 23, cy + 20, FloonsTheme.alpha(type.value().isBeneficial() ? FloonsTheme.GOOD : FloonsTheme.BAD, 0xC0));
            int d = e.getDuration();
            String time = d < 0 || d > 100000 ? "∞" : String.format(java.util.Locale.ROOT, "%d:%02d", d / 1200, (d / 20) % 60);
            ctx.drawTextWithShadow(tr, time, cx + (cellW - tr.getWidth(time)) / 2, cy + 21, d >= 0 && d < 200 ? FloonsTheme.BAD : FloonsTheme.MUTED);
        }
        ctx.getMatrices().popMatrix();
    }

    private static void session(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        if (mc.player == null) return;
        long sec = (System.currentTimeMillis() - START) / 1000;
        int free = 0;
        for (int i = 0; i < 36; i++) if (mc.player.getInventory().getStack(i).isEmpty()) free++;
        List<Row> rows = new ArrayList<>();
        rows.add(new Row("Spielzeit", "", String.format(java.util.Locale.ROOT, "%d:%02d:%02d", sec / 3600, (sec / 60) % 60, sec % 60), FloonsTheme.TEXT));
        rows.add(new Row("Freie Slots", "", free + "/36", free == 0 ? FloonsTheme.BAD : FloonsTheme.TEXT));
        if (mc.world != null) {
            long t = (mc.world.getTimeOfDay() / 1000 + 6) % 24;
            long m = (mc.world.getTimeOfDay() % 1000) * 60 / 1000;
            rows.add(new Row("Welt-Zeit", "", String.format(java.util.Locale.ROOT, "%02d:%02d", t, m), FloonsTheme.TEXT));
        }
        panel(ctx, tr, mc, cfg, "session", "Session", rows, 6, mc.getWindow().getScaledHeight() / 2f - 60, 120);
    }

    private static void notes(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        List<Row> rows = new ArrayList<>();
        int n = cfg.notes.size();
        for (int i = 0; i < Math.min(n, 10); i++)
            rows.add(new Row((i + 1) + ". " + tr.trimToWidth(cfg.notes.get(i), 170), "", "", FloonsTheme.TEXT));
        if (n > 10) rows.add(new Row("+" + (n - 10) + " weitere", "", "", FloonsTheme.MUTED));
        if (rows.isEmpty()) rows.add(new Row("/note add <Text>", "", "", FloonsTheme.MUTED));
        panel(ctx, tr, mc, cfg, "notes", "Notizen", rows, mc.getWindow().getScaledWidth() - 190, mc.getWindow().getScaledHeight() / 2f, 150);
    }

    private static List<Row> evRows(List<String[]> src) {
        List<Row> rows = new ArrayList<>();
        for (String[] s : src) {
            int col = switch (s[2]) { case "1" -> FloonsTheme.GOOD; case "2" -> FloonsTheme.BAD; case "3" -> FloonsTheme.MUTED; default -> FloonsTheme.TEXT; };
            rows.add(new Row(s[0], "", s[1], col));
        }
        return rows;
    }
    private static void twitch(DrawContext ctx, TextRenderer tr, MinecraftClient mc, FloonsConfig cfg) {
        List<FloonsTwitch.Msg> m = FloonsTwitch.last(Math.max(3, Math.min(15, cfg.twitchLines)));
        int w = 220, pad = 6, h = 17 + Math.max(1, m.size()) * 11 + pad;
        float[] pos = FloonsHud.place("twitch", 6, mc.getWindow().getScaledHeight() / 2f, (w + pad * 2) * cfg.hudScale, h * cfg.hudScale, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(cfg.hudScale, cfg.hudScale);
        FloonsTheme.panel(ctx, 0, 0, w + pad * 2, h);
        FloonsLogo.draw(ctx, pad, 4, 9);
        FloonsTheme.gradientText(ctx, tr, "Twitch-Chat", pad + 12, 5);
        String st = FloonsTwitch.state;
        ctx.drawTextWithShadow(tr, st, w + pad - tr.getWidth(st), 5, FloonsTheme.MUTED);
        int y = 17;
        if (m.isEmpty()) ctx.drawTextWithShadow(tr, cfg.twitchChannel.isBlank() ? "Kanal/Video im Menü setzen" : "warte auf Nachrichten …", pad, y, FloonsTheme.MUTED);
        for (var e : m) {
            String name = e.user() + ": ";
            ctx.drawTextWithShadow(tr, name, pad, y, e.color());
            ctx.drawTextWithShadow(tr, tr.trimToWidth(e.text(), Math.max(10, w - tr.getWidth(name))), pad + tr.getWidth(name), y, FloonsTheme.TEXT);
            y += 11;
        }
        ctx.getMatrices().popMatrix();
    }
}
