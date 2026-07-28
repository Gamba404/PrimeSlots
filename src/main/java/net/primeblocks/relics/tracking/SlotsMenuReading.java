package net.primeblocks.relics.tracking;

import java.util.List;
import java.util.Map;

/**
 * What could be read out of an open {@code /slots} menu.
 *
 * @param relicMenu       whether the container looked like the relic menu at all
 * @param activeSetNumber 1-based set number the menu shows as equipped, or {@code null} if the
 *                        selection marker could not be identified
 * @param setNames        set number to its custom label, omitting unnamed and locked sets
 * @param equippedForms   die sizes of the relics on display, in slot order — used as a fingerprint
 *                        to line the menu up with the sets the API returned
 */
public record SlotsMenuReading(
		boolean relicMenu,
		Integer activeSetNumber,
		Map<Integer, String> setNames,
		List<Integer> equippedForms
) {
	public static final SlotsMenuReading NOT_A_RELIC_MENU =
			new SlotsMenuReading(false, null, Map.of(), List.of());

	public boolean hasActiveSet() {
		return activeSetNumber != null;
	}
}
