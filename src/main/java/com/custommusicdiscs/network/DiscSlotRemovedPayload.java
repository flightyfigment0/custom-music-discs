package com.custommusicdiscs.network;

import com.custommusicdiscs.CustomMusicDiscs;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Server -> client payload telling the client that a disc slot was emptied (its
 * file was removed from the discs folder, or the pool was resized down). The client
 * drops the cached audio and unregisters the slot's sound.
 *
 * @param slot the disc slot that is no longer in use
 */
public record DiscSlotRemovedPayload(int slot) implements CustomPacketPayload {
	public static final Identifier S2C_ID = CustomMusicDiscs.id("disc_slot_removed");
	public static final Type<DiscSlotRemovedPayload> TYPE = new Type<>(S2C_ID);

	public static final StreamCodec<RegistryFriendlyByteBuf, DiscSlotRemovedPayload> STREAM_CODEC =
			StreamCodec.composite(
					ByteBufCodecs.VAR_INT, DiscSlotRemovedPayload::slot,
					DiscSlotRemovedPayload::new);

	@Override
	public Type<? extends CustomPacketPayload> type() {
		return TYPE;
	}
}