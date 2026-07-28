package net.primeblocks.relics.auth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import net.fabricmc.loader.api.FabricLoader;

import net.primeblocks.relics.PrimeRelicsClient;

/**
 * Persists API tokens so the account only has to be linked once, not on every launch.
 *
 * <p>Kept in its own file rather than in {@code primeslots.json}: the config is the file people
 * paste into a chat when asking for help, and a bearer token has no business travelling with it.
 * Tokens are keyed by player UUID so several accounts on one machine do not overwrite each other.
 */
public final class TokenStore {
	private static final String FILE_NAME = "primeslots-auth.json";
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private final Map<String, StoredToken> byPlayer = new LinkedHashMap<>();

	public static Path path() {
		return FabricLoader.getInstance().getConfigDir().resolve(FILE_NAME);
	}

	public static TokenStore load() {
		TokenStore store = new TokenStore();
		Path file = path();

		if (!Files.exists(file)) {
			return store;
		}

		try {
			Persisted persisted = GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
					Persisted.class);

			if (persisted != null && persisted.byPlayer != null) {
				store.byPlayer.putAll(persisted.byPlayer);
			}
		} catch (IOException | JsonSyntaxException e) {
			PrimeRelicsClient.LOGGER.warn("Could not read {}; a fresh link will be required", file, e);
		}

		return store;
	}

	/** The usable token for this player, or {@code null} if absent or expired. */
	public StoredToken get(UUID player) {
		StoredToken stored = byPlayer.get(player.toString());
		return stored != null && stored.isUsable() ? stored : null;
	}

	public boolean has(UUID player) {
		return get(player) != null;
	}

	public void put(UUID player, String name, String token) {
		byPlayer.put(player.toString(),
				new StoredToken(name, token, System.currentTimeMillis(), expiryOf(token)));
		save();
	}

	public void remove(UUID player) {
		if (byPlayer.remove(player.toString()) != null) {
			save();
		}
	}

	private void save() {
		Path file = path();

		try {
			Files.createDirectories(file.getParent());
			Persisted persisted = new Persisted();
			persisted.byPlayer = byPlayer;
			Files.writeString(file, GSON.toJson(persisted), StandardCharsets.UTF_8);
		} catch (IOException e) {
			PrimeRelicsClient.LOGGER.warn("Could not write {}", file, e);
		}
	}

	/**
	 * Reads the {@code exp} claim out of a JWT, in epoch millis; {@code 0} when there is none.
	 *
	 * <p>The payload is only decoded, never verified — the client has no key and does not need one.
	 * This is purely so an expired token can be replaced before a request rather than after a 401.
	 */
	public static long expiryOf(String jwt) {
		if (jwt == null) {
			return 0L;
		}

		String[] parts = jwt.split("\\.");

		if (parts.length < 2) {
			return 0L;
		}

		try {
			String payload = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
			JsonObject json = JsonParser.parseString(payload).getAsJsonObject();

			if (!json.has("exp")) {
				return 0L;
			}

			return json.get("exp").getAsLong() * 1000L;
		} catch (RuntimeException e) {
			// A token we cannot read is still a token; let the server be the judge.
			return 0L;
		}
	}

	private static final class Persisted {
		private Map<String, StoredToken> byPlayer;
	}
}
