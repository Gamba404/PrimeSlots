package net.primeblocks.relics.command;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;

import com.mojang.brigadier.arguments.StringArgumentType;

import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.ClientCommands;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import net.primeblocks.relics.CityBuildDetector;
import net.primeblocks.relics.PrimeRelicsClient;
import net.primeblocks.relics.RelicService;
import net.primeblocks.relics.RelicsConfig;
import net.primeblocks.relics.api.LoginPollResponse;
import net.primeblocks.relics.api.PrimeApiClient;
import net.primeblocks.relics.api.StartLoginResponse;
import net.primeblocks.relics.hud.RelicHudEditScreen;
import net.primeblocks.relics.hud.RelicOverlayRenderer;
import net.primeblocks.relics.model.RelicOverview;
import net.primeblocks.relics.state.RelicState;
import net.primeblocks.relics.tracking.ContainerSnapshot;
import net.primeblocks.relics.tracking.SlotsMenuReading;
import net.primeblocks.relics.tracking.SlotsScreenTracker;

/** Client-side {@code /primerelics} command tree. */
public final class RelicsCommands {
	private static final Duration LOGIN_POLL_INTERVAL = Duration.ofSeconds(3);
	private static final int LOGIN_POLL_ATTEMPTS = 100;

	private final RelicsConfig config;
	private final RelicState state;
	private final RelicService service;
	private final SlotsScreenTracker tracker;
	private final RelicOverlayRenderer renderer;

	public RelicsCommands(RelicsConfig config, RelicState state, RelicService service,
			SlotsScreenTracker tracker, RelicOverlayRenderer renderer) {
		this.config = config;
		this.state = state;
		this.service = service;
		this.tracker = tracker;
		this.renderer = renderer;
	}

