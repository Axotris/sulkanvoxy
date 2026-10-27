package org.axotris.sulkanvoxy.bridge;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.mojang.renderpearl.backend.vulkan.VulkanConst;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuBuffer;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuSampler;
import com.mojang.renderpearl.backend.vulkan.VulkanGpuTextureView;
import com.sulkan.shaders.graph.GraphRenderer;
import me.cortex.voxy.client.core.IVoxyRenderSystemHolder;
import me.cortex.voxy.client.core.RenderProperties;
import me.cortex.voxy.client.core.VoxyRenderSystem;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.MinecraftVkHost;
import me.cortex.voxy.client.core.vk.ShadercCompiler;
import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkCmd;
import me.cortex.voxy.client.core.vk.VkFrameCtx;
import me.cortex.voxy.client.core.vk.VkImage2D;
import me.cortex.voxy.client.core.vk.VkShaderSource;
import me.cortex.voxy.client.core.vk.VkUploadStream;
import me.cortex.voxy.client.core.vk.VulkanContext;
import me.cortex.voxy.client.core.vk.render.VkCompositor;
import me.cortex.voxy.client.core.vk.render.VkFrameHost;
import me.cortex.voxy.client.core.vk.render.VkViewport;
import net.minecraft.client.Minecraft;
import org.axotris.sulkanvoxy.SulkanVoxyClient;
import org.axotris.sulkanvoxy.bridge.LodShaderAssembler.PackResource;
import org.axotris.sulkanvoxy.bridge.LodShaderAssembler.UnsupportedShaderException;
import org.axotris.sulkanvoxy.bridge.vk.BridgePipeline;
import org.axotris.sulkanvoxy.bridge.vk.SpirvBindings;
import org.joml.Matrix4f;
import org.jspecify.annotations.Nullable;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkClearColorValue;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkImageSubresourceRange;
import org.lwjgl.vulkan.VkPhysicalDeviceProperties2;
import org.lwjgl.vulkan.VkPhysicalDevicePushDescriptorPropertiesKHR;
import org.lwjgl.vulkan.VkRect2D;
import org.lwjgl.vulkan.VkRenderingAttachmentInfo;
import org.lwjgl.vulkan.VkRenderingAttachmentInfoKHR;
import org.lwjgl.vulkan.VkRenderingInfoKHR;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdBeginRenderingKHR;
import static org.lwjgl.vulkan.KHRDynamicRendering.vkCmdEndRenderingKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;
import static org.lwjgl.vulkan.VK12.vkCmdDrawIndexedIndirectCount;

/**
 * Renders Voxy's LOD terrain with the active Sulkan pack.
 *
 * <p>Voxy's Vulkan frame is: depth setup, opaque/temporal LOD draws, SSAO,
 * translucent LOD draws, composite into Minecraft's frame. The bridge replaces the
 * three draw kinds and the composite, keeping Voxy's culling, geometry and draw
 * command generation:
 * <ul>
 *   <li>LOD draws run the pack's own terrain fragment shader with the resources
 *       Sulkan binds for its terrain (see {@link LodShaderAssembler}), and write
 *       Sulkan's scene targets plus a vanilla-projection depth next to Voxy's colour;</li>
 *   <li>the composite writes that depth into Minecraft's depth buffer and the LOD
 *       scene-target values into Sulkan's own targets, so Sulkan's post-processing
 *       (atmosphere, AO, reflections, shafts, fog) sees LODs as terrain.</li>
 * </ul>
 * Whenever something it needs is missing the bridge does nothing and Voxy renders
 * that frame itself; a pack it cannot adapt is skipped until the pack changes.
 */
public final class LodBridge {
	private static final int B_SCENE = 0;
	private static final int B_QUADS = 1;
	private static final int B_MODELS = 3;
	private static final int B_MODEL_COLOURS = 4;
	private static final int B_POSITIONS = 5;
	private static final int B_ATLAS = 8;
	private static final int B_DEPTH_BOUND = 10;
	private static final int B_MATERIALS = 11;
	private static final int B_PARAMS = 12;
	private static final int DUMMY_UBO_SIZE = 16384;
	private static final String OPAQUE_SNAPSHOT = "Sulkan $terrainOpaque";
	private static final String OPAQUE_DEPTH_SNAPSHOT = "Sulkan $terrainOpaqueDepth";

	/** What the bridge needs from Voxy's {@code VkTerrainRenderer} for one draw. */
	public record VoxyTerrain(VkFrameCtx ctx, VkUploadStream uploadStream, RenderProperties properties,
							  VkBuffer sceneUniform, VkBuffer quads, VkBuffer models, VkBuffer modelColours,
							  VkImage2D atlas, long atlasSampler, VkBuffer indexBuffer,
							  long depthBoundSampler, long lightmapSampler) {
	}

	private record TerrainKey(RenderPipeline program, boolean translucent, int colourFormat, int depthFormat, List<Integer> sceneFormats) {
	}

