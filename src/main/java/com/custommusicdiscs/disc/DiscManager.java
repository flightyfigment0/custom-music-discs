package com.custommusicdiscs.disc;

import com.custommusicdiscs.CustomMusicDiscs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Rarity;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Manages the fixed pool of disc slots.
 * <p>
 * Because the game's registries are frozen after bootstrap, the mod pre-registers
 * a fixed number of sound events (one per slot) plus a single shared disc item at
 * init time, on both the server and the client. Uploaded audio files are assigned
 * to free slots; the {@code /discs reload} command rescans the discs folders and
 * re-broadcasts the current slot contents to every connected player.
 */
public final class DiscManager {
	/** How many discs the server can hold. Must match between client and server (it is a shared constant). */
	public static final int SLOT_COUNT = 16;
	public static final String ITEM_ID = "custom_music_disc";

	private static final Disc[] SLOTS = new Disc[SLOT_COUNT];
	private static final Map<String, Integer> ID_TO_SLOT = new LinkedHashMap<>();
	private static final Map<Integer, SoundEvent> SLOT_SOUNDS = new LinkedHashMap<>();
	private static final Path SLOTS_FILE = DiscPaths.configRoot().resolve("slots.json");

	private static Item discItem;

	private DiscManager() {
	}

	/**
	 * Registers the slot pool (sound events + item) and then scans the discs folders.
	 * Runs on both the client and the server from the main entrypoint.
	 */
	public static void init() {
		registerPool();
		reload();
	}

	private static void registerPool() {
		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			Identifier soundId = slotSoundId(slot);
			SoundEvent sound = Registry.register(
					BuiltInRegistries.SOUND_EVENT,
					soundId,
					SoundEvent.createVariableRangeEvent(soundId));
			SLOT_SOUNDS.put(slot, sound);
		}

