package net.primeblocks.relics.model;

import com.google.gson.annotations.SerializedName;

/** A slot inside a relic set. Mirrors {@code RelicSlotResponse}. */
public record RelicSlot(
		@SerializedName("slot") int slot,
		@SerializedName("relic") Relic relic
) {
}
