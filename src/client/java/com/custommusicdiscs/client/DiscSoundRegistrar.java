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

	/**
	 * A slot's sound id, e.g. {@code custom-music-discs:disc_slot_3}. This is the form a
	 * {@link Sound} stores: the audio engine turns it into a file location with
	 * {@code getPath()} before opening it.
	 */
	public static Identifier soundId(int slot) {
		return DiscManager.slotSoundId(slot);
	}

	/**
	 * The file the engine actually opens for a slot, e.g.
	 * {@code custom-music-discs:sounds/disc_slot_3.ogg}. Derived with the same converter
	 * vanilla uses so it cannot drift from what {@link Sound#getPath()} produces; this is
	 * also the audio cache key.
	 */
	public static Identifier soundFileLocation(int slot) {
		return Sound.SOUND_LISTER.idToFile(soundId(slot));
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

		// The Sound must be given the sound ID, not the file location: the engine opens
		// Sound.getPath(), which runs the id through the sounds converter. Handing it a
		// path that already reads "sounds/...ogg" makes the engine look for
		// "sounds/sounds/...ogg.ogg", the audio cache misses, and playback fails silently.
		Identifier fileLocation = soundFileLocation(slot);
		if (!DiscAudioCache.has(fileLocation)) {
			CustomMusicDiscs.LOGGER.warn("Slot {} has no cached audio yet; sound will not play until bytes arrive.", slot);
		}

		Sound sound = new Sound(
				soundId(slot),
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