	private record TerrainPipeline(BridgePipeline pipeline, List<PackResource> resources) {
	}

	private record CompositeKey(int colourFormat, int depthFormat, List<Integer> sceneFormats) {
	}

	private record Retired(long frame, BridgePipeline pipeline) {
	}

	private static @Nullable LodBridge instance;
	private static @Nullable GraphRenderer failedGraph;
	private static final Set<String> logged = new HashSet<>();

	private final VkFrameCtx ctx;
	private final VulkanContext vk;
	private final int maxPushDescriptors;
	private final MaterialLut materials;
	private final VkBuffer params;
	private final VkBuffer globals;
	private final VkBuffer dummyUbo;
	private final VkImage2D dummyFloat;
	private final VkImage2D dummyUint;
	private final VkImage2D dummySint;
	private final long nearestSampler;
	private boolean dummiesCleared;
	private @Nullable LodTargets targets;
	private final Map<TerrainKey, TerrainPipeline> terrainPipelines = new HashMap<>();
	private @Nullable CompositeKey compositeKey;
	private @Nullable BridgePipeline compositePipeline;
	private final List<Retired> retired = new ArrayList<>();
	private @Nullable ByteBuffer terrainVertex;
	private @Nullable ByteBuffer compositeVertex;

	// Per frame
	private int frameId = Integer.MIN_VALUE;
	private boolean frameUsable;
	private boolean fellBack;
	private List<GpuTextureView> sulkanTargets = List.of();
	private int[] sceneFormats = new int[0];
	private List<GpuFormat> sceneGpuFormats = List.of();
	private final Matrix4f scratch = new Matrix4f();

	private LodBridge(VkFrameCtx ctx) {
		this.ctx = ctx;
		this.vk = ctx.vk();
		this.maxPushDescriptors = queryMaxPushDescriptors(this.vk);
		this.materials = new MaterialLut(ctx);
		this.params = new VkBuffer(ctx, 256);
		this.globals = new VkBuffer(ctx, 256);
		this.dummyUbo = new VkBuffer(ctx, DUMMY_UBO_SIZE).zero();
		int usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
		this.dummyFloat = new VkImage2D(ctx, 1, 1, 1, VK_FORMAT_R8G8B8A8_UNORM, usage, VK_IMAGE_ASPECT_COLOR_BIT, false);
		this.dummyUint = new VkImage2D(ctx, 1, 1, 1, VK_FORMAT_R32_UINT, usage, VK_IMAGE_ASPECT_COLOR_BIT, false);
		this.dummySint = new VkImage2D(ctx, 1, 1, 1, VK_FORMAT_R32_SINT, usage, VK_IMAGE_ASPECT_COLOR_BIT, false);
		this.nearestSampler = VkImage2D.createSampler(this.vk, false, false);
		ctx.addRetireListener(this::destroyRetired);
	}

	// =====================================================================
	// State

	/** Whether LODs are currently rendered through the pack (also gates the far-plane change). */
	public static boolean active() {
		if (!BridgeConfig.get().enabled) {
			return false;
		}
		GraphRenderer graph = SulkanState.activeGraph();
		if (graph == null || graph == failedGraph) {
			return false;
		}
		VoxyRenderSystem voxy = IVoxyRenderSystemHolder.getNullable();
		return voxy != null && voxy.vkCore != null;
	}

	public static float farPlane(float vanilla) {
		return active() ? Math.max(vanilla, BridgeConfig.get().minimumFarPlane) : vanilla;
	}

	private static void disable(GraphRenderer graph, String reason) {
		failedGraph = graph;
		SulkanVoxyClient.LOGGER.error("LOD shading disabled for the current Sulkan pack, Voxy renders LODs itself: {}", reason);
	}

	private static void warnOnce(String message, @Nullable Throwable cause) {
		if (logged.add(message)) {
			if (cause == null) {
				SulkanVoxyClient.LOGGER.warn(message);
			} else {
				SulkanVoxyClient.LOGGER.warn(message, cause);
			}
		}
	}

	private static LodBridge forContext(VkFrameCtx ctx) {
		if (instance == null || instance.ctx != ctx) {
			instance = new LodBridge(ctx);
		}
		return instance;
	}

	/** Voxy's render core is shutting down: release everything made on its device. */
	public static void release(VkFrameCtx ctx) {
		LodBridge bridge = instance;
		if (bridge == null || bridge.ctx != ctx) {
			return;
		}
		instance = null;
		if (MinecraftVkHost.get() == null) {
			// Minecraft's device is already gone; touching it would be a use-after-free.
			return;
		}
		vkDeviceWaitIdle(bridge.vk.device);
		bridge.terrainPipelines.values().forEach(p -> p.pipeline.destroy());
		bridge.terrainPipelines.clear();
		if (bridge.compositePipeline != null) {
			bridge.compositePipeline.destroy();
		}
		bridge.retired.forEach(r -> r.pipeline.destroy());
		bridge.retired.clear();
		if (bridge.targets != null) {
			bridge.targets.free();
		}
		bridge.materials.free();
		bridge.params.free();
		bridge.globals.free();
		bridge.dummyUbo.free();
		bridge.dummyFloat.free();
		bridge.dummyUint.free();
		bridge.dummySint.free();
	}

