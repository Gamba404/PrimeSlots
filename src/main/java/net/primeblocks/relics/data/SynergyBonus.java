package net.primeblocks.relics.data;

/**
 * One line of a synergy's effect, e.g. {@code +60,0% Erfahrung} or {@code -60,0% Job Münzen}.
 *
 * @param statId stat id as used by the API and {@link StatCatalog}
 * @param amount magnitude as printed in the wiki; negative for the malus half of a synergy
 */
public record SynergyBonus(int statId, double amount) {
	public StatDefinition stat() {
		return StatCatalog.byId(statId);
	}

	/** Signed contribution, applying the same "Größe counts downwards" rule as relic stats. */
	public double signedAmount() {
		StatDefinition definition = stat();

		if (definition != null && definition.naturallyNegative()) {
			return -Math.abs(amount);
		}

		return amount;
	}

	public boolean isMalus() {
		return amount < 0;
	}

	@Override
	public String toString() {
		StatDefinition definition = stat();

		if (definition == null) {
			return "stat#" + statId + " " + amount;
		}

		return definition.formatValue(signedAmount()) + " " + definition.displayName();
	}
}
