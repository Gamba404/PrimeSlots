package net.primeblocks.relics.model;

/**
 * The "form" of a relic, expressed as a die. The public API returns this as the raw
 * integer field {@code d} on a relic; the wiki refers to the same values as d0..d20.
 */
public enum DiceForm {
	D0(0),
	D4(4),
	D6(6),
	D8(8),
	D10(10),
	D12(12),
	D20(20);

	private final int sides;

	DiceForm(int sides) {
		this.sides = sides;
	}

	public int sides() {
		return sides;
	}

	public String displayName() {
		return "d" + sides;
	}

	/** Returns {@code null} for an unknown value rather than throwing — the server may add forms. */
	public static DiceForm fromSides(int sides) {
		for (DiceForm form : values()) {
			if (form.sides == sides) {
				return form;
			}
		}

		return null;
	}
}
