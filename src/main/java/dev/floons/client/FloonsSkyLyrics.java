package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.DisplayEntity;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.nbt.NbtFloat;
import net.minecraft.nbt.NbtList;
import net.minecraft.nbt.NbtOps;
import net.minecraft.storage.NbtReadView;
import net.minecraft.text.Text;
import net.minecraft.text.TextCodecs;
import net.minecraft.util.ErrorReporter;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Vec3d;

/**
 * Sky Lyrics in der Welt (wie bei FWM): die aktuelle Zeile schwebt groß im Himmel, vor dir oder an einer festen Stelle.
 * Umgesetzt mit Text-Displays, die nur in deinem Client existieren (der Server sieht nichts davon).
 */
public final class FloonsSkyLyrics {
    private FloonsSkyLyrics() {}

    private static final int ID_CUR = -771_001, ID_NEXT = -771_002, ID_HEAD = -771_003;
    private static DisplayEntity.TextDisplayEntity cur, next, head;
    private static String curText = null, nextText = null, headText = null;
    private static ClientWorld world;
    private static float anchorYaw;
    private static int lastLine = Integer.MIN_VALUE;
    private static long lineAt;

    public static void tick(MinecraftClient mc) {
        FloonsConfig c = FloonsConfig.get();
        FloonsLyrics.update(c);
        ClientPlayerEntity p = mc.player;
        boolean on = !FloonsClient.off() && c.lyricsHud && !"hud".equals(c.lyricsMode) && p != null && mc.world != null;
        String[] l = on ? FloonsLyrics.current() : null;
        if (!on || l == null) { clear(); return; }
        if (world != mc.world) { clear(); world = mc.world; }

        int line = parse(l[3]);
        if (line != lastLine) {
            lastLine = line; lineAt = System.currentTimeMillis();
            anchorYaw = p.getYaw(); // im Himmel: neue Zeile erscheint in Blickrichtung
        }
        float size = Math.max(0.3f, Math.min(4f, c.lyricsSize));
        float sCur, sNext, sHead;
        Vec3d base;
        switch (c.lyricsMode) {
            case "front" -> {
                sCur = 1.1f * size; sNext = 0.6f * size; sHead = 0.45f * size;
                base = p.getEyePos().add(p.getRotationVec(1f).multiply(4.0)).add(0, -0.2, 0);
            }
            case "block" -> {
                sCur = 2.2f * size; sNext = 1.2f * size; sHead = 0.9f * size;
                if (c.lyricsBY < -500) { c.lyricsBX = p.getX(); c.lyricsBY = p.getY() + 2.5; c.lyricsBZ = p.getZ(); c.save(); }
                base = new Vec3d(c.lyricsBX, c.lyricsBY, c.lyricsBZ);
            }
            default -> { // "sky"
                sCur = 14f * size; sNext = 7f * size; sHead = 5f * size;
                double r = Math.toRadians(anchorYaw), dist = 48 * size;
                base = new Vec3d(p.getX() - Math.sin(r) * dist, p.getY() + 22 * size, p.getZ() + Math.cos(r) * dist);
            }
        }
        // Text-Display: 1 Zeile ≈ 0,25 Blöcke × Größe
        double lh = 0.27;
        // sanftes Einblenden der neuen Zeile
        float t = Math.min(1f, (System.currentTimeMillis() - lineAt) / 220f);
        int alpha = (int) (64 + 191 * t);

        int c1 = color(c.lyricsColor, 0xFFE6F0FF), c2 = color(c.lyricsColor2, 0xFF8CC8FF);
        int c3 = FloonsTheme.lerp(c2, 0xFF8A8A9A, 0.4f);
        cur = show(cur, ID_CUR, l[1], c1, sCur * (0.92f + 0.08f * t), alpha, base, true);
        if (c.lyricsNext && !l[2].isEmpty()) next = show(next, ID_NEXT, l[2], c2, sNext, 150, base.add(0, -(sCur * lh + sNext * 0.1), 0), false);
        else next = remove(next);
        head = show(head, ID_HEAD, l[0], c3, sHead, 140, base.add(0, sCur * lh + sHead * 0.05, 0), false);
    }

    private static int parse(String s) { try { return Integer.parseInt(s); } catch (Exception e) { return -1; } }

    /** Schriftarten für Sky Lyrics: vanilla, clean (Poppins), bold (Poppins Bold). */
    public static final String[] FONTS = {"clean", "bold", "vanilla"};

    public static String fontName(String f) {
        return switch (f) { case "bold" -> "Clean Bold"; case "vanilla" -> "Minecraft"; default -> "Clean"; };
    }

    static int color(String hex, int def) { Integer v = FloonsTheme.parseHex(hex); return v != null ? v : def; }

