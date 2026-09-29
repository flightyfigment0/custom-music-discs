package com.custommusicdiscs.network;

import com.custommusicdiscs.disc.Disc;
import com.custommusicdiscs.disc.DiscManager;

import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.Arrays;

/**
 * Server-side networking: registers the chunked sync payloads and pushes every
 * disc's audio to players when they join, and to everyone after a {@code /discs reload}.
 * <p>
 * Audio is transferred in chunks because the vanilla custom payload is capped at
 * 1 MiB (1048576 bytes), which most real .ogg files exceed.
 */
public final class DiscNetworking {
	/** Chunk size for audio sync, comfortably under the 1 MiB packet cap. */
	public static final int CHUNK_SIZE = 512 * 1024;

	private DiscNetworking() {
	}

	/**
	 * Registers the payload types and the join-broadcast hook.
	 * Called from the main entrypoint, which runs on BOTH the server and the client,
	 * so these types must not be registered again from the client entrypoint.
	 */
	public static void init() {
		PayloadTypeRegistry.clientboundPlay().register(DiscChunkPayload.TYPE, DiscChunkPayload.STREAM_CODEC);
		PayloadTypeRegistry.clientboundPlay().register(DiscSlotRemovedPayload.TYPE, DiscSlotRemovedPayload.STREAM_CODEC);

		// Handle players who join after the initial scan.
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			for (Disc disc : DiscManager.all()) {
				sendChunked(sender, disc);
			}
		});
	}

	/** Splits a disc's audio into {@link #CHUNK_SIZE} chunks and sends them in order. */
	public static void sendChunked(PacketSender sender, Disc disc) {
		byte[] audio = disc.audio();
		if (audio == null || audio.length == 0) {
			return;
		}
		int total = (audio.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
		for (int i = 0; i < total; i++) {
			int from = i * CHUNK_SIZE;
			int to = Math.min(audio.length, from + CHUNK_SIZE);
			sender.sendPacket(new DiscChunkPayload(disc.slot(), i, total, Arrays.copyOfRange(audio, from, to)));
		}
	}

	/**
	 * Re-syncs every online player after a reload: tells them which slots were
	 * emptied, then sends the current contents of every loaded slot.
	 */
	public static void broadcastReload(MinecraftServer server, DiscManager.ReloadResult result) {
		for (ServerPlayer player : server.getPlayerList().getPlayers()) {
			PacketSender sender = ServerPlayNetworking.getSender(player);
			for (Disc removed : result.removed()) {
				sender.sendPacket(new DiscSlotRemovedPayload(removed.slot()));
			}
			for (Disc disc : result.discs()) {
				sendChunked(sender, disc);
			}
		}
	}
}