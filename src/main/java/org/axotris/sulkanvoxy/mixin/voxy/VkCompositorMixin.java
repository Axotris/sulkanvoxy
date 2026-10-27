package org.axotris.sulkanvoxy.mixin.voxy;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.render.VkCompositor;
import org.axotris.sulkanvoxy.bridge.LodBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(value = VkCompositor.class, remap = false)
public abstract class VkCompositorMixin {
	@Shadow
	@Final
	private VkFrameCtx ctx;

	@Shadow
	@Final
	private RenderProperties properties;

	/** Voxy's frame starts here: prepare the bridge's targets for this frame. */
	@Inject(method = "setupDepthStencil", at = @At("TAIL"))
	private void sulkanvoxy$beginFrame(VkCompositor.VkViewportRT rt, CallbackInfo ci) {
		LodBridge.beginFrame(this.ctx, rt);
	}

	@Inject(method = "composite", at = @At("HEAD"), cancellable = true)
	private void sulkanvoxy$composite(VkCompositor.VkViewportRT rt, CallbackInfo ci) {
		if (LodBridge.composite(this.ctx, this.properties, rt)) {
			ci.cancel();
		}
	}
}
