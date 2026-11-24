package dev.floons.client.mixin;

import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Setzt den Wert einer Option ohne die 0..1-Pruefung (fuer echtes Fullbright). */
@Mixin(SimpleOption.class)
public interface SimpleOptionAccessor {
    @Accessor("value")
    void floons$setRaw(Object v);
}
