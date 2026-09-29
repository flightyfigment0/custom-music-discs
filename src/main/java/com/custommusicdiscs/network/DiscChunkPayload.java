package com.custommusicdiscs.network;

import com.custommusicdiscs.CustomMusicDiscs;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client payload carrying one chunk of a disc's audio so the client can
 * play it despite it never being part of a resource pack.
 * <p>
 * The vanilla custom payload packet caps at 1 MiB, so audio is transferred in
 * {@link DiscNetworking#CHUNK_SIZE}-byte chunks. The client reassembles the chunks
 * per slot and caches the full file under the slot's sound location.
 *
 * @param slot        the disc slot (0-based, corresponds to {@code disc_slot_<slot>})
 * @param chunkIndex  zero-based index of this chunk
 * @param totalChunks total number of chunks for this slot's audio
 * @param chunk       this chunk's bytes
 */
public record DiscChunkPayload(int slot, int chunkIndex, int totalChunks, byte[] chunk) implements CustomPacketPayload {
	public static final Identifier S2C_ID = CustomMusicDiscs.id("disc_chunk");
	public static final Type<DiscChunkPayload> TYPE = new Type<>(S2C_ID);

	public static final StreamCodec<RegistryFriendlyByteBuf, DiscChunkPayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, DiscChunkPayload::slot,
					ByteBufCodecs.VAR_INT, DiscChunkPayload::chunkIndex,
					ByteBufCodecs.VAR_INT, DiscChunkPayload::totalChunks,
					ByteBufCodecs.BYTE_ARRAY, DiscChunkPayload::chunk,
					DiscChunkPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}