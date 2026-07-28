package net.primeblocks.relics.tracking;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;

import net.primeblocks.relics.RelicService;
import net.primeblocks.relics.model.RelicOverview;
import net.primeblocks.relics.model.RelicSet;
import net.primeblocks.relics.state.RelicState;

/**
 * Watches for the {@code /slots} menu.
 *
 * <p>Two jobs: pick up the player's custom set names, which the API does not carry, and switch the
 * service to its fast poll while the menu is open so swapping a relic shows up right away.
 */
public final class SlotsScreenTracker {
	/** Container contents arrive over several packets; re-reading every tick would be wasteful. */
	private static final int TICKS_BETWEEN_SCANS = 5;

	/** Grace period after closing the menu before the confirming read. */
	private static final long CLOSE_REFRESH_DELAY_MILLIS = 750;

	private final RelicState state;
	private final RelicService service;
	private final SlotsMenuParser parser = new SlotsMenuParser();

	private ContainerSnapshot lastSnapshot;
	private SlotsMenuReading lastReading;
	private Screen attachedScreen;
	private boolean attachedIsRelicMenu;
	private int tickCounter;

	public SlotsScreenTracker(RelicState state, RelicService service) {
		this.state = state;
		this.service = service;
	}

	public void register() {
		// Every container is attached to, because whether this is the relic menu can only be told
		// from its contents — the menu's title carries no readable text. The contents arrive a few
		// ticks after init, so the decision is deferred to the first scan.
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

		lastSnapshot = snapshot;
		lastReading = parser.parse(snapshot);

		if (lastReading.relicMenu() && !attachedIsRelicMenu) {
			attachedIsRelicMenu = true;

			// Opening the menu is a good moment to re-check the citybuild, and it switches the
			// service to its fast poll so equipping a relic — or editing on the website — shows up
			// within a second instead of at the next minute boundary.
			service.setMenuOpen(true);
			service.refreshForMenu();
		}

		if (attachedIsRelicMenu) {
			apply(lastReading);
		}
	}

	private void onRemove(Screen removed) {
		if (attachedScreen != removed) {
			return;
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

	/**
	 * Re-runs the last reading against freshly fetched relic data.
	 *
	 * <p>Only while the menu is actually on screen: once it is closed the active set comes from the
	 * API's own flag, and re-applying a stale menu reading would fight it.
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

		// A null id is still worth recording: on a citybuild where the player owns no relics the
		// API has nothing to match, but the menu's set number is real and gets shown on its own.
		state.onActiveSetObserved(menuNumber, resolveSetId(menuNumber, reading, overview));
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
