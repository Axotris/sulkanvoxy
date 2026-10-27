package org.axotris.sulkanvoxy;

import net.fabricmc.api.ClientModInitializer;
import org.axotris.sulkanvoxy.bridge.BridgeConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SulkanVoxyClient implements ClientModInitializer {
	public static final String MOD_ID = "sulkanvoxy";
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitializeClient() {
		BridgeConfig.load();
		LOGGER.info("Sulkan Voxy bridge loaded (enabled={})", BridgeConfig.get().enabled);
	}
}