    /** Text in der gewählten Lyrics-Schrift und Farbe. */
    public static Text styled(String text, int color, boolean main) {
        String f = FloonsConfig.get().lyricsFont;
        net.minecraft.text.Style st = net.minecraft.text.Style.EMPTY.withColor(color & 0xFFFFFF);
        if ("vanilla".equals(f)) st = st.withBold(main);
        else {
            String id = "bold".equals(f) ? "lyrics_bold" : main ? "lyrics_medium" : "lyrics_clean";
            st = st.withFont(new net.minecraft.text.StyleSpriteSource.Font(net.minecraft.util.Identifier.of("floonsclient", id)));
        }
        return Text.literal(text).setStyle(st);
    }

    private static DisplayEntity.TextDisplayEntity show(DisplayEntity.TextDisplayEntity e, int id, String text, int color, float scale, int alpha,
                                                        Vec3d pos, boolean main) {
        MinecraftClient mc = MinecraftClient.getInstance();
        ClientWorld w = mc.world;
        String key = text + "|" + Math.round(scale * 50) + "|" + (alpha >> 4) + "|" + color + "|" + FloonsConfig.get().lyricsFont;
        boolean fresh = e == null || e.isRemoved() || e.getEntityWorld() != w;
        if (fresh) {
            e = new DisplayEntity.TextDisplayEntity(EntityType.TEXT_DISPLAY, w);
            e.setId(id);
            apply(e, text, color, scale, alpha, main);
            e.setPosition(pos);
            w.addEntity(e);
            remember(id, key);
        } else if (!key.equals(known(id))) {
            apply(e, text, color, scale, alpha, main);
            remember(id, key);
        }
        e.setPosition(pos);
        e.lastRenderX = pos.x; e.lastRenderY = pos.y; e.lastRenderZ = pos.z; // kein Nachziehen beim Laufen
        return e;
    }

    private static String known(int id) { return id == ID_CUR ? curText : id == ID_NEXT ? nextText : headText; }
    private static void remember(int id, String k) { if (id == ID_CUR) curText = k; else if (id == ID_NEXT) nextText = k; else headText = k; }

    private static void apply(DisplayEntity.TextDisplayEntity e, String text, int color, float scale, int alpha, boolean main) {
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            NbtCompound n = new NbtCompound();
            Text tx = styled(text, color, main);
            n.put("text", TextCodecs.CODEC.encodeStart(NbtOps.INSTANCE, tx).getOrThrow());
            n.putString("billboard", "center");
            n.putInt("line_width", 1000);
            n.putByte("text_opacity", (byte) Math.max(26, Math.min(255, alpha)));
            n.putInt("background", 0);
            n.putBoolean("shadow", true);
            n.putBoolean("see_through", false);
            n.putFloat("view_range", 16f);
            NbtCompound br = new NbtCompound(); br.putInt("sky", 15); br.putInt("block", 15);
            n.put("brightness", br);
            NbtCompound tr = new NbtCompound();
            tr.put("left_rotation", floats(0, 0, 0, 1));
            tr.put("right_rotation", floats(0, 0, 0, 1));
            tr.put("translation", floats(0, 0, 0));
            tr.put("scale", floats(scale, scale, scale));
            n.put("transformation", tr);
            Vec3d keep = e.getEntityPos();
            e.readData(NbtReadView.create(ErrorReporter.EMPTY, mc.world.getRegistryManager(), n));
            e.setPosition(keep);
        } catch (Throwable ignored) {}
    }

    private static NbtList floats(float... v) {
        NbtList l = new NbtList();
        for (float f : v) l.add(NbtFloat.of(f));
        return l;
    }

    private static DisplayEntity.TextDisplayEntity remove(DisplayEntity.TextDisplayEntity e) {
        if (e != null && world != null) world.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
        if (e == next) nextText = null;
        return null;
    }

    public static void clear() {
        if (world != null) {
            for (DisplayEntity.TextDisplayEntity e : new DisplayEntity.TextDisplayEntity[]{cur, next, head})
                if (e != null) world.removeEntity(e.getId(), Entity.RemovalReason.DISCARDED);
        }
        cur = next = head = null; curText = nextText = headText = null; lastLine = Integer.MIN_VALUE;
    }

    /** /lyrics here: feste Stelle auf den Block, den du anschaust. */
    public static String here() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return "Nicht im Spiel.";
        FloonsConfig c = FloonsConfig.get();
        HitResult hr = mc.player.raycast(64, 1f, false);
        Vec3d at = hr instanceof BlockHitResult b && hr.getType() == HitResult.Type.BLOCK ? Vec3d.ofCenter(b.getBlockPos()).add(0, 2.5, 0) : mc.player.getEyePos().add(0, 1.5, 0);
        c.lyricsBX = at.x; c.lyricsBY = at.y; c.lyricsBZ = at.z; c.lyricsMode = "block"; c.save();
        clear();
        return "Sky Lyrics hängen jetzt hier (" + (int) at.x + " " + (int) at.y + " " + (int) at.z + ").";
    }
}
