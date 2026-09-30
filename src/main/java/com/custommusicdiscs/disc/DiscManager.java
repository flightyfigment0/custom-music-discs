package com.custommusicdiscs.disc;

import com.custommusicdiscs.CustomMusicDiscs;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.registry.DynamicRegistrySetupCallback;

import net.minecraft.core.Holder;
import net.minecraft.core.Registry;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.JukeboxSong;
import net.minecraft.world.item.Rarity;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Manages the fixed pool of disc slots.
 * <p>
 * Because the game's registries are frozen after bootstrap, the mod pre-registers
 * a fixed number of sound events and jukebox songs (one per slot) plus a single
 * shared disc item at init time, on both the server and the client. Uploaded audio
 * files are assigned to free slots; the {@code /discs reload} command rescans the
 * discs folders and re-broadcasts the current slot contents to every connected
 * player. A disc item points at its slot's song by name so that it survives saving.
 */
public final class DiscManager {
	/** How many discs the server can hold. Must match between client and server (it is a shared constant). */
	public static final int SLOT_COUNT = 16;
	public static final String ITEM_ID = "custom_music_disc";

	/**
	 * The slot the legacy shared item stands in for. Discs given out before 2.1.0 are all
	 * that one item, so it has to keep meaning a real slot for those stacks to stay
	 * playable.
	 */
	private static final int LEGACY_SLOT = 0;

	/**
	 * Length given to a slot that holds no disc when the songs are registered. An empty
	 * slot cannot get the real length (there is no file yet), and zero would make a
	 * jukebox stop a disc uploaded later the instant it started, so leave room for a
	 * typical track instead.
	 */
	private static final float UNKNOWN_SLOT_SECONDS = 600f;

	private static final Disc[] SLOTS = new Disc[SLOT_COUNT];
	private static final Map<String, Integer> ID_TO_SLOT = new LinkedHashMap<>();
	private static final Map<Integer, SoundEvent> SLOT_SOUNDS = new LinkedHashMap<>();
	private static final Map<Integer, Holder.Reference<SoundEvent>> SLOT_SOUND_HOLDERS = new LinkedHashMap<>();
	private static final Map<Integer, Item> SLOT_ITEMS = new LinkedHashMap<>();
	private static final Path SLOTS_FILE = DiscPaths.configRoot().resolve("slots.json");

	/** True once the jukebox songs are registered, i.e. after the init-time scan. */
	private static boolean SONGS_REGISTERED;

	private DiscManager() {
	}

	/**
	 * Registers the slot pool (sound events + item) and then scans the discs folders.
	 * Runs on both the client and the server from the main entrypoint.
	 */
	public static void init() {
		registerPool();
		reload();
		hookSongRegistration();
	}

	private static void registerPool() {
		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			Identifier soundId = slotSoundId(slot);
			Holder.Reference<SoundEvent> holder = Registry.registerForHolder(
					BuiltInRegistries.SOUND_EVENT,
					ResourceKey.create(Registries.SOUND_EVENT, soundId),
					SoundEvent.createVariableRangeEvent(soundId));
			SLOT_SOUNDS.put(slot, holder.value());
			SLOT_SOUND_HOLDERS.put(slot, holder);
		}

