package com.baggute.cheatclient.mixin.client;

import net.minecraft.client.gui.components.CommandSuggestions;
import net.minecraft.client.gui.components.EditBox;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.injection.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(CommandSuggestions.class)
public class CommandSuggestionsMixin {
    @Shadow @Final private EditBox input;
    @Inject(method = "updateCommandInfo", at = @At("HEAD"), cancellable = true)
    private void utilityclient$dotSuggestions(CallbackInfo ci) {
        if (com.baggute.cheatclient.util.CommandAutocomplete.update((CommandSuggestions)(Object)this, input)) ci.cancel();
    }
}
