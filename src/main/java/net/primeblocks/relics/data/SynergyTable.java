package net.primeblocks.relics.data;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.primeblocks.relics.model.DiceForm;

/**
 * Relic pair synergies, transcribed from the Relikt-System wiki page on 2026-07-28.
 *
 * <p>Per the wiki the two upper and the two lower relics of a set each form a pair, and the pair's
 * two forms decide the bonus. All 21 unordered form pairs are covered, so any fully-equipped set
 * has exactly two active synergies.
 *
 * <p>Confirmed against the live menu: for the three sets that were captured, the pairs computed
 * here reproduce the "Synergie" items the server itself displays, values included.
 */
public final class SynergyTable {
	// Stat ids, mirroring StatCatalog.
	private static final int MAX_HEALTH = 1;
	private static final int MOVEMENT_SPEED = 2;
	private static final int JOB_XP = 3;
	private static final int JOB_MONEY = 4;
	private static final int INTERACT_REACH = 6;
	private static final int ATTACK_SPEED = 7;
	private static final int DAMAGE = 8;
	private static final int SIZE_UP = 9;
	private static final int SIZE_DOWN = 10;
	private static final int ARMOR = 11;
	private static final int GAME_EXP = 13;
	private static final int SHINY_CHANCE = 16;
	private static final int MUTANT_CHANCE = 17;
	private static final int LUCKY_TABLET_CHANCE = 18;
	private static final int BUFF_TIME = 20;

	private static final Map<Long, List<SynergyBonus>> BY_PAIR = new HashMap<>();

	private SynergyTable() {
	}

	private static void register(int a, int b, SynergyBonus... bonuses) {
		BY_PAIR.put(key(a, b), List.of(bonuses));
	}

	private static SynergyBonus of(int statId, double amount) {
		return new SynergyBonus(statId, amount);
	}

	static {
		register(0, 4, of(MOVEMENT_SPEED, 5));
		register(8, 12, of(GAME_EXP, 20));
		register(8, 10, of(DAMAGE, 0.5));
		register(6, 12, of(SIZE_DOWN, 10));
		register(6, 10, of(SIZE_UP, 10));
		register(0, 12, of(JOB_MONEY, 5));
		register(0, 10, of(JOB_XP, 5));
		register(0, 8, of(INTERACT_REACH, 1.5));
		register(4, 6, of(LUCKY_TABLET_CHANCE, 10));
		register(4, 8, of(MAX_HEALTH, 20));
		register(4, 12, of(ARMOR, 30));
		register(0, 6, of(SHINY_CHANCE, 5));
		register(4, 10, of(ATTACK_SPEED, 5));
		register(4, 20, of(BUFF_TIME, 25));
		register(6, 8, of(MUTANT_CHANCE, 5));
		register(20, 8, of(MUTANT_CHANCE, 50), of(SHINY_CHANCE, -50));
		register(20, 0, of(SHINY_CHANCE, 50), of(MUTANT_CHANCE, -90));
		register(20, 6, of(GAME_EXP, 60), of(JOB_MONEY, -60));
		register(20, 10, of(JOB_XP, 20), of(JOB_MONEY, -30));
		register(20, 12, of(JOB_MONEY, 10), of(JOB_XP, -40));
		register(10, 12, of(MOVEMENT_SPEED, 15), of(JOB_MONEY, -30));
	}

	/** Bonuses for a pair of relic forms, or an empty list if either side is missing/unknown. */
	public static List<SynergyBonus> lookup(Integer formA, Integer formB) {
		if (formA == null || formB == null) {
			return List.of();
		}

		return BY_PAIR.getOrDefault(key(formA, formB), List.of());
	}

	public static List<SynergyBonus> lookup(DiceForm a, DiceForm b) {
		if (a == null || b == null) {
			return List.of();
		}

		return lookup(a.sides(), b.sides());
	}

	/** Order-independent key for an unordered pair of die sizes. */
	private static long key(int a, int b) {
		int low = Math.min(a, b);
		int high = Math.max(a, b);
		return ((long) low << 32) | (high & 0xFFFFFFFFL);
	}
}