	private void retire(BridgePipeline pipeline) {
		this.retired.add(new Retired(this.ctx.currentFrame(), pipeline));
	}

	private void destroyRetired(long retiredUpTo) {
		this.retired.removeIf(r -> {
			if (r.frame <= retiredUpTo) {
				r.pipeline.destroy();
				return true;
			}
			return false;
		});
	}

	private static int queryMaxPushDescriptors(VulkanContext vk) {
		try (MemoryStack stack = stackPush()) {
			var push = VkPhysicalDevicePushDescriptorPropertiesKHR.calloc(stack).sType$Default();
			var props = VkPhysicalDeviceProperties2.calloc(stack).sType$Default().pNext(push);
			vkGetPhysicalDeviceProperties2(vk.physicalDevice, props);
			return push.maxPushDescriptors();
		}
	}

	// =====================================================================
	// Frame start (end of Voxy's depth setup pass)

	public static void beginFrame(VkFrameCtx ctx, VkCompositor.VkViewportRT rt) {
		LodBridge bridge = instance != null && instance.ctx == ctx ? instance : null;
		if (bridge != null) {
			bridge.frameUsable = false;
		}
		if (!active()) {
			return;
		}
		forContext(ctx).prepare(rt);
	}

	private void prepare(VkCompositor.VkViewportRT rt) {
		VkViewport viewport = rt.viewport();
		this.fellBack = false;
		this.frameId = viewport.frameId;
		GraphRenderer graph = SulkanState.activeGraph();
		if (graph == null || viewport.width != rt.mcWidth() || viewport.height != rt.mcHeight()) {
			return;
		}

		List<GpuTextureView> sulkan;
		try {
			sulkan = SulkanState.sceneTargets(graph, Minecraft.getInstance().gameRenderer.mainRenderTarget());
		} catch (RuntimeException e) {
			warnOnce("Could not read Sulkan's scene targets; LODs fall back to Voxy shading this frame", e);
			return;
		}
		int[] formats = new int[sulkan.size()];
		List<GpuFormat> gpuFormats = new ArrayList<>();
		for (int i = 0; i < formats.length; i++) {
			GpuTextureView view = sulkan.get(i);
			if (view.getWidth(0) != viewport.width || view.getHeight(0) != viewport.height) {
				return;//Sulkan resizes its targets at scene start; try again next frame
			}
			gpuFormats.add(view.texture().getFormat());
			formats[i] = VulkanConst.toVk(view.texture().getFormat());
		}

		if (this.targets == null || !this.targets.matches(viewport.width, viewport.height, formats, viewport.colourSSAO.format)) {
			if (this.targets != null) {
				this.targets.free();
			}
			this.targets = new LodTargets(this.ctx, viewport.width, viewport.height, formats, viewport.colourSSAO.format);
		}
		if (!this.dummiesCleared) {
			this.clearDummies();
		}
		this.targets.clear(this.ctx);
		this.sulkanTargets = sulkan;
		this.sceneFormats = formats;
		this.sceneGpuFormats = gpuFormats;
		this.frameUsable = true;
	}

