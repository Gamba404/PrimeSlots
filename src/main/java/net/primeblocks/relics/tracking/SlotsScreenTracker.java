package net.primeblocks.relics.tracking;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import net.primeblocks.relics.PrimeRelicsClient;
import net.primeblocks.relics.RelicService;
import net.primeblocks.relics.RelicsConfig;
import net.primeblocks.relics.model.RelicOverview;
import net.primeblocks.relics.model.RelicSet;
import net.primeblocks.relics.state.RelicState;

/**
 * Watches for the {@code /slots} menu and keeps {@link RelicState}'s notion of the active set
 * up to date.
 *
 * <p>This is the half of the mod the API cannot provide: {@code RelicSetResponse} carries an id and
 * its slots, but nothing that says which set the player currently has equipped, and no set names.
 * Opening {@code /slots} is therefore the only moment the client learns either.
 *
 * <p>Everything here runs without player input. A container screen swallows keyboard focus, so no
 * command can be typed while the menu is open — dumps are written as the menu opens, whenever its
 * contents change, and once more on close.
 */
public final class SlotsScreenTracker {
	/** Container contents arrive over several packets; re-reading every tick would be wasteful. */
	private static final int TICKS_BETWEEN_SCANS = 5;

	/** Grace period after closing the menu before the confirming read. */
	private static final long CLOSE_REFRESH_DELAY_MILLIS = 750;

	private final RelicState state;
	private final RelicsConfig config;
	private final DumpWriter dumpWriter;
	private final RelicService service;
	private volatile SlotsMenuParser parser;

	private ContainerSnapshot lastSnapshot;
	private SlotsMenuReading lastReading;
	private Screen attachedScreen;
	private boolean attachedIsRelicMenu;
	private int tickCounter;

	public SlotsScreenTracker(RelicState state, RelicsConfig config, DumpWriter dumpWriter,
			RelicService service) {
		this.state = state;
		this.config = config;
		this.dumpWriter = dumpWriter;
		this.service = service;
		this.parser = new SlotsMenuParser(config);
	}

	/** Rebuilds the compiled patterns after the config changed. */
	public void reloadPatterns() {
		this.parser = new SlotsMenuParser(config);
	}

	public SlotsMenuParser parser() {
		return parser;
	}

	public DumpWriter dumpWriter() {
		return dumpWriter;
	}