		// One item per slot, so each disc can have its own texture. They all share the
		// same behaviour: which song plays comes from the stack's JUKEBOX_PLAYABLE
		// component, not from the item type.
		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			SLOT_ITEMS.put(slot, registerDiscItem(itemId(slot)));
		}

		// Earlier versions shipped a single shared item. Keep it registered and pointing
		// at slot 0 so discs already sitting in a world survive instead of turning into
		// an unknown item that the next save deletes.
		Item legacy = registerDiscItem(CustomMusicDiscs.id(ITEM_ID));
		SLOT_ITEMS.put(LEGACY_SLOT, legacy);
	}

	private static Item registerDiscItem(Identifier id) {
		Item.Properties properties = new Item.Properties()
				.stacksTo(1)
				.rarity(Rarity.RARE)
				.setId(ResourceKey.create(Registries.ITEM, id));
		return Registry.register(BuiltInRegistries.ITEM, id, new Item(properties));
	}

	/** The registry name of a slot's disc item, e.g. custom-music-discs:custom_music_disc_3. */
	public static Identifier itemId(int slot) {
		return CustomMusicDiscs.id(ITEM_ID + "_" + slot);
	}

	/**
	 * Registers one {@link JukeboxSong} per slot when the dynamic registries are set up.
	 * <p>
	 * A disc item stores its song as a registry reference, not as a value embedded in the
	 * stack, because vanilla's {@code JUKEBOX_PLAYABLE} codec only writes a song by name.
	 * Embedding the song with {@code Holder.direct} produced items that could not be
	 * saved: every world save dropped them from inventories and jukeboxes. Registering
	 * the songs makes the items serializable.
	 * <p>
	 * {@code jukebox_song} is a data pack registry, so the entries are added while it is
	 * still being built and are then synced to every client like any other. That also
	 * means a slot's title and length are fixed for as long as the world stays loaded:
	 * a later {@code /discs reload} changes the audio immediately but needs a restart
	 * for the jukebox's title and stop timer to match.
	 */
	private static void hookSongRegistration() {
		DynamicRegistrySetupCallback.EVENT.register(view -> {
			Optional<Registry<JukeboxSong>> optional = view.getOptional(Registries.JUKEBOX_SONG);
			if (optional.isEmpty()) {
				// The setup callback also runs for passes that do not build this registry.
				// Failing here would abort the world's data pack load, so step aside.
				CustomMusicDiscs.LOGGER.debug("No jukebox_song registry in this setup pass; skipping disc songs.");
				return;
			}
			Registry<JukeboxSong> registry = optional.get();
			for (int slot = 0; slot < SLOT_COUNT; slot++) {
				Holder.Reference<SoundEvent> sound = SLOT_SOUND_HOLDERS.get(slot);
				Disc disc = SLOTS[slot];
				Component description = disc == null
						? Component.literal("Empty disc slot")
						: Component.literal(CustomMusicDiscs.formatName(disc.id()));
				float duration = disc == null ? UNKNOWN_SLOT_SECONDS : disc.durationSeconds();
				Registry.register(registry, songKey(slot), new JukeboxSong(sound, description, duration, 15));
			}
			SONGS_REGISTERED = true;
			CustomMusicDiscs.LOGGER.info("Registered {} jukebox songs for the disc slots.", SLOT_COUNT);
		});

		// The setup callback runs more than once and not every pass holds the song
		// registry, so confirm against the registries the server ends up serving.
		ServerLifecycleEvents.SERVER_STARTED.register(server -> logSongStatus(server.registryAccess()));
	}

	/** Reports which slot songs the running server can actually hand out. */
	private static void logSongStatus(RegistryAccess access) {
		Optional<Registry<JukeboxSong>> optional = access.lookup(Registries.JUKEBOX_SONG);
		if (optional.isEmpty()) {
			CustomMusicDiscs.LOGGER.error("The jukebox_song registry is missing, so no disc can be given out.");
			return;
		}
		int present = 0;
		for (int slot = 0; slot < SLOT_COUNT; slot++) {
			if (optional.get().getOptional(songKey(slot)).isPresent()) {
				present++;
			}
		}
		SONGS_REGISTERED = present > 0;
		CustomMusicDiscs.LOGGER.info("{} of {} disc slot songs are available on this server.",
				present, SLOT_COUNT);
	}

	/** The registry name of a slot's song. */
	public static ResourceKey<JukeboxSong> songKey(int slot) {
		return ResourceKey.create(Registries.JUKEBOX_SONG, slotSoundId(slot));
	}

	/**
	 * The registered song for a slot, which disc items must reference to stay
	 * serializable. Resolved from the given registries so it stays correct after a
	 * world reload; empty if the song is not there.
	 */
	public static Optional<Holder.Reference<JukeboxSong>> slotSong(int slot, RegistryAccess access) {
		return access.lookup(Registries.JUKEBOX_SONG).flatMap(songs -> songs.get(songKey(slot)));
	}

	public static Identifier slotSoundId(int slot) {
		return CustomMusicDiscs.id("disc_slot_" + slot);
	}

	public static SoundEvent slotSound(int slot) {
		return SLOT_SOUNDS.get(slot);
	}

	/** The disc item for a slot, which is what carries that slot's texture. */
	public static Item discItem(int slot) {
		return SLOT_ITEMS.get(slot);
	}

	/** The legacy shared disc item, kept so discs from before 2.1.0 still resolve. */
	public static Item discItem() {
		return SLOT_ITEMS.get(LEGACY_SLOT);
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

		List<Disc> previous = all();
		String[] previousIds = new String[SLOT_COUNT];
		for (Disc disc : previous) {
			previousIds[disc.slot()] = disc.id();
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

		// The audio for a changed slot takes effect immediately, but a slot's jukebox
		// title and length live in a registry entry fixed at startup, so only say so
		// once that entry exists (otherwise this is the init-time scan, where it is not true).
		if (SONGS_REGISTERED) {
			for (int slot = 0; slot < SLOT_COUNT; slot++) {
				Disc now = SLOTS[slot];
				if (!Objects.equals(previousIds[slot], now == null ? null : now.id())) {
					CustomMusicDiscs.LOGGER.info("Slot {} changed from {} to {}; its jukebox title and length "
							+ "refresh on the next server start.", slot,
							previousIds[slot] == null ? "empty" : previousIds[slot],
							now == null ? "empty" : now.id());
				}
			}
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
		return new Disc(slot, id, file.getFileName().toString(), SLOT_SOUNDS.get(slot), audio, durationOf(audio));
	}

	/** The real playing time, falling back to a size estimate if the file is unreadable. */
	private static float durationOf(byte[] audio) {
		float duration = OggDuration.seconds(audio);
		return duration > 0f ? duration : estimateDurationSeconds(audio.length);
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
	 * Size-based duration estimate, used only when the file is not a readable Ogg
	 * Vorbis stream. Assumes roughly 128kbps (16 KB/s); the real length is preferred
	 * whenever {@link OggDuration} can read it.
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