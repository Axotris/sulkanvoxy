package org.axotris.sulkanvoxy.bridge;

import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkImageCopy;
import org.lwjgl.vulkan.VkImageSubresourceRange;

import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Offscreen images the LOD passes write next to Voxy's own colour target, sized
 * like Voxy's viewport (which is the size of Minecraft's framebuffer).
 */
final class LodTargets {
	/** Value {@link #depth} is cleared to: no LOD fragment here. */
	static final float NO_DEPTH = -1.0f;

	final int width;
	final int height;
	/** Vulkan formats of Sulkan's scene targets, in attachment order. */
	final int[] sceneFormats;
	/** LOD values of each Sulkan scene target (e.g. Sulkan Realistic's surface.data). */
	final List<VkImage2D> scene = new ArrayList<>();
	/** Vanilla-projection depth of the nearest LOD fragment. */
	final VkImage2D depth;
	/** LOD colour and depth before translucents: what the pack samples as "Opaque"/"OpaqueDepth" for LOD water. */
	final VkImage2D opaqueColour;
	final VkImage2D opaqueDepth;

	LodTargets(VkFrameCtx ctx, int width, int height, int[] sceneFormats, int colourFormat) {
		this.width = width;
		this.height = height;
		this.sceneFormats = sceneFormats.clone();
		int attachment = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
				| VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
		for (int format : sceneFormats) {
			this.scene.add(new VkImage2D(ctx, width, height, 1, format, attachment, VK_IMAGE_ASPECT_COLOR_BIT, false));
		}
		this.depth = new VkImage2D(ctx, width, height, 1, VK_FORMAT_R32_SFLOAT, attachment, VK_IMAGE_ASPECT_COLOR_BIT, false);
		int snapshot = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		this.opaqueColour = new VkImage2D(ctx, width, height, 1, colourFormat, snapshot, VK_IMAGE_ASPECT_COLOR_BIT, false);
		this.opaqueDepth = new VkImage2D(ctx, width, height, 1, VK_FORMAT_R32_SFLOAT, snapshot, VK_IMAGE_ASPECT_COLOR_BIT, false);
	}

	boolean matches(int width, int height, int[] sceneFormats, int colourFormat) {
		return this.width == width && this.height == height
				&& java.util.Arrays.equals(this.sceneFormats, sceneFormats)
				&& this.opaqueColour.format == colourFormat;
	}

	/** Start of a frame: clear everything the LOD passes write and leave it ready as attachments. */
	void clear(VkFrameCtx ctx) {
		var cmd = ctx.cmd();
		List<VkImage2D> images = new ArrayList<>(this.scene);
		images.add(this.depth);
		var toTransfer = new ArrayList<VkImage2D.BatchEntry>();
		var toAttachment = new ArrayList<VkImage2D.BatchEntry>();
		for (var image : images) {
			toTransfer.add(new VkImage2D.BatchEntry(image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
					VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_WRITE_BIT));
			toAttachment.add(new VkImage2D.BatchEntry(image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
					VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT));
		}
		VkImage2D.transitionBatch(toTransfer,
				VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT,
				VK_PIPELINE_STAGE_TRANSFER_BIT);
		try (MemoryStack stack = stackPush()) {
			var range = VkImageSubresourceRange.calloc(stack)
					.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			var zero = VkClearColorValue.calloc(stack);
			for (var image : this.scene) {
				vkCmdClearColorImage(cmd, image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, zero, range);
			}
			var none = VkClearColorValue.calloc(stack).float32(0, NO_DEPTH);
			vkCmdClearColorImage(cmd, this.depth.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, none, range);
		}
		VkImage2D.transitionBatch(toAttachment, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
	}

	/**
	 * Before LOD translucents: copy the finished opaque LOD colour and depth so LOD
	 * water can refract and absorb what is beneath it, like Sulkan's terrain
	 * snapshot does for vanilla water.
	 */
	void snapshotOpaque(VkFrameCtx ctx, VkImage2D lodColour) {
		var cmd = ctx.cmd();
		VkImage2D.transitionBatch(List.of(
						new VkImage2D.BatchEntry(lodColour, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
								VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT),
						new VkImage2D.BatchEntry(this.depth, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
								VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT),
						new VkImage2D.BatchEntry(this.opaqueColour, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
								VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT),
						new VkImage2D.BatchEntry(this.opaqueDepth, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
								VK_ACCESS_SHADER_READ_BIT, VK_ACCESS_TRANSFER_WRITE_BIT)),
				VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
				VK_PIPELINE_STAGE_TRANSFER_BIT);
		try (MemoryStack stack = stackPush()) {
			var region = VkImageCopy.calloc(1, stack);
			region.srcSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.dstSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).layerCount(1);
			region.extent().set(this.width, this.height, 1);
			vkCmdCopyImage(cmd, lodColour.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
					this.opaqueColour.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
			vkCmdCopyImage(cmd, this.depth.image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
					this.opaqueDepth.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
		}
		VkImage2D.transitionBatch(List.of(
						new VkImage2D.BatchEntry(lodColour, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
								VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT),
						new VkImage2D.BatchEntry(this.depth, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL,
								VK_ACCESS_TRANSFER_READ_BIT, VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT),
						new VkImage2D.BatchEntry(this.opaqueColour, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
								VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT),
						new VkImage2D.BatchEntry(this.opaqueDepth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
								VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT)),
				VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
	}

	/** Before the composite samples them. */
	void toSampled() {
		var entries = new ArrayList<VkImage2D.BatchEntry>();
		for (var image : this.scene) {
			entries.add(new VkImage2D.BatchEntry(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
					VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT));
		}
		entries.add(new VkImage2D.BatchEntry(this.depth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
				VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT));
		VkImage2D.transitionBatch(entries, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
	}

	void free() {
		this.scene.forEach(VkImage2D::free);
		this.depth.free();
		this.opaqueColour.free();
		this.opaqueDepth.free();
	}
}
