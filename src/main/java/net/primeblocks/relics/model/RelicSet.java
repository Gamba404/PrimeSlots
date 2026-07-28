package net.primeblocks.relics.model;

import java.util.List;

import com.google.gson.annotations.SerializedName;

/**
 * One of the player's relic sets (up to five per the wiki). Mirrors {@code RelicSetResponse}.
 *
 * <p>Note: the API deliberately does not say which set is currently equipped, and carries no
 * set name. Both of those come from the in-game {@code /slots} menu — see
 * {@code net.primeblocks.relics.tracking}.
 *
 * @param id    server-side set id
 * @param slots equipped relics, keyed by slot index
 */
public record RelicSet(
		@SerializedName("id") int id,
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
