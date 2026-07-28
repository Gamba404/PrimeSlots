package net.primeblocks.relics.model;

import com.google.gson.annotations.SerializedName;

/** A relic sitting in the altar storage rather than in a set. Mirrors {@code StoredRelicResponse}. */
public record StoredRelic(
		@SerializedName("id") long id,
		@SerializedName("relic") Relic relic
) {
}
