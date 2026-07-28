package net.primeblocks.relics.api;

import com.google.gson.annotations.SerializedName;

/**
 * Every public-api.primeblocks.net response is wrapped in this envelope.
 *
 * <p>Worth noting: the published OpenAPI document describes the *inner* payload only — it does
 * not mention the envelope. Verified against the live service on 2026-07-28.
 */
public record ApiResponse<T>(
		@SerializedName("success") boolean success,
		@SerializedName("data") T data,
		@SerializedName("error") String error,
		@SerializedName("message") String message
) {
	public String errorText() {
		if (error != null && !error.isBlank()) {
			return error;
		}

		if (message != null && !message.isBlank()) {
			return message;
		}

		return "unknown API error";
	}
}
