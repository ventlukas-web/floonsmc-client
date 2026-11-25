package dev.floons.client.mixin;

import dev.floons.client.FloonsConfig;
import net.minecraft.client.render.fog.FogRenderer;
import org.joml.Vector4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** NoFog: schiebt Start und Ende des Nebels (Welt, Wasser, Lava, Pulverschnee, Himmel, Wolken) ins Unendliche. */
@Mixin(FogRenderer.class)
public abstract class FogRendererMixin {
    private static final String M = "applyFog(Ljava/nio/ByteBuffer;ILorg/joml/Vector4f;FFFFFF)V";
    private static final float FAR = 1.0e7f;

    private static float far(float orig, boolean end) {
        try {
            if (FloonsConfig.get().noFog && !FloonsConfig.get().allOff) return end ? FAR + 1f : FAR;
        } catch (Throwable ignored) {}
        return orig;
    }

    @ModifyVariable(method = M, at = @At("HEAD"), argsOnly = true, ordinal = 0, require = 0)
    private float floons$envStart(float v) { return far(v, false); }

    @ModifyVariable(method = M, at = @At("HEAD"), argsOnly = true, ordinal = 1, require = 0)
    private float floons$envEnd(float v) { return far(v, true); }

    @ModifyVariable(method = M, at = @At("HEAD"), argsOnly = true, ordinal = 2, require = 0)
    private float floons$rdStart(float v) { return far(v, false); }

    @ModifyVariable(method = M, at = @At("HEAD"), argsOnly = true, ordinal = 3, require = 0)
    private float floons$rdEnd(float v) { return far(v, true); }

    @ModifyVariable(method = M, at = @At("HEAD"), argsOnly = true, ordinal = 4, require = 0)
    private float floons$skyEnd(float v) { return far(v, true); }

    @ModifyVariable(method = M, at = @At("HEAD"), argsOnly = true, ordinal = 5, require = 0)
    private float floons$cloudEnd(float v) { return far(v, true); }
}
