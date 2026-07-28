package net.primeblocks.relics.hud;

import java.util.List;
import java.util.Locale;

import org.lwjgl.glfw.GLFW;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

import net.primeblocks.relics.RelicsConfig;

/**
 * Drag-to-position editor and settings panel for the overlay, in the spirit of Inventory HUD+.
 *
 * <p>Grab the box with the mouse, nudge it with the arrow keys, resize with the mouse wheel.
 * Everything is committed to the config when the screen closes.
 *
 * <p>Which edge the box is anchored to is decided on drop: a box sitting in the right half of the
 * screen stores a negative {@code hudX}, so it keeps hugging the right edge when the window or GUI
 * scale changes instead of drifting.
 */
public final class RelicHudEditScreen extends Screen {
	private static final int DIM = 0xC0101010;
	private static final int OUTLINE = 0xFFFFD966;
	private static final int OUTLINE_DRAGGING = 0xFF7ED37E;
	private static final int HINT = 0xFFBFBFBF;

	private static final int DEFAULT_X = 4;
	private static final int DEFAULT_Y = 4;

	/** Panel alpha presets cycled by the background button. */
	private static final int[] OPACITY_STEPS = {0, 64, 144, 216};
	private static final String[] OPACITY_LABELS = {"aus", "leicht", "normal", "kräftig"};

	private static final int BUTTON_WIDTH = 108;
	private static final int BUTTON_HEIGHT = 20;
	private static final int BUTTON_GAP = 4;

	private final RelicOverlayRenderer renderer;
	private final RelicsConfig config;

	private int boxX;
	private int boxY;
	private int boxWidth;
	private int boxHeight;

	private boolean dragging;
	private int grabOffsetX;
	private int grabOffsetY;
	private Button scaleButton;

	public RelicHudEditScreen(RelicOverlayRenderer renderer, RelicsConfig config) {
		super(Component.literal("PrimeSlots — HUD einstellen"));
		this.renderer = renderer;
		this.config = config;
	}

	@Override
	protected void init() {
		measure();
		boxX = RelicOverlayRenderer.resolveX(config, width, boxWidth);
		boxY = RelicOverlayRenderer.resolveY(config, height, boxHeight);
		clampIntoView();

		int columns = 4;
		int rowWidth = columns * BUTTON_WIDTH + (columns - 1) * BUTTON_GAP;
		int startX = (width - rowWidth) / 2;
		int row1 = height - 2 * (BUTTON_HEIGHT + BUTTON_GAP) - 46;
		int row2 = row1 + BUTTON_HEIGHT + BUTTON_GAP;

		addToggle(startX, row1, "Hintergrund", this::opacityLabel, this::cycleOpacity);
		addToggle(startX + (BUTTON_WIDTH + BUTTON_GAP), row1, "Relikte",
				() -> onOff(config.showRelics), () -> config.showRelics = !config.showRelics);
		addToggle(startX + 2 * (BUTTON_WIDTH + BUTTON_GAP), row1, "Einzelwerte",
				() -> onOff(config.showStats), () -> config.showStats = !config.showStats);
		addToggle(startX + 3 * (BUTTON_WIDTH + BUTTON_GAP), row1, "Synergien",
				() -> onOff(config.showSynergies), () -> config.showSynergies = !config.showSynergies);

		addToggle(startX, row2, "Werte",
				() -> onOff(config.showTotals), () -> config.showTotals = !config.showTotals);
		addToggle(startX + (BUTTON_WIDTH + BUTTON_GAP), row2, "Stats",
				() -> config.hideMaluses ? "nur Boni" : "alle",
				() -> config.hideMaluses = !config.hideMaluses);
		scaleButton = addToggle(startX + 2 * (BUTTON_WIDTH + BUTTON_GAP), row2, "Größe",
				this::scaleLabel, () -> changeScale(RelicsConfig.SCALE_STEP));
		addToggle(startX + 3 * (BUTTON_WIDTH + BUTTON_GAP), row2, "Zurücksetzen",
				() -> "Standard", this::resetAll);
	}

	/**
	 * A button whose label is recomputed on every press, so it always reflects the live setting.
	 * Vanilla's CycleButton wants a fixed value list, which does not fit a plain boolean flip plus
	 * a re-measure of the preview.
	 */
	private Button addToggle(int x, int y, String name, LabelSupplier label, Runnable action) {
		Button button = Button.builder(Component.literal(name + ": " + label.get()), pressed -> {
			action.run();
			pressed.setMessage(Component.literal(name + ": " + label.get()));
			measure();
			clampIntoView();
		}).bounds(x, y, BUTTON_WIDTH, BUTTON_HEIGHT).build();

		return addRenderableWidget(button);
	}

	private void refreshScaleLabel() {
		if (scaleButton != null) {
			scaleButton.setMessage(Component.literal("Größe: " + scaleLabel()));
		}
	}

	private String onOff(boolean value) {
		return value ? "an" : "aus";
	}

	private String opacityLabel() {
		int current = Math.clamp(config.backgroundOpacity, 0, 255);
		int best = 0;

		for (int i = 1; i < OPACITY_STEPS.length; i++) {
			if (Math.abs(OPACITY_STEPS[i] - current) < Math.abs(OPACITY_STEPS[best] - current)) {
				best = i;
			}
		}

		return OPACITY_LABELS[best];
	}

