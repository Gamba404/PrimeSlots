package net.primeblocks.relics.tracking;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicInteger;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

import net.primeblocks.relics.PrimeRelicsClient;

/**
 * Writes container snapshots to {@code primerelics-dumps/} so the {@code /slots} menu can be
 * inspected offline.
 *
 * <p>This exists because the menu cannot be dumped by hand: while a container screen has focus the
 * chat is unreachable, so no command can be typed. Dumps are therefore written automatically as the
 * menu opens, changes and closes.
 */
public final class DumpWriter {
	private static final DateTimeFormatter SESSION_STAMP =
			DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final AtomicInteger sequence = new AtomicInteger();
	private final String session = LocalDateTime.now().format(SESSION_STAMP);
	private final int maxDumps;

	public DumpWriter(int maxDumps) {
		this.maxDumps = maxDumps;
	}

	public Path directory() {
		return FabricLoader.getInstance().getGameDir().resolve("primerelics-dumps");
	}

	public int written() {
		return sequence.get();
	}

	public boolean atLimit() {
		return sequence.get() >= maxDumps;
	}

	/**
	 * Writes one dump. Returns the file, or {@code null} if the per-session cap is reached or the
	 * write failed — callers treat this as best-effort diagnostics, never as a hard dependency.
	 */
	public Path write(String event, ContainerSnapshot snapshot, SlotsMenuReading reading) {
		if (sequence.get() >= maxDumps) {
			return null;
		}

		int index = sequence.incrementAndGet();

		if (index > maxDumps) {
			return null;
		}

		Path file = directory().resolve(String.format(Locale.ROOT, "%s-%02d-%s-%s.json",
				session, index, event, slug(snapshot.title())));

		try {
			Files.createDirectories(file.getParent());
			Files.writeString(file,
					GSON.toJson(new Dump(event, snapshot.title(), snapshot, reading)),
					StandardCharsets.UTF_8);
		} catch (IOException e) {
			PrimeRelicsClient.LOGGER.warn("Could not write dump {}", file, e);
			return null;
		}

		if (index == maxDumps) {
			PrimeRelicsClient.LOGGER.info(
					"Dump limit of {} reached for this session; raise maxDumpsPerSession to collect more",
					maxDumps);
		}

		return file;
	}

	private static String slug(String title) {
		StringBuilder builder = new StringBuilder();

		for (int i = 0; i < title.length() && builder.length() < 32; i++) {
			char c = title.charAt(i);
			builder.append(Character.isLetterOrDigit(c) ? Character.toLowerCase(c) : '_');
		}

		return builder.isEmpty() ? "untitled" : builder.toString();
	}

	/** Shape of a dump file. */
	private record Dump(String event, String title, ContainerSnapshot container,
			SlotsMenuReading parsed) {
	}
}
