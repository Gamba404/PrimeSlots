package net.primeblocks.relics.tracking;

import java.util.List;
import java.util.Locale;

/**
 * A single slot of an open container, flattened to plain strings so the parser never touches
 * live game objects off the client thread.
 *
 * @param index       slot index inside the menu
 * @param itemId      registry id, e.g. {@code minecraft:amethyst_shard}
 * @param displayName item name with formatting stripped
 * @param lore        lore lines with formatting stripped
 * @param hasFoil     whether the stack renders with an enchantment glint
 * @param count       stack size
 */
public record GuiSlotSnapshot(
		int index,
		String itemId,
		String displayName,
		List<String> lore,
		boolean hasFoil,
		int count
) {
	/** Name and lore joined into one lower-cased haystack for pattern matching. */
	public String searchText() {
		StringBuilder builder = new StringBuilder(displayName);

		for (String line : lore) {
			builder.append('\n').append(line);
		}

		return builder.toString().toLowerCase(Locale.ROOT);
	}
}
