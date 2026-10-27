package org.axotris.sulkanvoxy.bridge;

import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.buffers.GpuBufferSlice;
import com.mojang.renderpearl.api.commands.GpuQueryPool;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.pipeline.CompiledRenderPipeline;
import com.mojang.renderpearl.api.pipeline.IndexType;
import com.mojang.renderpearl.api.textures.GpuSampler;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import org.jspecify.annotations.Nullable;
import org.lwjgl.PointerBuffer;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * A render pass that draws nothing and only remembers its uniforms.
 *
 * <p>Sulkan binds everything its terrain shaders need (frame constants, shadow
 * cascades, scene snapshots) through {@code GraphRenderer.bindSceneUniforms}.
 * Handing it this pass yields exactly the resources Sulkan would bind for its own
 * terrain this frame, which the bridge then binds to Voxy's raw Vulkan pipeline.
 */
final class RecordingRenderPass implements RenderPass {
	record Texture(GpuTextureView view, GpuSampler sampler) {
	}

	private final Map<String, Object> uniforms = new LinkedHashMap<>();

	/** Name to {@link Texture}, {@link GpuBufferSlice} or {@link GpuBuffer}. */
	Map<String, Object> uniforms() {
		return this.uniforms;
	}

	@Override
	public void setUniform(String name, @Nullable GpuTextureView textureView, @Nullable GpuSampler sampler) {
		if (textureView != null && sampler != null) {
			this.uniforms.put(name, new Texture(textureView, sampler));
		}
	}

	@Override
	public void setUniform(String name, GpuBuffer value) {
		this.uniforms.put(name, value);
	}

	@Override
	public void setUniform(String name, GpuBufferSlice value) {
		this.uniforms.put(name, value);
	}

	@Override
	public void pushDebugGroup(Supplier<String> label) {
	}

	@Override
	public void popDebugGroup() {
	}

	@Override
	public void writeTimestamp(GpuQueryPool pool, int index) {
	}

	@Override
	public void setPipeline(CompiledRenderPipeline pipeline) {
	}

	@Override
	public void pushConstants(ByteBuffer value) {
	}

	@Override
	public void enableScissor(int x, int y, int width, int height) {
	}

	@Override
	public void disableScissor() {
	}

	@Override
	public void setVertexBuffer(int slot, @Nullable GpuBufferSlice vertexBuffer) {
	}

	@Override
	public void setIndexBuffer(GpuBuffer indexBuffer, IndexType indexType) {
	}

	@Override
	public void drawIndexed(int indexCount, int instanceCount, int firstIndex, int vertexOffset, int firstInstance) {
	}

	@Override
	public void multiDrawIndexed(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
	}

	@Override
	public void multiDrawIndexed(PointerBuffer firstIndexOffsets, IntBuffer indexCounts, IntBuffer vertexOffsets, int drawCount) {
	}

	@Override
	public void drawIndexedIndirect(GpuBufferSlice commands, int drawCount) {
	}

	@Override
	public <T> void drawMultipleIndexed(Collection<Draw<T>> draws, @Nullable GpuBuffer defaultIndexBuffer,
										@Nullable IndexType defaultIndexType, Collection<String> dynamicUniforms, T uniformArgument) {
	}

	@Override
	public void draw(int vertexCount, int instanceCount, int firstVertex, int firstInstance) {
	}

	@Override
	public void multiDraw(IntBuffer drawParameters, int instanceCount, int firstInstance, int drawCount) {
	}

	@Override
	public void multiDraw(IntBuffer firstVertices, IntBuffer vertexCounts, int drawCount) {
	}

	@Override
	public void drawIndirect(GpuBufferSlice commands, int drawCount) {
	}

	@Override
	public void close() {
	}
}
