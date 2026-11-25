package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardEntry;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** Ersetzt das Server-Scoreboard (rechte Seite) durch eine anpassbare Version: Ecken, Transparenz, Farben, Zahlen, Groesse. */
public final class FloonsScoreboard {
    private FloonsScoreboard() {}

    private static int rgb(String hex, int def) {
        Integer v = FloonsTheme.parseHex(hex);
        return v == null ? def : v;
    }

    public static void render(DrawContext ctx, RenderTickCounter tick, Runnable vanilla) {
        if (FloonsClient.off()) { vanilla.run(); return; }
        long p0 = FloonsProf.begin();
        try { render0(ctx, tick, vanilla); } finally { FloonsProf.end("Scoreboard", p0); }
    }

    private static void render0(DrawContext ctx, RenderTickCounter tick, Runnable vanilla) {
        FloonsConfig cfg = FloonsConfig.get();
        if (!cfg.sbCustom) { vanilla.run(); return; }
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.world == null || mc.options.hudHidden) return;
        TextRenderer tr = mc.textRenderer;

        Text title = null;
        List<Text> names = new ArrayList<>();
        List<String> nums = new ArrayList<>();
        var sb = mc.world.getScoreboard();
        ScoreboardObjective obj = sb.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
        if (obj != null) {
            title = obj.getDisplayName();
            List<ScoreboardEntry> es = new ArrayList<>();
            for (ScoreboardEntry e : sb.getScoreboardEntries(obj)) if (!e.hidden()) es.add(e);
            es.sort(Comparator.comparingInt(ScoreboardEntry::value).reversed().thenComparing(ScoreboardEntry::owner));
            for (int i = 0; i < Math.min(15, es.size()); i++) {
                ScoreboardEntry e = es.get(i);
                names.add(Team.decorateName(sb.getScoreHolderTeam(e.owner()), e.name()));
                nums.add(String.valueOf(e.value()));
            }
        } else if (mc.currentScreen instanceof FloonsHudEditor) {
            title = Text.literal("Scoreboard");
            for (int i = 1; i <= 4; i++) { names.add(Text.literal("Beispiel-Zeile " + i)); nums.add(String.valueOf(i * 10)); }
        } else {
            FloonsHud.BOUNDS.remove("scoreboard");
            return;
        }

        boolean th = cfg.sbTheme;
        int text = th ? 0xFFFFFFFF : 0xFF000000 | rgb(cfg.sbText, 0xFFFFFF);
        int bg = th ? FloonsTheme.alpha(FloonsTheme.lerp(0xFF0A0614, FloonsTheme.PURPLE, 0.12f), cfg.sbAlpha)
            : FloonsTheme.alpha(0xFF000000 | rgb(cfg.sbBg, 0x0B0618), cfg.sbAlpha);
        int border = th ? FloonsTheme.alpha(FloonsTheme.lerp(FloonsTheme.PURPLE, FloonsTheme.BLUE, 0.5f), 0xFF)
            : 0xFF000000 | rgb(cfg.sbBorder, 0xA855F7);
        int pad = 4, rowH = 10;
        int w = tr.getWidth(title);
        for (int i = 0; i < names.size(); i++) {
            int rw = tr.getWidth(names.get(i)) + (cfg.sbNumbers ? 10 + tr.getWidth(nums.get(i)) : 0);
            w = Math.max(w, rw);
        }
        int h = (names.size() + 1) * rowH + pad * 2 + 2;
        float sc = cfg.sbScale;
        int sw = mc.getWindow().getScaledWidth(), sh = mc.getWindow().getScaledHeight();
        float[] pos = FloonsHud.place("scoreboard", sw - (w + pad * 2) * sc - 4, sh / 2f - h * sc / 2f,
            (w + pad * 2) * sc, h * sc, mc);
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(pos[0], pos[1]);
        ctx.getMatrices().scale(sc, sc);
        int fw = w + pad * 2;
        int sbr = Math.max(5, cfg.sbRadius); // immer abgerundet
        FloonsTheme.roundRectExact(ctx, 0, 0, fw, h, sbr, bg);
        if (cfg.sbBorderOn) FloonsTheme.roundOutlineExact(ctx, 0, 0, fw, h, sbr, border);
        if (th) {
            String ts = title.getString();
            FloonsTheme.gradientText(ctx, tr, ts, (fw - tr.getWidth(ts)) / 2, pad + 1);
        } else ctx.drawTextWithShadow(tr, title, (fw - tr.getWidth(title)) / 2, pad + 1, text);
        ctx.fill(pad, pad + rowH, fw - pad, pad + rowH + 1, FloonsTheme.alpha(border, 0x90));
        int y = pad + rowH + 3;
        for (int i = 0; i < names.size(); i++) {
            ctx.drawTextWithShadow(tr, names.get(i), pad, y, text);
            if (cfg.sbNumbers) ctx.drawTextWithShadow(tr, nums.get(i), fw - pad - tr.getWidth(nums.get(i)), y, th ? FloonsTheme.BLUE : 0xFFFF5555);
            y += rowH;
        }
        ctx.getMatrices().popMatrix();
    }
}
