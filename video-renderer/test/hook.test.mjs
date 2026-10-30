import { test } from "node:test";
import assert from "node:assert/strict";
import { spawnSync } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { buildComposition } from "../src/build.mjs";
import { buildTimeline, clipFitError, readHookText } from "../src/story.mjs";
import { fixture, readJson, template, golden, loadTranscripts, firstDiff } from "./helpers.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const TITLE = "Tunguska — prueba vertical";

function compose(edit = () => {}) {
  const story = readJson(fixture("story.reference.json"));
  edit(story);
  return buildComposition({
    story,
    transcriptsByScene: loadTranscripts(),
    template,
    options: { title: TITLE, audioEndMarginSec: 0 },
  });
}

const videoTag = (html, sceneId) => html.match(new RegExp(`<video\\s+id="v-${sceneId}"[\\s\\S]*?</video>`))[0];

// ---------- texto del gancho ----------
test("sin hookText (ausente, null, vacío) la composición es idéntica al golden", () => {
  for (const value of [undefined, null, "", "   "]) {
    const { html, hookText } = compose((s) => {
      if (value !== undefined) s.hookText = value;
    });
    assert.equal(html, golden, `hookText=${JSON.stringify(value)}: ${firstDiff(html, golden)}`);
    assert.equal(hookText, null);
    assert.doesNotMatch(html, /hook-text/);
  }
});

