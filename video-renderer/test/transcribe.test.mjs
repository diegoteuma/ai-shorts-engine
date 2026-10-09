import { test } from "node:test";
import assert from "node:assert/strict";
import { mkdtemp, readFile, writeFile, mkdir, readdir, stat, rm } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { main, parseCliResult, transcribeScene, validateTranscript } from "../src/transcribe.mjs";
import { fixture, readJson } from "./helpers.mjs";

// Salida real (pegada del informe del agente de la prueba, CLI 0.8.143) de una corrida de `transcribe --json`.
const REAL_STDOUT_SAMPLE =
  '{"ok":true,"engine":"parakeet","model":"parakeet-tdt-0.6b-v3","detectedLanguage":null,"wordCount":14,"durationSeconds":4.32,"speechOnsetSeconds":null,"transcriptPath":"C:\\\\Users\\\\DUmana\\\\Desktop\\\\AI\\\\claude\\\\hf-tunguska\\\\build\\\\assets\\\\audio\\\\transcript.json"}';

const words = (n) => Array.from({ length: n }, (_, i) => ({ text: `p${i}`, start: i * 0.2, end: i * 0.2 + 0.2 }));

/** Simula la CLI: escribe transcript.json (nombre fijo) en la carpeta de --dir y imprime el JSON por stdout. */
function fakeCli(behavior = {}) {
  const calls = [];
  const runner = async (command, args, opts) => {
    calls.push({ command, args, opts });
    const dir = args[args.indexOf("--dir") + 1];
    if (behavior.exitCode !== undefined) return { exitCode: behavior.exitCode, stdout: behavior.stdout ?? "", stderr: "boom", timedOut: false, ms: 5 };
    if (behavior.timedOut) return { exitCode: -1, stdout: "", stderr: "colgado", timedOut: true, ms: 9 };
    const list = behavior.words ?? words(behavior.wordCount ?? 3);
    const path = behavior.pathOutside ?? join(dir, "transcript.json");
    if (!behavior.pathOutside) await writeFile(path, JSON.stringify(list));
    const out = behavior.stdout ?? JSON.stringify({ ok: true, engine: "parakeet", model: "m", detectedLanguage: null, wordCount: behavior.reportedCount ?? list.length, durationSeconds: behavior.durationSeconds ?? 4.458, speechOnsetSeconds: null, transcriptPath: path });
    return { exitCode: 0, stdout: `${out}\n`, stderr: '{"type":"words"}\n', timedOut: false, ms: 12 };
  };
  return { runner, calls };
}

const tmp = () => mkdtemp(join(tmpdir(), "vr-test-"));

test("parseCliResult toma la salida real de la CLI y salta líneas que no son JSON", () => {
  const r = parseCliResult(`ruido de npx\n${REAL_STDOUT_SAMPLE}\n`);
  assert.equal(r.ok, true);
  assert.equal(r.engine, "parakeet");
  assert.equal(r.wordCount, 14);
  assert.match(r.transcriptPath, /transcript\.json$/);
  assert.equal(parseCliResult("nada útil"), null);
  assert.equal(parseCliResult('{"sin":"ok"}'), null);
});

test("validateTranscript exige text/start/end, orden y no vacío", () => {
  assert.doesNotThrow(() => validateTranscript(words(3), "x"));
  assert.throws(() => validateTranscript([], "x"), /vacía/);
  assert.throws(() => validateTranscript({}, "x"), /no es un arreglo/);
  assert.throws(() => validateTranscript([{ text: "a", start: 0 }], "x"), /text\/start\/end/);
  assert.throws(() => validateTranscript([{ text: "a", start: 2, end: 1 }], "x"), /end < start/);
  assert.throws(() => validateTranscript([{ text: "a", start: 1, end: 2 }, { text: "b", start: 0, end: 0.5 }], "x"), /fuera de orden/);
});

test("transcribeScene: fija la versión, usa --dir propio, no pide confirmación y copia los bytes de la CLI", async () => {
  const out = join(await tmp(), "t", "escena.json");
  const { runner, calls } = fakeCli({ wordCount: 4 });
  const rec = await transcribeScene({ sceneId: "escena", audioPath: "/x/a.mp3", outFile: out, cliVersion: "0.8.143", language: "es", timeoutMs: 60000, runner });
  const c = calls[0];
  assert.equal(c.command, "npx");
  assert.equal(c.args[0], "hyperframes@0.8.143");
  assert.deepEqual(c.args.slice(1, 2), ["transcribe"]);
  assert.ok(c.args.includes("--json"));
  assert.equal(c.args[c.args.indexOf("--language") + 1], "es");
  assert.equal(c.opts.env.npm_config_yes, "true");
  assert.notEqual(c.opts.cwd, c.args[c.args.indexOf("--dir") + 1]); // la carpeta temporal no es el cwd de la CLI (bloqueo en Windows)
  assert.equal(rec.wordCount, 4);
  assert.deepEqual(JSON.parse(await readFile(out, "utf8")), words(4));
  // la carpeta temporal de la escena se borra
  await assert.rejects(stat(c.args[c.args.indexOf("--dir") + 1]));
});

