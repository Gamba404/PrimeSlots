package net.primeblocks.relics.data;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.primeblocks.relics.model.RelicStat;

import static net.primeblocks.relics.data.StatUnit.FLAT;
import static net.primeblocks.relics.data.StatUnit.PERCENT;

/**
 * The 20 relic stats, joining the wiki's value table to the API's ids and to the in-game labels.
 *
 * <p>Verified against live API data and a real {@code /slots} menu on 2026-07-28: the API returns
 * stat ids 1..20 with {@code SCREAMING_SNAKE} names, lining up one-to-one with the twenty rows of
 * the wiki table in the same order. Lookup is keyed on the id, which is stable, and falls back to
 * the name only if an unknown id shows up.
 *
 * <p>Two details come from the menu rather than the wiki: maluses are 1.5x the bonus magnitude
 * (see {@link StatDefinition#MALUS_FACTOR}), and both size stats are labelled just "Größe" in-game,
 * with the direction carried by the sign.
 */
public final class StatCatalog {
	private static final Map<Integer, StatDefinition> BY_ID = new LinkedHashMap<>();
	private static final Map<String, StatDefinition> BY_NAME = new LinkedHashMap<>();

	private StatCatalog() {
	}

	private static void register(StatDefinition definition) {
		BY_ID.put(definition.id(), definition);
		BY_NAME.put(normalise(definition.apiName()), definition);
		BY_NAME.putIfAbsent(normalise(definition.displayName()), definition);
	}

	private static StatDefinition stat(int id, String apiName, String label, boolean canBeNegative,
			StatUnit unit, double step) {
		return new StatDefinition(id, apiName, label, canBeNegative, false, unit, step);
	}

	static {
		// id, API name, in-game label, can roll as a malus, unit, level-0 magnitude.
		// Every wiki row is a linear ramp, so level N is (N + 1) x the level-0 value.
		register(stat(1, "MAX_HEALTH", "Herzen", true, PERCENT, 2.0));
		register(stat(2, "MOVEMENT_SPEED", "Geschwindigkeit", true, PERCENT, 2.0));
		register(stat(3, "JOB_XP", "Job XP", true, PERCENT, 2.0));
		register(stat(4, "JOB_MONEY", "Job Münzen", true, PERCENT, 0.7));
		register(stat(5, "ATTACK_REACH", "Attack-Reichweite", true, FLAT, 0.10));
		register(stat(6, "INTERACT_REACH", "Block-Reichweite", true, FLAT, 0.30));
		register(stat(7, "ATTACK_SPEED", "Angriffsgeschwindigkeit", true, PERCENT, 1.0));
		register(stat(8, "DAMAGE", "Schaden", true, FLAT, 0.10));
		// The menu writes both size stats as "Größe"; SIZE_DOWN always renders with a minus.
		register(new StatDefinition(9, "SIZE_UP", "Größe", false, false, PERCENT, 2.0));
		register(new StatDefinition(10, "SIZE_DOWN", "Größe", false, true, PERCENT, 2.0));
		register(stat(11, "ARMOR", "Rüstung", true, PERCENT, 2.0));
		register(stat(12, "KNOCKBACK_RESISTANCE", "Standfestigkeit", true, PERCENT, 3.0));
		register(stat(13, "GAME_EXP", "Erfahrung", true, PERCENT, 4.0));
		register(stat(14, "NATURAL_REGEN", "Regeneration", false, PERCENT, 3.5));
		register(stat(15, "LUCK", "Glück", false, FLAT, 1.0));
		register(stat(16, "SHINY_CHANCE", "Shiny-Chance", false, PERCENT, 0.7));
		register(stat(17, "MUTANT_CHANCE", "Mutanten-Chance", false, PERCENT, 0.7));
		register(stat(18, "LUCKY_TABLET_CHANCE", "Lucky-Tablet-Chance", false, PERCENT, 1.0));
		register(stat(19, "DEBUFF_TIME", "Debuff-Dauer", true, PERCENT, 2.5));
		register(stat(20, "BUFF_TIME", "Buff-Dauer", true, PERCENT, 3.5));
	}

	public static List<StatDefinition> all() {
		return List.copyOf(BY_ID.values());
	}

	public static StatDefinition byId(int id) {
		return BY_ID.get(id);
	}

	/** Resolves by id first, then by name; {@code null} if the stat is unknown to this build. */
	public static StatDefinition find(int id, String apiName) {
		StatDefinition byId = BY_ID.get(id);

		if (byId != null) {
			return byId;
		}

		if (apiName == null || apiName.isBlank()) {
			return null;
		}

		return BY_NAME.get(normalise(apiName));
	}

	public static StatDefinition find(RelicStat stat) {
		return find(stat.id(), stat.name());
	}

	/**
	 * One stat line in the menu's wording, e.g. {@code "+12,0% Job XP V"}. Level 0 carries no
	 * numeral, matching the menu. Unknown stats fall back to the raw API name so nothing silently
	 * disappears from the overlay.
	 */
	public static String describe(RelicStat stat) {
		StatDefinition definition = find(stat);

		if (definition == null) {
			return stat.name() + " " + romanNumeral(stat.level());
		}

		String numeral = romanNumeral(stat.level());
		String value = definition.formatValue(definition.signedValue(stat.level(), stat.negative()));

		return numeral.isEmpty()
				? value + " " + definition.displayName()
				: value + " " + definition.displayName() + " " + numeral;
	}

	/** Roman numeral for a stat level; empty at level 0, exactly as the menu shows it. */
	public static String romanNumeral(int level) {
		return switch (level) {
			case 0 -> "";
			case 1 -> "I";
			case 2 -> "II";
			case 3 -> "III";
			case 4 -> "IV";
			case 5 -> "V";
			case 6 -> "VI";
			default -> String.valueOf(level);
		};
	}

	/** Roman numeral that never collapses to empty — used for relic tiers, where 0 is meaningful. */
	public static String tierNumeral(int level) {
		return level == 0 ? "0" : romanNumeral(level);
	}

	/** Parses I..V as used by the set tabs in the menu; {@code -1} when unrecognised. */
	public static int parseRoman(String roman) {
		return switch (roman.trim().toUpperCase(Locale.ROOT)) {
			case "I" -> 1;
			case "II" -> 2;
			case "III" -> 3;
			case "IV" -> 4;
			case "V" -> 5;
			default -> -1;
		};
	}

	private static String normalise(String raw) {
		String folded = raw.trim().toLowerCase(Locale.ROOT)
				.replace("ä", "ae")
				.replace("ö", "oe")
				.replace("ü", "ue")
				.replace("ß", "ss");

		// "Größe+" and "Größe-" differ only by their trailing sign, so promote that to a word
		// before separators are stripped. Everywhere else '-' is just a separator.
		String suffix = "";

		if (folded.endsWith("+")) {
			suffix = "plus";
			folded = folded.substring(0, folded.length() - 1);
		} else if (folded.endsWith("-")) {
			suffix = "minus";
			folded = folded.substring(0, folded.length() - 1);
		}

		StringBuilder builder = new StringBuilder(folded.length() + suffix.length());

		for (int i = 0; i < folded.length(); i++) {
			char c = folded.charAt(i);

			if (Character.isLetterOrDigit(c)) {
				builder.append(c);
			}
		}

		return builder.append(suffix).toString();
	}
}
