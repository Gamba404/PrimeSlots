package net.primeblocks.relics.tracking;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.ItemLore;

/**
 * An immutable, plain-text copy of an open container screen. Taken on the client thread; safe to
 * read anywhere afterwards.
 */
public record ContainerSnapshot(String title, List<GuiSlotSnapshot> slots) {
	/**
	 * Captures the container part of {@code screen}. The player's own inventory slots are skipped:
	 * on a chest-style menu those sit after {@code menu.slots.size() - 36}, and they carry no
	 * information about the relic menu itself.
	 */
	public static ContainerSnapshot capture(AbstractContainerScreen<?> screen) {
		List<Slot> menuSlots = screen.getMenu().slots;
		int containerSlotCount = Math.max(0, menuSlots.size() - 36);
		List<GuiSlotSnapshot> captured = new ArrayList<>(containerSlotCount);

		for (int index = 0; index < containerSlotCount; index++) {
			ItemStack stack = menuSlots.get(index).getItem();

			if (stack.isEmpty()) {
				continue;
			}

			captured.add(new GuiSlotSnapshot(
					index,
					BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),
					stack.getHoverName().getString(),
					loreOf(stack),
					stack.hasFoil(),
					stack.getCount()
			));
		}

		return new ContainerSnapshot(screen.getTitle().getString(), List.copyOf(captured));
	}

	private static List<String> loreOf(ItemStack stack) {
		ItemLore lore = stack.get(DataComponents.LORE);

		if (lore == null) {
			return List.of();
		}

		List<String> lines = new ArrayList<>(lore.lines().size());

		for (Component line : lore.lines()) {
			lines.add(line.getString());
		}

		return List.copyOf(lines);
	}
}
