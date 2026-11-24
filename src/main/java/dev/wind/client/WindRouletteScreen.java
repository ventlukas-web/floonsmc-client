package dev.wind.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Europaeisches Roulette (0-36) mit rollender Kugel: Kessel dreht sich, die Kugel laeuft gegenlaeufig, faellt ein und huepft ins Fach. */
public class WindRouletteScreen extends WindScreen {
    private static final int[] ORDER = {0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24, 16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26};
    private static final Set<Integer> RED = Set.of(1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36);
    private static final double TAU = Math.PI * 2, STEP = TAU / 37;
    private static final String[] BETS = {"Rot", "Schwarz", "Gerade", "Ungerade", "1–18", "19–36"};

    private static final double TD = 5.5, WB0 = 14, WB_END = 3;   // Kugel laeuft bis TD im Aussenring
    private static final List<Integer> HISTORY = new ArrayList<>();
    private static int bet = 0, betNum = 17;

    private final Screen parent;
    private enum St { IDLE, SPIN, DONE }
    private St st = St.IDLE;
    private int result = -1, resultIdx;
    private double th0, phi0, relDrop, dist, dropLen;
    private long t0 = System.nanoTime(), idle0 = System.nanoTime();
    private double curTheta;
    private int lastPocket = -1;
    private long lastRoll;
    private boolean rebuilt;

    public WindRouletteScreen(Screen parent) {
        super(Text.literal("Roulette"));
        this.parent = parent;
    }

    // ---------- Logik ----------

    private static boolean isRed(int n) { return RED.contains(n); }
    private static int numColor(int n) { return n == 0 ? 0xFF16A34A : isRed(n) ? 0xFFC81E2D : 0xFF15151C; }

    private static boolean wins(int b, int n) {
        return switch (b) {
            case 0 -> n != 0 && isRed(n);
            case 1 -> n != 0 && !isRed(n);
            case 2 -> n != 0 && n % 2 == 0;
            case 3 -> n % 2 == 1;
            case 4 -> n >= 1 && n <= 18;
            case 5 -> n >= 19;
            default -> n == betNum;
        };
    }

    private static double mult() { return bet >= 6 ? 36.0 : 2.0; }

    private static double mod(double a, double m) { double r = a % m; return r < 0 ? r + m : r; }

    private double theta(double t) {
        double base = t <= 9 ? 1.6 * (t - 0.375 * t * t / 9) : 1.6 * (9 - 0.375 * 9) + 0.4 * (t - 9);
        return th0 + base;
    }

    private double wheelSpeed(double t) { return t <= 9 ? 1.6 * (1 - 0.75 * t / 9) : 0.4; }

    private void spin() {
        if (st == St.SPIN) return;
        ThreadLocalRandom rnd = ThreadLocalRandom.current();
        result = rnd.nextInt(37);
        resultIdx = 0;
        for (int i = 0; i < ORDER.length; i++) if (ORDER[i] == result) resultIdx = i;
        th0 = curTheta;
        phi0 = rnd.nextDouble() * TAU;
        double phiTd = phi0 - (WB0 * TD - (WB0 - WB_END) / (2 * TD) * TD * TD);
        relDrop = phiTd - (theta(TD) - th0 + th0);
        double ak = resultIdx * STEP;
        double d = mod(relDrop - ak, TAU);
        dist = d + (d < 1.5 ? TAU : 0);
        double wrel = WB_END + wheelSpeed(TD);
        dropLen = Math.max(1.8, Math.min(4.2, 2 * dist / wrel));
        t0 = System.nanoTime();
        lastRoll = 0;
        if (WindConfig.get().sound) { WindGamble.playId("block.note_block.chime", 0.8f, 0.7f); WindGamble.playId("entity.item.pickup", 0.6f, 0.6f); }
        st = St.SPIN;
        lastPocket = -1;
        rebuilt = false;
        clearAndInit();
    }

