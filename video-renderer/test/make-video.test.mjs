import { test } from "node:test";
import assert from "node:assert/strict";
import { spawn } from "node:child_process";
import { mkdtemp, mkdir, readFile, readdir, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { collectFindings, parseJsonOutput, stageFile } from "../src/make-video.mjs";
import { SCENE_IDS, fixture } from "./helpers.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const MAKE_VIDEO = join(here, "..", "src", "make-video.mjs");
const FAKE_HF = join(here, "support", "fake-hyperframes.mjs");
const FAKE_FFPROBE = join(here, "support", "fake-ffprobe.mjs");
const STAGES = ["prepare", "transcribe", "generate", "lint", "check", "render", "validate"];

const exists = (p) => stat(p).then(() => true, () => false);

/** Carpetas planas de clips y audios (contenido falso: las etapas reales que los leen están simuladas). */
async function setup({ skipClip, skipAudio } = {}) {
  const root = await mkdtemp(join(tmpdir(), "mv-test-"));
  const clips = join(root, "clips");
  const audio = join(root, "audio");
  await mkdir(clips);
  await mkdir(audio);
  for (const id of SCENE_IDS) {
    if (id !== skipClip) await writeFile(join(clips, `${id}.mp4`), `clip ${id}`);
    if (id !== skipAudio) await writeFile(join(audio, `${id}.mp3`), `audio ${id}`);
  }
  return { root, clips, audio, work: join(root, "work"), calls: join(root, "calls.jsonl"), pidFile: join(root, "nieto.pid") };
}

function runMakeVideo(s, { hf = {}, ffprobe = {}, extra = [] } = {}) {
  const argv = [
    MAKE_VIDEO,
    "--story", fixture("story.backend.json"),
    "--clips-dir", s.clips,
    "--audio-dir", s.audio,
    "--work", s.work,
    "--hyperframes-cmd", JSON.stringify([process.execPath, FAKE_HF]),
    "--ffprobe-cmd", JSON.stringify([process.execPath, FAKE_FFPROBE]),
    ...extra,
  ];
  const env = {
    ...process.env,
    FAKE_HF: JSON.stringify({ calls: s.calls, transcripts: fixture("transcripts"), pidFile: s.pidFile, ...hf }),
    FAKE_FFPROBE: JSON.stringify(ffprobe),
  };
  return new Promise((resolvePromise) => {
    const child = spawn(process.execPath, argv, { env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    child.stdout.on("data", (d) => (stdout += d));
    child.stderr.on("data", (d) => (stderr += d));
    child.on("close", async (code) => {
      const resultPath = join(s.work, "make-video-result.json");
      const result = (await exists(resultPath)) ? JSON.parse(await readFile(resultPath, "utf8")) : null;
      resolvePromise({ code, stdout, stderr, result });
    });
  });
}

async function hfCalls(s) {
  if (!(await exists(s.calls))) return [];
  return (await readFile(s.calls, "utf8")).trim().split("\n").map((l) => JSON.parse(l).args);
}

const isAlive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};

test("make-video: corre las etapas en orden, deja el reporte, junta avisos sin fallar y limpia lo pesado", async () => {
  const s = await setup();
  const lintWarn = { ok: true, errorCount: 0, warningCount: 1, findings: [{ code: "composition_file_too_large", severity: "warning", message: "1164 líneas" }] };
  const okSec = { ok: true, errorCount: 0, warningCount: 0, findings: [] };
  const check = {
    ok: true,
    lint: lintWarn, // repetido por check: no se debe duplicar
    runtime: okSec,
    layout: { ok: true, errorCount: 0, warningCount: 1, findings: [{ code: "content_overlap", severity: "info", message: "info" }, { code: "text_clip", severity: "warning", message: "recortado" }] },
    motion: okSec,
    contrast: okSec,
  };
  const r = await runMakeVideo(s, { hf: { lint: lintWarn, check } });
  assert.equal(r.code, 0, r.stderr);
  const res = r.result;
  assert.equal(res.status, "COMPLETED");
  assert.equal(res.stage, null);
  assert.equal(res.error, null);
  assert.equal(res.output, "final.mp4");
  assert.equal(res.storyId, "tunguska");
  assert.deepEqual(res.stages.map((x) => x.name), STAGES);
  assert.ok(res.stages.every((x) => x.exitCode === 0 && Number.isInteger(x.ms)));
  assert.equal(res.stages[1].scenes.length, 6);
  assert.equal(res.versions.hyperframes, "0.8.143");
  assert.deepEqual(res.outputCheck, { width: 720, height: 1280, hasAudio: true, duration: 37.434, expectedDuration: 37.431 });

  // avisos de generate (palabra inferida en tunguska), lint y check; el "info" no cuenta y el de lint no se repite
  const w = res.warnings.map((x) => `${x.stage}|${x.message}`);
  assert.ok(w.some((x) => x.startsWith("generate|1 palabra(s) con tiempo inferido")), w.join("\n"));
  assert.ok(w.includes("lint|composition_file_too_large: 1164 líneas"));
  assert.ok(w.includes("check|text_clip: recortado"));
  assert.equal(w.filter((x) => x.includes("composition_file_too_large")).length, 1);
  assert.ok(!w.some((x) => x.includes("content_overlap")));

  // llamadas a la CLI: 6 transcripciones, lint, check y render con los flags fijados
  const calls = await hfCalls(s);
  assert.deepEqual(calls.map((a) => a[0]), [...SCENE_IDS.map(() => "transcribe"), "lint", "check", "render"]);
  assert.deepEqual(calls.at(-1), ["render", "--output", "final.mp4", "--quality", "standard", "--workers", "1"]);
  assert.ok(calls[0].includes("--json"));

  // stdout: una línea por transición, en orden
  const etapas = r.stdout.split(/\r?\n/).filter((l) => l.startsWith("ETAPA "));
  assert.deepEqual(etapas.map((l) => l.split(" ").slice(1, 3).join(" ")), STAGES.flatMap((n) => [`${n} INICIO`, `${n} FIN`]));

  // queda lo liviano y útil; se va lo pesado/regenerable
  const left = (await readdir(s.work)).sort();
  for (const f of ["final.mp4", "make-video-result.json", "generation-report.json", "index.html", "words.global.json", "logs", "transcripts"]) {
    assert.ok(left.includes(f), `falta ${f}: ${left}`);
  }
  for (const f of ["assets", "fonts", "input", "tmp"]) assert.ok(!left.includes(f), `sobra ${f}`);
  for (const st of ["prepare", "transcribe", "generate", "lint", "check", "render", "validate"]) {
    assert.ok(await exists(join(s.work, "logs", `${st}.log`)), `falta el log de ${st}`);
  }
  assert.match(await readFile(join(s.work, "logs", "render.log"), "utf8"), /render simulado/);
  await rm(s.root, { recursive: true, force: true });
});

for (const stage of ["transcribe", "lint", "check", "render"]) {
  test(`make-video: si ${stage} termina con código != 0 el resultado es FAILED en ${stage}, con log y sin seguir`, async () => {
    const s = await setup();
    const bad = { ok: false, errorCount: 1, warningCount: 0, findings: [{ code: "root_missing_composition_id", severity: "error", message: "falta el id" }] };
    const hf = { exit: { [stage]: 1 } };
    if (stage === "lint") hf.lint = bad;
    if (stage === "check") hf.check = { ok: false, runtime: bad };
    const r = await runMakeVideo(s, { hf });
    assert.equal(r.code, 1);
    const res = r.result;
    assert.equal(res.status, "FAILED");
    assert.equal(res.stage, stage);
    const last = res.stages.at(-1);
    assert.equal(last.name, stage);
    assert.equal(last.exitCode, 1);
    assert.deepEqual(res.stages.map((x) => x.name), STAGES.slice(0, STAGES.indexOf(stage) + 1));
    if (stage === "lint" || stage === "check") assert.match(res.error, /root_missing_composition_id: falta el id/);
    if (stage === "transcribe") assert.match(res.error, /tunguska-gancho.*sin red/);
    if (stage === "render") assert.match(res.error, /render terminó con código 1.*Chrome se cayó/);
    assert.ok((await readFile(join(s.work, "logs", `${stage}.log`), "utf8")).length > 0);
    assert.match(r.stderr, /^ERROR /m);
    // en fallo se borran los medios pero no el resto
    assert.ok(!(await exists(join(s.work, "input"))));
    assert.ok(!(await exists(join(s.work, "assets"))));
    await rm(s.root, { recursive: true, force: true });
  });
}

test("make-video: un fallo de generate (transcripción que no corresponde a la narración) es FAILED en generate", async () => {
  const s = await setup();
  const r = await runMakeVideo(s, { hf: { transcribeGarbage: true } });
  assert.equal(r.code, 1);
  assert.equal(r.result.stage, "generate");
  assert.equal(r.result.stages.at(-1).exitCode, 1);
  assert.match(r.result.error, /^generate: /);
  assert.match(await readFile(join(s.work, "logs", "generate.log"), "utf8"), /ERROR/);
  assert.ok(!(await hfCalls(s)).some((a) => a[0] === "lint"));
  await rm(s.root, { recursive: true, force: true });
});

test("make-video: render que se pasa del tiempo se mata con sus hijos y queda FAILED", async () => {
  const s = await setup();
  const t0 = Date.now();
  const r = await runMakeVideo(s, { hf: { renderSleepSec: 60 }, extra: ["--render-timeout-sec", "2"] });
  assert.ok(Date.now() - t0 < 40_000, "no esperó a que el proceso terminara solo");
  assert.equal(r.code, 1);
  assert.equal(r.result.status, "FAILED");
  assert.equal(r.result.stage, "render");
  assert.equal(r.result.stages.at(-1).timedOut, true);
  assert.match(r.result.error, /render superó 2 s y se canceló/);
  const pid = Number(await readFile(s.pidFile, "utf8"));
  for (let i = 0; i < 50 && isAlive(pid); i++) await new Promise((ok) => setTimeout(ok, 100));
  assert.equal(isAlive(pid), false, `el nieto ${pid} sigue vivo`);
  await rm(s.root, { recursive: true, force: true });
});

test("make-video: si falta un clip o un audio falla en prepare con los ids, sin llamar a la CLI", async () => {
  const s = await setup({ skipClip: "tunguska-giro", skipAudio: "tunguska-cierre" });
  const r = await runMakeVideo(s);
  assert.equal(r.code, 1);
  assert.equal(r.result.stage, "prepare");
  assert.match(r.result.error, /tunguska-giro \(clip\)/);
  assert.match(r.result.error, /tunguska-cierre \(audio\)/);
  assert.deepEqual(await hfCalls(s), []);
  await rm(s.root, { recursive: true, force: true });
});

test("make-video: sin ffprobe falla en prepare antes de transcribir", async () => {
  const s = await setup();
  const r = await runMakeVideo(s, { ffprobe: { missing: true } });
  assert.equal(r.result.stage, "prepare");
  assert.match(r.result.error, /ffprobe no está disponible/);
  assert.deepEqual(await hfCalls(s), []);
  await rm(s.root, { recursive: true, force: true });
});

for (const [name, json, pattern] of [
  ["duración incorrecta", { streams: [{ codec_type: "video", width: 720, height: 1280 }, { codec_type: "audio" }], format: { duration: "37.700" } }, /dura 37\.700 s y la suma de targetDuration es 37\.431 s/],
  ["tamaño incorrecto", { streams: [{ codec_type: "video", width: 1080, height: 1920 }, { codec_type: "audio" }], format: { duration: "37.431" } }, /mide 1080x1920/],
  ["sin audio", { streams: [{ codec_type: "video", width: 720, height: 1280 }], format: { duration: "37.431" } }, /no tiene stream de audio/],
]) {
  test(`make-video: un video final con ${name} falla en validate`, async () => {
    const s = await setup();
    const r = await runMakeVideo(s, { ffprobe: { json } });
    assert.equal(r.code, 1);
    assert.equal(r.result.stage, "validate");
    assert.match(r.result.error, pattern);
    assert.equal(r.result.output, null);
    await rm(s.root, { recursive: true, force: true });
  });
}

test("make-video: el límite de duración es 0.25 s (justo por dentro pasa)", async () => {
  const s = await setup();
  const json = { streams: [{ codec_type: "video", width: 720, height: 1280 }, { codec_type: "audio" }], format: { duration: "37.680" } };
  const r = await runMakeVideo(s, { ffprobe: { json } });
  assert.equal(r.result.status, "COMPLETED", r.result.error);
  await rm(s.root, { recursive: true, force: true });
});

test("make-video: render que termina bien sin dejar el archivo es un fallo", async () => {
  const s = await setup();
  const r = await runMakeVideo(s, { hf: { renderNoOutput: true } });
  assert.equal(r.result.stage, "render");
  assert.match(r.result.error, /no dejó final\.mp4/);
  await rm(s.root, { recursive: true, force: true });
});

test("make-video: --keep-work conserva los assets", async () => {
  const s = await setup();
  const r = await runMakeVideo(s, { extra: ["--keep-work"] });
  assert.equal(r.result.status, "COMPLETED");
  assert.ok(await exists(join(s.work, "assets", "clips", "tunguska-gancho.mp4")));
  assert.ok(await exists(join(s.work, "input", "audio", "tunguska-gancho.mp3")));
  await rm(s.root, { recursive: true, force: true });
});

test("make-video: --skip-check no llama a check, lo deja en stages como omitido y sigue con render", async () => {
  const s = await setup();
  // un check que fallaría si se corriera: con --skip-check no debe importar
  const r = await runMakeVideo(s, { hf: { exit: { check: 1 }, check: { ok: false } }, extra: ["--skip-check"] });
  assert.equal(r.code, 0, r.stderr);
  assert.equal(r.result.status, "COMPLETED");
  assert.deepEqual(r.result.stages.map((x) => x.name), STAGES);
  assert.deepEqual(r.result.stages.find((x) => x.name === "check"), { name: "check", ms: 0, exitCode: null, skipped: true });
  assert.ok(r.result.stages.filter((x) => x.name !== "check").every((x) => x.exitCode === 0 && !x.skipped));
  assert.deepEqual((await hfCalls(s)).map((a) => a[0]).slice(-2), ["lint", "render"]);
  assert.ok(!(await exists(join(s.work, "logs", "check.log"))));
  assert.match(r.stdout, /^ETAPA check OMITIDA$/m);
  await rm(s.root, { recursive: true, force: true });
});

test("make-video: uso incorrecto -> código 2; una carpeta de trabajo con contenido no se toca", async () => {
  const s = await setup();
  await mkdir(s.work);
  await writeFile(join(s.work, "algo.txt"), "no borrar");
  const r = await runMakeVideo(s);
  assert.equal(r.code, 2);
  assert.match(r.stderr, /no está vacía/);
  assert.deepEqual(await readdir(s.work), ["algo.txt"]);

  const r2 = await runMakeVideo({ ...s, work: join(s.root, "otra") }, { extra: ["--render-timeout-sec", "cero"] });
  assert.equal(r2.code, 2);
  assert.match(r2.stderr, /--render-timeout-sec debe ser un número positivo/);
  await rm(s.root, { recursive: true, force: true });
});

test("stageFile: usa symlink si puede y copia si el sistema lo rechaza (EPERM)", async () => {
  const root = await mkdtemp(join(tmpdir(), "mv-stage-"));
  const src = join(root, "a.mp4");
  await writeFile(src, "contenido");
  const eperm = async () => {
    throw Object.assign(new Error("operation not permitted"), { code: "EPERM" });
  };
  assert.equal(await stageFile(src, join(root, "b.mp4"), { link: eperm }), "copy");
  assert.equal(await readFile(join(root, "b.mp4"), "utf8"), "contenido");
  const mode = await stageFile(src, join(root, "c.mp4")); // real: symlink en Linux, copia en Windows sin permisos
  assert.ok(["symlink", "copy"].includes(mode));
  assert.equal(await readFile(join(root, "c.mp4"), "utf8"), "contenido");
  await rm(root, { recursive: true, force: true });
});

test("parseJsonOutput y collectFindings con la forma real de lint y check (0.8.143)", () => {
  const lint = parseJsonOutput('ruido de npx\n{\n "ok": true,\n "findings": [{"code":"a","severity":"warning","message":"m"},{"code":"b","severity":"info","message":"i"}]\n}\n');
  assert.deepEqual(collectFindings(lint), { warnings: ["a: m"], errors: [] });
  assert.equal(parseJsonOutput("sin json"), null);
  const check = { lint: { findings: [{ code: "x", severity: "error", message: "no" }] }, layout: { findings: [{ code: "y", severity: "error", message: "e" }] } };
  assert.deepEqual(collectFindings(check, ["layout"]), { warnings: [], errors: ["y: e"] });
});
