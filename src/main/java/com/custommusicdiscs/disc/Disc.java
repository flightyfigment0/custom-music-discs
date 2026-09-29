package com.custommusicdiscs.disc;

import net.minecraft.sounds.SoundEvent;

/**
 * Represents a single custom music disc occupying one of the fixed disc slots.
 * <p>
 * The mod pre-registers a fixed pool of sound events (one per slot) at init time,
 * because the game's registries are frozen after bootstrap and new items/sounds
 * cannot be added at runtime. An uploaded file is assigned to a free slot and its
 * audio bytes are pushed to clients, which cache them under the slot's sound
 * location so the jukebox can play them.
 */
public record Disc(
		int slot,
		String id,
		String fileName,
		SoundEvent soundEvent,
		byte[] audio,
		float durationSeconds
) {
	public int audioLength() {
		return audio.length;
	}

	@Override
	public String toString() {
		return "Disc[" + id + "@slot" + slot + "]";
	}

	@Override
	public boolean equals(Object o) {
		if (this == o) {
			return true;
		}
		if (!(o instanceof Disc disc)) {
			return false;
		}
		return slot == disc.slot && id.equals(disc.id);
	}

	@Override
	public int hashCode() {
		return 31 * slot + id.hashCode();
	}
}