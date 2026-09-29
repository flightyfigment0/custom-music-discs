package com.custommusicdiscs.client;

import com.custommusicdiscs.CustomMusicDiscs;
import com.custommusicdiscs.client.mixin.SoundManagerAccessor;
import com.custommusicdiscs.disc.DiscManager;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.Identifier;

/**
 * Wires each disc slot's sound into the client {@link SoundManager} so the jukebox
 * can look up and stream the audio that was transferred from the server.
 * <p>
 * The slot {@link net.minecraft.sounds.SoundEvent}s themselves are pre-registered at
 * init time on BOTH sides (they live in the frozen registry), so no registry writes
 * happen here. This class only injects a {@link WeighedSoundEvents} entry into the
 * {@link SoundManager}'s mutable sound map.
 */
@Environment(EnvType.CLIENT)
public final class DiscSoundRegistrar {
	private DiscSoundRegistrar() {
	}

	/** The resource location of a slot's sound file, e.g. custom-music-discs:sounds/disc_slot_3.ogg. */
	public static Identifier soundFileLocation(int slot) {
		return CustomMusicDiscs.id("sounds/disc_slot_" + slot + ".ogg");
	}

	/**
	 * Adds a playable, streaming sound for the given slot, pointing at the cached
	 * audio file. Safe to call repeatedly; later calls overwrite the same key.
	 */
	public static void ensureRegistered(int slot) {
		Identifier eventId = DiscManager.slotSoundId(slot);
		SoundManager soundManager = Minecraft.getInstance().getSoundManager();
		if (soundManager == null) {
			CustomMusicDiscs.LOGGER.warn("Sound manager not ready for slot {}; will retry on next sync.", slot);
			return;
		}

		// The Sound's location is what the audio engine opens via SoundBufferLibrary,
		// which our mixin serves from the audio cache — it MUST be the cache key.
		Identifier fileLocation = soundFileLocation(slot);
		if (!DiscAudioCache.has(fileLocation)) {
			CustomMusicDiscs.LOGGER.warn("Slot {} has no cached audio yet; sound will not play until bytes arrive.", slot);
		}

		Sound sound = new Sound(
				fileLocation,
				r -> 1.0f,
				r -> 1.0f,
				1,
				Sound.Type.FILE,
				true,
				true,
				16);
		WeighedSoundEvents weighed = new WeighedSoundEvents(eventId, "");
		weighed.addSound(sound);

		SoundManagerAccessor accessor = (SoundManagerAccessor) soundManager;
		accessor.customMusicDiscs$getRegistry().put(eventId, weighed);
	}

	/** Removes a slot's sound entry (e.g. after the server emptied the slot). */
	public static void remove(int slot) {
		SoundManager soundManager = Minecraft.getInstance().getSoundManager();
		if (soundManager == null) {
			return;
		}
		((SoundManagerAccessor) soundManager).customMusicDiscs$getRegistry().remove(DiscManager.slotSoundId(slot));
	}
}