    /** {Winkel, Radius-Faktor} der Kugel zur Zeit t (Sekunden seit Start). */
    private double[] ball(double t, double rt, double rp) {
        if (t < TD) {
            double phi = phi0 - (WB0 * t - (WB0 - WB_END) / (2 * TD) * t * t);
            return new double[]{phi, rt};
        }
        double s = (t - TD) / dropLen;
        if (s >= 1) return new double[]{theta(t) + resultIdx * STEP, rp};
        double e = 1 - (1 - s) * (1 - s);
        double rel = relDrop - dist * e;
        double r = rp + (rt - rp) * (1 - s) * (1 - s) + Math.abs(Math.sin(s * Math.PI * 3.5)) * (1 - s) * 6;
        return new double[]{theta(t) + rel, r};
    }

    // ---------- Aufbau ----------

    @Override
    protected void build() {
        int px = width - 160, y0 = Math.max(8, height / 2 - 100);
        for (int i = 0; i < BETS.length; i++) {
            final int idx = i;
            button(() -> BETS[idx], () -> { bet = idx; clearAndInit(); },
                px + (i % 2) * 78, y0 + 22 + (i / 2) * 22, 72, 20, bet == i);
        }
        button(() -> "Zahl", () -> { bet = 6; clearAndInit(); }, px, y0 + 88, 72, 20, bet == 6);
        TextFieldWidget f = new TextFieldWidget(textRenderer, px + 78 + 8, y0 + 88 + 6, 72 - 16, 12, Text.empty());
        f.setDrawsBackground(false);
        final int bx0 = px + 78, by0 = y0 + 88;
        addDrawable((dc, mx, my, dl) -> WindTheme.fieldBg(dc, bx0, by0, 72, 20, f.isFocused()));
        f.setMaxLength(2);
        f.setText(String.valueOf(betNum));
        f.setChangedListener(s -> {
            try { int v = Integer.parseInt(s.trim()); if (v >= 0 && v <= 36) { betNum = v; bet = 6; } } catch (Exception ignored) {}
        });
        addDrawableChild(f);
        button(() -> st == St.SPIN ? "Kugel rollt …" : "DREHEN", this::spin, px, y0 + 114, 150, 22, true);
        boolean done = st == St.DONE;
        boolean won = done && wins(bet, result);
        // Gewinn/Verlust sind nur Anzeige (nicht klickbar)
        button(() -> "Gewinn ×" + (mult() == 36 ? "36" : "2"), () -> {}, px, y0 + 178, 72, 20, won).active = false;
        button(() -> "Verlust", () -> {}, px + 78, y0 + 178, 72, 20, done && !won).active = false;
        button(() -> "Zurück", this::close, 8, height - 28, 60, 20);
    }

    @Override
    public void close() { MinecraftClient.getInstance().setScreen(parent); }

    @Override
    public boolean shouldPause() { return false; }

    // ---------- Zeichnen ----------

    @Override
    public void renderBackground(DrawContext ctx, int mx, int my, float delta) {
        ctx.fillGradient(0, 0, width, height, 0xFF07110C, 0xFF030806);
    }

