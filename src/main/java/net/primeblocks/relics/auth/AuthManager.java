package net.primeblocks.relics.auth;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import net.primeblocks.relics.PrimeRelicsClient;
import net.primeblocks.relics.api.LoginPollResponse;
import net.primeblocks.relics.api.PrimeApiClient;
import net.primeblocks.relics.api.StartLoginResponse;

/**
 * Links the Minecraft account to the API and keeps the resulting token around.
 *
 * <p>The flow is: ask the API for a PIN, the player confirms it in-game with {@code /login <PIN>},
 * the API hands back a JWT. That confirmation step is the whole point — it proves the account is
 * the player's — so it cannot be automated away. It is a one-off: the token is written to disk and
 * survives restarts.
 */
public final class AuthManager {
	private static final Duration POLL_INTERVAL = Duration.ofSeconds(2);
	private static final int POLL_ATTEMPTS = 150;

	/** Server join spam would bury an immediate hint, so the prompt waits a moment. */
	private static final long PROMPT_DELAY_MILLIS = 4000;

	private final TokenStore store;
	private final Supplier<PrimeApiClient> clientSupplier;
	private final AtomicBoolean loginInFlight = new AtomicBoolean();

	private volatile Runnable onAuthenticated = () -> { };
	private UUID promptedFor;
	private long promptDueAt;

	public AuthManager(TokenStore store, Supplier<PrimeApiClient> clientSupplier) {
		this.store = store;
		this.clientSupplier = clientSupplier;
	}

	/** Runs once a token has been obtained, so relic data can be pulled immediately. */
	public void setOnAuthenticated(Runnable onAuthenticated) {
		this.onAuthenticated = onAuthenticated != null ? onAuthenticated : () -> { };
	}

	/** The stored token for this player, or {@code null} when the account is not linked. */
	public String tokenFor(UUID player) {
		StoredToken stored = store.get(player);
		return stored != null ? stored.token() : null;
	}

	public boolean isLinked(UUID player) {
		return store.has(player);
	}

	/** Forgets the token after the API rejected it, so the next tick offers a fresh link. */
	public void invalidate(UUID player) {
		if (!store.has(player)) {
			return;
		}

		store.remove(player);
		promptedFor = null;
	}

	public void onDisconnect() {
		promptedFor = null;
		promptDueAt = 0L;
	}

	/**
	 * Starts the link automatically once per session when no token is stored. Called every tick;
	 * cheap when the account is already linked.
	 */
	public void tick(Minecraft minecraft) {
		if (minecraft.player == null) {
			return;
		}

		UUID player = minecraft.player.getUUID();

		if (store.has(player) || loginInFlight.get() || player.equals(promptedFor)) {
			return;
		}

		long now = System.currentTimeMillis();

		if (promptDueAt == 0L) {
			promptDueAt = now + PROMPT_DELAY_MILLIS;
			return;
		}

		if (now < promptDueAt) {
			return;
		}

		promptedFor = player;
		promptDueAt = 0L;
		startLogin(minecraft);
	}

	/** Requests a PIN and waits for the player to confirm it in-game. */
	private void startLogin(Minecraft minecraft) {
		if (minecraft.player == null || !loginInFlight.compareAndSet(false, true)) {
			return;
		}

		UUID player = minecraft.player.getUUID();
		String name = minecraft.player.getGameProfile().name();

		clientSupplier.get().startLogin(name).whenComplete((session, throwable) ->
				minecraft.execute(() -> {
					if (throwable != null) {
						loginInFlight.set(false);
						sendError(minecraft, "Verbindung zur API fehlgeschlagen. Beim nächsten "
								+ "Serverwechsel versuche ich es erneut.");
						return;
					}

					announcePin(minecraft, session);
					pollLogin(minecraft, player, name, session, 0);
				}));
	}

	private void announcePin(Minecraft minecraft, StartLoginResponse session) {
		send(minecraft, Component.empty());
		send(minecraft, prefix().append(Component.literal("Einmalige Freischaltung — ein Klick:")
				.withStyle(ChatFormatting.WHITE)));
		send(minecraft, Component.literal("   ")
				.append(button("[ /login " + session.pin() + " ]", "/login " + session.pin(),
						"Schaltet die Reliktanzeige frei")));
		send(minecraft, Component.literal("   Danach zeigt der Mod deine Relikte dauerhaft an.")
				.withStyle(ChatFormatting.GRAY));
		send(minecraft, Component.empty());
	}

	private void pollLogin(Minecraft minecraft, UUID player, String name,
			StartLoginResponse session, int attempt) {
		if (attempt >= POLL_ATTEMPTS) {
			loginInFlight.set(false);
			// Let the next join offer a fresh PIN rather than leaving a dead one in chat.
			promptedFor = null;
			sendError(minecraft, "Freischaltung abgelaufen. Beim nächsten Join gibt es eine neue.");
			return;
		}

		Executor delayed = CompletableFuture.delayedExecutor(
				POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

		CompletableFuture
				.supplyAsync(() -> clientSupplier.get().pollLogin(session.sessionId()), delayed)
				.thenCompose(future -> future)
				.whenComplete((LoginPollResponse response, Throwable throwable) ->
						minecraft.execute(() -> {
							if (throwable != null) {
								loginInFlight.set(false);
								promptedFor = null;
								PrimeRelicsClient.LOGGER.warn("Login poll failed: {}",
										PrimeApiClient.rootCause(throwable).toString());
								return;
							}

							if (response.isConfirmed()) {
								loginInFlight.set(false);
								store.put(player, name, response.token());
								announceSuccess(minecraft);
								onAuthenticated.run();
								return;
							}

							if (response.isExpired()) {
								loginInFlight.set(false);
								promptedFor = null;
								sendError(minecraft,
										"Freischaltung abgelaufen. Beim nächsten Join gibt es eine neue.");
								return;
							}

							pollLogin(minecraft, player, name, session, attempt + 1);
						}));
	}

	private void announceSuccess(Minecraft minecraft) {
		send(minecraft, prefix()
				.append(Component.literal("Freigeschaltet — die Anzeige ist jetzt aktiv.")
						.withStyle(ChatFormatting.GREEN)));
	}

	private static MutableComponent prefix() {
		return Component.literal("[PrimeSlots] ").withStyle(ChatFormatting.AQUA);
	}

	private static MutableComponent button(String label, String command, String tooltip) {
		return Component.literal(label).withStyle(style -> style
				.withColor(ChatFormatting.GREEN)
				.withUnderlined(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Component.literal(tooltip))));
	}

	private static void send(Minecraft minecraft, Component message) {
		if (minecraft.player != null) {
			minecraft.player.sendSystemMessage(message);
		}
	}

	private static void sendError(Minecraft minecraft, String message) {
		send(minecraft, prefix().append(Component.literal(message).withStyle(ChatFormatting.RED)));
	}
}
