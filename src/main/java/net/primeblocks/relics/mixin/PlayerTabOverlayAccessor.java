package net.primeblocks.relics.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.components.PlayerTabOverlay;
import net.minecraft.network.chat.Component;

/**
 * Exposes the tab list header and footer, which vanilla only lets you write.
 *
 * <p>PrimeBlocks names the current server in the tab footer ("Du befindest dich derzeit auf:
 * CityBuild-1 (Farmwelt-5)"), and that is the only place the client can read it. Nothing else the
 * client receives — scoreboard, dimension id, server address — distinguishes one citybuild from
 * another.
 */
@Mixin(PlayerTabOverlay.class)
public interface PlayerTabOverlayAccessor {
	@Accessor("header")
	Component primerelics$getHeader();

	@Accessor("footer")
	Component primerelics$getFooter();
}