	private void clearDummies() {
		var cmd = this.ctx.cmd();
		List<VkImage2D> dummies = List.of(this.dummyFloat, this.dummyUint, this.dummySint);
		var toTransfer = new ArrayList<VkImage2D.BatchEntry>();
		var toSampled = new ArrayList<VkImage2D.BatchEntry>();
		for (var image : dummies) {
			toTransfer.add(new VkImage2D.BatchEntry(image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, VK_ACCESS_TRANSFER_WRITE_BIT));
			toSampled.add(new VkImage2D.BatchEntry(image, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, VK_ACCESS_TRANSFER_WRITE_BIT, VK_ACCESS_SHADER_READ_BIT));
		}
		VkImage2D.transitionBatch(toTransfer, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
		try (MemoryStack stack = stackPush()) {
			var range = VkImageSubresourceRange.calloc(stack).aspectMask(VK_IMAGE_ASPECT_COLOR_BIT).levelCount(1).layerCount(1);
			var zero = VkClearColorValue.calloc(stack);
			for (var image : dummies) {
				vkCmdClearColorImage(cmd, image.image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, zero, range);
			}
		}
		VkImage2D.transitionBatch(toSampled, VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT);
		this.dummiesCleared = true;
	}

	// =====================================================================
	// LOD terrain draws (replace VkTerrainRenderer.renderTerrain)

	/** @return true if the bridge drew this pass and Voxy must not. */
	public static boolean renderTerrain(VoxyTerrain voxy, VkViewport viewport, long colourView, boolean translucent,
										long indirectOffset, long drawCountOffset, int maxDrawCount) {
		LodBridge bridge = instance;
		if (bridge == null || bridge.ctx != voxy.ctx() || !bridge.frameUsable || bridge.frameId != viewport.frameId) {
			return false;
		}
		boolean drawn = bridge.drawTerrain(voxy, viewport, colourView, translucent, indirectOffset, drawCountOffset, maxDrawCount);
		if (!drawn) {
			// Voxy draws this pass itself, so its composite must run as well.
			bridge.fellBack = true;
		}
		return drawn;
	}

	private boolean drawTerrain(VoxyTerrain voxy, VkViewport viewport, long colourView, boolean translucent,
								long indirectOffset, long drawCountOffset, int maxDrawCount) {
		var program = SulkanState.terrainProgram(translucent);
		if (program == null) {
			return false;
		}

		TerrainPipeline pipeline;
		try {
			pipeline = this.terrainPipeline(voxy, program, translucent, viewport);
		} catch (UnsupportedShaderException e) {
			disable(program.owner(), e.getMessage());
			return false;
		}

		Map<String, Object> uniforms;
		try {
			uniforms = SulkanState.terrainUniforms(program);
		} catch (RuntimeException e) {
			warnOnce("Sulkan's terrain resources were not ready for LODs (" + e.getMessage() + "); Voxy shades them this frame", null);
			return false;
		}

		LodTargets targets = this.targets;
		VkCommandBuffer cmd = this.ctx.cmd();
		this.uploadUniforms(voxy.uploadStream(), viewport);
		if (translucent) {
			targets.snapshotOpaque(this.ctx, viewport.colourSSAO);
		}
		//Same dependency Voxy's renderTerrain sets up before its draws
		this.ctx.barrier(VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT,
				VK_ACCESS_SHADER_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT,
				VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_INPUT_BIT,
				VK_ACCESS_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_INDEX_READ_BIT | VK_ACCESS_UNIFORM_READ_BIT);

		try (MemoryStack stack = stackPush()) {
			int count = 2 + targets.scene.size();
			var colour = VkRenderingAttachmentInfoKHR.calloc(count, stack);
			attachment(colour.get(0), colourView, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			for (int i = 0; i < targets.scene.size(); i++) {
				attachment(colour.get(1 + i), targets.scene.get(i).view, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			}
			attachment(colour.get(count - 1), targets.depth.view, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			var depth = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
					.imageView(viewport.depthStencil.view)
					.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
					.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			var stencil = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
					.imageView(viewport.depthStencil.view)
					.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
					.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
					.renderArea(VkRect2D.calloc(stack).extent(e -> e.width(viewport.width).height(viewport.height)))
					.layerCount(1)
					.pColorAttachments(colour)
					.pDepthAttachment(depth)
					.pStencilAttachment(stencil);
			vkCmdBeginRenderingKHR(cmd, info);
		}

		pipeline.pipeline.bind(cmd);
		VkCmd.setViewportScissor(cmd, viewport.width, viewport.height);
		try (var d = pipeline.pipeline.descriptors()) {
			d.buffer(B_SCENE, BridgePipeline.UBO, voxy.sceneUniform().buffer, 0, voxy.sceneUniform().size())
					.buffer(B_QUADS, BridgePipeline.SSBO, voxy.quads().buffer, 0, voxy.quads().size())
					.buffer(B_MODELS, BridgePipeline.SSBO, voxy.models().buffer, 0, voxy.models().size())
					.buffer(B_MODEL_COLOURS, BridgePipeline.SSBO, voxy.modelColours().buffer, 0, voxy.modelColours().size())
					.buffer(B_POSITIONS, BridgePipeline.SSBO, viewport.positionScratchBuffer.buffer, 0, viewport.positionScratchBuffer.size())
					.sampler(B_ATLAS, voxy.atlas().view, voxy.atlasSampler(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
					.sampler(B_DEPTH_BOUND, viewport.depthBoundSampleView, voxy.depthBoundSampler(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			VkBuffer lut = this.materials.buffer(SulkanState.materials());
			d.buffer(B_MATERIALS, BridgePipeline.SSBO, lut.buffer, 0, lut.size())
					.buffer(B_PARAMS, BridgePipeline.UBO, this.params.buffer, 0, 64);
			for (PackResource resource : pipeline.resources) {
				this.bindPackResource(d, resource, uniforms, voxy, translucent);
			}
			d.push(cmd);
		}
		vkCmdBindIndexBuffer(cmd, voxy.indexBuffer().buffer, 0, VK_INDEX_TYPE_UINT16);
		if (this.vk.hasDrawIndirectCount) {
			vkCmdDrawIndexedIndirectCount(cmd, viewport.drawCallBuffer.buffer, indirectOffset,
					viewport.drawCountCallBuffer.buffer, drawCountOffset, maxDrawCount, 5 * 4);
		} else {
			vkCmdDrawIndexedIndirect(cmd, viewport.drawCallBuffer.buffer, indirectOffset, maxDrawCount, 5 * 4);
		}
		vkCmdEndRenderingKHR(cmd);
		return true;
	}

	private static void attachment(VkRenderingAttachmentInfo info, long view, int layout) {
		info.sType$Default().imageView(view).imageLayout(layout)
				.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
	}

	private void uploadUniforms(VkUploadStream upload, VkViewport viewport) {
		long ptr = upload.upload(this.params, 0, 64);
		this.scratch.set(viewport.vanillaProjection).mul(viewport.modelView).getToAddress(ptr);

		// Sodium's u_Globals as the pack's terrain shader declares it (std140).
		ptr = upload.upload(this.globals, 0, 192);
		MemoryUtil.memSet(ptr, 0, 192);
		viewport.vanillaProjection.getToAddress(ptr);
		viewport.modelView.getToAddress(ptr + 64);
		var fog = viewport.fogParameters;
		if (fog != null) {
			MemoryUtil.memPutFloat(ptr + 128, fog.red());
			MemoryUtil.memPutFloat(ptr + 132, fog.green());
			MemoryUtil.memPutFloat(ptr + 136, fog.blue());
			MemoryUtil.memPutFloat(ptr + 140, fog.alpha());
			MemoryUtil.memPutFloat(ptr + 144, fog.environmentalStart());
			MemoryUtil.memPutFloat(ptr + 148, fog.environmentalEnd());
		}
		// Render-distance fog fades vanilla chunks out at their edge; LODs are what
		// lies beyond that edge, so it must never apply to them.
		MemoryUtil.memPutFloat(ptr + 152, Float.MAX_VALUE * 0.5f);
		MemoryUtil.memPutFloat(ptr + 156, Float.MAX_VALUE * 0.5f);
		upload.commit();
	}

	private void bindPackResource(BridgePipeline.Descriptors d, PackResource resource, Map<String, Object> uniforms,
								  VoxyTerrain voxy, boolean translucent) {
		int binding = resource.binding();
		if (resource.kind() == LodShaderAssembler.ResourceKind.UNIFORM_BUFFER) {
			Object value = uniforms.get(resource.name());
			if (resource.name().equals("u_Globals")) {
				d.buffer(binding, BridgePipeline.UBO, this.globals.buffer, 0, 192);
			} else if (value instanceof GpuBufferSlice slice) {
				d.buffer(binding, BridgePipeline.UBO, ((VulkanGpuBuffer) slice.buffer()).vkBuffer(), slice.offset(), slice.length());
			} else if (value instanceof GpuBuffer buffer) {
				d.buffer(binding, BridgePipeline.UBO, ((VulkanGpuBuffer) buffer).vkBuffer(), 0, buffer.size());
			} else {
				d.buffer(binding, BridgePipeline.UBO, this.dummyUbo.buffer, 0, DUMMY_UBO_SIZE);
			}
			return;
		}

		switch (resource.name()) {
			case "u_BlockTex" -> {
				d.sampler(binding, voxy.atlas().view, voxy.atlasSampler(), VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
				return;
			}
			case "u_LightTex" -> {
				d.sampler(binding, VkFrameHost.lightmapView(), voxy.lightmapSampler(), VK_IMAGE_LAYOUT_GENERAL);
				return;
			}
			default -> {
			}
		}
		if (uniforms.get(resource.name()) instanceof RecordingRenderPass.Texture texture) {
			String label = texture.view().texture().getLabel();
			if (OPAQUE_SNAPSHOT.equals(label) || OPAQUE_DEPTH_SNAPSHOT.equals(label)) {
				// Sulkan's snapshot holds vanilla terrain only; LOD water refracts LOD terrain.
				VkImage2D snapshot = !translucent ? this.dummyFloat
						: OPAQUE_SNAPSHOT.equals(label) ? this.targets.opaqueColour : this.targets.opaqueDepth;
				d.sampler(binding, snapshot.view, this.nearestSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			} else {
				d.sampler(binding, ((VulkanGpuTextureView) texture.view()).vkImageView(),
						((VulkanGpuSampler) texture.sampler()).vkSampler(), VK_IMAGE_LAYOUT_GENERAL);
			}
			return;
		}
		VkImage2D dummy = resource.type().startsWith("u") ? this.dummyUint
				: resource.type().startsWith("i") ? this.dummySint : this.dummyFloat;
		d.sampler(binding, dummy.view, this.nearestSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
	}

	private TerrainPipeline terrainPipeline(VoxyTerrain voxy, SulkanState.TerrainProgram program, boolean translucent,
											VkViewport viewport) throws UnsupportedShaderException {
		List<Integer> formats = new ArrayList<>();
		for (int format : this.sceneFormats) {
			formats.add(format);
		}
		var key = new TerrainKey(program.pipeline(), translucent, viewport.colour.format, viewport.depthStencil.format, formats);
		TerrainPipeline cached = this.terrainPipelines.get(key);
		if (cached != null) {
			return cached;
		}
		// A different program or format for this pass kind: the old one is done.
		this.terrainPipelines.entrySet().removeIf(entry -> {
			if (entry.getKey().translucent() == translucent) {
				this.retire(entry.getValue().pipeline());
				return true;
			}
			return false;
		});

		var unknown = LodShaderAssembler.unknownInputs(program.fragmentSource());
		if (!unknown.isEmpty()) {
			warnOnce("Pack terrain inputs without an LOD equivalent are zero on LODs: " + unknown, null);
		}

		Map<String, String> defines = new LinkedHashMap<>();
		boolean reverseZ = voxy.properties().isReverseZ();
		defines.put("SV_IN_FRONT(a, b)", reverseZ ? "((a) > (b))" : "((a) < (b))");
		var cardinal = Minecraft.getInstance().level.cardinalLighting();
		defines.put("SV_NO_SHADE_FACE_TINT", Float.toString(cardinal.up()));
		defines.put("SV_UP_FACE_TINT", Float.toString(cardinal.up()));
		defines.put("SV_DOWN_FACE_TINT", Float.toString(cardinal.down()));
		defines.put("SV_Z_AXIS_FACE_TINT", Float.toString(cardinal.north()));
		defines.put("SV_X_AXIS_FACE_TINT", Float.toString(cardinal.east()));

		var assembled = LodShaderAssembler.assemble(program.fragmentSource(), translucent, this.sceneFormats.length, defines);
		String name = "sulkanvoxy-lod-" + (translucent ? "translucent" : "opaque");
		ByteBuffer fragment = this.compile(assembled.source(), ShaderType.FRAGMENT, name + ".frag");
		ByteBuffer vertex = this.terrainVertex(voxy.properties());

		List<BridgePipeline.Binding> bindings = new ArrayList<>(List.of(
				new BridgePipeline.Binding(B_SCENE, BridgePipeline.UBO),
				new BridgePipeline.Binding(B_QUADS, BridgePipeline.SSBO),
				new BridgePipeline.Binding(B_MODELS, BridgePipeline.SSBO),
				new BridgePipeline.Binding(B_MODEL_COLOURS, BridgePipeline.SSBO),
				new BridgePipeline.Binding(B_POSITIONS, BridgePipeline.SSBO),
				new BridgePipeline.Binding(B_ATLAS, BridgePipeline.SAMPLER),
				new BridgePipeline.Binding(B_DEPTH_BOUND, BridgePipeline.SAMPLER),
				new BridgePipeline.Binding(B_MATERIALS, BridgePipeline.SSBO),
				new BridgePipeline.Binding(B_PARAMS, BridgePipeline.UBO)));
		for (PackResource resource : assembled.resources()) {
			bindings.add(new BridgePipeline.Binding(resource.binding(),
					resource.kind() == LodShaderAssembler.ResourceKind.SAMPLER ? BridgePipeline.SAMPLER : BridgePipeline.UBO));
		}
		if (bindings.size() > this.maxPushDescriptors) {
			throw new UnsupportedShaderException("LOD shader needs " + bindings.size()
					+ " descriptors but this GPU pushes at most " + this.maxPushDescriptors);
		}
		validate(bindings, vertex, fragment);

		Set<Integer> written = new HashSet<>();
		assembled.outputs().forEach(output -> written.add(output.location()));
		var desc = new BridgePipeline.Desc();
		desc.name = name;
		desc.vertexSpirv = vertex;
		desc.fragmentSpirv = fragment;
		desc.bindings = bindings;
		desc.attachments.add(new BridgePipeline.Attachment(viewport.colour.format, translucent, 0xF));
		for (int i = 0; i < this.sceneFormats.length; i++) {
			// Targets the pack does not write keep their cleared value.
			desc.attachments.add(new BridgePipeline.Attachment(this.sceneFormats[i], false, written.contains(1 + i) ? 0xF : 0));
		}
		desc.attachments.add(new BridgePipeline.Attachment(VK_FORMAT_R32_SFLOAT, false, VK_COLOR_COMPONENT_R_BIT));
		desc.depthFormat = viewport.depthStencil.format;
		desc.stencilFormat = viewport.depthStencil.format;
		desc.depthTest = true;
		desc.depthWrite = true;
		desc.depthCompare = VkCmd.closerEqual(voxy.properties());
		desc.stencilEqual1 = true;

		var built = new TerrainPipeline(new BridgePipeline(this.vk, desc), assembled.resources());
		this.terrainPipelines.put(key, built);
		SulkanVoxyClient.LOGGER.info("Built {} LOD pipeline from the Sulkan pack's terrain shader ({} pack resources)",
				translucent ? "translucent" : "opaque", assembled.resources().size());
		return built;
	}

	private ByteBuffer terrainVertex(RenderProperties properties) throws UnsupportedShaderException {
		if (this.terrainVertex == null) {
			String source = VkShaderSource.load("sulkanvoxy:lod/terrain.vert", VkShaderSource.defs().props(properties).build());
			this.terrainVertex = this.compile(source, ShaderType.VERTEX, "sulkanvoxy-lod.vert");
		}
		return this.terrainVertex;
	}

	private ByteBuffer compile(String source, ShaderType type, String name) throws UnsupportedShaderException {
		try {
			return ShadercCompiler.compile(source, type, name);
		} catch (IllegalStateException e) {
			if (BridgeConfig.get().dumpShaderOnError) {
				SulkanVoxyClient.LOGGER.error("Generated source of {}:\n{}", name, numbered(source));
			}
			throw new UnsupportedShaderException(e.getMessage());
		}
	}

	private static String numbered(String source) {
		StringBuilder out = new StringBuilder();
		String[] lines = source.split("\n", -1);
		for (int i = 0; i < lines.length; i++) {
			out.append(String.format("%5d  ", i + 1)).append(lines[i]).append('\n');
		}
		return out.toString();
	}

	/** Every resource the shaders use must be one the bridge binds, with the type it binds. */
	private static void validate(List<BridgePipeline.Binding> bindings, ByteBuffer... modules) throws UnsupportedShaderException {
		Map<Integer, Integer> expected = new HashMap<>();
		bindings.forEach(b -> expected.put(b.binding(), b.type()));
		for (ByteBuffer module : modules) {
			for (var resource : SpirvBindings.scan(module)) {
				Integer type = expected.get(resource.binding());
				if (resource.set() != 0 || type == null || type != resource.type()) {
					throw new UnsupportedShaderException("shader resource at set " + resource.set() + " binding "
							+ resource.binding() + " is not one the bridge provides");
				}
			}
		}
	}

	// =====================================================================
	// Composite (replaces VkCompositor.composite)

	/** @return true if the bridge composited (or deliberately skipped) and Voxy must not. */
	public static boolean composite(VkFrameCtx ctx, RenderProperties properties, VkCompositor.VkViewportRT rt) {
		LodBridge bridge = instance;
		if (bridge == null || bridge.ctx != ctx || !bridge.frameUsable || bridge.fellBack
				|| bridge.frameId != rt.viewport().frameId) {
			return false;
		}
		try {
			return bridge.compositeFrame(properties, rt);
		} catch (UnsupportedShaderException e) {
			GraphRenderer graph = SulkanState.activeGraph();
			if (graph != null) {
				disable(graph, e.getMessage());
			}
			return false;
		}
	}

	private boolean compositeFrame(RenderProperties properties, VkCompositor.VkViewportRT rt) throws UnsupportedShaderException {
		VkViewport viewport = rt.viewport();
		if (viewport.fogParameters != null && viewport.fogParameters.environmentalEnd() < VoxyRenderSystem.getVanillaRenderDistance()) {
			return true;//fog hides everything past vanilla terrain, as in Voxy's composite
		}
		int colourFormat = VkFrameHost.vkFormat(rt.mcColour());
		int depthFormat = VkFrameHost.vkFormat(rt.mcDepth());
		BridgePipeline pipeline = this.compositePipeline(properties, colourFormat, depthFormat);
		LodTargets targets = this.targets;
		VkCommandBuffer cmd = this.ctx.cmd();

		targets.toSampled();
		viewport.colourSSAO.transition(VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
				VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT,
				VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT, VK_ACCESS_SHADER_READ_BIT);

		try (MemoryStack stack = stackPush()) {
			int count = 1 + this.sulkanTargets.size();
			var colour = VkRenderingAttachmentInfoKHR.calloc(count, stack);
			// Minecraft's attachments in the layout Voxy's own composite uses for them.
			attachment(colour.get(0), VkFrameHost.vkView(rt.mcColour()), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
			for (int i = 0; i < this.sulkanTargets.size(); i++) {
				// Minecraft keeps its own textures (Sulkan's targets included) in GENERAL.
				attachment(colour.get(1 + i), ((VulkanGpuTextureView) this.sulkanTargets.get(i)).vkImageView(), VK_IMAGE_LAYOUT_GENERAL);
			}
			var depth = VkRenderingAttachmentInfoKHR.calloc(stack).sType$Default()
					.imageView(VkFrameHost.vkView(rt.mcDepth()))
					.imageLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
					.loadOp(VK_ATTACHMENT_LOAD_OP_LOAD).storeOp(VK_ATTACHMENT_STORE_OP_STORE);
			var info = VkRenderingInfoKHR.calloc(stack).sType$Default()
					.renderArea(VkRect2D.calloc(stack).extent(e -> e.width(rt.mcWidth()).height(rt.mcHeight())))
					.layerCount(1)
					.pColorAttachments(colour)
					.pDepthAttachment(depth);
			vkCmdBeginRenderingKHR(cmd, info);
		}
		pipeline.bind(cmd);
		VkCmd.setViewportScissor(cmd, rt.mcWidth(), rt.mcHeight());
		try (var d = pipeline.descriptors()) {
			d.sampler(0, viewport.colourSSAO.view, this.nearestSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL)
					.sampler(1, targets.depth.view, this.nearestSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			for (int i = 0; i < targets.scene.size(); i++) {
				d.sampler(2 + i, targets.scene.get(i).view, this.nearestSampler, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
			}
			d.push(cmd);
		}
		vkCmdDraw(cmd, 3, 1, 0, 0);
		vkCmdEndRenderingKHR(cmd);
		// Minecraft resumes rendering into these images (Sodium's water writes Sulkan's
		// targets next) and only synchronises after its own passes.
		this.ctx.barrier(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT,
				VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT,
				VK_PIPELINE_STAGE_ALL_COMMANDS_BIT, VK_ACCESS_MEMORY_READ_BIT | VK_ACCESS_MEMORY_WRITE_BIT);
		return true;
	}

	private BridgePipeline compositePipeline(RenderProperties properties, int colourFormat, int depthFormat)
			throws UnsupportedShaderException {
		List<Integer> formats = new ArrayList<>();
		for (int format : this.sceneFormats) {
			formats.add(format);
		}
		var key = new CompositeKey(colourFormat, depthFormat, formats);
		if (key.equals(this.compositeKey) && this.compositePipeline != null) {
			return this.compositePipeline;
		}
		if (this.compositePipeline != null) {
			this.retire(this.compositePipeline);
			this.compositePipeline = null;
		}

		StringBuilder declarations = new StringBuilder();
		StringBuilder writes = new StringBuilder();
		List<BridgePipeline.Binding> bindings = new ArrayList<>(List.of(
				new BridgePipeline.Binding(0, BridgePipeline.SAMPLER),
				new BridgePipeline.Binding(1, BridgePipeline.SAMPLER)));
		for (int i = 0; i < this.sceneFormats.length; i++) {
			String prefix = glslPrefix(this.sceneGpuFormats.get(i));
			declarations.append("layout(set = 0, binding = ").append(2 + i).append(") uniform ").append(prefix)
					.append("sampler2D sv_target").append(i).append(";\n")
					.append("layout(location = ").append(1 + i).append(") out ").append(prefix)
					.append("vec4 sv_out").append(i).append(";\n");
			writes.append("    sv_out").append(i).append(" = texelFetch(sv_target").append(i).append(", pixel, 0);\n");
			bindings.add(new BridgePipeline.Binding(2 + i, BridgePipeline.SAMPLER));
		}
		// Reverse-Z: keep LOD depth above Sulkan's "this pixel is sky" threshold (1e-7).
		String depthRange = properties.isReverseZ()
				? "#define SV_MIN_DEPTH 1.5e-7\n#define SV_MAX_DEPTH 1.0\n"
				: "#define SV_MIN_DEPTH 0.0\n#define SV_MAX_DEPTH 0.9999999\n";
		String fragmentSource = LodShaderAssembler.resource("composite.fsh")
				.replaceFirst("#version 450\n", "#version 450\n" + depthRange)
				.replace("SV_TARGET_DECLARATIONS", declarations.toString())
				.replace("SV_TARGET_WRITES", writes.toString());
		if (this.compositeVertex == null) {
			this.compositeVertex = this.compile(LodShaderAssembler.resource("composite.vsh"), ShaderType.VERTEX, "sulkanvoxy-composite.vert");
		}
		ByteBuffer fragment = this.compile(fragmentSource, ShaderType.FRAGMENT, "sulkanvoxy-composite.frag");
		validate(bindings, this.compositeVertex, fragment);

		var desc = new BridgePipeline.Desc();
		desc.name = "sulkanvoxy-composite";
		desc.vertexSpirv = this.compositeVertex;
		desc.fragmentSpirv = fragment;
		desc.bindings = bindings;
		desc.attachments.add(new BridgePipeline.Attachment(colourFormat, true, 0xF));
		for (int format : this.sceneFormats) {
			desc.attachments.add(new BridgePipeline.Attachment(format, false, 0xF));
		}
		desc.depthFormat = depthFormat;
		desc.depthTest = true;
		desc.depthWrite = true;
		desc.depthCompare = VkCmd.closerEqual(properties);

		this.compositePipeline = new BridgePipeline(this.vk, desc);
		this.compositeKey = key;
		return this.compositePipeline;
	}

	private static String glslPrefix(GpuFormat format) {
		String name = format.name();
		return name.endsWith("_UINT") ? "u" : name.endsWith("_SINT") ? "i" : "";
	}
}
