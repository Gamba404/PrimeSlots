package net.primeblocks.relics.auth;

import com.google.gson.annotations.SerializedName;

/**
 * A saved API token for one Minecraft account.
 *
 * @param name       the account's name when the token was issued, for readable diagnostics
 * @param token      the JWT
 * @param obtainedAt epoch millis the token was issued to this client
 * @param expiresAt  epoch millis the JWT's {@code exp} claim points at, or {@code 0} when the
 *                   token carries no expiry that could be read
 */
public record StoredToken(
		@SerializedName("name") String name,
		@SerializedName("token") String token,
		@SerializedName("obtainedAt") long obtainedAt,
		@SerializedName("expiresAt") long expiresAt
) {
	/** A minute of slack, so a token is not used at the very moment it lapses. */
	private static final long EXPIRY_MARGIN_MILLIS = 60_000;

	public boolean isUsable() {
		if (token == null || token.isBlank()) {
			return false;
		}

		// No readable expiry means the server did not tell us; assume valid and let a 401 decide.
		return expiresAt <= 0 || System.currentTimeMillis() < expiresAt - EXPIRY_MARGIN_MILLIS;
	}
}
