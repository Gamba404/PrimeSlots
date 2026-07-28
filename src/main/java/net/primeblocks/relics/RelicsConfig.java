package net.primeblocks.relics;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;

import net.fabricmc.loader.api.FabricLoader;

import net.primeblocks.relics.api.PrimeApiClient;

/**
 * User-editable settings, stored at {@code config/primerelics.json}.
 *
 * <p>Deliberately not stored here: the JWT from the in-game login flow. It lives in memory for the
 * session only, so the config file never holds a credential.
 */
public final class RelicsConfig {
	private static final String FILE_NAME = "primerelics.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	/** Master switch for the HUD overlay. */
	public boolean hudEnabled = true;

	/** HUD anchor in scaled GUI pixels. Negative values anchor to the right/bottom edge. */
	public int hudX = 4;
	public int hudY = 4;

	/** Overlay scale, 1.0 = normal. Clamped to {@link #MIN_SCALE}..{@link #MAX_SCALE}. */
	public double hudScale = 1.0;

	/**
	 * Alpha of the panel behind the text, 0..255. Zero draws no panel at all, leaving the text
	 * floating over the world.
	 */
	public int backgroundOpacity = 144;

	/** List the equipped relics with their form and tier. */
	public boolean showRelics = true;

	/** Hide every malus, leaving only the bonuses. Applies to relic stats and to the totals. */
	public boolean hideMaluses = false;

	/**
	 * Render each relic's individual stat rolls under it. Off by default: with four relics at four
	 * stats each that is sixteen extra lines, and {@link #showTotals} usually says more.
	 */
	public boolean showStats = false;

	/** Render the two pair synergies of the active set. */
	public boolean showSynergies = true;

	/**
	 * Render the set's effective values — the same figures the menu shows under "Relikt-Werte",
	 * computed client-side so they stay visible after the menu is closed.
	 */
	public boolean showTotals = true;

	/**
	 * Pins the relic database ({@code cb1}/{@code cb2}/{@code cb3}). Leave null so the mod follows
	 * the citybuild you are actually on — a pinned value keeps showing that server's relics after
	 * you switch.
	 */
	public String database = null;

	public String apiBaseUrl = PrimeApiClient.DEFAULT_BASE_URL;

	/** How often relic data is re-fetched from the API while connected. */
	public int refreshIntervalSeconds = 60;

	/**
	 * How often to re-fetch while the {@code /slots} menu is open, in milliseconds. This is when
	 * relics get swapped, so it pays to look often; clamped to at least
	 * {@link #MIN_MENU_REFRESH_MILLIS} so the API is never hammered.
	 */
	public int menuRefreshMillis = 1000;

	/**
	 * Only talk to the API while connected to a server whose address contains this string.
	 * Blank disables the check.
	 */
	public String serverAddressFilter = "primeblocks";

	/**
	 * Matched against item names to find the set tabs. Group 1 is the set's Roman numeral, group 2
	 * its custom name when the player has renamed it.
	 *
	 * <p>The {@code /slots} menu is identified by these tabs rather than by its window title: the
	 * real title is a run of private-use glyphs from a custom GUI font and contains no readable
	 * text, so no title pattern can match it.
	 */
	public String setTabPattern = "^Relikt-Set\\s+([IVX]+)(?::\\s*(.+))?$";

	/**
	 * Matched against a set tab's name and lore to recognise the equipped set. The equipped tab
	 * reads "Dies ist dein aktuell ausgerüstetes Relikt-Set."; an enchantment glint is used as a
	 * fallback signal when this finds nothing.
	 */
	public String activeSetMarkerPattern = "aktuell\\s+ausger(ü|ue)stetes";

	/** Set tabs carrying this in their lore have not been purchased and are skipped. */
	public String lockedSetPattern = "nicht\\s+freigeschaltet";

	/** Set names matching this are placeholders, so the overlay shows "Set II" instead. */
	public String unnamedSetPattern = "unbenannt";

	/** Matched against item names to find equipped relics; group 1 is the die size. */
	public String relicNamePattern = "^D(0|4|6|8|10|12|20)-Relikt\\b";

	/** Log every container the tracker inspects. Useful while calibrating the patterns above. */
	public boolean verboseTracking = false;

	/**
	 * Write container snapshots to {@code primerelics-dumps/} automatically as a menu opens,
	 * changes and closes.
	 *
	 * <p>On by default because there is no other way to capture the menu: a container screen holds
	 * keyboard focus, so {@code /primerelics dump} cannot be typed while it is open.
	 */
	public boolean autoDump = true;

	/**
	 * Also watch and dump containers whose title does not match
	 * {@link #slotsScreenTitlePattern}.
	 *
	 * <p>The title pattern is a guess until a real dump confirms it. Without discovery mode a wrong
	 * guess silently captures nothing. Turn this off once the pattern is known to match.
	 */
	public boolean discoveryMode = true;

	/** Safety cap so a session cannot fill the disk with dumps. */
	public int maxDumpsPerSession = 60;

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static RelicsConfig load() {
		Path file = path();

		if (!Files.exists(file)) {
			RelicsConfig config = new RelicsConfig();
			config.save();
			return config;
		}

		try {
			String json = Files.readString(file, StandardCharsets.UTF_8);
			RelicsConfig config = GSON.fromJson(json, RelicsConfig.class);
			return config != null ? config : new RelicsConfig();
		} catch (IOException | JsonSyntaxException e) {
			PrimeRelicsClient.LOGGER.warn("Could not read {}, falling back to defaults", file, e);
			return new RelicsConfig();
		}
	}

	public void save() {
		Path file = path();

		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (IOException e) {
			PrimeRelicsClient.LOGGER.warn("Could not write {}", file, e);
		}
	}

	public static final double MIN_SCALE = 0.5;
	public static final double MAX_SCALE = 3.0;
	public static final double SCALE_STEP = 0.1;

	public static final int MIN_MENU_REFRESH_MILLIS = 250;

	public int refreshIntervalMillis() {
		return Math.max(10, refreshIntervalSeconds) * 1000;
	}

	/** Poll interval while the relic menu is open, never slower than the idle interval. */
	public int menuRefreshIntervalMillis() {
		return Math.min(Math.max(menuRefreshMillis, MIN_MENU_REFRESH_MILLIS), refreshIntervalMillis());
	}

	public float scale() {
		return (float) Math.clamp(hudScale, MIN_SCALE, MAX_SCALE);
	}

	public int backgroundColour() {
		int alpha = Math.clamp(backgroundOpacity, 0, 255);
		return alpha << 24;
	}

	public boolean hasBackground() {
		return Math.clamp(backgroundOpacity, 0, 255) > 0;
	}
}
