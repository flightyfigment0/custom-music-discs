package com.custommusicdiscs.command;

import com.custommusicdiscs.CustomMusicDiscs;
import com.custommusicdiscs.disc.Disc;
import com.custommusicdiscs.disc.DiscManager;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.JukeboxPlayable;
import net.minecraft.world.item.JukeboxSong;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The {@code /getdisc} command, usable by any player (no operator permission needed).
 * <p>
 * {@code /getdisc <name>} - gives that player the named music disc.
 * {@code /getdisc} - lists the available discs.
 * {@code /getdisc list} - same as listing the available discs.
 * <p>
 * Discs are one shared item that points at the jukebox song registered for the
 * disc's slot, so the stack stays saveable and every client can play the slot's
 * sound event.
 */
public final class GetDiscCommand {
	private GetDiscCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register(GetDiscCommand::register);
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext access, Commands.CommandSelection env) {
		dispatcher.register(
				Commands.literal("getdisc")
						.executes(ctx -> listDiscs(ctx))
						.then(Commands.argument("name", StringArgumentType.string())
								.executes(ctx -> giveDisc(ctx, StringArgumentType.getString(ctx, "name")))));
	}

	private static int giveDisc(CommandContext<CommandSourceStack> ctx, String name) {
		CommandSourceStack source = ctx.getSource();
		if (!(source.getEntity() instanceof ServerPlayer player)) {
			source.sendFailure(Component.literal("This command can only be used by a player."));
			return 0;
		}

		Disc disc = DiscManager.get(name);
		if (disc == null) {
			source.sendFailure(Component.literal("Unknown disc '" + name + "'."));
			return 0;
		}

		// The slot's own item, so the disc shows that slot's colour.
		ItemStack stack = new ItemStack(DiscManager.discItem(disc.slot()));
		String displayName = CustomMusicDiscs.formatName(disc.id());

		// The song has to be the slot's registered one and not a value embedded in the
		// stack: vanilla only writes a JUKEBOX_PLAYABLE by song name, so an embedded
		// Holder.direct song made every disc unsavable and the game dropped it from
		// inventories and jukeboxes on each save.
		Holder.Reference<JukeboxSong> song = DiscManager.slotSong(disc.slot(), player.level().registryAccess())
				.orElse(null);
		if (song == null) {
			source.sendFailure(Component.literal("Slot " + disc.slot() + " has no registered song."));
			return 0;
		}
		stack.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(song));
		stack.set(DataComponents.CUSTOM_NAME, Component.literal("Music Disc \u2014 " + displayName));

		if (!player.getInventory().add(stack)) {
			player.drop(stack, false);
		}
		player.sendSystemMessage(Component.literal("You received music disc '" + name + "'."));
		return 1;
	}

	private static int listDiscs(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		List<Disc> discs = DiscManager.all();
		if (discs.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No custom discs are available on this server."), false);
			return 0;
		}
		String names = discs.stream().map(Disc::id).collect(Collectors.joining(", "));
		source.sendSuccess(() -> Component.literal("Available discs: " + names), false);
		return discs.size();
	}
}