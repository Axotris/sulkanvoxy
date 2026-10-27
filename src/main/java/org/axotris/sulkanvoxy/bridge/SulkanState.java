package org.axotris.sulkanvoxy.bridge;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.commands.RenderPassDescriptor;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.textures.GpuTextureView;
import com.sulkan.shaders.graph.GraphRenderer;
import com.sulkan.shaders.graph.GraphShaderSources;
import com.sulkan.shaders.graph.MaterialTable;
import com.sulkan.shaders.graph.NativePackRuntime;
import com.sulkan.shaders.runtime.NativeShaderPreprocessor;
import org.axotris.sulkanvoxy.mixin.sulkan.NativePackRuntimeAccessor;
import org.jspecify.annotations.Nullable;
import org.joml.Vector4fc;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Everything the bridge reads from Sulkan, in one place. Render thread only. */
public final class SulkanState {
	/** A Sulkan terrain replacement program, as the active pack built it for Sodium. */
	public record TerrainProgram(GraphRenderer owner, RenderPipeline pipeline, String fragmentSource) {
	}

	private static @Nullable TerrainProgram opaque;
	private static @Nullable TerrainProgram translucent;
	private static @Nullable TerrainProgram derivedTranslucent;

	private SulkanState() {
	}

	public static @Nullable GraphRenderer activeGraph() {
		if (!NativePackRuntime.usesNativeTransparency()) {
			return null;
		}
		return NativePackRuntimeAccessor.sulkanvoxy$active();
	}

	/**
	 * Called with every pipeline Sulkan hands Sodium's terrain. Native replacements
	 * of solid and translucent terrain are what LODs must be shaded with.
	 */
	public static void captureScenePipeline(RenderPipeline base, RenderPipeline replacement) {
		if (replacement == base) {
			return;
		}
		String path = base.getLocation().getPath();
		boolean isSolid = path.endsWith("solid_terrain");
		boolean isTranslucent = path.endsWith("translucent_terrain");
		if (!isSolid && !isTranslucent) {
			return;
		}
		GraphRenderer graph = activeGraph();
		String fragment = GraphShaderSources.get(replacement, ShaderType.FRAGMENT);
		if (graph == null || fragment == null) {
			// A fallback pipeline (pack paused or failed): not a shader to copy.
			return;
		}
		TerrainProgram current = isSolid ? opaque : translucent;
		if (current != null && current.pipeline == replacement && current.owner == graph) {
			return;
		}
		String source = NativeShaderPreprocessor.injectDefines(fragment, replacement.getShaderDefines());
		TerrainProgram program = new TerrainProgram(graph, replacement, source);
		if (isSolid) {
			opaque = program;
		} else {
			translucent = program;
		}
	}

	/** The pack's terrain program for this frame, or null if Sulkan has not drawn terrain with it yet. */
	public static @Nullable TerrainProgram terrainProgram(boolean translucentPass) {
		GraphRenderer graph = activeGraph();
		if (graph == null) {
			return null;
		}
		if (translucentPass) {
			if (usable(translucent, graph)) {
				return translucent;
			}
			// Sodium only binds its translucent program when vanilla translucent terrain is
			// in view. Until then derive it the way Sulkan does: the solid terrain program
			// with Sulkan's translucent define and Sodium's translucent alpha cutout.
			if (!usable(opaque, graph)) {
				return null;
			}
			if (derivedTranslucent == null || derivedTranslucent.pipeline != opaque.pipeline) {
				String source = opaque.fragmentSource;
				int versionEnd = source.indexOf('\n');
				derivedTranslucent = new TerrainProgram(opaque.owner, opaque.pipeline, source.substring(0, versionEnd + 1)
						+ "#define SULKAN_TRANSLUCENT\n#define ALPHA_CUTOUT 0.01\n" + source.substring(versionEnd + 1));
			}
			return derivedTranslucent;
		}
		return usable(opaque, graph) ? opaque : null;
	}

	private static boolean usable(@Nullable TerrainProgram program, GraphRenderer graph) {
		// Replacements are dropped when the pack reloads; never use a retired one.
		return program != null && program.owner == graph
				&& GraphShaderSources.get(program.pipeline, ShaderType.FRAGMENT) != null;
	}

	/**
	 * Sulkan's extra scene attachments (e.g. Sulkan Realistic's {@code surface.data}),
	 * in the order Sulkan's terrain shaders write them. Obtained by asking Sulkan to
	 * extend a descriptor that targets the main framebuffer, exactly as it does for
	 * Minecraft's own passes.
	 */
	public static List<GpuTextureView> sceneTargets(GraphRenderer graph, RenderTarget main) {
		var probe = new RenderPassDescriptor(
				() -> "sulkanvoxy scene target probe",
				List.of(new RenderPassDescriptor.Attachment<Optional<Vector4fc>>(main.getColorTextureView(), Optional.empty())),
				null,
				new RenderPass.RenderArea(0, 0, main.width, main.height));
		var extended = graph.sceneAttachments(probe, main);
		List<GpuTextureView> targets = new ArrayList<>();
		for (int i = 1; i < extended.colorAttachments().size(); i++) {
			var attachment = extended.colorAttachments().get(i);
			if (attachment == null) {
				throw new IllegalStateException("Sulkan scene attachment " + i + " is unused");
			}
			targets.add(attachment.textureView());
		}
		return targets;
	}

	/** The uniforms Sulkan binds for its own terrain this frame (frame constants, shadows, snapshots). */
	public static Map<String, Object> terrainUniforms(TerrainProgram program) {
		var recorder = new RecordingRenderPass();
		program.owner.bindSceneUniforms(recorder, program.pipeline);
		return recorder.uniforms();
	}

	public static @Nullable MaterialTable materials() {
		return NativePackRuntime.materials();
	}
}