    @Override
    public void render(DrawContext ctx, int mx, int my, float delta) {
        double now = System.nanoTime();
        int px = width - 160, y0 = Math.max(8, height / 2 - 100);
        int cx = (px - 10) / 2, cy = height / 2;
        int R = Math.max(40, Math.min((int) (height * 0.42), (px - 10) / 2 - 6));
        double t = (now - t0) / 1e9;
        if (st == St.IDLE) curTheta = th0 + 0.3 * ((now - idle0) / 1e9);
        else curTheta = theta(t);

        double rt = R * 0.87, rp = R * 0.63;
        if (st == St.SPIN && t < TD && WindConfig.get().sound) {
            // Kugel rollt im Aussenring: leises Rattern, wird mit der Zeit langsamer
            long nowMs = System.currentTimeMillis();
            long gap = (long) (70 + 160 * (t / TD));
            if (nowMs - lastRoll >= gap) { lastRoll = nowMs; WindGamble.playId("block.note_block.hat", 0.35f, 1.3f - (float) (t / TD) * 0.5f); }
        }
        if (st == St.SPIN && t >= TD) {
            double s = (t - TD) / dropLen;
            double rel = relDrop - dist * (1 - (1 - Math.min(1, s)) * (1 - Math.min(1, s)));
            int pk = (int) Math.floor(mod(rel, TAU) / STEP);
            if (pk != lastPocket) { lastPocket = pk; if (WindConfig.get().sound) WindGamble.playId("block.note_block.hat", 0.9f, 0.9f + (float) Math.min(1, s) * 0.3f); }
        }
        if (st == St.SPIN && t >= TD + dropLen + 0.1) {
            st = St.DONE;
            HISTORY.add(result);
            if (HISTORY.size() > 16) HISTORY.remove(0);
            boolean w = wins(bet, result);
            WindGamble.chat("Roulette: " + result + (result == 0 ? " Grün" : isRed(result) ? " Rot" : " Schwarz") + " – " + (w ? "Wette gewonnen (×" + (int) mult() + ")" : "Wette verloren"));
            if (WindConfig.get().sound) {
                WindGamble.playId("block.note_block.bell", 1f, result == 0 ? 0.6f : 1.2f);
                WindGamble.playId(w ? "entity.player.levelup" : "block.note_block.bass", 1f, 1f);
            }
            if (!rebuilt) { rebuilt = true; clearAndInit(); }
        }

        // Kessel: Rand, Holz, Kugelbahn, Faecherring
        WindTheme.roundRectExact(ctx, cx - R - 3, cy - R - 3, 2 * R + 6, 2 * R + 6, R + 3, 0xFF000000);
        WindTheme.roundRectExact(ctx, cx - R, cy - R, 2 * R, 2 * R, R, 0xFFB8862B);
        int r2 = R - 5;
        WindTheme.roundRectExact(ctx, cx - r2, cy - r2, 2 * r2, 2 * r2, r2, 0xFF3A1D10);
        int r3 = (int) (R * 0.76);
        WindTheme.roundRectExact(ctx, cx - r3 - 2, cy - r3 - 2, 2 * r3 + 4, 2 * r3 + 4, r3 + 2, 0xFFB8862B);
        WindTheme.roundRectExact(ctx, cx - r3, cy - r3, 2 * r3, 2 * r3, r3, 0xFF0B0B0B);

        // drehender Teil: Faecher + Zahlen
        var m = ctx.getMatrices();
        m.pushMatrix();
        m.translate(cx, cy);
        m.rotate((float) curTheta);
        double[] bands = {0.50, 0.58, 0.67, 0.76};
        for (int k = 0; k < 37; k++) {
            int n = ORDER[k];
            m.pushMatrix();
            m.rotate((float) (k * STEP));
            int col = numColor(n);
            for (int b = 0; b < 3; b++) {
                double ri = R * bands[b], ro = R * bands[b + 1];
                int hw = Math.max(1, (int) Math.round(TAU * (ri + ro) / 2 / 37 / 2) - 1);
                ctx.fill(-hw, (int) -ro, hw, (int) -ri + 1, col);
            }
            String s = String.valueOf(n);
            m.pushMatrix();
            m.translate(0, (float) (-R * 0.715));
            float sc = Math.max(0.4f, R / 170f);
            m.scale(sc, sc);
            ctx.drawTextWithShadow(textRenderer, s, -textRenderer.getWidth(s) / 2, -4, 0xFFFFFFFF);
            m.popMatrix();
            m.popMatrix();
        }
        // Mittelkegel
        int rc = (int) (R * 0.50);
        WindTheme.roundRectExact(ctx, -rc, -rc, 2 * rc, 2 * rc, rc, 0xFF5A3418);
        int rc2 = (int) (R * 0.40);
        WindTheme.roundRectExact(ctx, -rc2, -rc2, 2 * rc2, 2 * rc2, rc2, 0xFF7A4A22);
        for (int i = 0; i < 4; i++) {
            m.pushMatrix();
            m.rotate((float) (i * Math.PI / 2));
            ctx.fill(-2, (int) -(R * 0.40), 2, (int) (R * 0.40), 0xFFD9A441);
            m.popMatrix();
        }
        m.popMatrix();
        int hub = Math.max(5, (int) (R * 0.12));
        WindTheme.roundRectExact(ctx, cx - hub, cy - hub, 2 * hub, 2 * hub, hub, 0xFFD9A441);

        // Kugel mit Schweif
        if (st != St.IDLE) {
            for (int g = 3; g >= 0; g--) {
                double tt = Math.max(0, t - g * 0.035);
                double[] bp = ball(tt, rt, rp);
                int bx = cx + (int) Math.round(Math.sin(bp[0]) * bp[1]);
                int by = cy - (int) Math.round(Math.cos(bp[0]) * bp[1]);
                int d = Math.max(5, R / 13);
                int a = g == 0 ? 0xFF : 0x55 - g * 0x12;
                if (g == 0) WindTheme.roundRectExact(ctx, bx - d / 2 + 1, by - d / 2 + 1, d, d, d / 2, 0x88000000);
                WindTheme.roundRectExact(ctx, bx - d / 2, by - d / 2, d, d, d / 2, WindTheme.alpha(0xFFF4F4F4, a));
            }
        }

        // Ergebnis in der Mitte
        if (st == St.DONE) {
            int rb = Math.max(14, (int) (R * 0.26));
            WindTheme.roundRectExact(ctx, cx - rb, cy - rb, 2 * rb, 2 * rb, rb, 0xFFFFFFFF);
            WindTheme.roundRectExact(ctx, cx - rb + 2, cy - rb + 2, 2 * rb - 4, 2 * rb - 4, rb - 2, numColor(result));
            m.pushMatrix();
            m.translate(cx, cy);
            m.scale(2f, 2f);
            String rs = String.valueOf(result);
            ctx.drawTextWithShadow(textRenderer, rs, -textRenderer.getWidth(rs) / 2, -4, 0xFFFFFFFF);
            m.popMatrix();
        }

        // Verlauf unten links
        int hx = 80, hy = height - 26;
        for (int i = 0; i < HISTORY.size(); i++) {
            int n = HISTORY.get(HISTORY.size() - 1 - i);
            int x = hx + i * 17;
            if (x + 16 > px - 8) break;
            WindTheme.roundRectExact(ctx, x, hy, 15, 17, 6, numColor(n));
            String s = String.valueOf(n);
            ctx.drawTextWithShadow(textRenderer, s, x + 8 - textRenderer.getWidth(s) / 2, hy + 5, 0xFFFFFFFF);
        }

        // Seitenpanel
        WindTheme.panel(ctx, px - 10, y0 - 6, 170, 212, 0xE0);
        WindTheme.gradientText(ctx, textRenderer, "Roulette", px, y0 + 4);
        super.render(ctx, mx, my, delta);
        String betTxt = bet >= 6 ? "Zahl " + betNum + " (×36)" : BETS[bet] + " (×2)";
        ctx.drawTextWithShadow(textRenderer, "Wette: " + betTxt, px, y0 + 142, WindTheme.MUTED);
        if (st == St.DONE) {
            boolean w = wins(bet, result);
            String col = result == 0 ? "Grün" : isRed(result) ? "Rot" : "Schwarz";
            ctx.drawTextWithShadow(textRenderer, result + " " + col, px, y0 + 154, 0xFFFFFFFF);
            ctx.drawTextWithShadow(textRenderer, w ? "GEWONNEN" : "VERLOREN", px, y0 + 166, w ? WindTheme.GOOD : WindTheme.BAD);
        } else if (st == St.SPIN) {
            ctx.drawTextWithShadow(textRenderer, "Kugel rollt …", px, y0 + 154, 0xFFFBBF24);
        } else {
            ctx.drawTextWithShadow(textRenderer, "Wette wählen und drehen", px, y0 + 154, WindTheme.MUTED);
        }
    }
}
