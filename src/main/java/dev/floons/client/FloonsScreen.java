package dev.floons.client;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/** Basis fuer alle Floons-Screens: Buttons mit runden Ecken im Floons-Design. */
public abstract class FloonsScreen extends Screen {
    protected record Btn(ButtonWidget w, Supplier<String> label, boolean accent) {}
    protected final List<Btn> btns = new ArrayList<>();
    private final java.util.Map<ButtonWidget, Float> hoverAnim = new java.util.HashMap<>();

    protected FloonsScreen(Text title) { super(title); }

    protected abstract void build();

    @Override
    protected final void init() {
        btns.clear();
        build();
    }

    protected ButtonWidget button(Supplier<String> label, Runnable action, int x, int y, int w, int h) {
        return button(label, action, x, y, w, h, false);
    }

    protected ButtonWidget button(Supplier<String> label, Runnable action, int x, int y, int w, int h, boolean accent) {
        ButtonWidget b = ButtonWidget.builder(Text.empty(), btn -> action.run()).dimensions(x, y, w, h).build();
        b.setAlpha(0f); // unsichtbar, wir zeichnen den Button selbst mit runden Ecken
        addDrawableChild(b);
        btns.add(new Btn(b, label, accent));
        return b;
    }

    /** Unsichtbare Klickflaeche (wird nicht als Button gezeichnet, z. B. fuer Item-Raster). */
    protected ButtonWidget hitbox(Runnable action, int x, int y, int w, int h) {
        ButtonWidget b = ButtonWidget.builder(Text.empty(), btn -> action.run()).dimensions(x, y, w, h).build();
        b.setAlpha(0f);
        addDrawableChild(b);
        return b;
    }

    protected void extra(DrawContext ctx, int mouseX, int mouseY) {}

    /** true = dieser Button wird als grosse Verlaufs-Pille gezeichnet. */
    protected boolean bigButton(ButtonWidget w) { return false; }
    protected boolean bigOn() { return true; }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        renderContent(ctx, mouseX, mouseY, delta);
    }

    /** Zeichnet alle Buttons und die Extras (ueberschreibbar, z. B. fuer Fenster mit Reihenfolge). */
    protected void renderContent(DrawContext ctx, int mouseX, int mouseY, float delta) {
        for (Btn b : btns) drawBtn(ctx, b, mouseX, mouseY);
        extra(ctx, mouseX, mouseY);
    }

    protected void drawBtn(DrawContext ctx, Btn b, int mouseX, int mouseY) {

            ButtonWidget w = b.w();
            if (!w.visible) return;
            boolean hov = w.isMouseOver(mouseX, mouseY);
            float cur = hoverAnim.getOrDefault(w, 0f);
            cur += ((hov ? 1f : 0f) - cur) * 0.25f;
            hoverAnim.put(w, cur);
            int x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight();
            float base = b.accent() ? 0.38f : 0.16f;
            float mix = base + (0.6f - base) * cur;
            boolean big = bigButton(w);
            int pr = bh / 2;
            if (big || b.accent()) FloonsTheme.bigPill(ctx, x, y, bw, bh, cur, big ? bigOn() : true);
            else {
                // Wolken-Pille: weiss, durchsichtig, heller Rand
                int wa = Math.max(0x14, Math.min(0x50, 160 / 6));
                FloonsTheme.roundRectExact(ctx, x, y, bw, bh, pr, FloonsTheme.alpha(FloonsTheme.btnTint(), wa + (int) (0x2C * cur)));
                FloonsTheme.roundOutlineExact(ctx, x, y, bw, bh, pr, FloonsTheme.alpha(FloonsTheme.btnTint(), 0x40 + (int) (0x50 * cur)));
            }
            String s = b.label().get();
            int maxW = bw - 10;
            if (textRenderer.getWidth(net.minecraft.text.Text.literal(s)) > maxW) {
                while (s.length() > 1 && textRenderer.getWidth(net.minecraft.text.Text.literal(s + "…")) > maxW) s = s.substring(0, s.length() - 1);
                s = s + "…";
            }
            net.minecraft.text.Text lt = net.minecraft.text.Text.literal(s);
            ctx.drawTextWithShadow(textRenderer, lt, x + (bw - textRenderer.getWidth(lt)) / 2, y + (bh - 8) / 2, (w.active ? FloonsTheme.TEXT : FloonsTheme.MUTED));
    }
}
