package net.primeblocks.relics.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.primeblocks.relics.model.Relic;
import net.primeblocks.relics.model.RelicSet;
import net.primeblocks.relics.model.RelicStat;

/**
 * Aggregates a set into the effective values it grants — the same figure the {@code /slots} menu
 * shows under "Relikt-Werte".
 *
 * <p>Verified line by line against that panel for a real set: all twelve totals, including the
 * synergy contributions, reproduce exactly. So this can be computed client-side from API data
 * rather than scraped, which means it stays available after the menu is closed.
 */
public final class StatTotals {
	private StatTotals() {
	}

	/**
	 * One aggregated stat.
	 *
	 * @param stat  any definition from the group; all members share label and unit
	 * @param value summed signed magnitude
	 * @param malus whether the menu groups this among the penalties
	 */
	public record Total(StatDefinition stat, double value, boolean malus) {
		public String format() {
			return stat.formatValue(value) + " " + stat.displayName();
		}
	}

	/**
	 * Effective values for {@code set}: every relic's stats plus both pair synergies.
	 *
	 * <p>Ordering mirrors the menu exactly, as derived from the real panel: penalties last;
	 * within each group percentage stats before flat ones; then descending magnitude; ties broken
	 * by stat id. The unit rule is what puts "+1,4% Mutanten-Chance" ahead of "+3,0 Glück".
	 * "Größe" counts as a non-penalty despite its minus sign, which is how the menu sorts it.
	 */
	public static List<Total> compute(RelicSet set) {
		Map<String, Accumulator> byLabel = new LinkedHashMap<>();

		for (var slot : set.slotsOrEmpty()) {
			Relic relic = slot.relic();

			if (relic == null) {
				continue;
			}

			for (RelicStat stat : relic.statsOrEmpty()) {
				StatDefinition definition = StatCatalog.find(stat);

				if (definition == null) {
					continue;
				}

				add(byLabel, definition, definition.signedValue(stat.level(), stat.negative()));
			}
		}

		for (int upper = 1; upper <= 3; upper += 2) {
			Relic first = set.relicAt(upper);
			Relic second = set.relicAt(upper + 1);

			if (first == null || second == null) {
				continue;
			}

			for (SynergyBonus bonus : SynergyTable.lookup(first.d(), second.d())) {
				StatDefinition definition = bonus.stat();

				if (definition != null) {
					add(byLabel, definition, bonus.signedAmount());
				}
			}
		}

		List<Total> totals = new ArrayList<>(byLabel.size());

		for (Accumulator accumulator : byLabel.values()) {
			if (Math.abs(accumulator.value) < 1e-9) {
				continue;
			}

			boolean malus = accumulator.value < 0 && !accumulator.definition.naturallyNegative();
			totals.add(new Total(accumulator.definition, accumulator.value, malus));
		}

		totals.sort(Comparator.comparing(Total::malus)
				.thenComparing(total -> total.stat().unit() == StatUnit.PERCENT ? 0 : 1)
				.thenComparing(total -> -Math.abs(total.value()))
				.thenComparing(total -> total.stat().id()));

		return List.copyOf(totals);
	}

	private static void add(Map<String, Accumulator> byLabel, StatDefinition definition, double value) {
		if (Double.isNaN(value)) {
			return;
		}

		byLabel.computeIfAbsent(definition.displayName(), key -> new Accumulator(definition))
				.value += value;
	}

	private static final class Accumulator {
		private final StatDefinition definition;
		private double value;

		private Accumulator(StatDefinition definition) {
			this.definition = definition;
		}
	}
}
