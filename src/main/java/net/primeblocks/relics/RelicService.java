package net.primeblocks.relics;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import net.primeblocks.relics.api.PrimeApiClient;
import net.primeblocks.relics.model.RelicOverview;
import net.primeblocks.relics.state.RelicState;

/**
 * Owns the polling loop against the public API and keeps {@link RelicState}'s relic data fresh.
 *
 * <p>Reads go through the unauthenticated {@code /debug/players/{uuid}} endpoint by default: the
 * client already knows its own UUID, so no login is needed. The API document calls that endpoint
 * debug-only, so {@link #useToken} lets the mod switch to the authenticated {@code /me} endpoint
 * once the player has completed the in-game login flow.
 */
public final class RelicService {
	private final RelicsConfig config;
	private final RelicState state;
	private final AtomicBoolean fetchInFlight = new AtomicBoolean();

	private volatile PrimeApiClient client;
	private volatile String token;
	private volatile String resolvedDatabase;
	private volatile String lastDetected;
	private volatile boolean menuOpen;
	private volatile Runnable afterFetch = () -> { };
	private long nextFetchAt;

	public RelicService(RelicsConfig config, RelicState state) {
		this.config = config;
		this.state = state;
		this.client = new PrimeApiClient(config.apiBaseUrl);
	}

	public PrimeApiClient client() {
		return client;
	}

	/** Runs on the client thread after every successful fetch, so readers can re-resolve. */
	public void setAfterFetch(Runnable afterFetch) {
		this.afterFetch = afterFetch != null ? afterFetch : () -> { };
	}

	/** Switches to the fast poll while the relic menu is on screen. */
	public void setMenuOpen(boolean menuOpen) {
		this.menuOpen = menuOpen;

		if (menuOpen) {
			nextFetchAt = 0L;
		}
	}

	/**
	 * Equipping or removing a relic changes the data behind the overlay, and so does editing on the
	 * website, so an open menu is exactly when it is worth watching closely.
	 */
	private int pollIntervalMillis() {
		return menuOpen ? config.menuRefreshIntervalMillis() : config.refreshIntervalMillis();
	}

	/** Rebuilds the HTTP client after {@code apiBaseUrl} changed. */
	public void reloadClient() {
		this.client = new PrimeApiClient(config.apiBaseUrl);
		this.resolvedDatabase = null;
	}

	/** Switches subsequent reads to the authenticated endpoint. Held in memory only. */
	public void useToken(String token) {
		this.token = token;
	}

	public boolean hasToken() {
		return token != null && !token.isBlank();
	}

	public void onDisconnect() {
		state.reset();
		resolvedDatabase = null;
		lastDetected = null;
		nextFetchAt = 0L;
	}

	/**
	 * Joining does not reset anything. Walking into a farm world reconnects the client, and at that
	 * moment the tab footer has not arrived yet — clearing here would blank the overlay on every
	 * farm world trip even though the citybuild, and the player's relics, are unchanged. If the new
	 * server really is a different citybuild, {@link #tick} notices as soon as the footer lands.
	 */
	public void onJoin() {
		nextFetchAt = 0L;
	}

	/** Called once per client tick. */
	public void tick(Minecraft minecraft) {
		if (!shouldPoll(minecraft)) {
			return;
		}

		// Walking from one citybuild to another does not reconnect, so the switch has to be noticed
		// here. Doing it every tick means the overlay corrects itself on its own, without waiting
		// for the poll interval or for the player to open /slots.
		String detected = databaseFor(minecraft);

		if (detected != null && !detected.equals(lastDetected)) {
			if (lastDetected != null) {
				PrimeRelicsClient.LOGGER.info("Citybuild changed: {} -> {}", lastDetected, detected);
			} else if (config.verboseTracking) {
				PrimeRelicsClient.LOGGER.info("Citybuild detected: {}", detected);
			}

			lastDetected = detected;
			resolvedDatabase = detected;
			state.onDatabaseChanged(detected);
			nextFetchAt = 0L;
		}

		long now = System.currentTimeMillis();

		if (now < nextFetchAt) {
			return;
		}

		nextFetchAt = now + pollIntervalMillis();
		refresh(minecraft);
	}

