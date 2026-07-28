package net.primeblocks.relics.model;

import com.google.gson.annotations.SerializedName;

/**
 * One rolled stat on a relic. Mirrors {@code RelicStatResponse} in the public API.
 *
 * <p>{@code level} is 0..6 and maps onto the wiki's 0/I/II/III/IV/V/VI columns.
 * {@code negative} means the roll is applied as a malus.
 */
public record RelicStat(
		@SerializedName("id") int id,
		@SerializedName("name") String name,
		@SerializedName("level") int level,
		@SerializedName("negative") boolean negative
) {
	/** Roman numeral for the stat level, matching how the wiki and the in-game menu label tiers. */
	public String levelLabel() {
		return switch (level) {
			case 0 -> "0";
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			case 6 -> "VI";
			default -> String.valueOf(level);
		};
	}
}