test("transcribeScene: si falla el borrado de la carpeta temporal (EBUSY en Windows) el resultado se conserva y solo se avisa", async () => {
  const out = join(await tmp(), "x.json");
  const { runner } = fakeCli({ wordCount: 3 });
  const busy = async () => { throw Object.assign(new Error("resource busy or locked"), { code: "EBUSY" }); };
  const rec = await transcribeScene({ sceneId: "x", audioPath: "/x/a.mp3", outFile: out, cliVersion: "0.8.143", timeoutMs: 1000, runner, removeDir: busy });
  assert.equal(rec.wordCount, 3);
  assert.match(rec.cleanupWarning, /EBUSY/);
  assert.deepEqual(JSON.parse(await readFile(out, "utf8")), words(3));
  // y tampoco tapa un error real
  await assert.rejects(
    transcribeScene({ sceneId: "y", audioPath: "/x/a.mp3", outFile: join(await tmp(), "y.json"), cliVersion: "0.8.143", timeoutMs: 1000, runner: fakeCli({ exitCode: 1, stdout: '{"ok":false,"error":"real"}' }).runner, removeDir: busy }),
    /real/,
  );
});

test("transcribeScene: cada escena usa una carpeta distinta (no se comparte transcript.json)", async () => {
  const base = await tmp();
  const { runner, calls } = fakeCli();
  for (const id of ["a", "b"]) {
    await transcribeScene({ sceneId: id, audioPath: "/x/a.mp3", outFile: join(base, `${id}.json`), cliVersion: "0.8.143", timeoutMs: 1000, runner });
  }
  assert.notEqual(calls[0].args[calls[0].args.indexOf("--dir") + 1], calls[1].args[calls[1].args.indexOf("--dir") + 1]);
});

test("transcribeScene: el fallo que ocurrió en la prueba (exitCode -1 y stdout vacío) NO copia nada", async () => {
  const out = join(await tmp(), "x.json");
  const { runner } = fakeCli({ exitCode: -1 });
  await assert.rejects(transcribeScene({ sceneId: "x", audioPath: "/x/a.mp3", outFile: out, cliVersion: "0.8.143", timeoutMs: 1000, runner }), /código -1/);
  await assert.rejects(stat(out));
});

test("transcribeScene: con la salida real de la CLI sin red (código 1, ok=false) el error trae el motivo", async () => {
  // Observado ejecutando hyperframes@0.8.143 en un sandbox sin acceso a huggingface.co.
  const real = '{"ok":false,"error":"getaddrinfo ENOTFOUND huggingface.co"}';
  const { runner } = fakeCli({ exitCode: 1, stdout: real });
  await assert.rejects(
    transcribeScene({ sceneId: "x", audioPath: "/x/a.mp3", outFile: join(await tmp(), "x.json"), cliVersion: "0.8.143", timeoutMs: 1000, runner }),
    /código 1\. getaddrinfo ENOTFOUND huggingface\.co/,
  );
});

test("transcribeScene: rechaza timeout, ok=false, transcriptPath ajeno, conteo distinto y versión insegura", async () => {
  const base = await tmp();
  const o = (n) => join(base, `${n}.json`);
  const run = (id, behavior, extra = {}) =>
    transcribeScene({ sceneId: id, audioPath: "/x/a.mp3", outFile: o(id), cliVersion: "0.8.143", timeoutMs: 1000, runner: fakeCli(behavior).runner, ...extra });

  await assert.rejects(run("t1", { timedOut: true }), /superó/);
  await assert.rejects(
    run("t2", { stdout: '{"ok":false,"skipped":true,"reason":"whisper_unavailable","install":"hyperframes models install parakeet"}' }),
    /whisper_unavailable.*models install parakeet/,
  );
  await assert.rejects(run("t3", { pathOutside: join(base, "viejo", "transcript.json"), stdout: JSON.stringify({ ok: true, wordCount: 3, transcriptPath: join(base, "viejo", "transcript.json") }) }), /fuera de la carpeta temporal/);
  await assert.rejects(run("t4", { wordCount: 3, reportedCount: 9 }), /wordCount informado/);
  await assert.rejects(run("t5", {}, { cliVersion: "latest; rm -rf /" }), /Versión de la CLI inválida/);
  for (const n of ["t1", "t2", "t3", "t4", "t5"]) await assert.rejects(stat(o(n)));
});

