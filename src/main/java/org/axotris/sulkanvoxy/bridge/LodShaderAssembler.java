package org.axotris.sulkanvoxy.bridge;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a Sulkan pack's terrain fragment shader into a fragment shader for Voxy's
 * LOD quads.
 *
 * <p>The pack shader is kept intact and runs as-is; only its interface is adapted:
 * <ul>
 *   <li>its {@code main} becomes {@code sv_packMain}, called by a bridge entry point
 *       (appended, see {@code terrain_bridge.fsh}) that decodes Voxy's quad;</li>
 *   <li>its vertex inputs become plain globals the entry point fills in with the
 *       values Sulkan's terrain vertex shader would have produced;</li>
 *   <li>its uniforms get explicit bindings in Voxy's push-descriptor set, from
 *       {@link #FIRST_PACK_BINDING} on;</li>
 *   <li>{@code gl_FragCoord.z} reads vanilla-projection depth, so depth the pack
 *       stores matches the depth the composite writes;</li>
 *   <li>atlas sampling returns Voxy's pre-filtered model texture.</li>
 * </ul>
 */
final class LodShaderAssembler {
	static final int FIRST_PACK_BINDING = 16;

	enum ResourceKind { SAMPLER, UNIFORM_BUFFER }

	/** A pack uniform, rebound at {@code binding}. {@code type} is the GLSL type for samplers. */
	record PackResource(String name, ResourceKind kind, String type, int binding) {
	}

	/** A fragment output the pack declares. */
	record Output(int location, String type, String name) {
	}

	record Result(String source, List<PackResource> resources, List<Output> outputs, int depthLocation) {
	}

	/** Thrown when the pack shader uses something the bridge cannot provide to LODs. */
	static final class UnsupportedShaderException extends Exception {
		UnsupportedShaderException(String message) {
			super(message);
		}
	}

	/**
	 * Inputs of Sulkan's terrain fragment ABI (Sulkan Realistic's terrain.vsh
	 * outputs) and the bridge value standing in for each. Anything else is zeroed.
	 */
	private static final Map<String, String> KNOWN_INPUTS = Map.of(
			"tint", "sv_tint",
			"uv", "sv_uv",
			"relativePosition", "sv_relPos",
			"lightLevel", "sv_lightLevel",
			"fade", "1.0",
			"materialId", "sv_material",
			"terrainMaterial", "sv_sodiumMaterial");

	private static final Pattern MAIN = Pattern.compile("\\bvoid\\s+main\\s*\\(\\s*(?:void\\s*)?\\)");
	private static final Pattern INPUT = Pattern.compile(
			"^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|sample|highp|mediump|lowp)\\s+)*in\\s+(\\w+)\\s+(\\w+)\\s*(\\[[^\\]]*\\])?\\s*;(.*)$");
	private static final Pattern OUTPUT = Pattern.compile(
			"^\\s*(?:layout\\s*\\(([^)]*)\\)\\s*)?(?:(?:flat|smooth|noperspective|centroid|sample|highp|mediump|lowp)\\s+)*out\\s+(\\w+)\\s+(\\w+)\\s*;.*$");
	private static final Pattern OPAQUE_UNIFORM = Pattern.compile(
			"^(\\s*)(?:layout\\s*\\(([^)]*)\\)\\s*)?uniform\\s+(?:(?:highp|mediump|lowp)\\s+)?(\\w*(?:sampler|image|texture)\\w*)\\s+(\\w+)\\s*;(.*)$");
	private static final Pattern BLOCK_UNIFORM = Pattern.compile(
			"^(\\s*)(?:layout\\s*\\(([^)]*)\\)\\s*)?uniform\\s+(\\w+)\\s*(\\{.*)?$");
	private static final Pattern BUFFER_BLOCK = Pattern.compile(
			"^\\s*(?:layout\\s*\\([^)]*\\)\\s*)?(?:(?:readonly|writeonly|restrict|coherent|volatile)\\s+)*buffer\\s+\\w+");
	private static final Pattern LOCATION = Pattern.compile("\\blocation\\s*=\\s*(\\d+)");
	private static final Pattern FRAG_COORD = Pattern.compile("\\bgl_FragCoord\\b");
	private static final Pattern ATLAS_SAMPLER = Pattern.compile("\\bvec4\\s+sulkanSampleTerrainMaterial\\s*\\(");

	private LodShaderAssembler() {
	}

	/**
	 * @param packSource  pack fragment source with Sulkan's and the pipeline's defines already injected
	 * @param translucent whether this is the translucent LOD pass
	 * @param sceneTargets number of Sulkan scene attachments after the colour output
	 * @param defines     extra bridge defines (face tints, depth convention)
	 */
	static Result assemble(String packSource, boolean translucent, int sceneTargets, Map<String, String> defines)
			throws UnsupportedShaderException {
		// Pack and Sulkan include files mix CRLF and LF endings.
		String[] lines = packSource.replace("\r\n", "\n").replace('\r', '\n').split("\n", -1);
		if (lines.length == 0 || !lines[0].trim().startsWith("#version")) {
			throw new UnsupportedShaderException("pack fragment shader does not start with #version");
		}

		Map<String, String> inputs = new LinkedHashMap<>();
		List<Output> outputs = new ArrayList<>();
		Map<String, PackResource> resources = new LinkedHashMap<>();
		int lastExtension = 0;

		for (int i = 0; i < lines.length; i++) {
			String line = lines[i];
			String trimmed = line.trim();
			if (trimmed.startsWith("#")) {
				if (trimmed.startsWith("#extension")) {
					lastExtension = i;
				}
				continue;
			}

			Matcher input = INPUT.matcher(line);
			if (input.matches()) {
				if (input.group(3) != null) {
					throw new UnsupportedShaderException("array fragment input " + input.group(2));
				}
				inputs.put(input.group(2), input.group(1));
				lines[i] = input.group(1) + " " + input.group(2) + ";" + input.group(4);
				continue;
			}

			Matcher output = OUTPUT.matcher(line);
			if (output.matches()) {
				Matcher location = output.group(1) == null ? null : LOCATION.matcher(output.group(1));
				if (location == null || !location.find()) {
					throw new UnsupportedShaderException("fragment output " + output.group(3) + " has no explicit location");
				}
				outputs.add(new Output(Integer.parseInt(location.group(1)), output.group(2), output.group(3)));
				continue;
			}

			if (BUFFER_BLOCK.matcher(line).find()) {
				throw new UnsupportedShaderException("storage buffers in the terrain fragment shader");
			}

			Matcher opaque = OPAQUE_UNIFORM.matcher(line);
			if (opaque.matches()) {
				String type = opaque.group(3);
				if (!type.contains("sampler") || type.contains("Buffer") || type.contains("Shadow") || type.contains("MS")) {
					throw new UnsupportedShaderException("uniform type " + type + " (" + opaque.group(4) + ")");
				}
				PackResource resource = resources.computeIfAbsent(opaque.group(4),
						name -> new PackResource(name, ResourceKind.SAMPLER, type, FIRST_PACK_BINDING + resources.size()));
				lines[i] = opaque.group(1) + "layout(" + qualifiers(opaque.group(2), resource.binding()) + ") uniform "
						+ type + " " + resource.name() + ";" + opaque.group(5);
				continue;
			}

			Matcher block = BLOCK_UNIFORM.matcher(line);
			if (block.matches()) {
				if (block.group(2) != null && block.group(2).contains("push_constant")) {
					throw new UnsupportedShaderException("push constants in the terrain fragment shader");
				}
				PackResource resource = resources.computeIfAbsent(block.group(3),
						name -> new PackResource(name, ResourceKind.UNIFORM_BUFFER, "", FIRST_PACK_BINDING + resources.size()));
				lines[i] = block.group(1) + "layout(" + qualifiers(block.group(2), resource.binding()) + ") uniform "
						+ resource.name() + " " + (block.group(4) == null ? "" : block.group(4));
			}
		}

		int depthLocation = 1 + sceneTargets;
		Output colour = null;
		for (Output output : outputs) {
			if (output.location() >= depthLocation) {
				throw new UnsupportedShaderException("fragment output " + output.name() + " at location " + output.location()
						+ " but Sulkan only has " + sceneTargets + " scene target(s)");
			}
			if (output.location() == 0) {
				colour = output;
			}
		}
		if (colour == null || !colour.type().equals("vec4")) {
			throw new UnsupportedShaderException("no vec4 colour output at location 0");
		}

		StringBuilder body = new StringBuilder();
		for (int i = 0; i < lines.length; i++) {
			body.append(lines[i]).append('\n');
			if (i == lastExtension) {
				body.append(preamble(translucent, depthLocation, defines));
			}
		}

		String source = body.toString();
		Matcher main = MAIN.matcher(source);
		if (!main.find()) {
			throw new UnsupportedShaderException("no main() in the terrain fragment shader");
		}
		source = main.replaceFirst("void sv_packMain()");
		source = FRAG_COORD.matcher(source).replaceAll("sv_FragCoord");

		Matcher atlas = ATLAS_SAMPLER.matcher(source);
		if (atlas.find()) {
			// Sulkan samples the block atlas with Sodium's UVs; LOD faces live in
			// Voxy's model atlas and are already filtered by the entry point.
			source = atlas.replaceFirst(Matcher.quoteReplacement(
					"vec4 sulkanSampleTerrainMaterial(sampler2D sv_atlas, vec2 sv_atlasUv, uint sv_atlasMaterial) { return sv_LodAlbedo; }\n"
							+ "vec4 sv_packSampleTerrainMaterial("));
		}

		StringBuilder assign = new StringBuilder();
		for (var entry : inputs.entrySet()) {
			String value = KNOWN_INPUTS.getOrDefault(entry.getKey(), "0");
			assign.append("    ").append(entry.getKey()).append(" = ").append(entry.getValue())
					.append('(').append(value).append(");\n");
		}

		String entryPoint = resource("terrain_bridge.fsh")
				.replace("SV_ASSIGN_INPUTS", assign.toString())
				.replace("SV_COLOR_OUTPUT", colour.name());

		return new Result(source + "\n" + entryPoint, List.copyOf(resources.values()), List.copyOf(outputs), depthLocation);
	}

	/** Pack inputs the bridge has no value for; logged so pack authors know what LODs lack. */
	static List<String> unknownInputs(String packSource) {
		List<String> unknown = new ArrayList<>();
		for (String line : packSource.replace("\r\n", "\n").split("\n")) {
			Matcher input = INPUT.matcher(line);
			if (input.matches() && !KNOWN_INPUTS.containsKey(input.group(2))) {
				unknown.add(input.group(2));
			}
		}
		return unknown;
	}

	private static String preamble(boolean translucent, int depthLocation, Map<String, String> defines) {
		StringBuilder out = new StringBuilder("// ---- Sulkan Voxy bridge ----\n");
		if (translucent) {
			out.append("#define SV_TRANSLUCENT\n");
		}
		out.append("#define SV_DEPTH_LOCATION ").append(depthLocation).append('\n');
		defines.forEach((name, value) -> out.append("#define ").append(name).append(' ').append(value).append('\n'));
		out.append("vec4 sv_FragCoord;\n");
		out.append("vec4 sv_LodAlbedo;\n");
		out.append("// ---- pack source ----\n");
		return out.toString();
	}

	/** Existing layout qualifiers minus any set/binding, plus the bridge's own. */
	private static String qualifiers(String existing, int binding) {
		StringBuilder out = new StringBuilder();
		if (existing != null) {
			for (String qualifier : existing.split(",")) {
				String q = qualifier.trim();
				if (q.isEmpty() || q.startsWith("binding") || q.startsWith("set")) {
					continue;
				}
				out.append(q).append(", ");
			}
		}
		return out.append("set = 0, binding = ").append(binding).toString();
	}

	static String resource(String name) {
		String path = "/assets/sulkanvoxy/shaders/lod/" + name;
		try (InputStream in = LodShaderAssembler.class.getResourceAsStream(path)) {
			if (in == null) {
				throw new IllegalStateException("Missing bridge shader " + path);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}
}
