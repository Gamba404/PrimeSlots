package net.primeblocks.relics.data;

import java.util.Locale;

/**
 * A relic stat and the magnitude it grants at each level.
 *
 * <p>Base values transcribed from the
 * <a href="https://wiki.primeblocks.net/de/citybuild/relikt-system">Relikt-System wiki page</a>;
 * ids, API names, the malus factor and the display format all verified against the live
 * {@code /slots} menu on 2026-07-28.
 *
 * @param id                 stable stat id used by the API ({@code RelicStatResponse.id}), 1..20
 * @param apiName            the API's {@code SCREAMING_SNAKE} name
 * @param displayName        label exactly as the in-game menu writes it
 * @param canBeNegative      whether the stat can roll as a malus
 * @param naturallyNegative  stat whose effect reads as a reduction, so it is always shown with a
 *                           minus even though it is not a malus (only {@code SIZE_DOWN})
 * @param unit               how the magnitude is formatted
 * @param step               magnitude at level 0; level N grants {@code step * (N + 1)}
 */
public record StatDefinition(
		int id,
		String apiName,
		String displayName,
		boolean canBeNegative,
		boolean naturallyNegative,
		StatUnit unit,
		double step
) {
	/**
	 * A malus rolls at 1.5x the magnitude of the same stat and level as a bonus.
	 *
	 * <p>The wiki only tabulates the bonus side. Derived from eleven negative rolls read out of the
	 * live menu — e.g. Rüstung at level 0 shows -3.0% against a +2.0% bonus, and Erfahrung at level
	 * I shows -12.0% against +8.0%. Every sample matches this factor.
	 */
	public static final double MALUS_FACTOR = 1.5;

	/** Unsigned magnitude, or {@code NaN} outside the wiki's 0..VI range. */
	public double magnitude(int level, boolean negative) {
		if (level < 0 || level > 6) {
			return Double.NaN;
		}

		return step * (level + 1) * (negative ? MALUS_FACTOR : 1.0);
	}

	/** Signed magnitude: maluses and {@code SIZE_DOWN} count downwards. */
	public double signedValue(int level, boolean negative) {
		double magnitude = magnitude(level, negative);

		if (Double.isNaN(magnitude)) {
			return Double.NaN;
		}

		return negative || naturallyNegative ? -magnitude : magnitude;
	}

	/**
	 * Formats a signed value the way the menu does: German decimal comma, one decimal place,
	 * always signed — {@code +12,0%} or {@code -0,3}.
	 */
	public String formatValue(double signed) {
		if (Double.isNaN(signed)) {
			return "?";
		}

		String sign = signed < 0 ? "-" : "+";
		double magnitude = Math.abs(signed);

		return switch (unit) {
			case PERCENT -> String.format(Locale.GERMAN, "%s%.1f%%", sign, magnitude);
			case FLAT -> String.format(Locale.GERMAN, "%s%.1f", sign, magnitude);
		};
	}
}
