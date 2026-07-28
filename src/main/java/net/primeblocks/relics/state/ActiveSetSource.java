package net.primeblocks.relics.state;

/** Where the "currently equipped set" information came from. */
public enum ActiveSetSource {
	/** Nothing known yet — the player has not opened {@code /slots} since joining. */
	UNKNOWN,
	/** Read live from an open {@code /slots} menu. */
	SLOTS_MENU,
	/** Remembered from the last time the {@code /slots} menu was open this session. */
	REMEMBERED,
	/** The player has exactly one set, so it must be the active one. */
	ONLY_SET
}
