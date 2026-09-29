package com.custommusicdiscs.command;

import com.custommusicdiscs.disc.Disc;
import com.custommusicdiscs.disc.DiscManager;
import com.custommusicdiscs.network.DiscNetworking;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;

import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;

import net.minecraft.commands.CommandBuildContext;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;

import java.util.List;
import java.util.stream.Collectors;

/**
 * The {@code /discs} command, usable by ANY player (no operator permission needed).
 * <p>
 * {@code /discs reload} - rescans the server's discs folders, re-assigns the slot
 * pool, and pushes the fresh audio to every connected player.
 * {@code /discs list} - lists the discs currently loaded on this server.
 */
public final class DiscsCommand {
	private DiscsCommand() {
	}

	public static void register() {
		CommandRegistrationCallback.EVENT.register(DiscsCommand::register);
	}

	private static void register(CommandDispatcher<CommandSourceStack> dispatcher, CommandBuildContext access, Commands.CommandSelection env) {
		dispatcher.register(
				Commands.literal("discs")
						.then(Commands.literal("reload")
								.executes(DiscsCommand::reload))
						.then(Commands.literal("list")
								.executes(DiscsCommand::list)));
	}

	private static int reload(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		MinecraftServer server = source.getServer();

		DiscManager.ReloadResult result = DiscManager.reload();
		DiscNetworking.broadcastReload(server, result);

		List<String> names = result.discs().stream().map(Disc::id).collect(Collectors.toList());
		source.sendSuccess(() -> Component.literal(
				"Discs reloaded: " + names.size() + " loaded, " + result.removed().size() + " removed."), false);
		if (!names.isEmpty()) {
			source.sendSuccess(() -> Component.literal("Available: " + String.join(", ", names)), false);
		}
		return names.size();
	}

	private static int list(CommandContext<CommandSourceStack> ctx) {
		CommandSourceStack source = ctx.getSource();
		List<Disc> discs = DiscManager.all();
		if (discs.isEmpty()) {
			source.sendSuccess(() -> Component.literal("No custom discs loaded. Upload audio to the server's discs folder, then run /discs reload."), false);
			return 0;
		}
		String names = discs.stream().map(Disc::id).collect(Collectors.joining(", "));
		source.sendSuccess(() -> Component.literal("Loaded discs: " + names), false);
		return discs.size();
	}
}