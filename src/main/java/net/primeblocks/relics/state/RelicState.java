package net.primeblocks.relics.state;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.primeblocks.relics.model.RelicOverview;
import net.primeblocks.relics.model.RelicSet;

/**
 * Everything the HUD needs, merged from the two sources this mod has:
 *
 * <ul>
 *   <li>the public API, which knows <em>what</em> is in each set but not which set is worn;</li>
 *   <li>the in-game {@code /slots} menu, which is the only place that reveals the active set
 *       and the player's set names.</li>
 * </ul>
 *
 * <p>Written from HTTP callback threads and the client tick thread, read from the render thread,
 * so every field is volatile or a concurrent collection.
 */
public final class RelicState {
	private volatile RelicOverview overview;
	private volatile String database;
	/** False when the database was guessed rather than read off the server; surfaced in the HUD. */
	private volatile boolean databaseCertain;
	private volatile long lastFetchAt;
	private volatile String lastError;

	private volatile Integer activeSetId;
	/**
	 * The set number the menu showed, kept separately from {@link #activeSetId}: on a citybuild
	 * where the player owns no relics the API returns no sets to resolve against, but the menu
	 * still names the selected slot, and that is worth displaying.
	 */
	private volatile Integer activeSetNumber;
	private volatile ActiveSetSource activeSetSource = ActiveSetSource.UNKNOWN;
	private volatile long activeSetSeenAt;

	private final Map<Integer, String> setNames = new ConcurrentHashMap<>();
	/** Last known active set per database, so switching citybuilds back and forth is seamless. */
	private final Map<String, Remembered> memory = new ConcurrentHashMap<>();

	public RelicOverview overview() {
		return overview;
	}

	public String database() {
		return database;
	}

	public long lastFetchAt() {
		return lastFetchAt;
	}

	public String lastError() {
		return lastError;
	}

	public ActiveSetSource activeSetSource() {
		return activeSetSource;
	}

	public long activeSetSeenAt() {
		return activeSetSeenAt;
	}

	public boolean databaseCertain() {
		return databaseCertain;
	}

	public void onFetched(String database, boolean certain, RelicOverview overview) {
		this.database = database;
		this.databaseCertain = certain;
		this.overview = overview;
		this.lastFetchAt = System.currentTimeMillis();
		this.lastError = null;

		// With a single set there is nothing to choose between, so the menu is not needed.
		List<RelicSet> sets = overview.setsOrEmpty();

		if (sets.size() == 1 && activeSetSource != ActiveSetSource.SLOTS_MENU) {
			this.activeSetId = sets.getFirst().id();
			this.activeSetNumber = sets.getFirst().id();
			this.activeSetSource = ActiveSetSource.ONLY_SET;
		}
	}

	/**
	 * Switches to another citybuild: the old one's relic data is dropped, and whatever was last
	 * seen on the new one is restored.
	 *
	 * <p>The memory is what makes hopping to a farm world and back seamless — that is a full
	 * reconnect, and without it the overlay would fall back to "open /slots" every time even though
	 * nothing about the player's relics changed.
	 */
	public void onDatabaseChanged(String newDatabase) {
		if (database != null) {
			memory.put(database, new Remembered(activeSetNumber, activeSetId, Map.copyOf(setNames)));
		}

		overview = null;
		setNames.clear();

		Remembered remembered = newDatabase != null ? memory.get(newDatabase) : null;

		if (remembered != null) {
			activeSetNumber = remembered.setNumber();
			activeSetId = remembered.setId();
			setNames.putAll(remembered.names());
			// Not read live from the menu, so it stays flagged as a memory.
			activeSetSource = remembered.setNumber() != null
					? ActiveSetSource.REMEMBERED : ActiveSetSource.UNKNOWN;
		} else {
			activeSetNumber = null;
			activeSetId = null;
			activeSetSource = ActiveSetSource.UNKNOWN;
		}

		database = newDatabase;
	}

	/** What was last known about one citybuild, kept so returning to it is instant. */
	private record Remembered(Integer setNumber, Integer setId, Map<Integer, String> names) {
	}

	public void onFetchFailed(String message) {
		this.lastError = message;
		this.lastFetchAt = System.currentTimeMillis();
	}

	/**
	 * Records the set the {@code /slots} menu is currently showing as selected.
	 *
	 * @param menuNumber the 1-based number from the menu tab
	 * @param setId      the matching API set id, or {@code null} when there is nothing to match
	 *                   against (no relics on this citybuild yet)
	 */
	public void onActiveSetObserved(int menuNumber, Integer setId) {
		this.activeSetNumber = menuNumber;
		this.activeSetId = setId;
		this.activeSetSource = ActiveSetSource.SLOTS_MENU;
		this.activeSetSeenAt = System.currentTimeMillis();
	}

	public Integer activeSetNumber() {
		return activeSetNumber;
	}

	/** Called when the {@code /slots} menu closes: what we saw is now a memory, not a live reading. */
	public void onSlotsMenuClosed() {
		if (activeSetSource == ActiveSetSource.SLOTS_MENU) {
			this.activeSetSource = ActiveSetSource.REMEMBERED;
		}
	}

	public void putSetName(int setId, String name) {
		if (name != null && !name.isBlank()) {
			setNames.put(setId, name);
		}
	}

	public String setName(int setId) {
		return setNames.get(setId);
	}

	/** The active set, or {@code null} while the active set is still unknown. */
	public RelicSet activeSet() {
		RelicOverview snapshot = overview;
		Integer id = activeSetId;

		if (snapshot == null || id == null) {
			return null;
		}

		return snapshot.setById(id);
	}

	public Integer activeSetId() {
		return activeSetId;
	}

	/**
	 * Clears the live state for a new session. The per-citybuild memory survives, so reconnecting
	 * — which is what entering a farm world does — restores the overlay instead of blanking it.
	 */
	public void reset() {
		overview = null;
		database = null;
		lastFetchAt = 0L;
		lastError = null;
		activeSetId = null;
		activeSetNumber = null;
		activeSetSource = ActiveSetSource.UNKNOWN;
		activeSetSeenAt = 0L;
		setNames.clear();
	}
}
