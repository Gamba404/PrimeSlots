package net.primeblocks.relics.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.primeblocks.relics.RelicsConfig;
import net.primeblocks.relics.data.StatCatalog;
import net.primeblocks.relics.data.StatTotals;
import net.primeblocks.relics.data.SynergyBonus;
import net.primeblocks.relics.data.SynergyTable;
import net.primeblocks.relics.model.Relic;
import net.primeblocks.relics.model.RelicSet;
import net.primeblocks.relics.model.RelicSlot;
import net.primeblocks.relics.model.RelicStat;
import net.primeblocks.relics.state.ActiveSetSource;
import net.primeblocks.relics.state.RelicState;

/**
 * Builds and draws the overlay. Shared by the live HUD element and the drag-to-position editor so
 * both show exactly the same box.
 */
public final class RelicOverlayRenderer {
	static final int COLOUR_TITLE = 0xFFFFD966;
	private static final int COLOUR_RELIC = 0xFFE8E8E8;
	private static final int COLOUR_STAT = 0xFF9BD9A0;
	private static final int COLOUR_MALUS = 0xFFE08A8A;
	private static final int COLOUR_SYNERGY = 0xFF8FC7E8;
	private static final int COLOUR_SECTION = 0xFFB8B8B8;
	private static final int COLOUR_MUTED = 0xFF9A9A9A;
	private static final int LINE_SPACING = 2;
	private static final int PADDING = 4;

	private final RelicState state;
	private final RelicsConfig config;

	public RelicOverlayRenderer(RelicState state, RelicsConfig config) {
		this.state = state;
		this.config = config;
	}

	/**
	 * The lines to draw. Empty means "render nothing at all"; the editor passes
	 * {@code alwaysShow} so the box stays visible while being positioned.
	 */
	public List<HudLine> buildLines(boolean alwaysShow) {
		List<HudLine> lines = new ArrayList<>();
		RelicSet active = state.activeSet();

		if (active == null) {
			// The menu may have named a set the API has nothing for — that happens on a citybuild
			// where the player owns no relics. Showing just "Set II" is the honest result.
			Integer menuNumber = state.activeSetNumber();

			if (menuNumber != null) {
				lines.add(new HudLine(titleFor(menuNumber), COLOUR_TITLE));
				return lines;
			}

			String note = describeMissingState();

			if (note == null && !alwaysShow) {
				return List.of();
			}

			lines.add(new HudLine("Relikte", COLOUR_TITLE));
			lines.add(new HudLine(note != null ? note : "keine Daten", COLOUR_MUTED, 2));
			return lines;
		}

		lines.add(new HudLine(titleFor(active.id()), COLOUR_TITLE));

		if (config.showRelics) {
			appendRelics(lines, active);
		}

		if (config.showSynergies) {
			appendSynergies(lines, active);
		}

		if (config.showTotals) {
			appendTotals(lines, active);
		}

		return lines;
	}

	private void appendRelics(List<HudLine> lines, RelicSet active) {
		List<RelicSlot> slots = active.slotsOrEmpty().stream()
				.sorted((a, b) -> Integer.compare(a.slot(), b.slot()))
				.toList();

		// Without the per-relic stat breakdown the forms fit comfortably on one line.
		if (!config.showStats) {
			StringJoiner joiner = new StringJoiner("  ");

			for (RelicSlot slot : slots) {
				Relic relic = slot.relic();

				if (relic != null) {
					joiner.add(relic.formLabel() + " " + StatCatalog.tierNumeral(relic.level()));
				}
			}

			if (joiner.length() > 0) {
				lines.add(new HudLine(joiner.toString(), COLOUR_RELIC, 2));
			}

			return;
		}

		for (RelicSlot slot : slots) {
			Relic relic = slot.relic();

			if (relic == null) {
				continue;
			}

			lines.add(new HudLine(
					relic.formLabel() + "  " + StatCatalog.tierNumeral(relic.level()),
					COLOUR_RELIC, 2));

			for (RelicStat stat : relic.statsOrEmpty()) {
				if (stat.negative() && config.hideMaluses) {
					continue;
				}

				lines.add(new HudLine(StatCatalog.describe(stat),
						stat.negative() ? COLOUR_MALUS : COLOUR_STAT, 10));
			}
		}
	}

	/**
	 * Per the wiki the two upper and the two lower relics each form a pair. Slot numbers are 1-based
	 * in the API, so the pairs are 1+2 and 3+4 — addressed by number rather than by list position,
	 * because a set can be missing a slot and would otherwise pair the wrong relics.
	 */
	private void appendSynergies(List<HudLine> lines, RelicSet active) {
		for (int upper = 1; upper <= 3; upper += 2) {
			Relic first = active.relicAt(upper);
			Relic second = active.relicAt(upper + 1);

			if (first == null || second == null) {
				continue;
			}

			List<SynergyBonus> bonuses = SynergyTable.lookup(first.d(), second.d());

			if (bonuses.isEmpty()) {
				continue;
			}

			StringJoiner joiner = new StringJoiner(", ");

			for (SynergyBonus bonus : bonuses) {
				joiner.add(bonus.toString());
			}

			lines.add(new HudLine(
					first.formLabel() + "+" + second.formLabel() + ": " + joiner,
					COLOUR_SYNERGY, 2));
		}
	}

