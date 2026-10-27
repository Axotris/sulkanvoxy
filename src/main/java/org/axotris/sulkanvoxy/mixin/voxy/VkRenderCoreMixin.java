package org.axotris.sulkanvoxy.mixin.voxy;

import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.render.VkRenderCore;
import org.axotris.sulkanvoxy.bridge.LodBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VkRenderCore.class, remap = false)
public abstract class VkRenderCoreMixin {
	@Shadow
	@Final
	private VkFrameCtx frameCtx;

	/** Free the bridge's GPU objects before Voxy tears down the frame context they were made with. */
	@Inject(method = "shutdown", at = @At("HEAD"))
	private void sulkanvoxy$releaseBridge(CallbackInfo ci) {
		LodBridge.release(this.frameCtx);
	}
}