	public void register() {
		// Every container is attached to, because whether this is the relic menu can only be told
		// from its contents — the menu's title is a run of private-use glyphs from a custom GUI
		// font and carries no readable text. The contents arrive a few ticks after init, so the
		// decision is deferred to the first scan.
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			if (screen instanceof AbstractContainerScreen<?> containerScreen) {
				attach(containerScreen);
			}
		});
	}

	private void attach(AbstractContainerScreen<?> screen) {
		// AFTER_INIT fires again whenever the window is resized. Re-registering on the same screen
		// would stack duplicate listeners, so only the first init of a given screen attaches.
		if (attachedScreen == screen) {
			return;
		}

		lastSnapshot = null;
		lastReading = null;
		tickCounter = 0;
		attachedScreen = screen;
		attachedIsRelicMenu = false;

		ScreenEvents.afterTick(screen).register(this::onTick);
		ScreenEvents.remove(screen).register(this::onRemove);
	}

	private void onTick(Screen screen) {
		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
			return;
		}

		if (++tickCounter % TICKS_BETWEEN_SCANS != 0) {
			return;
		}

		ContainerSnapshot snapshot = ContainerSnapshot.capture(containerScreen);

		if (snapshot.equals(lastSnapshot)) {
			return;
		}

		boolean first = lastSnapshot == null;
		lastSnapshot = snapshot;
		lastReading = parser.parse(snapshot);

		if (lastReading.relicMenu() && !attachedIsRelicMenu) {
			attachedIsRelicMenu = true;

			if (config.verboseTracking) {
				PrimeRelicsClient.LOGGER.info("Relic menu recognised ({} set tabs, forms {})",
						lastReading.setNames().size(), lastReading.equippedForms());
			}

			// Opening the menu is the moment to re-check which citybuild this is: the player may
			// have switched servers since the last poll. It also switches the service to its fast
			// poll, so equipping a relic — or editing on the website — shows up within seconds
			// instead of at the next minute boundary.
			service.setMenuOpen(true);
			service.refreshForMenu();
		}

		// Non-relic containers are only worth dumping while hunting for the menu.
		if (attachedIsRelicMenu || config.discoveryMode) {
			dump(first ? "open" : "change", snapshot, lastReading);
		}

		if (attachedIsRelicMenu) {
			apply(lastReading);
		}
	}

	private void onRemove(Screen removed) {
		if (attachedScreen != removed) {
			return;
		}

		// The final state matters most: it is what the player left the menu on.
		if (lastSnapshot != null && (attachedIsRelicMenu || config.discoveryMode)) {
			dump("close", lastSnapshot, lastReading);
		}

		boolean wasRelicMenu = attachedIsRelicMenu;
		attachedScreen = null;
		attachedIsRelicMenu = false;

		if (!wasRelicMenu) {
			return;
		}

		service.setMenuOpen(false);
		state.onSlotsMenuClosed();

		// The server may persist the last change as the menu closes, so one delayed read catches
		// what the final in-menu poll could still have missed.
		CompletableFuture.runAsync(service::refreshNow,
				CompletableFuture.delayedExecutor(CLOSE_REFRESH_DELAY_MILLIS, TimeUnit.MILLISECONDS));
	}

	private void dump(String event, ContainerSnapshot snapshot, SlotsMenuReading reading) {
		if (!config.autoDump || dumpWriter.atLimit()) {
			return;
		}

		Path file = dumpWriter.write(event, snapshot, reading);

		if (file != null && config.verboseTracking) {
			PrimeRelicsClient.LOGGER.info("Dumped container ({}) -> {}", event, file.getFileName());
		}
	}

	/**
	 * Re-runs the last reading against freshly fetched relic data.
	 *
	 * <p>Only while the menu is actually on screen: once it is closed the active set is a memory,
	 * and re-applying would advertise it as a live reading again. The refreshed relic data still
	 * reaches the overlay — the set id does not change when its contents do.
	 */
	public void reapplyLastReading() {
		SlotsMenuReading reading = lastReading;

		if (attachedIsRelicMenu && reading != null && reading.relicMenu()) {
			apply(reading);
		}
	}

	private void apply(SlotsMenuReading reading) {
		RelicOverview overview = state.overview();

		for (Map.Entry<Integer, String> entry : reading.setNames().entrySet()) {
			Integer setId = resolveSetId(entry.getKey(), reading, overview);
			state.putSetName(setId != null ? setId : entry.getKey(), entry.getValue());
		}

		if (!reading.hasActiveSet()) {
			return;
		}

		int menuNumber = reading.activeSetNumber();
		Integer setId = resolveSetId(menuNumber, reading, overview);

		// A null id is still worth recording: on a citybuild where the player owns no relics the
		// API has nothing to match, but the menu's set number is real and gets shown on its own.
		state.onActiveSetObserved(menuNumber, setId);

		if (config.verboseTracking) {
			PrimeRelicsClient.LOGGER.info("Active relic set: menu #{} -> API set id {}",
					menuNumber, setId != null ? setId : "none (no relic data for this citybuild)");
		}
	}

	/**
	 * Maps a 1-based set number from the menu onto the set ids the API returned.
	 *
	 * <p>Preferred route is the relic forms on display: if exactly one set holds that combination
	 * of dice, that is the set, whatever it is numbered. Only when that is ambiguous does this fall
	 * back to positional order, which assumes the menu lists sets by ascending id.
	 */
	private Integer resolveSetId(int menuSetNumber, SlotsMenuReading reading, RelicOverview overview) {
		if (overview == null) {
			return null;
		}

		List<RelicSet> sets = overview.setsOrEmpty();

		if (sets.isEmpty()) {
			return null;
		}

		if (Integer.valueOf(menuSetNumber).equals(reading.activeSetNumber())) {
			Integer byForms = matchByForms(reading.equippedForms(), sets);

			if (byForms != null) {
				return byForms;
			}
		}

		List<RelicSet> ordered = new ArrayList<>(sets);
		ordered.sort((a, b) -> Integer.compare(a.id(), b.id()));

		int index = menuSetNumber - 1;
		return index >= 0 && index < ordered.size() ? ordered.get(index).id() : null;
	}

	/** The id of the single set whose relic forms match {@code forms}, or null if not unique. */
	private Integer matchByForms(List<Integer> forms, List<RelicSet> sets) {
		if (forms.isEmpty()) {
			return null;
		}

		List<Integer> needle = forms.stream().sorted().toList();
		Integer match = null;

		for (RelicSet set : sets) {
			List<Integer> candidate = set.formFingerprint().stream()
					.filter(form -> form >= 0)
					.sorted()
					.toList();

			if (!candidate.equals(needle)) {
				continue;
			}

			if (match != null) {
				// Two sets with identical dice — the fingerprint cannot disambiguate them.
				return null;
			}

			match = set.id();
		}

		return match;
	}
}
