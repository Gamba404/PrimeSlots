package net.primeblocks.relics.api;

import com.google.gson.annotations.SerializedName;

/**
 * Result of {@code POST /api/auth/login}. The player confirms {@code pin} in-game, which flips the
 * session to {@code CONFIRMED} and makes a token available via {@link PrimeApiClient#pollLogin}.
 */
public record StartLoginResponse(
		@SerializedName("sessionId") String sessionId,
		@SerializedName("uuid") String uuid,
		@SerializedName("name") String name,
		@SerializedName("pin") String pin,
		@SerializedName("expiresAt") String expiresAt
) {
}