	public void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) ->
				dispatcher.register(ClientCommands.literal("primerelics")
						.executes(context -> status(context.getSource()))
						.then(ClientCommands.literal("status")
								.executes(context -> status(context.getSource())))
						.then(ClientCommands.literal("refresh")
								.executes(context -> refresh(context.getSource())))
						.then(ClientCommands.literal("reload")
								.executes(context -> reload(context.getSource())))
						.then(ClientCommands.literal("dump")
								.executes(context -> dump(context.getSource())))
						.then(ClientCommands.literal("dumps")
								.executes(context -> dumps(context.getSource())))
						.then(ClientCommands.literal("where")
								.executes(context -> where(context.getSource())))
						.then(ClientCommands.literal("login")
								.executes(context -> login(context.getSource())))
						.then(ClientCommands.literal("hud")
								.then(ClientCommands.literal("on")
										.executes(context -> setHud(context.getSource(), true)))
								.then(ClientCommands.literal("off")
										.executes(context -> setHud(context.getSource(), false)))
								.then(ClientCommands.literal("move")
										.executes(context -> openEditor(context.getSource()))))
						.then(ClientCommands.literal("database")
								.then(ClientCommands.argument("name", StringArgumentType.word())
										.executes(context -> setDatabase(context.getSource(),
												StringArgumentType.getString(context, "name")))))));
	}

	private int status(FabricClientCommandSource source) {
		RelicOverview overview = state.overview();

		source.sendFeedback(Component.literal("§6PrimeSlots"));
		source.sendFeedback(Component.literal("  Datenbank: §f"
				+ (state.database() != null ? state.database() : "—")
				+ (service.hasToken() ? " §7(authentifiziert)" : " §7(debug-Endpoint)")));
		source.sendFeedback(Component.literal("  Sets: §f"
				+ (overview != null ? overview.setsOrEmpty().size() : 0)
				+ "§r, Lager: §f" + (overview != null ? overview.storageOrEmpty().size() : 0)));
		source.sendFeedback(Component.literal("  Aktives Set: §f"
				+ (state.activeSetId() != null ? state.activeSetId() : "unbekannt")
				+ " §7(" + state.activeSetSource() + ")"));

		if (state.lastError() != null) {
			source.sendFeedback(Component.literal("  §cLetzter Fehler: §f" + state.lastError()));
		}

		if (state.activeSetId() == null) {
			source.sendFeedback(Component.literal(
					"  §7Öffne §f/slots§7, damit der Mod das aktive Set erkennt."));
		}

		return 1;
	}

	private int refresh(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal("§7Lade Reliktdaten neu …"));

		service.refreshNow().whenComplete((ignored, throwable) -> onClient(source, () -> {
			if (throwable != null) {
				source.sendError(Component.literal("Fehlgeschlagen: "
						+ PrimeApiClient.rootCause(throwable).getMessage()));
				return;
			}

			source.sendFeedback(Component.literal("§aReliktdaten aktualisiert."));
		}));

		return 1;
	}

	private int reload(FabricClientCommandSource source) {
		RelicsConfig loaded = RelicsConfig.load();
		PrimeRelicsClient.copyInto(loaded, config);
		tracker.reloadPatterns();
		service.reloadClient();

		source.sendFeedback(Component.literal("§aKonfiguration neu geladen: §f" + RelicsConfig.path()));
		return 1;
	}

	private int setHud(FabricClientCommandSource source, boolean enabled) {
		config.hudEnabled = enabled;
		config.save();

		source.sendFeedback(Component.literal(enabled ? "§aHUD an." : "§7HUD aus."));
		return 1;
	}

	private int setDatabase(FabricClientCommandSource source, String name) {
		config.database = "auto".equalsIgnoreCase(name) ? null : name;
		config.save();
		service.reloadClient();

		source.sendFeedback(Component.literal("§aDatenbank: §f"
				+ (config.database != null ? config.database : "auto")));
		service.refreshNow();
		return 1;
	}

	/**
	 * Shows how the citybuild was determined and every server string the detector inspected.
	 * If the wrong database is being used, the answer is visible here.
	 */
	private int where(FabricClientCommandSource source) {
		Minecraft minecraft = source.getClient();
		String detected = CityBuildDetector.detect(minecraft);

		source.sendFeedback(Component.literal("§6Citybuild-Erkennung"));
		source.sendFeedback(Component.literal("  Erkannt: §f"
				+ (detected != null ? detected : "§cnichts gefunden")));
		source.sendFeedback(Component.literal("  Config-Override: §f"
				+ (config.database != null && !config.database.isBlank() ? config.database : "aus (auto)")));
		source.sendFeedback(Component.literal("  Aktiv genutzt: §f"
				+ (state.database() != null ? state.database() : "—")));
		source.sendFeedback(Component.literal("§7  Untersuchte Server-Texte:"));

		List<String> texts = CityBuildDetector.candidateTexts(minecraft);

		if (texts.isEmpty()) {
			source.sendFeedback(Component.literal("§7    (keine)"));
		}

		for (String text : texts.stream().distinct().limit(20).toList()) {
			source.sendFeedback(Component.literal("§7    · §f" + text));
		}

		return 1;
	}

	private int openEditor(FabricClientCommandSource source) {
		Minecraft minecraft = source.getClient();
		// The command is dispatched while the chat screen is still up; opening the editor has to
		// wait until that screen has closed, or it would be replaced immediately.
		minecraft.execute(() -> minecraft.gui.setScreen(new RelicHudEditScreen(renderer, config)));
		return 1;
	}

	/**
	 * Manual dump of the open container. Normally unnecessary — the tracker dumps automatically,
	 * because a container screen holds keyboard focus and no command can be typed while one is
	 * open. Kept for containers the tracker is not watching.
	 */
	private int dump(FabricClientCommandSource source) {
		Minecraft minecraft = source.getClient();
		Screen screen = minecraft.gui.screen();

		if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
			source.sendError(Component.literal(
					"Kein Container-Menü offen. Der Tracker dumpt /slots ohnehin automatisch — "
							+ "siehe /primerelics dumps."));
			return 0;
		}

		ContainerSnapshot snapshot = ContainerSnapshot.capture(containerScreen);
		SlotsMenuReading reading = tracker.parser().parse(snapshot);
		Path file = tracker.dumpWriter().write("manual", snapshot, reading);

		if (file == null) {
			source.sendError(Component.literal("Dump fehlgeschlagen oder Limit erreicht."));
			return 0;
		}

		source.sendFeedback(Component.literal("§aDump: §f" + file));
		source.sendFeedback(Component.literal("  Titel: §f" + snapshot.title()
				+ " §7(" + snapshot.slots().size() + " belegte Slots)"));
		source.sendFeedback(Component.literal("  Erkanntes aktives Set: §f"
				+ (reading.activeSetNumber() != null ? reading.activeSetNumber() : "§ckeins")));

		return 1;
	}

	/** Where the automatic dumps went and how many there are. */
	private int dumps(FabricClientCommandSource source) {
		source.sendFeedback(Component.literal("§6Dumps§7 (" + tracker.dumpWriter().written()
				+ " diese Sitzung, max " + config.maxDumpsPerSession + ")"));
		source.sendFeedback(Component.literal("  §f" + tracker.dumpWriter().directory()));
		source.sendFeedback(Component.literal("  Auto-Dump: §f" + (config.autoDump ? "an" : "aus")
				+ "§r, Discovery: §f" + (config.discoveryMode ? "an" : "aus")));
		return 1;
	}

	private int login(FabricClientCommandSource source) {
		String name = source.getPlayer().getGameProfile().name();

		source.sendFeedback(Component.literal("§7Starte Login für §f" + name + "§7 …"));

		service.client().startLogin(name)
				.whenComplete((response, throwable) -> onClient(source, () -> {
					if (throwable != null) {
						source.sendError(Component.literal("Login fehlgeschlagen: "
								+ PrimeApiClient.rootCause(throwable).getMessage()));
						return;
					}

					source.sendFeedback(Component.literal("§6PIN: §f§l" + response.pin()));
					source.sendFeedback(Component.literal(
							"§7Bestätige die PIN im Spiel. Ich warte auf die Bestätigung …"));
					pollLogin(source, response, 0);
				}));

		return 1;
	}

	private void pollLogin(FabricClientCommandSource source, StartLoginResponse session, int attempt) {
		if (attempt >= LOGIN_POLL_ATTEMPTS) {
			onClient(source, () -> source.sendError(
					Component.literal("Login abgelaufen — keine Bestätigung erhalten.")));
			return;
		}

		Executor delayed = CompletableFuture.delayedExecutor(
				LOGIN_POLL_INTERVAL.toMillis(), TimeUnit.MILLISECONDS);

		CompletableFuture.supplyAsync(() -> service.client().pollLogin(session.sessionId()), delayed)
				.thenCompose(future -> future)
				.whenComplete((LoginPollResponse response, Throwable throwable) -> {
					if (throwable != null) {
						onClient(source, () -> source.sendError(Component.literal(
								"Login-Abfrage fehlgeschlagen: "
										+ PrimeApiClient.rootCause(throwable).getMessage())));
						return;
					}

					if (response.isConfirmed()) {
						service.useToken(response.token());
						onClient(source, () -> {
							source.sendFeedback(Component.literal(
									"§aLogin bestätigt — nutze ab jetzt den authentifizierten Endpoint."));
							service.refreshNow();
						});
						return;
					}

					if (response.isExpired()) {
						onClient(source, () -> source.sendError(
								Component.literal("Login-Sitzung abgelaufen.")));
						return;
					}

					pollLogin(source, session, attempt + 1);
				});
	}

	/** Hops back onto the client thread; API callbacks land on the HTTP client's virtual threads. */
	private static void onClient(FabricClientCommandSource source, Runnable action) {
		source.getClient().execute(action);
	}

}
