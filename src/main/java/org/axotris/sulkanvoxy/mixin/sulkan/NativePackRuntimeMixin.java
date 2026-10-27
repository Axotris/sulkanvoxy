package org.axotris.sulkanvoxy.mixin.sulkan;

import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.sulkan.shaders.graph.NativePackRuntime;
import org.axotris.sulkanvoxy.bridge.SulkanState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.List;

/** Remembers the terrain programs Sulkan builds for Sodium, so LODs can be shaded with the same ones. */
@Mixin(value = NativePackRuntime.class, remap = false)
public abstract class NativePackRuntimeMixin {
	@Inject(method = "scenePipeline", at = @At("RETURN"))
	private static void sulkanvoxy$captureTerrainProgram(RenderPipeline base, List<?> attachments,
														 CallbackInfoReturnable<RenderPipeline> cir) {
		SulkanState.captureScenePipeline(base, cir.getReturnValue());
	}
}
