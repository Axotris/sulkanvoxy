package org.axotris.sulkanvoxy.bridge.vk;

import me.cortex.voxy.client.core.vk.VulkanContext;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkGraphicsPipelineCreateInfo;
import org.lwjgl.vulkan.VkPipelineColorBlendAttachmentState;
import org.lwjgl.vulkan.VkPipelineColorBlendStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDepthStencilStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineDynamicStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineInputAssemblyStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineLayoutCreateInfo;
import org.lwjgl.vulkan.VkPipelineMultisampleStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRasterizationStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineRenderingCreateInfoKHR;
import org.lwjgl.vulkan.VkPipelineShaderStageCreateInfo;
import org.lwjgl.vulkan.VkPipelineVertexInputStateCreateInfo;
import org.lwjgl.vulkan.VkPipelineViewportStateCreateInfo;
import org.lwjgl.vulkan.VkShaderModuleCreateInfo;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.KHRPushDescriptor.vkCmdPushDescriptorSetKHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * A graphics pipeline over one push-descriptor set with any number of colour
 * attachments and per-attachment blending, for dynamic rendering. Voxy's own
 * {@code VkShaderPipeline} covers a single colour attachment only.
 */
public final class BridgePipeline {
	public static final int UBO = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
	public static final int SSBO = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
	public static final int SAMPLER = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;

	public record Binding(int binding, int type) {
	}

	/** Colour attachment: format, alpha blending (Voxy's src-alpha/one-minus-src-alpha), write mask. */
	public record Attachment(int format, boolean blend, int writeMask) {
	}

	public static final class Desc {
		public String name;
		public ByteBuffer vertexSpirv;
		public ByteBuffer fragmentSpirv;
		public List<Binding> bindings = new ArrayList<>();
		public List<Attachment> attachments = new ArrayList<>();
		public int depthFormat = VK_FORMAT_UNDEFINED;
		public int stencilFormat = VK_FORMAT_UNDEFINED;
		public boolean depthTest;
		public boolean depthWrite;
		public int depthCompare = VK_COMPARE_OP_ALWAYS;
		/** Stencil EQUAL 1, keep: Voxy's "only where vanilla terrain is absent" mask. */
		public boolean stencilEqual1;
		public int topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
	}

	private final VulkanContext vk;
	private final long setLayout;
	private final long pipelineLayout;
	private final long pipeline;
	private final long vertexModule;
	private final long fragmentModule;

	public BridgePipeline(VulkanContext vk, Desc d) {
		this.vk = vk;
		try (MemoryStack stack = stackPush()) {
			this.vertexModule = module(vk, d.vertexSpirv, stack);
			this.fragmentModule = module(vk, d.fragmentSpirv, stack);

			var layoutBindings = VkDescriptorSetLayoutBinding.calloc(d.bindings.size(), stack);
			for (int i = 0; i < d.bindings.size(); i++) {
				var b = d.bindings.get(i);
				layoutBindings.get(i).binding(b.binding()).descriptorType(b.type()).descriptorCount(1)
						.stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
			}
			var setInfo = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
					.flags(VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR)
					.pBindings(layoutBindings);
			var handle = stack.mallocLong(1);
			check(vkCreateDescriptorSetLayout(vk.device, setInfo, null, handle), "vkCreateDescriptorSetLayout");
			this.setLayout = handle.get(0);

			var layoutInfo = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default().pSetLayouts(stack.longs(this.setLayout));
			check(vkCreatePipelineLayout(vk.device, layoutInfo, null, handle), "vkCreatePipelineLayout");
			this.pipelineLayout = handle.get(0);

			var stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
			stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(this.vertexModule).pName(stack.UTF8("main"));
			stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(this.fragmentModule).pName(stack.UTF8("main"));

			var vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
			var inputAssembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default().topology(d.topology);
			var dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
					.pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
			var viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default().viewportCount(1).scissorCount(1);
			var raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
					.polygonMode(VK_POLYGON_MODE_FILL).cullMode(VK_CULL_MODE_NONE)
					.frontFace(VK_FRONT_FACE_COUNTER_CLOCKWISE).lineWidth(1);
			var multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
					.rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);

			var depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default()
					.depthTestEnable(d.depthTest).depthWriteEnable(d.depthWrite).depthCompareOp(d.depthCompare);
			if (d.stencilEqual1) {
				depth.stencilTestEnable(true);
				depth.front().failOp(VK_STENCIL_OP_KEEP).passOp(VK_STENCIL_OP_KEEP).depthFailOp(VK_STENCIL_OP_KEEP)
						.compareOp(VK_COMPARE_OP_EQUAL).compareMask(0xFF).writeMask(0).reference(1);
				depth.back(depth.front());
			}

			var blendStates = VkPipelineColorBlendAttachmentState.calloc(d.attachments.size(), stack);
			var formats = stack.mallocInt(d.attachments.size());
			for (int i = 0; i < d.attachments.size(); i++) {
				var a = d.attachments.get(i);
				var state = blendStates.get(i).colorWriteMask(a.writeMask()).blendEnable(a.blend());
				if (a.blend()) {
					state.srcColorBlendFactor(VK_BLEND_FACTOR_SRC_ALPHA)
							.dstColorBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
							.colorBlendOp(VK_BLEND_OP_ADD)
							.srcAlphaBlendFactor(VK_BLEND_FACTOR_ONE)
							.dstAlphaBlendFactor(VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA)
							.alphaBlendOp(VK_BLEND_OP_ADD);
				}
				formats.put(i, a.format());
			}
			var blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default().pAttachments(blendStates);

			var rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack).sType$Default()
					.colorAttachmentCount(d.attachments.size())
					.pColorAttachmentFormats(formats)
					.depthAttachmentFormat(d.depthFormat)
					.stencilAttachmentFormat(d.stencilFormat);