test("main: transcribe todas las escenas de la historia, escribe el reporte y avisa si la duración no cuadra", async () => {
  const base = await tmp();
  const assets = join(base, "assets");
  await mkdir(join(assets, "audio"), { recursive: true });
  const story = readJson(fixture("story.backend.json"));
  for (const s of story.scenes) await writeFile(join(assets, "audio", `${s.id}.mp3`), "x");
  const storyFile = join(base, "story.json");
  await writeFile(storyFile, JSON.stringify(story));
  const out = join(base, "transcripts");

  const { runner, calls } = fakeCli({ wordCount: 5, durationSeconds: 99 });
  await main(["--story", storyFile, "--assets", assets, "--out", out], runner);
  assert.equal(calls.length, story.scenes.length);
  const files = (await readdir(out)).sort();
  assert.ok(files.includes("transcribe-report.json"));
  assert.equal(files.filter((f) => f.endsWith(".json") && f !== "transcribe-report.json").length, story.scenes.length);
  const report = readJson(join(out, "transcribe-report.json"));
  assert.equal(report.cliVersion, "0.8.143");
  assert.equal(report.warnings.length, story.scenes.length); // 99 s contra ~4-8 s de targetDuration
  assert.match(report.warnings[0], /más de 0\.15 s después del final/);

  // una duración MENOR que targetDuration (silencio final) es lo normal y no avisa
  const quiet = join(base, "t2");
  await main(["--story", storyFile, "--assets", assets, "--out", quiet], fakeCli({ wordCount: 5, durationSeconds: 3.84 }).runner);
  assert.deepEqual(readJson(join(quiet, "transcribe-report.json")).warnings, []);

  // segunda corrida sin --force: se niega a sobrescribir
  await assert.rejects(main(["--story", storyFile, "--assets", assets, "--out", out], runner), /ya existe/);
  await main(["--story", storyFile, "--assets", assets, "--out", out, "--force"], runner);
  await rm(base, { recursive: true, force: true });
});

test("compare-transcripts: detecta diferencias de texto, tiempo y conteo, y respeta la tolerancia", async () => {
  const { compareWords, compareDirs } = await import("../src/compare-transcripts.mjs");
  const a = words(3);
  assert.deepEqual(compareWords(a, structuredClone(a)).diffs, []);
  const b = structuredClone(a);
  b[1].text = "otra";
  b[2].end += 0.01;
  const r = compareWords(a, b);
  assert.equal(r.diffs.length, 2);
  assert.equal(compareWords(a, b.map((w, i) => (i === 2 ? w : w)), 20).diffs.length, 1); // 10 ms entra en 20 ms, el texto no
  assert.match(compareWords(a, a.slice(0, 2)).diffs[0], /distinto número/);
  // --loose-text: el ASR escribe "Rusia." / "El" donde el guion dice "Rusia:" / "el" (diferencias reales vistas en tunguska)
  const g = [{ text: "Rusia:", start: 0, end: 1 }, { text: "¿qué", start: 1, end: 2 }];
  const h = [{ text: "Rusia.", start: 0, end: 1 }, { text: "¿Qué", start: 1, end: 2 }];
  assert.equal(compareWords(g, h).diffs.length, 2);
  assert.equal(compareWords(g, h, 0, true).diffs.length, 0);
  assert.equal(compareWords(g, [h[0], { ...h[1], text: "otra", end: 2 }], 0, true).diffs.length, 1);

  const base = await tmp();
  await mkdir(join(base, "A")); await mkdir(join(base, "B"));
  await writeFile(join(base, "A", "x.json"), JSON.stringify(a));
  await writeFile(join(base, "B", "x.json"), JSON.stringify(a.map((w) => ({ ...w, extra: 1 })))); // campos extra se ignoran
  await writeFile(join(base, "A", "solo.json"), "[]");
  const res = await compareDirs(join(base, "A"), join(base, "B"));
  assert.equal(res.ok, false);
  assert.equal(res.scenes.find((s) => s.file === "x.json").diffs.length, 0);
  assert.match(res.scenes.find((s) => s.file === "solo.json").diffs[0], /solo existe en A/);
});