test("con hookText se dibuja arriba desde el fotograma 0 hasta el fin del GANCHO, sin fundido de entrada", () => {
  const { html, hookText, scenes } = compose((s) => (s.hookText = "  EXPLOSIÓN SIN CRÁTER "));
  const hook = scenes.find((s) => s.role === "GANCHO");
  const end = (hook.start + hook.duration).toFixed(6);

  assert.equal(hookText, "EXPLOSIÓN SIN CRÁTER");
  assert.match(html, /<div id="hook-text"><div id="hook-text-box">EXPLOSIÓN SIN CRÁTER<\/div><\/div>/);
  // tercio superior, Montserrat 800, encima de los videos (z 1-6) y de los subtítulos (z 10)
  const css = html.match(/#hook-text \{[\s\S]*?\}/)[0];
  assert.match(css, /top: 11%/);
  assert.match(css, /z-index: 11/);
  assert.match(html, /#hook-text-box \{[\s\S]*?font-family: "Montserrat"[\s\S]*?font-weight: 800/);
  // visible en el fotograma 0: ningún opacity/visibility inicial oculto ni fundido de entrada
  assert.doesNotMatch(html.match(/#hook-text \{[\s\S]*?#hook-text-box \{[\s\S]*?\}/)[0], /opacity|visibility/);
  const js = html.slice(html.indexOf("// Texto del gancho"), html.indexOf("window.__timelines"));
  assert.doesNotMatch(js, /opacity: 0 \}, \{ opacity: 1/);
  assert.match(js, new RegExp(`tl\\.set\\("#hook-text", \\{ opacity: 0, visibility: "hidden" \\}, ${end.replace(".", "\\.")}\\);`));
  assert.doesNotMatch(js, /Math\.random|Date\.now/);
  // los subtítulos de abajo no cambian
  assert.equal((html.match(/class="hl-group"/g) ?? []).length, (golden.match(/class="hl-group"/g) ?? []).length);
});

test("con hookText el resto de la composición queda igual: solo se agregan las tres piezas del gancho", () => {
  const { html } = compose((s) => (s.hookText = "EXPLOSIÓN SIN CRÁTER"));
  const stripped = html
    .replace(/\n      #hook-text \{[\s\S]*?\n      #hook-text-box \{[\s\S]*?\n      \}\n/, "")
    .replace(/      <div id="hook-text">.*<\/div><\/div>\n/, "")
    .replace(/\n  \/\/ Texto del gancho[\s\S]*?\n  \}\)\(\);\n/, "");
  assert.equal(stripped, golden, firstDiff(stripped, golden));
});

test("un hookText hostil no puede cerrar el <script> ni inyectar HTML", () => {
  const { html } = compose((s) => (s.hookText = '</script><img src=x onerror="alert(1)"> & "AURORAS"'));
  assert.equal((html.match(/<\/script>/g) ?? []).length, (golden.match(/<\/script>/g) ?? []).length);
  assert.doesNotMatch(html, /<img/);
  assert.match(html, /&lt;\/script&gt;&lt;img src=x onerror=&quot;alert\(1\)&quot;&gt; &amp; &quot;AURORAS&quot;/);
  // el texto nunca entra al <script>: el JS lo lee del DOM
  const js = html.slice(html.indexOf("// Texto del gancho"), html.indexOf("window.__timelines"));
  assert.doesNotMatch(js, /AURORAS|onerror/);
});

test("hookText que no es texto se rechaza", () => {
  for (const bad of [5, ["X"], { t: "X" }, true]) {
    assert.throws(() => readHookText({ hookText: bad }), /hookText debe ser texto/, JSON.stringify(bad));
  }
});

test("hookText sin escena GANCHO: se muestra en la primera escena y se avisa", () => {
  const { html, warnings, scenes } = compose((s) => {
    s.hookText = "SIN GANCHO";
    for (const scene of s.scenes) if (scene.role === "GANCHO") scene.role = "OTRO";
  });
  const first = scenes[0];
  assert.match(html, new RegExp(`visibility: "hidden" \\}, ${(first.start + first.duration).toFixed(6).replace(".", "\\.")}\\);`));
  assert.ok(warnings.some((w) => /ninguna escena GANCHO/.test(w)), warnings.join("\n"));
});

// ---------- clipStartOffset ----------
test("clipStartOffset va a data-media-start de su escena; las demás siguen en 0", () => {
  const { html, scenes } = compose((s) => (s.scenes.find((x) => x.role === "GANCHO").clipStartOffset = 0.5));
  assert.equal(scenes.find((s) => s.role === "GANCHO").clipStartOffset, 0.5);
  assert.match(videoTag(html, "tunguska-gancho"), /data-media-start="0.5"/);
  assert.match(videoTag(html, "tunguska-explicacion"), /data-media-start="0"/);
  // la línea de tiempo no cambia: el offset solo mueve qué parte del clip se ve
  assert.match(videoTag(html, "tunguska-gancho"), /data-start="0.000000"/);
  assert.equal(videoTag(html, "tunguska-explicacion"), videoTag(golden, "tunguska-explicacion"));
});

test("clipStartOffset ausente o null vale 0; negativo, no numérico o infinito se rechaza", () => {
  const base = () => readJson(fixture("story.backend.json"));
  const s = base();
  s.scenes[0].clipStartOffset = null;
  assert.deepEqual(buildTimeline(s).scenes.map((x) => x.clipStartOffset), [0, 0, 0, 0, 0, 0]);
  for (const bad of [-0.1, "3", Infinity, NaN, true, {}]) {
    const t = base();
    t.scenes[0].clipStartOffset = bad;
    assert.throws(() => buildTimeline(t), /clipStartOffset inválido/, String(bad));
  }
});

test("clipFitError: alcanza dentro de 1 ms, no alcanza con mensaje claro", () => {
  const scene = { id: "x-gancho", duration: 4.458, clipStartOffset: 0.542 };
  assert.equal(clipFitError(scene, 5), null);
  assert.equal(clipFitError({ ...scene, clipStartOffset: 0.5425 }, 5), null); // 0.5 ms de más: tolerado
  const error = clipFitError({ ...scene, clipStartOffset: 0.6 }, 5);
  assert.match(error, /x-gancho: el clip dura 5\.000 s/);
  assert.match(error, /hasta el segundo 5\.058/);
  assert.match(error, /Bajar el offset a 0\.542 s o menos/);
});

// ---------- generate.mjs con ffprobe real sobre los clips versionados de tunguska ----------
const data = join(here, "..", "..", "data");
const hasFfprobe = spawnSync("ffprobe", ["-version"]).status === 0;

async function runGenerate(edit, { fakeAudio = false } = {}) {
  const dir = await mkdtemp(join(tmpdir(), "hook-gen-"));
  const story = readJson(fixture("story.backend.json"));
  edit(story);
  await writeFile(join(dir, "story.json"), JSON.stringify(story));
  let assets = data;
  if (fakeAudio) {
    // clips reales, audios que ffprobe no puede leer
    assets = join(dir, "assets");
    await mkdir(join(assets, "clips"), { recursive: true });
    await mkdir(join(assets, "audio"), { recursive: true });
    for (const s of story.scenes) {
      await copyFile(join(data, "clips", `${s.id}.mp4`), join(assets, "clips", `${s.id}.mp4`));
      await writeFile(join(assets, "audio", `${s.id}.mp3`), "mp3 simulado");
    }
  }
  const r = spawnSync(process.execPath, [
    join(here, "..", "src", "generate.mjs"),
    "--story", join(dir, "story.json"),
    "--transcripts", fixture("transcripts"),
    "--assets", assets,
    "--out", join(dir, "out"),
  ], { encoding: "utf8" });
  return { r, dir };
}

test("generate.mjs: el offset se comprueba aunque ffprobe no pueda leer el audio (ffprobe real)", { skip: !hasFfprobe && "sin ffprobe" }, async () => {
  const { r, dir } = await runGenerate((s) => (s.scenes.find((x) => x.role === "GANCHO").clipStartOffset = 1), { fakeAudio: true });
  try {
    assert.equal(r.status, 1, r.stdout + r.stderr);
    assert.match(r.stderr, /ERROR tunguska-gancho: el clip dura 5\.000 s/);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("generate.mjs: con un offset que el clip no alcanza falla con mensaje claro (ffprobe real)", { skip: !hasFfprobe && "sin ffprobe" }, async () => {
  // tunguska-gancho.mp4 dura 5.000 s y la escena 4.458 s: el offset máximo es 0.542 s
  const { r, dir } = await runGenerate((s) => (s.scenes.find((x) => x.role === "GANCHO").clipStartOffset = 1));
  try {
    assert.equal(r.status, 1, r.stdout + r.stderr);
    assert.match(r.stderr, /ERROR tunguska-gancho: el clip dura 5\.000 s y con clipStartOffset 1\.000 s/);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});

test("generate.mjs: con un offset que alcanza genera y lo deja en data-media-start (ffprobe real)", { skip: !hasFfprobe && "sin ffprobe" }, async () => {
  const { r, dir } = await runGenerate((s) => {
    s.scenes.find((x) => x.role === "GANCHO").clipStartOffset = 0.5;
    s.hookText = "EXPLOSIÓN SIN CRÁTER";
  });
  try {
    assert.equal(r.status, 0, r.stdout + r.stderr);
    const html = await readFile(join(dir, "out", "index.html"), "utf8");
    assert.match(videoTag(html, "tunguska-gancho"), /data-media-start="0.5"/);
    assert.match(html, /<div id="hook-text-box">EXPLOSIÓN SIN CRÁTER<\/div>/);
    const report = JSON.parse(await readFile(join(dir, "out", "generation-report.json"), "utf8"));
    assert.equal(report.mediaChecks.find((c) => c.scene === "tunguska-gancho").clipStartOffset, 0.5);
  } finally {
    await rm(dir, { recursive: true, force: true });
  }
});
