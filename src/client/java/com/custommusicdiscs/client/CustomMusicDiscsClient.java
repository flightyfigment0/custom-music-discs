package com.custommusicdiscs.client;

import com.custommusicdiscs.network.DiscChunkPayload;
import com.custommusicdiscs.network.DiscSlotRemovedPayload;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

import net.minecraft.resources.Identifier;

import java.util.HashMap;
import java.util.Map;

/**
 * Client entrypoint: receives the chunked disc sync from the server, reassembles
 * each slot's audio, caches the bytes and wires the slot's sound into the client
 * {@link net.minecraft.client.sounds.SoundManager}.
 * <p>
 * Payload types are registered from the main entrypoint ({@code DiscNetworking.init}),
 * which runs on both sides, so they must NOT be registered again here.
 */
public class CustomMusicDiscsClient implements ClientModInitializer {
	/** Per-slot in-progress chunk reassembly buffers. */
	private static final Map<Integer, byte[][]> PENDING_SLOTS = new HashMap<>();
	private static final Map<Integer, Integer> PENDING_TOTALS = new HashMap<>();

	@Override
	public void onInitializeClient() {
		// Drop everything from the previous server when joining a new one.
		ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
			DiscAudioCache.clearMemoryCacheOnly();
			synchronized (CustomMusicDiscsClient.class) {
				PENDING_SLOTS.clear();
				PENDING_TOTALS.clear();
			}
		});

		// Chunked audio for a slot; reassemble and register when the final chunk lands.
		// Handlers run on the netty event loop; hop to the render thread before touching
		// the client's audio cache and sound manager.
		ClientPlayNetworking.registerGlobalReceiver(DiscChunkPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				byte[] complete = assemble(payload);
				if (complete != null) {
					Identifier fileLocation = DiscSoundRegistrar.soundFileLocation(payload.slot());
					DiscAudioCache.put(fileLocation, complete);
					DiscSoundRegistrar.ensureRegistered(payload.slot());
				}
			});
		});

		// A slot was emptied on the server: drop its audio and unregister its sound.
		ClientPlayNetworking.registerGlobalReceiver(DiscSlotRemovedPayload.TYPE, (payload, context) -> {
			context.client().execute(() -> {
				synchronized (CustomMusicDiscsClient.class) {
					PENDING_SLOTS.remove(payload.slot());
					PENDING_TOTALS.remove(payload.slot());
				}
				DiscSoundRegistrar.remove(payload.slot());
				DiscAudioCache.remove(DiscSoundRegistrar.soundFileLocation(payload.slot()));
			});
		});
	}

	/**
	 * Feeds one chunk into the reassembly buffer for its slot. Returns the full audio
	 * bytes once every chunk has arrived, otherwise {@code null}.
	 * <p>
	 * Chunks for a single slot are sent in order over one connection, so reassembly
	 * simply appends by chunk index. If the server restarts a transfer (new total size),
	 * the buffer resets.
	 */
	private static synchronized byte[] assemble(DiscChunkPayload payload) {
		int slot = payload.slot();
		int total = payload.totalChunks();
		Integer knownTotal = PENDING_TOTALS.get(slot);
		if (knownTotal == null || knownTotal != total) {
			PENDING_SLOTS.put(slot, new byte[total][]);
			PENDING_TOTALS.put(slot, total);
		}
		byte[][] parts = PENDING_SLOTS.get(slot);
		if (payload.chunkIndex() < 0 || payload.chunkIndex() >= total) {
			return null;
		}
		parts[payload.chunkIndex()] = payload.chunk();

		int filled = 0;
		for (byte[] part : parts) {
			if (part != null) {
				filled++;
			}
		}
		if (filled < total) {
			return null;
		}

		int size = 0;
		for (byte[] part : parts) {
			size += part.length;
		}
		byte[] full = new byte[size];
		int offset = 0;
		for (byte[] part : parts) {
			System.arraycopy(part, 0, full, offset, part.length);
			offset += part.length;
		}

		PENDING_SLOTS.remove(slot);
		PENDING_TOTALS.remove(slot);
		return full;
	}
}