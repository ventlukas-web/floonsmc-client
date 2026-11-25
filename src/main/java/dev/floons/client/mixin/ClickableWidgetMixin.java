package dev.floons.client.mixin;

import dev.floons.client.FloonsConfig;
import dev.floons.client.FloonsScreen;
import dev.floons.client.FloonsTheme;
import dev.floons.client.FloonsTitle;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Alle Vanilla-Buttons und Slider im Floons-Design. */
@Mixin(ClickableWidget.class)
public abstract class ClickableWidgetMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void floons$render(DrawContext ctx, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        try {
            if (!FloonsConfig.get().niceButtons) return;
            Object self = this;
            boolean button = self.getClass() == ButtonWidget.class || self instanceof CyclingButtonWidget;
            boolean slider = self instanceof SliderWidget;
            if (!button && !slider) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.currentScreen instanceof FloonsScreen) return;
            ClickableWidget w = (ClickableWidget) self;
            if (button) FloonsTitle.styledButton(ctx, mc, w, mouseX, mouseY);
            else slider(ctx, mc, w, self instanceof SliderAccessor acc ? acc.floons$getValue() : 0.5, mouseX, mouseY);
            ci.cancel();
        } catch (Throwable ignored) {
        }
    }

    private static void slider(DrawContext ctx, MinecraftClient mc, ClickableWidget w, double value, int mouseX, int mouseY) {
        if (!w.visible) return;
        int x = w.getX(), y = w.getY(), bw = w.getWidth(), bh = w.getHeight();
        boolean hov = w.active && mouseX >= x && mouseX < x + bw && mouseY >= y && mouseY < y + bh;
        int r = Math.min(bh / 2, 10);
        FloonsTheme.roundRectExact(ctx, x, y, bw, bh, r, FloonsTheme.alpha(0xFF040507, w.active ? 0xEC : 0xA0));
        double v = Math.max(0, Math.min(1, value));
        int hw = 8;
        int hx = x + (int) (v * (bw - hw));
        int fillW = Math.max(hw, hx - x + hw);
        FloonsTheme.roundRectExact(ctx, x, y, Math.min(bw, fillW), bh, r,
            FloonsTheme.alpha(FloonsTheme.lerp(FloonsTheme.PURPLE, FloonsTheme.BLUE, 0.65f), hov ? 0xA0 : 0x78));
        FloonsTheme.roundRectExact(ctx, hx, y + 1, hw, bh - 2, Math.min(4, (bh - 2) / 2), FloonsTheme.alpha(hov ? 0xFFFFFFFF : 0xFFE6F2FF, 0xF0));
        FloonsTheme.roundOutlineExact(ctx, x, y, bw, bh, r, FloonsTheme.alpha(FloonsTheme.BLUE, hov ? 0xB0 : 0x60));
        String s = w.getMessage().getString();
        ctx.drawTextWithShadow(mc.textRenderer, s, x + (bw - mc.textRenderer.getWidth(s)) / 2, y + (bh - 8) / 2, w.active ? FloonsTheme.TEXT : 0xFF8A93A8);
    }
}
