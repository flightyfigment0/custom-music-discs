package com.custommusicdiscs.client;

import com.custommusicdiscs.CustomMusicDiscs;

import net.minecraft.resources.Identifier;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client-side store of the audio bytes for each disc, received from the server.
 * <p>
 * The bytes are held in memory (so they survive resource reloads) and also cached
 * to disk inside the game directory so they can be reused between game sessions.
 */
public final class DiscAudioCache {
	private static final Map<Identifier, byte[]> AUDIO = new ConcurrentHashMap<>();

	private DiscAudioCache() {
	}

	/** Called when a {@link com.custommusicdiscs.network.DiscChunkPayload} fully arrives. */
	public static void put(Identifier audioLocation, byte[] bytes) {
		if (bytes == null || bytes.length == 0) {
			return;
		}
		AUDIO.put(audioLocation, bytes);
		CustomMusicDiscs.LOGGER.info("Cached {} bytes of audio for {}", bytes.length, audioLocation);
		try {
			Path path = diskPath(audioLocation);
			Files.createDirectories(path.getParent());
			Files.write(path, bytes);
		} catch (IOException e) {
			CustomMusicDiscs.LOGGER.warn("Could not write audio cache file for {}: {}", audioLocation, e.toString());
		}
	}

	public static boolean has(Identifier audioLocation) {
		return AUDIO.containsKey(audioLocation);
	}

	/**
	 * Returns an input stream over the stored bytes for the given sound file location,
	 * or {@code null} if the client does not have the audio for that location yet.
	 */
	public static InputStream open(Identifier audioLocation) {
		byte[] bytes = AUDIO.get(audioLocation);
		return bytes == null ? null : new ByteArrayInputStream(bytes);
	}

	/** Clears the in-memory map when joining a new server so stale discs from the previous one are forgotten. */
	public static void clearMemoryCacheOnly() {
		AUDIO.clear();
	}

	/** Drops a slot's audio, from memory and from the on-disk cache. */
	public static void remove(Identifier audioLocation) {
		AUDIO.remove(audioLocation);
		try {
			Files.deleteIfExists(diskPath(audioLocation));
		} catch (IOException e) {
			CustomMusicDiscs.LOGGER.warn("Could not delete audio cache file for {}: {}", audioLocation, e.toString());
		}
	}

	private static Path diskPath(Identifier soundFileLocation) {
		// soundFileLocation is like custom-music-discs:sounds/disc_x.ogg
		String name = soundFileLocation.getPath().replace('/', '_');
		return Path.of(".").resolve("custom-music-discs").resolve("discs").resolve(name).toAbsolutePath().normalize();
	}
}
