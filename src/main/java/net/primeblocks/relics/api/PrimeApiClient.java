package net.primeblocks.relics.api;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.google.gson.reflect.TypeToken;

import net.primeblocks.relics.model.RelicOverview;

/**
 * Thin async client for the PrimeBlocks public API.
 *
 * <p>Two ways to read a player's relics:
 * <ul>
 *   <li>{@link #fetchOverviewUnauthenticated} hits {@code /debug/players/{owner}}, which needs no
 *       token. The API document labels it "intended for debugging only", so treat it as something
 *       that may disappear.</li>
 *   <li>{@link #fetchOwnOverview} hits {@code /me} with a bearer token from the login flow
 *       ({@link #startLogin} → confirm in-game → {@link #pollLogin}).</li>
 * </ul>
 *
 * <p>All calls are non-blocking; every future completes on the shared executor, never on the
 * render thread.
 */
public final class PrimeApiClient {
	public static final String DEFAULT_BASE_URL = "https://public-api.primeblocks.net";

	private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
	private static final Type DATABASE_LIST_TYPE = new TypeToken<ApiResponse<List<String>>>() { }.getType();
	private static final Type OVERVIEW_TYPE = new TypeToken<ApiResponse<RelicOverview>>() { }.getType();
	private static final Type START_LOGIN_TYPE = new TypeToken<ApiResponse<StartLoginResponse>>() { }.getType();
	private static final Type POLL_LOGIN_TYPE = new TypeToken<ApiResponse<LoginPollResponse>>() { }.getType();

	private final Gson gson = new Gson();
	private final String baseUrl;
	private final HttpClient http;

	public PrimeApiClient(String baseUrl) {
		this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
		this.http = HttpClient.newBuilder()
				.connectTimeout(Duration.ofSeconds(5))
				.followRedirects(HttpClient.Redirect.NORMAL)
				.executor(Executors.newThreadPerTaskExecutor(
						Thread.ofVirtual().name("primeslots-http-", 0).factory()))
				.build();
	}

	public PrimeApiClient() {
		this(DEFAULT_BASE_URL);
	}

	/** Configured relic databases, e.g. {@code [cb1, cb2, cb3]}. */
	public CompletableFuture<List<String>> listDatabases() {
		return send(get("/api/relic/databases", null), DATABASE_LIST_TYPE);
	}

	/**
	 * Relic overview via the unauthenticated debug endpoint.
	 *
	 * <p>The owner UUID must be dashed — the service rejects the undashed form with
	 * {@code "owner must be a valid UUID"}.
	 */
	public CompletableFuture<RelicOverview> fetchOverviewUnauthenticated(String database, UUID owner) {
		return send(get("/api/relic/" + database + "/debug/players/" + owner, null), OVERVIEW_TYPE);
	}

	/** Relic overview for the player the bearer token belongs to. */
	public CompletableFuture<RelicOverview> fetchOwnOverview(String database, String bearerToken) {
		return send(get("/api/relic/" + database + "/me", bearerToken), OVERVIEW_TYPE);
	}

	/** Starts a login session; the returned PIN must be confirmed in-game within 10 minutes. */
	public CompletableFuture<StartLoginResponse> startLogin(String minecraftName) {
		String body = gson.toJson(new StartLoginRequest(minecraftName));
		HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/api/auth/login"))
				.timeout(REQUEST_TIMEOUT)
				.header("Content-Type", "application/json")
				.header("Accept", "application/json")
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();

		return send(request, START_LOGIN_TYPE);
	}

	/** Polls a pending login session; carries the JWT once the session is {@code CONFIRMED}. */
	public CompletableFuture<LoginPollResponse> pollLogin(String sessionId) {
		return send(get("/api/auth/sessions/" + sessionId, null), POLL_LOGIN_TYPE);
	}

	private HttpRequest get(String path, String bearerToken) {
		HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
				.timeout(REQUEST_TIMEOUT)
				.header("Accept", "application/json")
				.GET();

		if (bearerToken != null && !bearerToken.isBlank()) {
			builder.header("Authorization", "Bearer " + bearerToken);
		}

		return builder.build();
	}

	private <T> CompletableFuture<T> send(HttpRequest request, Type envelopeType) {
		return http.sendAsync(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8))
				.thenApply(response -> unwrap(request, response, envelopeType));
	}

	private <T> T unwrap(HttpRequest request, HttpResponse<String> response, Type envelopeType) {
		ApiResponse<T> envelope;

		try {
			envelope = gson.fromJson(response.body(), envelopeType);
		} catch (JsonSyntaxException e) {
			throw new ApiException("Malformed response from " + request.uri() + " (HTTP "
					+ response.statusCode() + ")", e);
		}

		if (envelope == null) {
			throw new ApiException("Empty response from " + request.uri() + " (HTTP "
					+ response.statusCode() + ")");
		}

		if (!envelope.success() || envelope.data() == null) {
			throw new ApiException(request.uri().getPath() + " failed (HTTP "
					+ response.statusCode() + "): " + envelope.errorText(), response.statusCode());
		}

		return envelope.data();
	}

	/** Any non-success outcome from the API, including transport-level failures wrapped by callers. */
	public static final class ApiException extends RuntimeException {
		/** HTTP status, or 0 when the failure happened before a response arrived. */
		private final int statusCode;

		public ApiException(String message) {
			this(message, 0);
		}

		public ApiException(String message, int statusCode) {
			super(message);
			this.statusCode = statusCode;
		}

		public ApiException(String message, Throwable cause) {
			super(message, cause);
			this.statusCode = 0;
		}

		public int statusCode() {
			return statusCode;
		}

		/** The token was rejected — expired, revoked, or issued for another player. */
		public boolean isUnauthorized() {
			return statusCode == 401 || statusCode == 403;
		}
	}

	/** Unwraps the {@link java.util.concurrent.CompletionException} chain for readable logging. */
	public static Throwable rootCause(Throwable throwable) {
		Throwable current = throwable;

		while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
				|| current instanceof IOException && current.getCause() != null)) {
			current = current.getCause();
		}

		return current;
	}
}
