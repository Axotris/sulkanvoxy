package org.axotris.sulkanvoxy.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParseException;
import net.fabricmc.loader.api.FabricLoader;
import org.axotris.sulkanvoxy.SulkanVoxyClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** {@code config/sulkanvoxy.json}. Read once at startup; edit and restart to change. */
public final class BridgeConfig {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static BridgeConfig instance = new BridgeConfig();

	/** Master switch. When false Voxy renders LODs exactly as it would without this mod. */
	public boolean enabled = true;

	/**
	 * Vanilla's projection ends at 4x the render distance, which clamps every LOD
	 * pixel's depth onto that plane: Sulkan's fog, mist and AO then think all LODs
	 * sit at render distance. While the bridge is active the far plane is raised
	 * to at least this many blocks. Reverse-Z float depth keeps near precision.
	 */
	public float minimumFarPlane = 262144.0f;

	/** Log the generated LOD shader source when it fails to compile. */
	public boolean dumpShaderOnError = true;

	public static BridgeConfig get() {
		return instance;
	}

	public static void load() {
		Path path = FabricLoader.getInstance().getConfigDir().resolve(SulkanVoxyClient.MOD_ID + ".json");
		if (Files.isRegularFile(path)) {
			try {
				BridgeConfig loaded = GSON.fromJson(Files.readString(path), BridgeConfig.class);
				if (loaded != null) {
					instance = loaded;
				}
			} catch (IOException | JsonParseException e) {
				SulkanVoxyClient.LOGGER.error("Could not read {}, using defaults", path, e);
			}
		}
		try {
			Files.createDirectories(path.getParent());
			Files.writeString(path, GSON.toJson(instance));
		} catch (IOException e) {
			SulkanVoxyClient.LOGGER.warn("Could not write {}", path, e);
		}
	}

	private BridgeConfig() {
	}
}
