package org.axotris.sulkanvoxy.mixin.voxy;

import it.unimi.dsi.fastutil.objects.Object2IntMap;
import me.cortex.voxy.client.core.model.ModelFactory;
import net.minecraft.world.level.block.state.BlockState;
import org.axotris.sulkanvoxy.bridge.BridgeConfig;
import org.axotris.sulkanvoxy.bridge.StateIdMapping;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Bake the vanilla block-state id into every LOD model, so LODs can look up their Sulkan material. */
@Mixin(value = ModelFactory.class, remap = false)
public abstract class ModelFactoryMixin {
	@Shadow
	private Object2IntMap<BlockState> customBlockStateIdMapping;

	@Inject(method = "<init>", at = @At("RETURN"))
	private void sulkanvoxy$installStateIds(CallbackInfo ci) {
		// Iris installs its own block id mapping later when it is present; leave it alone then.
		if (BridgeConfig.get().enabled && this.customBlockStateIdMapping == null) {
			this.customBlockStateIdMapping = new StateIdMapping();
		}
	}
}
