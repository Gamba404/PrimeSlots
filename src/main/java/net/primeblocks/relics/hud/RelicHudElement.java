package net.primeblocks.relics.hud;

import java.util.List;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

import net.primeblocks.relics.RelicsConfig;

/** Draws the relic overlay onto the in-game HUD. */
public final class RelicHudElement implements HudElement {
	private final RelicOverlayRenderer renderer;
	private final RelicsConfig config;

	public RelicHudElement(RelicOverlayRenderer renderer, RelicsConfig config) {
		this.renderer = renderer;
		this.config = config;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, DeltaTracker deltaTracker) {
		if (!config.hudEnabled) {
			return;
		}

		Minecraft minecraft = Minecraft.getInstance();

		// Gui.extractRenderState only reaches the HUD when the world is being drawn, so F1 and the
		// no-level cases are already handled upstream.
		if (minecraft.player == null) {
			return;
		}

		List<HudLine> lines = renderer.buildLines(false);

		if (lines.isEmpty()) {
			return;
		}

		Font font = minecraft.font;
		float scale = config.scale();

		// Anchoring uses the on-screen size, so a scaled overlay still hugs the edge it was
		// dropped against.
		int boxX = RelicOverlayRenderer.resolveX(config, context.guiWidth(),
				RelicOverlayRenderer.scaledWidth(font, lines, scale));
		int boxY = RelicOverlayRenderer.resolveY(config, context.guiHeight(),
				RelicOverlayRenderer.scaledHeight(font, lines, scale));

		renderer.draw(context, font, lines, boxX, boxY, scale, config.backgroundColour());
	}
}
