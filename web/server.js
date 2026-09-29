// Custom Music Discs — upload server (zero-dependency Deno).
//
// Players upload audio here; the server converts it to .ogg (Vorbis), sanitizes the
// name, and drops it into the mod's discs folder. Then anyone runs `/discs reload`
// in-game and grabs the new disc with `/getdisc <name>`.
//
// Run:
//   DISC_DIR=$HOME/serverTest/config/custom-music-discs/discs \
//   deno run --allow-net --allow-read --allow-write --allow-run=ffmpeg --allow-env web/server.js
//
// Config via env vars:
//   DISC_DIR  (default: ./config/custom-music-discs/discs relative to cwd)
//   PORT      (default: 8787)
//   MAX_MB    (default: 50, upload size limit)

const DISC_DIR = Deno.env.get("DISC_DIR") ?? "./config/custom-music-discs/discs";
const PORT = Number(Deno.env.get("PORT") ?? 8787);
const MAX_BYTES = (Number(Deno.env.get("MAX_MB") ?? 50)) * 1024 * 1024;
const FFMPEG = "ffmpeg";

// Mirrors DiscPaths.sanitizeId in the mod: lowercase, [a-z0-9_], dots/dashes -> _
function sanitizeId(raw) {
  let s = raw.toLowerCase()
    .replace(/[^a-z0-9_.-]/g, "_")
    .replace(/[.]/g, "_")
    .replace(/-/g, "_")
    .replace(/^_+|_+$/g, "");
  return s;
}

async function convertToOgg(inputPath, outputPath) {
  const proc = new Deno.Command(FFMPEG, {
    args: ["-y", "-i", inputPath, "-c:a", "libvorbis", "-q:a", "5", outputPath],
    stdout: "piped",
    stderr: "piped",
  });
  const { code, stderr } = await proc.output();
  if (code !== 0) {
    throw new Error("ffmpeg failed: " + new TextDecoder().decode(stderr).slice(0, 2000));
  }
}

async function listDiscs() {
  const out = [];
  try {
    for await (const entry of Deno.readDir(DISC_DIR)) {
      if (!entry.isFile || !entry.name.toLowerCase().endsWith(".ogg")) continue;
      const stat = await Deno.stat(`${DISC_DIR}/${entry.name}`);
      out.push({
        id: sanitizeId(entry.name.replace(/\.ogg$/i, "")),
        fileName: entry.name,
        sizeBytes: stat.size,
        mtime: stat.mtime?.toISOString() ?? null,
      });
    }
  } catch (_) {
    // folder may not exist yet
  }
  out.sort((a, b) => a.id.localeCompare(b.id));
  return out;
}

function json(res, status, body) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "content-type": "application/json; charset=utf-8" },
  });
}

