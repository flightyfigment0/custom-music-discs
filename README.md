# Custom Music Discs

A **Minecraft 26.2 Fabric** mod + **web uploader** that lets a server turn audio
files (mp3, wav, flac, ogg, ...) into playable music discs. Players upload audio
through a small web page, the server converts it to OGG Vorbis, and after a
`/discs reload` anyone can grab the disc with `/getdisc <name>` — **no operator
permission required**.

## How it works

1. A player uploads audio to the web uploader (or the owner drops `.ogg` files
   into the server's discs folder).
2. The uploader converts it to **OGG Vorbis** with ffmpeg and saves it as
   `config/custom-music-discs/discs/<id>.ogg`.
3. Anyone runs `/discs reload` — the mod rescans the folder, assigns each file to
   a free slot from a fixed pool of 16, and pushes the audio bytes to every
   connected player over **chunked** network payloads.
4. Players run `/getdisc <name>` to receive a disc. The disc's title, sound, and
   length are embedded directly into the item stack.

The mod's registries stay frozen at init time (Minecraft 26.2 freezes all
registries after bootstrap), so instead of registering per-song items/sounds at
runtime it pre-registers **16 fixed sound events** and a shared disc item, and maps
uploaded files onto those slots.

## Requirements

- Minecraft **26.2**
- Fabric Loader **0.19.3+**
- Fabric API (**0.158.0+26.2** or compatible)
- Java **25**
- [Deno](https://deno.com) 2.x on the uploader host
- `ffmpeg` on the uploader host

## Installation

1. Put `custom-music-discs-*.jar` in the `mods/` folder on **both** the server and
   the client. The server also needs `fabric-api-*.jar` in `mods/` if it isn't
   there already.
2. Restart the server.

## Web uploader (server host)

The uploader is a zero-dependency Deno server in `web/server.js`. Start it from the
mod directory (or anywhere — it just needs the discs folder path):

```
DISC_DIR=config/custom-music-discs/discs \
deno run --allow-net --allow-read --allow-write --allow-run=ffmpeg --allow-env web/server.js
```

- Default port **8787**, binds **0.0.0.0** so it's reachable across your LAN.
- Override the port with `PORT` and the size limit with `MAX_MB` (default 50).
- Open `http://<server-ip>:8787`, upload a file, then run `/discs reload` in-game.
- Want internet exposure? Run a tunnel (e.g. `cloudflared tunnel --url http://localhost:8787`).
- The page serves a `GET /` upload page, a `GET /api/discs` listing, and a
  `POST /api/upload` multipart endpoint (field name `file`).

Any player can point their browser at the page and upload — treat the server's
trusted-LAN setup accordingly.

## Adding discs (manual)

Drop `.ogg` files into:

```
config/custom-music-discs/discs/
```

Every `*.ogg` file becomes a disc. The file name (lower-cased, non-alphanumeric
characters replaced with `_`) is the disc id players use in commands. For example
`My Song.ogg` becomes disc id `my_song`.

To scan additional folders, edit `config/custom-music-discs/discs.txt` and add one
path per line (the file is created on first run):

```
# example
/home/aiden/mymusic
./extra_discs
```

### Slot assignment

Files are assigned to the fixed slot pool (16 slots) in id order, and the mapping
is persisted to `config/custom-music-discs/slots.json`, so ids keep their slots
across restarts and `/discs reload` calls. If more than 16 files exist, extras are
ignored with a warning.

## Commands (any player)

```
/discs reload    → rescans the discs folders, rebroadcasts audio to everyone
/discs list      → lists the discs currently loaded
/getdisc         → lists the available discs
/getdisc <name>  → gives you the named disc
```

No operator rank is required for any of these. If inventory is full the disc is
dropped at your feet.

| Command           | Permission | Description                  |
|-------------------|------------|------------------------------|
| `/discs reload`   | everyone   | Reload + rebroadcast discs   |
| `/discs list`     | everyone   | List loaded discs            |
| `/getdisc`        | everyone   | List available discs         |
| `/getdisc <name>` | everyone   | Give yourself a disc         |

## Notes & limitations

- Audio is converted to **OGG Vorbis** (`.ogg`) by the uploader; a jukebox only
  plays Vorbis-in-OGG.
- Audio is shipped to clients in 512 KiB chunks because the vanilla custom payload
  caps at 1 MiB. Clients reassemble and cache the bytes, and stream them on demand.
- The jukebox "length" is estimated from the file size so it can stop the disc when
  the song "ends". The estimate is approximate.
- Only **16 discs** can be loaded at once (fixed slot pool, because registries are
  frozen). If you swap a file, rerun `/discs reload`; existing players holding the
  old disc will play the newly uploaded audio on their next use of that slot.
- When a disc is removed from the folder, clients automatically drop its cached
  audio after the next `/discs reload`.

## Building

```
./gradlew build
```

The compiled jar is written to `build/libs/custom-music-discs-2.0.0.jar`.

## License

MIT