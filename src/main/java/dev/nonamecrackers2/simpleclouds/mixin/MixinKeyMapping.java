package dev.nonamecrackers2.simpleclouds.mixin;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Migrate only the invalid unbound key persisted by earlier versions of this port. */
@Mixin(KeyMapping.class)
public abstract class MixinKeyMapping {
    @Shadow public abstract String getName();

    @ModifyVariable(method = "setKey", at = @At("HEAD"), argsOnly = true, require = 1)
    private InputConstants.Key simpleclouds$migrateLegacyUnbound(InputConstants.Key key) {
        if (getName().equals("simpleclouds.key.openConfig")
                && key.getType() == InputConstants.Type.KEYBOARD && key.getValue() == -1) {
            return InputConstants.UNKNOWN;
        }
        return key;
    }
}
