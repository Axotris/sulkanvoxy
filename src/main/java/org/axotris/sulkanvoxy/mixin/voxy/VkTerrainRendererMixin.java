package org.axotris.sulkanvoxy.mixin.voxy;

import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkShaderPipeline;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.client.core.vk.render.VkModelStore;
import me.cortex.voxy.client.core.vk.render.VkSectionGeometryData;
import me.cortex.voxy.client.core.vk.render.VkTerrainRenderer;
import me.cortex.voxy.client.core.vk.render.VkViewport;
import org.axotris.sulkanvoxy.bridge.LodBridge;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every LOD draw (opaque, temporal, translucent) goes through renderTerrain; shade it with the pack. */
@Mixin(value = VkTerrainRenderer.class, remap = false)
public abstract class VkTerrainRendererMixin {
	@Shadow @Final private VkFrameCtx ctx;
	@Shadow @Final private VkUploadStream uploadStream;
	@Shadow @Final private RenderProperties properties;
	@Shadow @Final private VkSectionGeometryData geometry;
	@Shadow @Final private VkModelStore modelStore;
	@Shadow @Final private VkBuffer uniform;
	@Shadow @Final private VkBuffer indexBuffer;
	@Shadow @Final private long depthBoundSampler;
	@Shadow @Final private long lightmapSampler;
	@Shadow private VkShaderPipeline terrainTranslucent;

	@Inject(method = "renderTerrain", at = @At("HEAD"), cancellable = true)
	private void sulkanvoxy$renderWithPack(VkViewport viewport, long colorView, VkShaderPipeline pipeline,
										   long indirectOffset, long drawCountOffset, int maxDrawCount, boolean clear,
										   CallbackInfo ci) {
		var models = (VkModelStoreAccessor) this.modelStore;
		var voxy = new LodBridge.VoxyTerrain(this.ctx, this.uploadStream, this.properties, this.uniform,
				this.geometry.geometryBuffer(), models.sulkanvoxy$modelBuffer(), models.sulkanvoxy$modelColourBuffer(),
				models.sulkanvoxy$atlas(), this.modelStore.atlasSampler, this.indexBuffer,
				this.depthBoundSampler, this.lightmapSampler);
		if (LodBridge.renderTerrain(voxy, viewport, colorView, pipeline == this.terrainTranslucent,
				indirectOffset, drawCountOffset, maxDrawCount)) {
			ci.cancel();
		}
	}
}