	/** Forces an immediate fetch, ignoring the poll interval. */
	public CompletableFuture<Void> refreshNow() {
		Minecraft minecraft = Minecraft.getInstance();
		nextFetchAt = System.currentTimeMillis() + pollIntervalMillis();
		return refresh(minecraft);
	}

	/**
	 * Refresh triggered by the {@code /slots} menu opening. The citybuild is re-detected first, so
	 * walking from cb1 to cb2 and opening the menu shows that server's relics rather than stale
	 * ones.
	 */
	public CompletableFuture<Void> refreshForMenu() {
		Minecraft minecraft = Minecraft.getInstance();
		String detected = databaseFor(minecraft);

		if (detected != null && !detected.equals(state.database())) {
			state.onDatabaseChanged(detected);
		}

		return refreshNow();
	}

	private boolean shouldPoll(Minecraft minecraft) {
		if (minecraft.player == null || minecraft.getConnection() == null) {
			return false;
		}

		String filter = config.serverAddressFilter;

		if (filter == null || filter.isBlank()) {
			return true;
		}

		ServerData server = minecraft.getCurrentServer();

		if (server == null || server.ip == null) {
			return false;
		}

		return server.ip.toLowerCase(Locale.ROOT).contains(filter.toLowerCase(Locale.ROOT));
	}

	private CompletableFuture<Void> refresh(Minecraft minecraft) {
		if (minecraft.player == null) {
			return CompletableFuture.completedFuture(null);
		}

		if (!fetchInFlight.compareAndSet(false, true)) {
			return CompletableFuture.completedFuture(null);
		}

		UUID owner = minecraft.player.getUUID();
		String detected = databaseFor(minecraft);
		boolean certain = detected != null;

		return resolveDatabase(detected)
				.thenCompose(database -> fetchOverview(database, owner)
						.thenAccept(overview -> {
							state.onFetched(database, certain, overview);
							// Fresh relic data can change which set the open menu maps onto, so
							// give the tracker a chance to re-resolve. Client thread: the callback
							// touches screen state.
							minecraft.execute(afterFetch);
						}))
				.exceptionally(throwable -> {
					Throwable cause = PrimeApiClient.rootCause(throwable);
					state.onFetchFailed(cause.getMessage());
					PrimeRelicsClient.LOGGER.warn("Relic fetch failed: {}", cause.toString());
					return null;
				})
				.whenComplete((ignored, throwable) -> fetchInFlight.set(false));
	}

	private CompletableFuture<RelicOverview> fetchOverview(String database, UUID owner) {
		String bearer = token;

		if (bearer != null && !bearer.isBlank()) {
			return client.fetchOwnOverview(database, bearer);
		}

		return client.fetchOverviewUnauthenticated(database, owner);
	}

	/**
	 * The database this citybuild maps to, from the config override or from the server itself.
	 * {@code null} when neither could tell us.
	 */
	private String databaseFor(Minecraft minecraft) {
		String configured = config.database;

		if (configured != null && !configured.isBlank()) {
			return configured.trim();
		}

		return CityBuildDetector.detect(minecraft);
	}

	/**
	 * Settles on a database, or fails if the citybuild is not known yet.
	 *
	 * <p>There is deliberately no "pick whichever database holds data" fallback. It looks helpful
	 * and is quietly wrong: right after a server transfer the tab footer has not arrived yet, so
	 * detection is briefly blank — and the fallback would then answer "cb1" simply because that is
	 * where the relics are, no matter which citybuild the player actually landed on. Waiting a few
	 * ticks for the footer costs nothing; showing another server's relics as fact costs trust.
	 */
	private CompletableFuture<String> resolveDatabase(String detected) {
		if (detected != null) {
			resolvedDatabase = detected;
			return CompletableFuture.completedFuture(detected);
		}

		String cached = resolvedDatabase;

		if (cached != null) {
			return CompletableFuture.completedFuture(cached);
		}

		return CompletableFuture.failedFuture(
				new PrimeApiClient.ApiException("Citybuild noch nicht erkannt"));
	}
}
