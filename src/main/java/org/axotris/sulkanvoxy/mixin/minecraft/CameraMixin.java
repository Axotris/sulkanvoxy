package org.axotris.sulkanvoxy.mixin.minecraft;

import net.minecraft.client.Camera;
import net.minecraft.client.DeltaTracker;
import org.axotris.sulkanvoxy.bridge.LodBridge;
import org.objectweb.asm.Opcodes;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla's projection ends at 4x the render distance. Voxy composites LOD depth
 * into that projection, clamped onto the far plane, so Sulkan reconstructs every
 * LOD pixel at render distance. While LODs are shaded by the pack, push the far
 * plane past the LODs so their depth, and Sulkan's fog and AO, are correct.
 */
@Mixin(Camera.class)
public abstract class CameraMixin {
	@Shadow
	private float depthFar;

	@Inject(method = "update", at = @At(value = "FIELD", target = "Lnet/minecraft/client/Camera;depthFar:F",
			opcode = Opcodes.PUTFIELD, shift = At.Shift.AFTER))
	private void sulkanvoxy$extendFarPlane(DeltaTracker deltaTracker, CallbackInfo ci) {
		this.depthFar = LodBridge.farPlane(this.depthFar);
	}
}
