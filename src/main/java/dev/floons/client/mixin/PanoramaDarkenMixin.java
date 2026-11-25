package dev.floons.client.mixin;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Panorama-Hintergrund (Hauptmenue usw.) dunkler, mit dunkelblauem Ton. */
@Mixin(Screen.class)
public abstract class PanoramaDarkenMixin {
    @Inject(method = "renderPanoramaBackground", at = @At("TAIL"), require = 0)
    private void floons$darken(DrawContext ctx, float delta, CallbackInfo ci) {
        if (!dev.floons.client.FloonsConfig.get().niceButtons || dev.floons.client.FloonsConfig.get().allOff) return;
        Screen s = (Screen) (Object) this;
        ctx.fillGradient(0, 0, s.width, s.height, 0xA003060D, 0xC8020814);
    }
}
