package net.primeblocks.relics.tracking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import net.primeblocks.relics.PrimeRelicsClient;
import net.primeblocks.relics.RelicsConfig;
import net.primeblocks.relics.data.StatCatalog;

/**
 * Turns a {@link ContainerSnapshot} of the {@code /slots} menu into a {@link SlotsMenuReading}.
 *
 * <p>The API cannot say which set is equipped, so this is where that comes from.
 *
 * <p>The menu is recognised by its <em>contents</em>, not its title: the real title is a run of
 * private-use glyphs (a custom GUI font) and carries no readable text at all, so no title pattern
 * could ever match it. Instead the presence of "Relikt-Set …" tabs identifies the screen.
 *
 * <p>All patterns are config options rather than constants, so they can be corrected without a
 * rebuild if the server rewords the menu.
 */
public final class SlotsMenuParser {
	private final Pattern setTabPattern;
	private final Pattern activeMarkerPattern;
	private final Pattern lockedSetPattern;
	private final Pattern unnamedSetPattern;
	private final Pattern relicNamePattern;

	public SlotsMenuParser(RelicsConfig config) {
		this.setTabPattern = compile(config.setTabPattern, "setTabPattern");
		this.activeMarkerPattern = compile(config.activeSetMarkerPattern, "activeSetMarkerPattern");
		this.lockedSetPattern = compile(config.lockedSetPattern, "lockedSetPattern");
		this.unnamedSetPattern = compile(config.unnamedSetPattern, "unnamedSetPattern");
		this.relicNamePattern = compile(config.relicNamePattern, "relicNamePattern");
	}

	/** Whether this container carries relic set tabs, i.e. looks like the {@code /slots} menu. */
	public boolean isRelicMenu(ContainerSnapshot snapshot) {
		if (setTabPattern == null) {
			return false;
		}

		for (GuiSlotSnapshot slot : snapshot.slots()) {
			if (setTabPattern.matcher(slot.displayName()).find()) {
				return true;
			}
		}

		return false;
	}

	public SlotsMenuReading parse(ContainerSnapshot snapshot) {
		if (!isRelicMenu(snapshot)) {
			return SlotsMenuReading.NOT_A_RELIC_MENU;
		}

		Map<Integer, String> setNames = new LinkedHashMap<>();
		Integer markedActive = null;
		Integer foilActive = null;

		for (GuiSlotSnapshot slot : snapshot.slots()) {
			Matcher tab = setTabPattern.matcher(slot.displayName());

			if (!tab.find()) {
				continue;
			}

			int setNumber = StatCatalog.parseRoman(tab.group(1));

			if (setNumber < 0) {
				continue;
			}

			// Sets the player has not bought yet still get a tab; they can never be the active one.
			if (lockedSetPattern != null && lockedSetPattern.matcher(slot.searchText()).find()) {
				continue;
			}

			String name = tab.groupCount() >= 2 ? tab.group(2) : null;

			if (name != null && !name.isBlank()
					&& (unnamedSetPattern == null || !unnamedSetPattern.matcher(name).find())) {
				setNames.put(setNumber, name.trim());
			}

			// Two independent signals. The lore marker is authoritative; the enchantment glint is a
			// convention, used only if no marker was found anywhere in the menu.
			if (markedActive == null && activeMarkerPattern != null
					&& activeMarkerPattern.matcher(slot.searchText()).find()) {
				markedActive = setNumber;
			}

			if (foilActive == null && slot.hasFoil()) {
				foilActive = setNumber;
			}
		}

		Integer active = markedActive != null ? markedActive : foilActive;

		return new SlotsMenuReading(true, active, Map.copyOf(setNames), readEquippedForms(snapshot));
	}

	/** Die sizes of the relics on display, in slot order, read off their "D12-Relikt VI" names. */
	private List<Integer> readEquippedForms(ContainerSnapshot snapshot) {
		if (relicNamePattern == null) {
			return List.of();
		}

		List<Integer> forms = new ArrayList<>();

		for (GuiSlotSnapshot slot : snapshot.slots()) {
			Matcher matcher = relicNamePattern.matcher(slot.displayName());

			if (matcher.find()) {
				forms.add(Integer.parseInt(matcher.group(1)));
			}
		}

		return List.copyOf(forms);
	}

	private static Pattern compile(String regex, String option) {
		if (regex == null || regex.isBlank()) {
			return null;
		}

		try {
			return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
		} catch (PatternSyntaxException e) {
			PrimeRelicsClient.LOGGER.warn("Config option {} is not a valid regex: {}", option, regex, e);
			return null;
		}
	}
}
