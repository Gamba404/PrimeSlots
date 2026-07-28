package net.primeblocks.relics.state;

/** Where the "currently equipped set" information came from. */
public enum ActiveSetSource {
	/** Nothing known yet. */
	UNKNOWN,
	/** Straight from the API's {@code active} flag — authoritative, and needs no menu visit. */
	API,
	/** Read live from an open {@code /slots} menu; reacts faster than the next poll. */
	SLOTS_MENU,
	/** Remembered from the last time the {@code /slots} menu was open this session. */
	REMEMBERED,
	/** The player has exactly one set, so it must be the active one. */
	ONLY_SET
}
