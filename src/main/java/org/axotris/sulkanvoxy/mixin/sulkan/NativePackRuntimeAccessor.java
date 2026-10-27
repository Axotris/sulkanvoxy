package org.axotris.sulkanvoxy.mixin.sulkan;

import com.sulkan.shaders.graph.GraphRenderer;
import com.sulkan.shaders.graph.NativePackRuntime;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(value = NativePackRuntime.class, remap = false)
public interface NativePackRuntimeAccessor {
	@Accessor("active")
	static GraphRenderer sulkanvoxy$active() {
		throw new AssertionError();
	}
}
