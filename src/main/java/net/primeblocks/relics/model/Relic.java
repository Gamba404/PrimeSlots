package net.primeblocks.relics.model;

import java.util.List;

import com.google.gson.annotations.SerializedName;

/**
 * A single relic. Mirrors {@code RelicResponse} in the public API.
 *
 * @param level   upgrade tier 0..6 (identified = 0, then I..VI)
 * @param d       the relic form as a die size; see {@link DiceForm}
 * @param boundTo UUID of the player the relic is bound to
 * @param stats   rolled stats
 */
public record Relic(
		@SerializedName("level") int level,
		@SerializedName("d") int d,
		@SerializedName("boundTo") String boundTo,
		@SerializedName("stats") List<RelicStat> stats
) {
	public DiceForm form() {
		return DiceForm.fromSides(d);
	}

	public String formLabel() {
		DiceForm form = form();
		return form != null ? form.displayName() : "d" + d;
	}

	public List<RelicStat> statsOrEmpty() {
		return stats != null ? stats : List.of();
	}
}
