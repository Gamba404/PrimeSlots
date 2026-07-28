package net.primeblocks.relics.hud;

/**
 * One rendered line of the overlay.
 *
 * @param text   plain text
 * @param color  ARGB colour
 * @param indent extra left offset in pixels
 */
public record HudLine(String text, int color, int indent) {
	public HudLine(String text, int color) {
		this(text, color, 0);
	}
}
