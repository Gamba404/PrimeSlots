package net.primeblocks.relics.model;

import java.util.List;

import com.google.gson.annotations.SerializedName;

/**
 * One of the player's relic sets (up to five per the wiki). Mirrors {@code RelicSetResponse}.
 *
 * <p>{@code active} was added to the API on 2026-07-28 and marks the equipped set. Before that the
 * only way to know was to read the {@code /slots} menu while it was open; the menu is still parsed,
 * because it reacts instantly and is the only source of the player's custom set names.
 *
 * @param id     server-side set id
 * @param active whether this is the set the player currently has equipped
 * @param slots  equipped relics, keyed by slot index
 */
public record RelicSet(
		@SerializedName("id") int id,
		@SerializedName("active") boolean active,
		@SerializedName("slots") List<RelicSlot> slots
) {
	public List<RelicSlot> slotsOrEmpty() {
		return slots != null ? slots : List.of();
	}

	/** The relic in {@code slotIndex}, or {@code null} if that slot is empty. */
	public Relic relicAt(int slotIndex) {
		for (RelicSlot slot : slotsOrEmpty()) {
			if (slot.slot() == slotIndex) {
				return slot.relic();
			}
		}

		return null;
	}

	/**
	 * The set's relic forms in slot order, used to fingerprint a set so it can be matched
	 * against what the {@code /slots} menu is showing.
	 */
	public List<Integer> formFingerprint() {
		return slotsOrEmpty().stream()
				.sorted((a, b) -> Integer.compare(a.slot(), b.slot()))
				.map(slot -> slot.relic() != null ? slot.relic().d() : -1)
				.toList();
	}
}