function pageHtml() {
  return String.raw`<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>Music Disc Uploader</title>
<style>
  :root { color-scheme: dark; }
  body { font-family: system-ui, sans-serif; max-width: 640px; margin: 40px auto; padding: 0 16px; background:#111; color:#eee; }
  h1 { font-size: 1.4rem; }
  .box { border: 1px solid #333; border-radius: 8px; padding: 16px; margin: 16px 0; background:#1a1a1a; }
  label { display:block; margin: 8px 0 4px; color:#aaa; font-size:.9rem; }
  input[type=file] { width:100%; }
  button { margin-top: 12px; padding: 10px 18px; border:0; border-radius:6px; background:#2f81f7; color:#fff; cursor:pointer; font-size:1rem; }
  button:disabled { opacity:.5; cursor: default; }
  #status { margin-top:12px; white-space: pre-wrap; font-family: monospace; font-size:.9rem; }
  .ok { color:#3fb950; }
  .err { color:#f85149; }
  table { width:100%; border-collapse: collapse; }
  td, th { text-align:left; padding:6px 8px; border-bottom:1px solid #222; font-size:.9rem; }
  .muted { color:#888; font-size:.9rem; }
  .getdisc { background:#333; border-radius:4px; padding:2px 6px; font-family:monospace; }
</style>
</head>
<body>
<h1>&#127925; Music Disc Uploader</h1>
<p class="muted">Upload audio (mp3, wav, flac, m4a, ogg...). It is converted to .ogg,
sanitized, and saved to the server's discs folder. Then run
<span class="getdisc">/discs reload</span> in-game and grab it with
<span class="getdisc">/getdisc &lt;name&gt;</span>.</p>

<div class="box">
  <label for="file">Audio file</label>
  <input type="file" id="file" accept="audio/*,.ogg,.mp3,.wav,.flac,.m4a,.aac,.opus">
  <button id="upload">Upload</button>
  <div id="status"></div>
</div>

<div class="box">
  <h2 style="margin-top:0;">Current discs</h2>
  <table id="discs">
    <thead><tr><th>id</th><th>size</th></tr></thead>
    <tbody></tbody>
  </table>
</div>

<script>
const $ = (id) => document.getElementById(id);
$("upload").onclick = async () => {
  const file = $("file").files[0];
  if (!file) { $("status").className = "err"; $("status").textContent = "Pick a file first."; return; }
  const btn = $("upload");
  btn.disabled = true;
  $("status").className = "";
  $("status").textContent = "Uploading " + file.name + "...";
  try {
    const fd = new FormData();
    fd.append("file", file);
    const res = await fetch("/api/upload", { method: "POST", body: fd });
    const data = await res.json();
    if (!res.ok) throw new Error(data.error || ("HTTP " + res.status));
    $("status").className = "ok";
    $("status").textContent = "Uploaded as \"" + data.id + "\".\nRun /discs reload in-game, then /getdisc " + data.id;
    $("file").value = "";
    refresh();
  } catch (e) {
    $("status").className = "err";
    $("status").textContent = "Error: " + e.message;
  } finally {
    btn.disabled = false;
  }
};

async function refresh() {
  try {
    const res = await fetch("/api/discs");
    const discs = await res.json();
    const tbody = $("discs").querySelector("tbody");
    tbody.innerHTML = "";
    if (!discs.length) {
      const tr = document.createElement("tr");
      const td = document.createElement("td");
      td.colSpan = 2;
      td.className = "muted";
      td.textContent = "No discs yet. Upload the first one above!";
      tr.appendChild(td);
      tbody.appendChild(tr);
      return;
    }
    for (const d of discs) {
      const tr = document.createElement("tr");
      const td1 = document.createElement("td");
      td1.textContent = d.id;
      const td2 = document.createElement("td");
      td2.textContent = (d.sizeBytes / 1024).toFixed(1) + " KB";
      tr.append(td1, td2);
      tbody.appendChild(tr);
    }
  } catch (_) { /* ignore */ }
}
refresh();
</script>
</body>
</html>`;
}

async function handle(req) {
  const url = new URL(req.url);

  if (req.method === "GET" && url.pathname === "/") {
    return new Response(pageHtml(), { headers: { "content-type": "text/html; charset=utf-8" } });
  }

  if (req.method === "GET" && url.pathname === "/api/discs") {
    return json(req, 200, await listDiscs());
  }

  if (req.method === "POST" && url.pathname === "/api/upload") {
    const form = await req.formData().catch(() => null);
    if (!form) return json(req, 400, { error: "Expected multipart/form-data." });
    const file = form.get("file");
    if (!(file instanceof File)) return json(req, 400, { error: "Missing 'file' field." });
    if (file.size === 0) return json(req, 400, { error: "File is empty." });
    if (file.size > MAX_BYTES) return json(req, 413, { error: `File too large (max ${MAX_BYTES / 1024 / 1024} MB).` });

    const base = sanitizeId(file.name.replace(/\.[a-z0-9]+$/i, ""));
    if (!base) return json(req, 400, { error: "Could not derive a disc id from that file name." });

    try {
      await Deno.mkdir(DISC_DIR, { recursive: true });
      const tmpIn = await Deno.makeTempFile({ suffix: ".in" });
      const tmpOut = await Deno.makeTempFile({ suffix: ".ogg" });
      try {
        await Deno.writeFile(tmpIn, new Uint8Array(await file.arrayBuffer()));
        await convertToOgg(tmpIn, tmpOut);
        const finalPath = `${DISC_DIR}/${base}.ogg`;
        await Deno.copyFile(tmpOut, finalPath);
        // makeTempFile leaves the destination 0600; the Minecraft server may run
        // under a different user than this uploader, so the disc must be group/world readable.
        await Deno.chmod(finalPath, 0o644);
        return json(req, 200, { id: base, fileName: `${base}.ogg`, note: "Run /discs reload in-game." });
      } finally {
        await Deno.remove(tmpIn, { force: true });
        await Deno.remove(tmpOut, { force: true });
      }
    } catch (e) {
      return json(req, 500, { error: "Conversion failed: " + e.message });
    }
  }

  return new Response("Not found", { status: 404 });
}

Deno.serve({ port: PORT, hostname: "0.0.0.0" }, handle);
console.log(`Disc uploader on http://0.0.0.0:${PORT} -> ${DISC_DIR}`);