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
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.resources.Identifier;

import net.primeblocks.relics.command.RelicsCommands;
import net.primeblocks.relics.hud.RelicHudEditScreen;
import net.primeblocks.relics.hud.RelicHudElement;
import net.primeblocks.relics.hud.RelicOverlayRenderer;
import net.primeblocks.relics.state.RelicState;
import net.primeblocks.relics.tracking.DumpWriter;
import net.primeblocks.relics.tracking.SlotsScreenTracker;

/**
 * Client entrypoint.
 *
 * <p>The mod combines two sources because neither is sufficient alone: the public API knows the
 * contents of every relic set but not which one is worn, and the {@code /slots} menu knows which
 * set is selected but is only readable while it is open. See
 * {@link net.primeblocks.relics.tracking.SlotsScreenTracker}.
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
		DumpWriter dumpWriter = new DumpWriter(config.maxDumpsPerSession);
		SlotsScreenTracker tracker = new SlotsScreenTracker(state, config, dumpWriter, service);
		RelicOverlayRenderer renderer = new RelicOverlayRenderer(state, config);

		// Every fetch may change which set the open menu maps onto, so the tracker re-resolves.
		service.setAfterFetch(tracker::reapplyLastReading);

		tracker.register();
		new RelicsCommands(config, state, service, tracker, renderer).register();

		HudElementRegistry.addLast(HUD_ELEMENT_ID, new RelicHudElement(renderer, config));

		// Both are ordinary key mappings, so they show up under Options -> Controls -> Miscellaneous
		// and can be rebound there. F6/F7 are unbound in vanilla.
		KeyMapping editKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.primerelics.edit_hud", GLFW.GLFW_KEY_F6, KeyMapping.Category.MISC));
		KeyMapping toggleKey = KeyMappingHelper.registerKeyMapping(new KeyMapping(
				"key.primerelics.toggle_hud", GLFW.GLFW_KEY_F7, KeyMapping.Category.MISC));

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			service.tick(client);
			handleKeys(client, editKey, toggleKey, renderer, config);
		});

		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> service.onJoin());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> service.onDisconnect());

		LOGGER.info("PrimeSlots ready — config at {}", RelicsConfig.path());
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

	/**
	 * Copies reloaded settings onto the live config instance, so every component that captured a
	 * reference keeps seeing current values.
	 */
	public static void copyInto(RelicsConfig source, RelicsConfig target) {
		target.hudEnabled = source.hudEnabled;
		target.hudX = source.hudX;
		target.hudY = source.hudY;
		target.hudScale = source.hudScale;
		target.backgroundOpacity = source.backgroundOpacity;
		target.showRelics = source.showRelics;
		target.showStats = source.showStats;
		target.showSynergies = source.showSynergies;
		target.showTotals = source.showTotals;
		target.hideMaluses = source.hideMaluses;
		target.database = source.database;
		target.apiBaseUrl = source.apiBaseUrl;
		target.refreshIntervalSeconds = source.refreshIntervalSeconds;
		target.menuRefreshMillis = source.menuRefreshMillis;
		target.serverAddressFilter = source.serverAddressFilter;
		target.setTabPattern = source.setTabPattern;
		target.activeSetMarkerPattern = source.activeSetMarkerPattern;
		target.lockedSetPattern = source.lockedSetPattern;
		target.unnamedSetPattern = source.unnamedSetPattern;
		target.relicNamePattern = source.relicNamePattern;
		target.verboseTracking = source.verboseTracking;
		target.autoDump = source.autoDump;
		target.discoveryMode = source.discoveryMode;
		target.maxDumpsPerSession = source.maxDumpsPerSession;
	}
}
