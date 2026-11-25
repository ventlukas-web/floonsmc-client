package dev.floons.client;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;

/** Eigenes Crosshair: Stile, Farbe, Groesse, Abstand, Dicke, Mittelpunkt, Kontur und 3 eigene Speicherplaetze. Reine Anzeige. */
public final class FloonsCrosshair {
    public static final String[] STYLES = {"Plus", "Fadenkreuz", "Punkt", "Kreis", "T-Form", "Eigenes"};
    // len, gap, thick, dot
    private static final int[][] PRESET = {{5, 1, 1, 0}, {6, 3, 1, 1}, {0, 0, 2, 1}, {6, 2, 1, 1}, {6, 2, 1, 1}};
    public static final String[] COLORS = {"Theme", "Weiß", "Grün", "Rot", "Gelb", "Cyan", "Pink"};
    private static final int[] RGB = {0, 0xFFFFFFFF, 0xFF4ADE80, 0xFFEF4444, 0xFFFBBF24, 0xFF22D3EE, 0xFFFF5CB8};

    private FloonsCrosshair() {}

    public static void register() {
        HudElementRegistry.replaceElement(VanillaHudElements.CROSSHAIR, orig -> (ctx, tick) -> {
            FloonsConfig c = FloonsConfig.get();
            MinecraftClient mc = MinecraftClient.getInstance();
            if (!c.chOn || c.allOff) { orig.render(ctx, tick); return; }
            if (mc.options == null || !mc.options.getPerspective().isFirstPerson() || mc.options.hudHidden) return;
            draw(ctx, ctx.getScaledWindowWidth() / 2, ctx.getScaledWindowHeight() / 2, c);
        });
    }

    public static int color(FloonsConfig c) {
        Integer hex = c.chHex == null || c.chHex.isBlank() ? null : FloonsTheme.parseHex(c.chHex.trim());
        if (hex != null) return hex;
        int i = Math.floorMod(c.chColorIdx, RGB.length);
        return i == 0 ? FloonsTheme.lerp(FloonsTheme.PURPLE, FloonsTheme.BLUE, 0.6f) : RGB[i];
    }

    public static void applyStyle(FloonsConfig c, int style) {
        c.chStyle = Math.floorMod(style, STYLES.length);
        if (c.chStyle < PRESET.length) {
            int[] p = PRESET[c.chStyle];
            c.chLen = p[0]; c.chGap = p[1]; c.chThick = p[2]; c.chDot = p[3] == 1;
        }
    }

    public static void draw(DrawContext ctx, int cx, int cy, FloonsConfig c) {
        int len = c.chLen, gap = c.chGap, t = Math.max(1, c.chThick), half = t / 2, col = color(c);
        int shadow = 0xB0000000;
        boolean ring = c.chStyle == 3, tForm = c.chStyle == 4;
        for (int pass = c.chOutline ? 0 : 1; pass < 2; pass++) {
            int e = pass == 0 ? 1 : 0, cc = pass == 0 ? shadow : col;
            if (ring) {
                int r = gap + len / 2 + 2;
                for (int a = 0; a < 72; a++) {
                    double ang = a * Math.PI / 36;
                    int px = cx + (int) Math.round(Math.cos(ang) * r), py = cy + (int) Math.round(Math.sin(ang) * r);
                    ctx.fill(px - half - e, py - half - e, px - half + t + e, py - half + t + e, cc);
                }
            } else if (len > 0) {
                // links, rechts, unten, oben (oben entfaellt bei T-Form)
                ctx.fill(cx - gap - len - e, cy - half - e, cx - gap + e, cy - half + t + e, cc);
                ctx.fill(cx + gap + 1 - e, cy - half - e, cx + gap + 1 + len + e, cy - half + t + e, cc);
                ctx.fill(cx - half - e, cy + gap + 1 - e, cx - half + t + e, cy + gap + 1 + len + e, cc);
                if (!tForm) ctx.fill(cx - half - e, cy - gap - len - e, cx - half + t + e, cy - gap + e, cc);
            }
            if (c.chDot) {
                int d = Math.max(1, t);
                ctx.fill(cx - d / 2 - e, cy - d / 2 - e, cx - d / 2 + d + e, cy - d / 2 + d + e, cc);
            }
        }
    }

    // ---- Eigene Speicherplaetze (3) ----
    public static String pack(FloonsConfig c) {
        return c.chLen + ";" + c.chGap + ";" + c.chThick + ";" + (c.chDot ? 1 : 0) + ";" + (c.chOutline ? 1 : 0) + ";" + c.chStyle + ";" + c.chColorIdx + ";" + (c.chHex == null ? "" : c.chHex);
    }

    public static boolean load(FloonsConfig c, String s) {
        if (s == null || s.isBlank()) return false;
        try {
            String[] p = s.split(";", -1);
            c.chLen = Integer.parseInt(p[0]); c.chGap = Integer.parseInt(p[1]); c.chThick = Integer.parseInt(p[2]);
            c.chDot = p[3].equals("1"); c.chOutline = p[4].equals("1"); c.chStyle = Integer.parseInt(p[5]);
            c.chColorIdx = Integer.parseInt(p[6]); c.chHex = p.length > 7 ? p[7] : "";
            return true;
        } catch (Exception e) { return false; }
    }
}
