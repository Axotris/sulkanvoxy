package org.axotris.sulkanvoxy.mixin.voxy;

import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkImage2D;
import me.cortex.voxy.client.core.vk.render.VkModelStore;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = VkModelStore.class, remap = false)
public interface VkModelStoreAccessor {
	@Accessor("modelBuffer")
	VkBuffer sulkanvoxy$modelBuffer();

	@Accessor("modelColourBuffer")
	VkBuffer sulkanvoxy$modelColourBuffer();

	@Accessor("atlas")
	VkImage2D sulkanvoxy$atlas();
}
