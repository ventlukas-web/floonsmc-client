package dev.floons.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.util.function.Consumer;

/** Item aus dem eigenen Inventar auswählen (z. B. für das Auktions-Item). */
public class FloonsItemPicker extends FloonsScreen {
    private static final int CELL = 20, COLS = 9;
    private final Screen parent;
    private final Consumer<String> onPick;
    private int left, top;

    public FloonsItemPicker(Screen parent, Consumer<String> onPick) {
        super(Text.literal("Item auswählen"));
        this.parent = parent;
        this.onPick = onPick;
    }

    /** "16x Diamant" bzw. "Elytra" (Anzeigename inkl. Umbenennung). */
    public static String describe(ItemStack st) {
        if (st == null || st.isEmpty()) return "";
        String n = st.getName().getString();
        return st.getCount() > 1 ? st.getCount() + "x " + n : n;
    }

    /** Inventar-Index -> Anzeige-Reihenfolge: Hotbar (0-8) unten, Rest (9-35) darüber. */
    private static int slotAt(int row, int col) { return row < 3 ? 9 + row * COLS + col : col; }

    @Override
    protected void build() {
        int w = COLS * CELL, h = 4 * CELL + 4;
        left = width / 2 - w / 2;
        top = height / 2 - h / 2;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player != null) {
            for (int r = 0; r < 4; r++) for (int c = 0; c < COLS; c++) {
                final int slot = slotAt(r, c);
                int x = left + c * CELL, y = top + r * CELL + (r == 3 ? 4 : 0);
                hitbox(() -> {
                    ItemStack st = mc.player.getInventory().getStack(slot);
                    if (st.isEmpty()) return;
                    onPick.accept(describe(st));
                    close();
                }, x, y, CELL, CELL);
            }
        }
        button(() -> "Abbrechen", this::close, width / 2 - 50, top + h + 30, 100, 18);
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fillGradient(0, 0, width, height,
            FloonsTheme.alpha(FloonsTheme.lerp(0xFF03060D, FloonsTheme.PURPLE, 0.15f), 0xC0),
            FloonsTheme.alpha(FloonsTheme.lerp(0xFF03060D, FloonsTheme.BLUE, 0.12f), 0xF2));
        int w = COLS * CELL, h = 4 * CELL + 4;
        FloonsTheme.solidPanel(ctx, left - 10, top - 26, w + 20, h + 76);
        FloonsLogo.draw(ctx, left, top - 19, 10);
        FloonsTheme.gradientText(ctx, textRenderer, "Item für die Auktion", left + 14, top - 18);
    }

    @Override
    protected void extra(DrawContext ctx, int mouseX, int mouseY) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null) return;
        String hover = null;
        for (int r = 0; r < 4; r++) for (int c = 0; c < COLS; c++) {
            int slot = slotAt(r, c);
            int x = left + c * CELL, y = top + r * CELL + (r == 3 ? 4 : 0);
            boolean in = mouseX >= x && mouseX < x + CELL && mouseY >= y && mouseY < y + CELL;
            FloonsTheme.roundRectExact(ctx, x + 1, y + 1, CELL - 2, CELL - 2, 4, FloonsTheme.alpha(in ? FloonsTheme.BLUE : 0xFFFFFFFF, in ? 0x70 : 0x22));
            ItemStack st = mc.player.getInventory().getStack(slot);
            if (st.isEmpty()) continue;
            ctx.drawItem(st, x + 2, y + 2);
            if (st.getCount() > 1) {
                String n = String.valueOf(st.getCount());
                ctx.drawTextWithShadow(textRenderer, n, x + CELL - 2 - textRenderer.getWidth(n), y + CELL - 9, 0xFFFFFFFF);
            }
            if (in) hover = describe(st);
        }
        String info = hover != null ? hover : "Klick auf ein Item – Hotbar ist unten";
        ctx.drawTextWithShadow(textRenderer, info, width / 2 - textRenderer.getWidth(info) / 2, top + 4 * CELL + 12, hover != null ? FloonsTheme.TEXT : FloonsTheme.MUTED);
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void close() { MinecraftClient.getInstance().setScreen(parent); }
}