	private void cycleOpacity() {
		int current = Math.clamp(config.backgroundOpacity, 0, 255);
		int index = 0;

		for (int i = 1; i < OPACITY_STEPS.length; i++) {
			if (Math.abs(OPACITY_STEPS[i] - current) < Math.abs(OPACITY_STEPS[index] - current)) {
				index = i;
			}
		}

		config.backgroundOpacity = OPACITY_STEPS[(index + 1) % OPACITY_STEPS.length];
	}

	private String scaleLabel() {
		return String.format(Locale.ROOT, "%.0f%%", config.scale() * 100);
	}

	/**
	 * Left click on the size button grows the overlay, right click shrinks it.
	 *
	 * <p>Rounded to whole steps because {@code scale()} returns a float: adding to it repeatedly
	 * would otherwise drift into values like 1.0000000953674317 and write those to the config.
	 */
	private void changeScale(double delta) {
		double next = Math.round((config.scale() + delta) / RelicsConfig.SCALE_STEP)
				* RelicsConfig.SCALE_STEP;
		config.hudScale = Math.clamp(next, RelicsConfig.MIN_SCALE, RelicsConfig.MAX_SCALE);
		refreshScaleLabel();
	}

	private void resetAll() {
		config.hudScale = 1.0;
		refreshScaleLabel();
		config.backgroundOpacity = 144;
		config.showRelics = true;
		config.showStats = false;
		config.showSynergies = true;
		config.showTotals = true;
		config.hideMaluses = false;
		config.hudX = DEFAULT_X;
		config.hudY = DEFAULT_Y;
		boxX = DEFAULT_X;
		boxY = DEFAULT_Y;
	}

	private void measure() {
		List<HudLine> lines = renderer.buildLines(true);
		float scale = config.scale();
		boxWidth = RelicOverlayRenderer.scaledWidth(font, lines, scale);
		boxHeight = RelicOverlayRenderer.scaledHeight(font, lines, scale);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor context, int mouseX, int mouseY, float delta) {
		context.fill(0, 0, width, height, DIM);

		List<HudLine> lines = renderer.buildLines(true);
		measure();
		clampIntoView();

		renderer.draw(context, font, lines, boxX, boxY, config.scale(), config.backgroundColour());
		drawOutline(context, dragging ? OUTLINE_DRAGGING : OUTLINE);

		super.extractRenderState(context, mouseX, mouseY, delta);

		context.centeredText(font, Component.literal(
						"Ziehen zum Verschieben  ·  Größe: Linksklick größer, Rechtsklick kleiner"),
				width / 2, height - 30, RelicOverlayRenderer.COLOUR_TITLE);
		context.centeredText(font, Component.literal("Esc speichern  ·  " + describeAnchor()),
				width / 2, height - 16, HINT);
	}

	private void drawOutline(GuiGraphicsExtractor context, int colour) {
		int right = boxX + boxWidth;
		int bottom = boxY + boxHeight;

		context.fill(boxX - 1, boxY - 1, right + 1, boxY, colour);
		context.fill(boxX - 1, bottom, right + 1, bottom + 1, colour);
		context.fill(boxX - 1, boxY, boxX, bottom, colour);
		context.fill(right, boxY, right + 1, bottom, colour);
	}

	private String describeAnchor() {
		return (anchorsBottom() ? "unten" : "oben") + " " + (anchorsRight() ? "rechts" : "links")
				+ "  ·  Größe " + scaleLabel();
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		// Vanilla buttons only react to the left button, so the shrink half is handled here,
		// before the widgets get a look at the click.
		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && isOverScaleButton(event.x(), event.y())) {
			changeScale(-RelicsConfig.SCALE_STEP);
			measure();
			clampIntoView();
			return true;
		}

		if (super.mouseClicked(event, doubleClick)) {
			return true;
		}

		if (event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && isInsideBox(event.x(), event.y())) {
			dragging = true;
			grabOffsetX = (int) event.x() - boxX;
			grabOffsetY = (int) event.y() - boxY;
			return true;
		}

		return false;
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent event, double dragX, double dragY) {
		if (!dragging) {
			return super.mouseDragged(event, dragX, dragY);
		}

		boxX = (int) event.x() - grabOffsetX;
		boxY = (int) event.y() - grabOffsetY;
		clampIntoView();
		return true;
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent event) {
		if (dragging && event.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
			dragging = false;
			return true;
		}

		return super.mouseReleased(event);
	}

	private boolean isOverScaleButton(double x, double y) {
		return scaleButton != null
				&& x >= scaleButton.getX() && x < scaleButton.getX() + scaleButton.getWidth()
				&& y >= scaleButton.getY() && y < scaleButton.getY() + scaleButton.getHeight();
	}

	private boolean isInsideBox(double x, double y) {
		return x >= boxX && x < boxX + boxWidth && y >= boxY && y < boxY + boxHeight;
	}

	private void clampIntoView() {
		boxX = Math.max(0, Math.min(boxX, Math.max(0, width - boxWidth)));
		boxY = Math.max(0, Math.min(boxY, Math.max(0, height - boxHeight)));
	}

	private boolean anchorsRight() {
		return boxX + boxWidth / 2 > width / 2;
	}

	private boolean anchorsBottom() {
		return boxY + boxHeight / 2 > height / 2;
	}

	/** Positive = offset from the left edge, negative = gap from the right edge. */
	private int storedX() {
		return anchorsRight() ? boxX + boxWidth - width : boxX;
	}

	private int storedY() {
		return anchorsBottom() ? boxY + boxHeight - height : boxY;
	}

	@Override
	public void onClose() {
		config.hudX = storedX();
		config.hudY = storedY();
		config.save();
		super.onClose();
	}

	@FunctionalInterface
	private interface LabelSupplier {
		String get();
	}
}