	private void appendTotals(List<HudLine> lines, RelicSet active) {
		List<StatTotals.Total> totals = StatTotals.compute(active).stream()
				.filter(total -> !config.hideMaluses || !total.malus())
				.toList();

		if (totals.isEmpty()) {
			return;
		}

		lines.add(new HudLine("Werte", COLOUR_SECTION, 2));

		for (StatTotals.Total total : totals) {
			lines.add(new HudLine(total.format(),
					total.malus() ? COLOUR_MALUS : COLOUR_STAT, 10));
		}
	}

	private String titleFor(int setNumber) {
		String roman = StatCatalog.romanNumeral(setNumber);
		String fallback = "Set " + (roman.isEmpty() ? String.valueOf(setNumber) : roman);
		String name = state.setName(setNumber);
		String label = name != null ? name + " (" + fallback + ")" : fallback;

		label = switch (state.activeSetSource()) {
			case API, SLOTS_MENU, ONLY_SET -> label;
			// Only reachable in the gap between closing the menu and the next poll confirming it.
			case REMEMBERED -> label + " *";
			case UNKNOWN -> label + " (?)";
		};

		// A guessed database can show another citybuild's relics, which looks perfectly normal.
		// Say so on the overlay rather than let it pass as fact.
		if (!state.databaseCertain() && state.database() != null) {
			label += " · " + state.database() + "?";
		}

		return label;
	}

	/** Explains why there is nothing to show, or null when the overlay should stay hidden. */
	private String describeMissingState() {
		if (state.lastError() != null) {
			return "API nicht erreichbar";
		}

		if (state.overview() == null) {
			return null;
		}

		if (state.overview().setsOrEmpty().isEmpty()) {
			return "keine Relikt-Sets gefunden";
		}

		if (state.activeSetSource() == ActiveSetSource.UNKNOWN) {
			// The API flags the equipped set, so this only happens if no set is flagged at all.
			return "kein Set ausgerüstet";
		}

		return null;
	}

	public static int boxWidth(Font font, List<HudLine> lines) {
		int content = 0;

		for (HudLine line : lines) {
			content = Math.max(content, line.indent() + font.width(line.text()));
		}

		return content + PADDING * 2;
	}

	public static int boxHeight(Font font, List<HudLine> lines) {
		int lineHeight = font.lineHeight + LINE_SPACING;
		return lines.size() * lineHeight - LINE_SPACING + PADDING * 2;
	}

	/** On-screen width once the configured scale is applied. */
	public static int scaledWidth(Font font, List<HudLine> lines, float scale) {
		return Math.round(boxWidth(font, lines) * scale);
	}

	public static int scaledHeight(Font font, List<HudLine> lines, float scale) {
		return Math.round(boxHeight(font, lines) * scale);
	}

	/** Resolves the stored anchor into a pixel position; negative values anchor to the far edge. */
	public static int resolveX(RelicsConfig config, int guiWidth, int boxWidth) {
		return config.hudX >= 0 ? config.hudX : guiWidth + config.hudX - boxWidth;
	}

	public static int resolveY(RelicsConfig config, int guiHeight, int boxHeight) {
		return config.hudY >= 0 ? config.hudY : guiHeight + config.hudY - boxHeight;
	}

	/**
	 * Draws the overlay with its top-left corner at {@code boxX}/{@code boxY}.
	 *
	 * <p>Scaling goes through the matrix stack rather than the font, so the text scales smoothly
	 * and the layout maths stays in unscaled units.
	 *
	 * @param backgroundColour ARGB panel colour; fully transparent alpha skips the panel
	 */
	public void draw(GuiGraphicsExtractor context, Font font, List<HudLine> lines,
			int boxX, int boxY, float scale, int backgroundColour) {
		boolean scaled = Math.abs(scale - 1.0f) > 1e-4f;

		if (scaled) {
			context.pose().pushMatrix();
			context.pose().translate(boxX, boxY);
			context.pose().scale(scale);
		}

		int originX = scaled ? 0 : boxX;
		int originY = scaled ? 0 : boxY;

		if ((backgroundColour >>> 24) != 0) {
			context.fill(originX, originY,
					originX + boxWidth(font, lines), originY + boxHeight(font, lines),
					backgroundColour);
		}

		int lineHeight = font.lineHeight + LINE_SPACING;
		int textX = originX + PADDING;
		int textY = originY + PADDING;

		for (HudLine line : lines) {
			context.text(font, line.text(), textX + line.indent(), textY, line.color());
			textY += lineHeight;
		}

		if (scaled) {
			context.pose().popMatrix();
		}
	}
}