		Identifier itemId = CustomMusicDiscs.id(ITEM_ID);
		Item.Properties properties = new Item.Properties()
				.stacksTo(1)
				.rarity(Rarity.RARE)
				.setId(ResourceKey.create(Registries.ITEM, itemId));
		discItem = Registry.register(BuiltInRegistries.ITEM, itemId, new Item(properties));
	}

	public static Identifier slotSoundId(int slot) {
		return CustomMusicDiscs.id("disc_slot_" + slot);
	}

	public static SoundEvent slotSound(int slot) {
		return SLOT_SOUNDS.get(slot);
	}

	public static Item discItem() {
		return discItem;
	}

	/**
	 * Rescans the discs folders, keeps existing id -> slot assignments where possible,
	 * assigns any new files to the lowest free slot, and returns what changed.
	 */
	public static ReloadResult reload() {
		Map<String, Path> found = new TreeMap<>();
		for (Path folder : DiscPaths.discDirectories()) {
			scanFolder(folder, found);
		}

		Map<String, Integer> mapping = loadMapping();
		boolean[] used = new boolean[SLOT_COUNT];
		Map<String, Integer> assigned = new LinkedHashMap<>();

		// First pass: keep existing assignments for files that are still present.
		for (Map.Entry<String, Integer> entry : mapping.entrySet()) {
			String id = entry.getKey();
			Integer slot = entry.getValue();
			if (found.containsKey(id) && slot != null && slot >= 0 && slot < SLOT_COUNT && !used[slot]) {
				assigned.put(id, slot);
				used[slot] = true;
			}
		}

		// Second pass: any new file gets the lowest free slot.
		for (String id : found.keySet()) {
			if (assigned.containsKey(id)) {
				continue;
			}
			int slot = firstFreeSlot(used);
			if (slot < 0) {
				CustomMusicDiscs.LOGGER.warn("Disc pool is full ({} slots); ignoring '{}'.", SLOT_COUNT, id);
				continue;
			}
			assigned.put(id, slot);
			used[slot] = true;
		}

		List<Disc> discs = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : assigned.entrySet()) {
			Disc disc = buildDisc(entry.getKey(), found.get(entry.getKey()), entry.getValue());
			if (disc != null) {
				discs.add(disc);
			}
		}
		discs.sort(java.util.Comparator.comparingInt(Disc::slot));

		List<Disc> removed = new ArrayList<>();
		for (Disc old : all()) {
			if (!assigned.containsKey(old.id())) {
				removed.add(old);
			}
		}

		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			SLOTS[slot] = null;
		}
		ID_TO_SLOT.clear();
		for (Disc disc : discs) {
			SLOTS[disc.slot()] = disc;
			ID_TO_SLOT.put(disc.id(), disc.slot());
		}

		saveMapping(assigned);

		if (discs.isEmpty()) {
			CustomMusicDiscs.LOGGER.info("No custom music discs found. Place .ogg files in {} to add discs, "
					+ "then run /discs reload.",
					DiscPaths.defaultDiscFolder().toAbsolutePath());
		} else {
			CustomMusicDiscs.LOGGER.info("Loaded {} custom music disc(s): {}", discs.size(),
					discs.stream().map(Disc::id).toList());
		}

		return new ReloadResult(discs, removed);
	}

	private static void scanFolder(Path folder, Map<String, Path> out) {
		if (Files.notExists(folder)) {
			return;
		}
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(folder, "*.ogg")) {
			for (Path file : stream) {
				if (!Files.isRegularFile(file)) {
					continue;
				}
				String id = DiscPaths.sanitizeId(file.getFileName().toString().replaceAll("(?i)\\.ogg$", ""));
				if (id.isEmpty()) {
					continue;
				}
				out.putIfAbsent(id, file);
			}
		} catch (IOException e) {
			CustomMusicDiscs.LOGGER.error("Failed to read discs folder {}: {}", folder, e.toString());
		}
	}

	private static Disc buildDisc(String id, Path file, int slot) {
		byte[] audio;
		try {
			audio = Files.readAllBytes(file);
			if (audio.length == 0) {
				CustomMusicDiscs.LOGGER.warn("Disc file {} is empty; skipping.", file);
				return null;
			}
		} catch (IOException e) {
			CustomMusicDiscs.LOGGER.error("Could not read disc file {}: {}", file, e.toString());
			return null;
		}
		return new Disc(slot, id, file.getFileName().toString(), SLOT_SOUNDS.get(slot), audio);
	}

	private static int firstFreeSlot(boolean[] used) {
		for (int slot = 0; slot < used.length; slot++) {
			if (!used[slot]) {
				return slot;
			}
		}
		return -1;
	}

	/**
	 * Rough duration estimate used for the jukebox song length (so the jukebox knows
	 * when the "song" is over). Assumes roughly 128kbps (16 KB/s) OGG Vorbis; good
	 * enough for jukebox stop timing.
	 */
	public static float estimateDurationSeconds(int byteCount) {
		if (byteCount <= 0) {
			return 60f;
		}
		return (float) Math.max(1.0, byteCount / 16_000.0);
	}

	public static Disc get(String id) {
		Integer slot = ID_TO_SLOT.get(id);
		return slot == null ? null : SLOTS[slot];
	}

	public static Optional<Disc> tryGet(String id) {
		return Optional.ofNullable(get(id));
	}

	public static Disc bySlot(int slot) {
		if (slot < 0 || slot >= SLOT_COUNT) {
			return null;
		}
		return SLOTS[slot];
	}

	public static List<Disc> all() {
		List<Disc> result = new ArrayList<>();
		for (Disc disc : SLOTS) {
			if (disc != null) {
				result.add(disc);
			}
		}
		return result;
	}

	public static boolean isEmpty() {
		return all().isEmpty();
	}

	// ---- slot mapping persistence (disc id -> slot) ----

	private static Map<String, Integer> loadMapping() {
		Map<String, Integer> mapping = new LinkedHashMap<>();
		try {
			if (Files.exists(SLOTS_FILE)) {
				JsonObject obj = JsonParser.parseString(Files.readString(SLOTS_FILE)).getAsJsonObject();
				for (Map.Entry<String, com.google.gson.JsonElement> entry : obj.entrySet()) {
					mapping.put(entry.getKey(), entry.getValue().getAsInt());
				}
			}
		} catch (Exception e) {
			CustomMusicDiscs.LOGGER.warn("Could not read slot mapping {}: {}", SLOTS_FILE, e.toString());
		}
		return mapping;
	}

	private static void saveMapping(Map<String, Integer> mapping) {
		try {
			Files.createDirectories(SLOTS_FILE.getParent());
			JsonObject obj = new JsonObject();
			mapping.forEach(obj::addProperty);
			Files.writeString(SLOTS_FILE, obj.toString());
		} catch (IOException e) {
			CustomMusicDiscs.LOGGER.warn("Could not write slot mapping {}: {}", SLOTS_FILE, e.toString());
		}
	}

	public record ReloadResult(List<Disc> discs, List<Disc> removed) {
	}
}