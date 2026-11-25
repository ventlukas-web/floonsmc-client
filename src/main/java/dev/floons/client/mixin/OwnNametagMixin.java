package dev.floons.client.mixin;

import dev.floons.client.FloonsConfig;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Eigener Nametag ueber dem Kopf in der 3. Person (Vanilla zeigt ihn dort nicht). */
@Mixin(LivingEntityRenderer.class)
public abstract class OwnNametagMixin {
    @Inject(method = "shouldShowName", at = @At("RETURN"), cancellable = true, require = 0)
    private void floons$ownTag2(LivingEntity entity, double squaredDistanceToCamera, CallbackInfoReturnable<Boolean> cir) { floons$ownTag(entity, squaredDistanceToCamera, cir); }

    @Inject(method = "hasLabel", at = @At("RETURN"), cancellable = true, require = 0)
    private void floons$ownTag(LivingEntity entity, double squaredDistanceToCamera, CallbackInfoReturnable<Boolean> cir) {
        try {
            if (cir.getReturnValueZ() || !FloonsConfig.get().ownNametag) return;
            MinecraftClient mc = MinecraftClient.getInstance();
            if (entity != mc.player || mc.options.hudHidden) return;
            if (mc.options.getPerspective().isFirstPerson()) return;
            cir.setReturnValue(true);
            dev.floons.client.FloonsOwnTag.mixinAt = System.currentTimeMillis();
        } catch (Throwable ignored) {}
    }
}
