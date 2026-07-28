package net.primeblocks.relics;

import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ServerData;

import net.primeblocks.relics.api.PrimeApiClient;
import net.primeblocks.relics.auth.AuthManager;
import net.primeblocks.relics.state.RelicState;

/**
 * Owns the polling loop against the public API and keeps {@link RelicState}'s relic data fresh.
 *
 * <p>Reads go exclusively through the authenticated {@code /me} endpoint. The API also exposes an
 * unauthenticated debug route, but it is labelled debug-only and could disappear without warning,
 * so the mod does not build on it.
 */
public final class RelicService {
	private final RelicsConfig config;
	private final RelicState state;
	private final AtomicBoolean fetchInFlight = new AtomicBoolean();

	private volatile PrimeApiClient client;
	private volatile AuthManager authManager;
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

	public void setAuthManager(AuthManager authManager) {
		this.authManager = authManager;
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
		String bearer = authManager != null ? authManager.tokenFor(owner) : null;

		// Without a link there is nothing to ask with. AuthManager is already nudging the player.
		if (bearer == null) {
			fetchInFlight.set(false);
			state.onNotLinked();
			return CompletableFuture.completedFuture(null);
		}

		String detected = databaseFor(minecraft);

		return resolveDatabase(detected)
				.thenCompose(database -> client.fetchOwnOverview(database, bearer)
						.thenAccept(overview -> {
							state.onFetched(database, overview);
							// Fresh relic data can change which set the open menu maps onto, so
							// give the tracker a chance to re-resolve. Client thread: the callback
							// touches screen state.
							minecraft.execute(afterFetch);
						}))
				.exceptionally(throwable -> {
					Throwable cause = PrimeApiClient.rootCause(throwable);

					// A rejected token is not a transient error: drop it so the player is asked to
					// link again instead of silently retrying forever.
					if (cause instanceof PrimeApiClient.ApiException api && api.isUnauthorized()
							&& authManager != null) {
						minecraft.execute(() -> authManager.invalidate(owner));
					}

					state.onFetchFailed(cause.getMessage());
					return null;
				})
				.whenComplete((ignored, throwable) -> fetchInFlight.set(false));
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
