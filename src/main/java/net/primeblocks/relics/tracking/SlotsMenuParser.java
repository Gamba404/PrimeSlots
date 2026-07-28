package net.primeblocks.relics.tracking;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.primeblocks.relics.data.StatCatalog;

/**
 * Reads the {@code /slots} menu.
 *
 * <p>Since the API gained its {@code active} flag, this is no longer how the equipped set is found.
 * What it still provides is the player's custom set names ("Money"), which appear nowhere else, and
 * a reading that lands within a tick rather than at the next poll.
 *
 * <p>The menu is recognised by its <em>contents</em>, not its title: the real title is a run of
 * private-use glyphs from a custom GUI font and carries no readable text at all.
 *
 * <p>The patterns below were derived from real menu dumps. If the server ever rewords the menu the
 * worst case is cosmetic — the overlay falls back to "Set II" instead of the custom name.
 */
public final class SlotsMenuParser {
	/** Set tabs read "Relikt-Set II: Money"; group 1 is the numeral, group 2 the custom name. */
	private static final Pattern SET_TAB =
			compile("^Relikt-Set\\s+([IVX]+)(?::\\s*(.+))?$");

	/** The equipped tab's lore reads "Dies ist dein aktuell ausgerüstetes Relikt-Set." */
	private static final Pattern ACTIVE_MARKER = compile("aktuell\\s+ausger(ü|ue)stetes");

	/** Sets the player has not bought yet still get a tab. */
	private static final Pattern LOCKED_SET = compile("nicht\\s+freigeschaltet");

	/** Placeholder name, so the overlay shows "Set II" rather than "Unbenanntes Set". */
	private static final Pattern UNNAMED_SET = compile("unbenannt");

	/** Equipped relics are named "D12-Relikt VI"; group 1 is the die size. */
	private static final Pattern RELIC_NAME = compile("^D(0|4|6|8|10|12|20)-Relikt\\b");

	private static Pattern compile(String regex) {
		return Pattern.compile(regex, Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
	}

	/** Whether this container carries relic set tabs, i.e. looks like the {@code /slots} menu. */
	public boolean isRelicMenu(ContainerSnapshot snapshot) {
		for (GuiSlotSnapshot slot : snapshot.slots()) {
			if (SET_TAB.matcher(slot.displayName()).find()) {
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
			Matcher tab = SET_TAB.matcher(slot.displayName());

			if (!tab.find()) {
				continue;
			}

			int setNumber = StatCatalog.parseRoman(tab.group(1));

			if (setNumber < 0 || LOCKED_SET.matcher(slot.searchText()).find()) {
				continue;
			}

			String name = tab.group(2);

			if (name != null && !name.isBlank() && !UNNAMED_SET.matcher(name).find()) {
				setNames.put(setNumber, name.trim());
			}

			// The lore marker is authoritative; the enchantment glint is a convention, used only
			// if no marker was found anywhere in the menu.
			if (markedActive == null && ACTIVE_MARKER.matcher(slot.searchText()).find()) {
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
		List<Integer> forms = new ArrayList<>();

		for (GuiSlotSnapshot slot : snapshot.slots()) {
			Matcher matcher = RELIC_NAME.matcher(slot.displayName());

			if (matcher.find()) {
				forms.add(Integer.parseInt(matcher.group(1)));
			}
		}

		return List.copyOf(forms);
	}
}
