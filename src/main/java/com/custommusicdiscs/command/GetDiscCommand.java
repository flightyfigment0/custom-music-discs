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
 * Discs are one shared item whose jukebox song (sound, title, length) is embedded
 * directly into the stack's components, so each disc can carry its real song even
 * though the mod's registries stay fixed at init time.
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

		ItemStack stack = new ItemStack(DiscManager.discItem());
		String displayName = CustomMusicDiscs.formatName(disc.id());

		// Per-stack jukebox song: embedded directly (Holder.direct) so the title and
		// length travel with the stack and the client can play the slot's sound event.
		JukeboxSong song = new JukeboxSong(
				Holder.direct(disc.soundEvent()),
				Component.literal(displayName),
				DiscManager.estimateDurationSeconds(disc.audioLength()),
				15);
		stack.set(DataComponents.JUKEBOX_PLAYABLE, new JukeboxPlayable(Holder.direct(song)));
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