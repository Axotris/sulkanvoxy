package org.axotris.sulkanvoxy.bridge;

import it.unimi.dsi.fastutil.objects.Object2IntOpenHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Handed to Voxy's model factory as its "custom block state id" mapping (the hook
 * Voxy uses for Iris block ids). Every baked LOD model then carries the vanilla
 * block-state id of its block, and {@link MaterialLut} turns that into the active
 * pack's Sulkan material on the GPU, so changing packs never needs a rebake.
 */
public final class StateIdMapping extends Object2IntOpenHashMap<BlockState> {
	@Override
	public boolean containsKey(Object key) {
		return key instanceof BlockState;
	}

	@Override
	public int getInt(Object key) {
		return key instanceof BlockState state ? Block.getId(state) : 0;
	}
}
