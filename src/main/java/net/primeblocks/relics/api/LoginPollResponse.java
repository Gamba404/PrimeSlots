package net.primeblocks.relics.api;

import com.google.gson.annotations.SerializedName;

/** Result of {@code GET /api/auth/sessions/{sessionId}}. {@code token} is null until confirmed. */
public record LoginPollResponse(
		@SerializedName("sessionId") String sessionId,
		@SerializedName("uuid") String uuid,
		@SerializedName("name") String name,
		@SerializedName("status") String status,
		@SerializedName("expiresAt") String expiresAt,
		@SerializedName("confirmedAt") String confirmedAt,
		@SerializedName("token") String token
) {
	public boolean isConfirmed() {
		return "CONFIRMED".equals(status) && token != null && !token.isBlank();
	}

	public boolean isExpired() {
		return "EXPIRED".equals(status);
	}
}
