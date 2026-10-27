package org.axotris.sulkanvoxy.bridge;

import com.sulkan.shaders.graph.MaterialTable;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import net.minecraft.world.level.block.Block;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryUtil;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_STORAGE_BUFFER_BIT;

/**
 * GPU table from vanilla block-state id to the active pack's Sulkan material id
 * (what Sulkan writes into Sodium's vertices for the same block). Rebuilt only
 * when Sulkan swaps its material table, i.e. on pack or option changes.
 */
final class MaterialLut {
	private final VkFrameCtx ctx;
	private @Nullable VkBuffer buffer;
	private @Nullable MaterialTable source;
	private boolean built;

	MaterialLut(VkFrameCtx ctx) {
		this.ctx = ctx;
	}

	VkBuffer buffer(@Nullable MaterialTable table) {
		if (!this.built || table != this.source) {
			this.rebuild(table);
		}
		return this.buffer;
	}

	private void rebuild(@Nullable MaterialTable table) {
		int states = Math.max(1, Block.BLOCK_STATE_REGISTRY.size());
		// Host-visible and written once: the old buffer may still be read by frames in
		// flight, so it is replaced rather than overwritten (free() is deferred).
		var next = new VkBuffer(this.ctx, states * 4L, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT, true);
		long ptr = next.map();
		for (int id = 0; id < states; id++) {
			var state = Block.BLOCK_STATE_REGISTRY.byId(id);
			int material = table == null || state == null ? 0 : table.id(state);
			MemoryUtil.memPutInt(ptr + id * 4L, material);
		}
		if (this.buffer != null) {
			this.buffer.free();
		}
		this.buffer = next;
		this.source = table;
		this.built = true;
	}

	void free() {
		if (this.buffer != null) {
			this.buffer.free();
			this.buffer = null;
		}
		this.built = false;
	}
}
