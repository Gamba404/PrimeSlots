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
 * User settings, stored at {@code config/primeslots.json}.
 *
 * <p>Everything here is reachable from the in-game editor (F6); the file exists so settings
 * survive restarts, not as the place people are expected to work. The API token is deliberately
 * kept elsewhere — see {@link net.primeblocks.relics.auth.TokenStore}.
 */
public final class RelicsConfig {
	private static final String FILE_NAME = "primeslots.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	public static final double MIN_SCALE = 0.5;
	public static final double MAX_SCALE = 3.0;
	public static final double SCALE_STEP = 0.1;
	public static final int MIN_MENU_REFRESH_MILLIS = 250;

	/** Master switch for the HUD overlay. */
	public boolean hudEnabled = true;

	/** HUD anchor in scaled GUI pixels. Negative values anchor to the right/bottom edge. */
	public int hudX = 4;
	public int hudY = 4;

	/** Overlay scale, 1.0 = normal. Clamped to {@link #MIN_SCALE}..{@link #MAX_SCALE}. */
	public double hudScale = 1.0;

	/** Alpha of the panel behind the text, 0..255. Zero draws no panel at all. */
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

	/** Render the set's effective values — the same figures the menu shows under "Relikt-Werte". */
	public boolean showTotals = true;

	/**
	 * Pins the relic database ({@code cb1}/{@code cb2}/{@code cb3}). Leave null so the mod follows
	 * the citybuild you are actually on — a pinned value keeps showing that server's relics after
	 * you switch.
	 */
	public String database = null;

	public String apiBaseUrl = PrimeApiClient.DEFAULT_BASE_URL;

	/** How often relic data is re-fetched while connected. */
	public int refreshIntervalSeconds = 60;

	/** How often to re-fetch while the relic menu is open, in milliseconds. */
	public int menuRefreshMillis = 1000;

	/**
	 * Only talk to the API while connected to a server whose address contains this string.
	 * Blank disables the check.
	 */
	public String serverAddressFilter = "primeblocks";

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static RelicsConfig load() {
		Path file = path();

		if (!Files.exists(file)) {
			// The mod was called PrimeRelics before; carry the old settings over rather than
			// silently resetting someone's overlay position.
			Path legacy = file.resolveSibling("primerelics.json");

			if (Files.exists(legacy)) {
				try {
					Files.move(legacy, file);
				} catch (IOException e) {
					PrimeRelicsClient.LOGGER.warn("Could not migrate {}", legacy, e);
				}
			}
		}

		if (!Files.exists(file)) {
			RelicsConfig config = new RelicsConfig();
			config.save();
			return config;
		}

		try {
			RelicsConfig config = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
					RelicsConfig.class);
			return config != null ? config : new RelicsConfig();
		} catch (IOException | JsonSyntaxException e) {
			PrimeRelicsClient.LOGGER.warn("Could not read {}, using defaults", file, e);
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
		return Math.clamp(backgroundOpacity, 0, 255) << 24;
	}
}
