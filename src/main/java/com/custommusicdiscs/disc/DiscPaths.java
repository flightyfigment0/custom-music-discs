package com.custommusicdiscs.disc;

import com.custommusicdiscs.CustomMusicDiscs;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Resolves the directories where players/server owners can drop .ogg files.
 * <p>
 * A default folder, {@code config/custom-music-discs/discs}, is always created.
 * Extra folders can be listed in {@code config/custom-music-discs/discs.txt}, one
 * absolute or server-relative path per line (lines starting with {@code #} are ignored).
 */
public final class DiscPaths {
	public static final String FALLBACK_LIST_NAME = "discs.txt";

	private DiscPaths() {
	}

	public static Path configRoot() {
		return Path.of("config", CustomMusicDiscs.MOD_ID);
	}

	public static Path defaultDiscFolder() {
		return configRoot().resolve("discs");
	}

	private static Path listFile() {
		return configRoot().resolve(FALLBACK_LIST_NAME);
	}

	/**
	 * Returns the list of directories to scan for .ogg files, creating
	 * the default folder (and a helpful discs.txt) if they do not exist yet.
	 */
	public static List<Path> discDirectories() {
		List<Path> result = new ArrayList<>();
		try {
			Files.createDirectories(configRoot());
			Files.createDirectories(defaultDiscFolder());
			writeExampleListIfMissing();
		} catch (IOException e) {
			CustomMusicDiscs.LOGGER.error("Could not set up discs directory: {}", e.toString());
			return List.of(defaultDiscFolder());
		}

		result.add(defaultDiscFolder());
		try {
			List<String> lines = Files.readAllLines(listFile());
			for (String line : lines) {
				String trimmed = line.trim();
				if (trimmed.isEmpty() || trimmed.startsWith("#")) {
					continue;
				}
				Path p = Path.of(trimmed);
				if (!p.isAbsolute()) {
					p = Path.of(".").resolve(p).normalize();
				}
				result.add(p);
			}
		} catch (IOException e) {
			// disks.txt is optional; just use the default folder.
		}
		return result;
	}

	private static void writeExampleListIfMissing() throws IOException {
		Path list = listFile();
		if (Files.exists(list)) {
			return;
		}
		List<String> lines = Arrays.asList(
				"# custom-music-discs",
				"#",
				"# Add additional folders to scan for .ogg music disc files.",
				"# Each non-empty, non-comment line is a path (absolute, or relative to the server directory).",
				"# The default folder below is always scanned:",
				"#   config/custom-music-discs/discs",
				"",
				"# example:",
				"# /home/user/mymusic");
		Files.write(list, lines);
	}

	/**
	 * Normalizes a file base name into a registry-safe id (lowercase, [a-z0-9_].
	 */
	public static String sanitizeId(String raw) {
		String s = raw.toLowerCase().replaceAll("[^a-z0-9_.-]", "_").replaceAll("[.]", "_").replaceAll("-", "_");
		s = s.replaceAll("^_+|_+$", "");
		return s;
	}
}
