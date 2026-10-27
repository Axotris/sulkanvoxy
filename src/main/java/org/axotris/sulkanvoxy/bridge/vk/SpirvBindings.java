package org.axotris.sulkanvoxy.bridge.vk;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
import static org.lwjgl.vulkan.VK10.VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;

/**
 * Minimal SPIR-V reflection: the descriptor set, binding and descriptor type of
 * every resource variable in a module. Used to prove a generated shader only uses
 * bindings the bridge actually fills, before it reaches the driver.
 */
public final class SpirvBindings {
	public record Resource(int set, int binding, int type) {
	}

	private static final int OP_TYPE_IMAGE = 25;
	private static final int OP_TYPE_SAMPLER = 26;
	private static final int OP_TYPE_SAMPLED_IMAGE = 27;
	private static final int OP_TYPE_ARRAY = 28;
	private static final int OP_TYPE_RUNTIME_ARRAY = 29;
	private static final int OP_TYPE_STRUCT = 30;
	private static final int OP_TYPE_POINTER = 32;
	private static final int OP_VARIABLE = 59;
	private static final int OP_DECORATE = 71;

	private static final int DECORATION_BUFFER_BLOCK = 3;
	private static final int DECORATION_BINDING = 33;
	private static final int DECORATION_DESCRIPTOR_SET = 34;

	private static final int STORAGE_UNIFORM_CONSTANT = 0;
	private static final int STORAGE_UNIFORM = 2;
	private static final int STORAGE_STORAGE_BUFFER = 12;

	/** Descriptor type of a resource the bridge does not bind (storage images, separate samplers, arrays). */
	public static final int UNSUPPORTED = -1;

	private SpirvBindings() {
	}

	public static Set<Resource> scan(ByteBuffer spirv) {
		IntBuffer words = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
		Map<Integer, Integer> bindings = new HashMap<>();
		Map<Integer, Integer> sets = new HashMap<>();
		Set<Integer> bufferBlocks = new HashSet<>();
		Map<Integer, Integer> typeOpcodes = new HashMap<>();
		Map<Integer, int[]> pointers = new HashMap<>();//id -> {storage class, pointee}
		Map<Integer, int[]> variables = new HashMap<>();//id -> {pointer type, storage class}

		int position = 5;//header
		while (position < words.limit()) {
			int first = words.get(position);
			int count = first >>> 16;
			int opcode = first & 0xFFFF;
			if (count == 0) {
				throw new IllegalArgumentException("Malformed SPIR-V at word " + position);
			}
			switch (opcode) {
				case OP_DECORATE -> {
					int target = words.get(position + 1);
					int decoration = words.get(position + 2);
					if (decoration == DECORATION_BINDING) bindings.put(target, words.get(position + 3));
					if (decoration == DECORATION_DESCRIPTOR_SET) sets.put(target, words.get(position + 3));
					if (decoration == DECORATION_BUFFER_BLOCK) bufferBlocks.add(target);
				}
				case OP_TYPE_IMAGE, OP_TYPE_SAMPLER, OP_TYPE_SAMPLED_IMAGE, OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY, OP_TYPE_STRUCT ->
						typeOpcodes.put(words.get(position + 1), opcode);
				case OP_TYPE_POINTER -> pointers.put(words.get(position + 1),
						new int[]{words.get(position + 2), words.get(position + 3)});
				case OP_VARIABLE -> variables.put(words.get(position + 2),
						new int[]{words.get(position + 1), words.get(position + 3)});
				default -> {
				}
			}
			position += count;
		}

		Set<Resource> resources = new HashSet<>();
		for (var entry : variables.entrySet()) {
			int id = entry.getKey();
			int storage = entry.getValue()[1];
			if (storage != STORAGE_UNIFORM_CONSTANT && storage != STORAGE_UNIFORM && storage != STORAGE_STORAGE_BUFFER) {
				continue;
			}
			if (!bindings.containsKey(id)) {
				continue;
			}
			int[] pointer = pointers.get(entry.getValue()[0]);
			int pointee = pointer == null ? -1 : pointer[1];
			int pointeeOp = typeOpcodes.getOrDefault(pointee, -1);
			int type;
			if (storage == STORAGE_STORAGE_BUFFER || (storage == STORAGE_UNIFORM && bufferBlocks.contains(pointee))) {
				type = VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
			} else if (storage == STORAGE_UNIFORM) {
				type = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
			} else if (pointeeOp == OP_TYPE_SAMPLED_IMAGE) {
				type = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
			} else {
				type = UNSUPPORTED;
			}
			resources.add(new Resource(sets.getOrDefault(id, 0), bindings.get(id), type));
		}
		return resources;
	}
}
