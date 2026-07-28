package net.primeblocks.relics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.chat.Component;
import net.minecraft.world.scores.DisplaySlot;
import net.minecraft.world.scores.Objective;
import net.minecraft.world.scores.PlayerScoreEntry;
import net.minecraft.world.scores.Scoreboard;

import net.primeblocks.relics.mixin.PlayerTabOverlayAccessor;

/**
 * Works out which citybuild the player is on, so relic data is read from the matching database.
 *
 * <p>Guessing by "whichever database has data" is wrong: a player standing on cb2 with an empty
 * relic collection but a full one on cb1 would be shown cb1's sets. The server identity has to come
 * from the server itself.
 *
 * <p>PrimeBlocks writes it into the tab list footer — "Du befindest dich derzeit auf: CityBuild-1
 * (Farmwelt-5)" — which is checked first. That also keeps the reading correct inside a farm world,
 * since the footer still names the owning citybuild there. The scoreboard and other sources are
 * kept as secondary candidates in case the footer format changes.
 *
 * <p>Every text this looks at is exposed through {@code /primerelics where}, so a miss can be
 * diagnosed without guessing.
 */
public final class CityBuildDetector {
	/**
	 * Matches both the long form the tab footer uses ("CityBuild-1") and the short form
	 * ("CB2", "CB 3", "CB-2") in case it appears elsewhere.
	 */
	private static final Pattern CITYBUILD =
			Pattern.compile("\\b(?:cb|citybuild)\\s*-?\\s*([1-9][0-9]?)\\b",
					Pattern.CASE_INSENSITIVE);

	private CityBuildDetector() {
	}

	/** The database name (e.g. {@code cb2}) inferred from the server, or {@code null}. */
	public static String detect(Minecraft minecraft) {
		for (String candidate : candidateTexts(minecraft)) {
			String database = fromText(candidate);

			if (database != null) {
				return database;
			}
		}

		return null;
	}

	/** The database name mentioned in one string, or {@code null}. */
	public static String fromText(String text) {
		if (text == null) {
			return null;
		}

		Matcher matcher = CITYBUILD.matcher(text);
		return matcher.find() ? "cb" + matcher.group(1) : null;
	}

	/**
	 * Every server-provided string the detector inspects, most authoritative first. Exposed so
	 * {@code /primerelics where} can show exactly what the client can see.
	 */
	public static List<String> candidateTexts(Minecraft minecraft) {
		List<String> texts = new ArrayList<>();
		ClientPacketListener connection = minecraft.getConnection();

		if (connection == null) {
			return texts;
		}

		addTabList(minecraft, texts);

		Scoreboard scoreboard = connection.scoreboard();
		Objective sidebar = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR);

		if (sidebar != null) {
			texts.add(sidebar.getDisplayName().getString());

			for (PlayerScoreEntry entry : scoreboard.listPlayerScores(sidebar)) {
				if (!entry.isHidden()) {
					texts.add(entry.ownerName().getString());
				}
			}
		}

		for (Objective objective : scoreboard.getObjectives()) {
			texts.add(objective.getName());
			texts.add(objective.getDisplayName().getString());
		}

		if (minecraft.level != null) {
			texts.add(minecraft.level.dimension().identifier().toString());
		}

		if (minecraft.getCurrentServer() != null) {
			texts.add(minecraft.getCurrentServer().ip);
			texts.add(minecraft.getCurrentServer().name);
		}

		texts.removeIf(text -> text == null || text.isBlank());
		return texts;
	}

	private static void addTabList(Minecraft minecraft, List<String> texts) {
		if (minecraft.gui == null) {
			return;
		}

		PlayerTabOverlay tabList = minecraft.gui.hud.getTabList();

		if (!(tabList instanceof PlayerTabOverlayAccessor accessor)) {
			return;
		}

		// Footer before header: the footer is where the current server is named.
		addComponent(texts, accessor.primerelics$getFooter());
		addComponent(texts, accessor.primerelics$getHeader());
	}

	private static void addComponent(List<String> texts, Component component) {
		if (component != null) {
			texts.add(component.getString());
		}
	}
}
