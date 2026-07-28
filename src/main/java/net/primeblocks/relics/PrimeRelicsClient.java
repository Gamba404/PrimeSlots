package net.primeblocks.relics;

import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;

import net.primeblocks.relics.auth.AuthManager;
import net.primeblocks.relics.auth.TokenStore;
import net.primeblocks.relics.hud.RelicHudEditScreen;
import net.primeblocks.relics.hud.RelicHudElement;
import net.primeblocks.relics.hud.RelicOverlayRenderer;
import net.primeblocks.relics.state.RelicState;
import net.primeblocks.relics.tracking.SlotsScreenTracker;

/**
 * Client entrypoint.
 *
 * <p>Everything runs by itself: the account links on the first join, the citybuild is detected from
 * the server, and relic data is polled from the API. The mod registers no commands — the only thing
 * a player ever types is the server's own {@code /login <PIN>}, once.
 */
public final class PrimeRelicsClient implements ClientModInitializer {
	public static final String MOD_ID = "primeslots";
	public static final Logger LOGGER = LoggerFactory.getLogger("PrimeSlots");

	private static final Identifier HUD_ELEMENT_ID =
			Identifier.fromNamespaceAndPath(MOD_ID, "relic_overlay");

	@Override
	public void onInitializeClient() {
		RelicsConfig config = RelicsConfig.load();
		RelicState state = new RelicState();
		RelicService service = new RelicService(config, state);

		AuthManager auth = new AuthManager(TokenStore.load(), service::client);
		auth.setOnAuthenticated(service::refreshNow);
		service.setAuthManager(auth);

		SlotsScreenTracker tracker = new SlotsScreenTracker(state, service);
		RelicOverlayRenderer renderer = new RelicOverlayRenderer(state, config);

		// Every fetch may change which set the open menu maps onto, so the tracker re-resolves.
		service.setAfterFetch(tracker::reapplyLastReading);
		tracker.register();

		HudElementRegistry.addLast(HUD_ELEMENT_ID, new RelicHudElement(renderer, config));

		// Ordinary key mappings, so they appear under Options -> Controls -> Miscellaneous and can
		// be rebound there. F6/F7 are unbound in vanilla.
		KeyMapping editKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.primeslots.edit_hud", GLFW.GLFW_KEY_F6, KeyMapping.Category.MISC));
		KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.primeslots.toggle_hud", GLFW.GLFW_KEY_F7, KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			auth.tick(client);
			service.tick(client);
			handleKeys(client, editKey, toggleKey, renderer, config);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> service.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
			service.onDisconnect();
			auth.onDisconnect();
		});
	}

	private static void handleKeys(Minecraft client, KeyMapping editKey, KeyMapping toggleKey,
			RelicOverlayRenderer renderer, RelicsConfig config) {
		// consumeClick drains the queue, so both have to run to completion even when the press is
		// ignored — otherwise the click resurfaces the next time a screen closes.
		boolean edit = drain(editKey);
		boolean toggle = drain(toggleKey);

		if (client.player == null || client.gui.screen() != null) {
			return;
		}

		if (toggle) {
			config.hudEnabled = !config.hudEnabled;
			config.save();
		}

		if (edit) {
			client.gui.setScreen(new RelicHudEditScreen(renderer, config));
		}
	}

	private static boolean drain(KeyMapping key) {
		boolean pressed = false;

		while (key.consumeClick()) {
			pressed = true;
		}

		return pressed;
	}
}