			var info = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
					.pNext(rendering)
					.pStages(stages)
					.pVertexInputState(vertexInput)
					.pInputAssemblyState(inputAssembly)
					.pViewportState(viewport)
					.pRasterizationState(raster)
					.pMultisampleState(multisample)
					.pDepthStencilState(depth)
					.pColorBlendState(blend)
					.pDynamicState(dynamic)
					.layout(this.pipelineLayout);
			check(vkCreateGraphicsPipelines(vk.device, VK_NULL_HANDLE, info, null, handle), "vkCreateGraphicsPipelines(" + d.name + ")");
			this.pipeline = handle.get(0);
		}
	}

	private static long module(VulkanContext vk, ByteBuffer spirv, MemoryStack stack) {
		var info = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
		var handle = stack.mallocLong(1);
		check(vkCreateShaderModule(vk.device, info, null, handle), "vkCreateShaderModule");
		return handle.get(0);
	}

	static void check(int result, String what) {
		if (result != VK_SUCCESS) {
			throw new IllegalStateException(what + " failed: VkResult " + result);
		}
	}

	public void bind(VkCommandBuffer cmd) {
		vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.pipeline);
	}

	public Descriptors descriptors() {
		return new Descriptors(this);
	}

	/** Destroys immediately; the caller guarantees no in-flight frame still uses it. */
	public void destroy() {
		vkDestroyPipeline(this.vk.device, this.pipeline, null);
		vkDestroyPipelineLayout(this.vk.device, this.pipelineLayout, null);
		vkDestroyDescriptorSetLayout(this.vk.device, this.setLayout, null);
		vkDestroyShaderModule(this.vk.device, this.vertexModule, null);
		vkDestroyShaderModule(this.vk.device, this.fragmentModule, null);
	}

	/** Collects descriptor writes for one draw and pushes them. Render thread scratch use. */
	public static final class Descriptors implements AutoCloseable {
		private final BridgePipeline owner;
		private final MemoryStack stack = stackPush();
		private final List<VkWriteDescriptorSet> writes = new ArrayList<>();

		private Descriptors(BridgePipeline owner) {
			this.owner = owner;
		}

		public Descriptors buffer(int binding, int type, long buffer, long offset, long range) {
			var info = VkDescriptorBufferInfo.calloc(1, this.stack).buffer(buffer).offset(offset).range(range);
			this.writes.add(VkWriteDescriptorSet.calloc(this.stack).sType$Default()
					.dstBinding(binding).descriptorType(type).descriptorCount(1).pBufferInfo(info));
			return this;
		}

		public Descriptors sampler(int binding, long view, long sampler, int layout) {
			var info = VkDescriptorImageInfo.calloc(1, this.stack).imageView(view).sampler(sampler).imageLayout(layout);
			this.writes.add(VkWriteDescriptorSet.calloc(this.stack).sType$Default()
					.dstBinding(binding).descriptorType(SAMPLER).descriptorCount(1).pImageInfo(info));
			return this;
		}

		public void push(VkCommandBuffer cmd) {
			var all = VkWriteDescriptorSet.calloc(this.writes.size(), this.stack);
			for (int i = 0; i < this.writes.size(); i++) {
				all.put(i, this.writes.get(i));
			}
			vkCmdPushDescriptorSetKHR(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS, this.owner.pipelineLayout, 0, all);
		}

		@Override
		public void close() {
			this.stack.close();
		}
	}
}
