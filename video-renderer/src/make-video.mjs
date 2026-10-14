#!/usr/bin/env node
// Video final de una historia en un solo comando: preparar -> transcribir -> generar -> lint -> check -> render -> validar.
//
//   node src/make-video.mjs --story story.json --clips-dir ../data/clips --audio-dir ../data/audio --work ../data/renders/x/work
//
// Lo lanza el backend Java (un proceso por trabajo) y también se puede correr a mano.
// --clips-dir y --audio-dir son carpetas PLANAS (<idEscena>.mp4 y <idEscena>.mp3, como las dejan CLIPS_DIR y AUDIO_DIR).
// --work debe no existir o estar vacía: es la carpeta del proyecto HyperFrames y del resultado.
// --skip-check omite `hyperframes check`: en la VM (e2-small) tardó 1119.5 s de 2722.9 s (n = 1) y la composición sale
// siempre de la misma plantilla, así que se valida en desarrollo. Queda en `stages` como { name: "check", skipped: true }.
//
// Carpeta de trabajo:
//   input/{clips,audio}/   symlinks a los archivos originales (o copias si el sistema no deja crear symlinks)
//   transcripts/<id>.json  transcripción de cada escena
//   index.html, words.global.json, generation-report.json, assets/, fonts/   lo que escribe generate.mjs
//   final.mp4              el video
//   logs/<etapa>.log       salida completa de cada etapa
//   make-video-result.json estado máquina-legible; se reescribe (atómico) en cada transición de etapa
//
// Códigos de salida: 0 ok, 1 falló una etapa (detalle en make-video-result.json), 2 uso incorrecto.
// Qué se sabe de la CLI 0.8.143 (ejecutado en Windows, tunguska): `lint` y `check` devuelven 0 con solo avisos
// (y con hallazgos "info") y 1 con errores; con --json imprimen un objeto con ok/errorCount/findings.
// `render --help` lista --output, --quality (standard = high) y --workers.
import { copyFile, mkdir, readdir, readFile, rename, rm, stat, symlink, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadStory, buildTimeline } from "./story.mjs";
import { DEFAULT_CLI_VERSION, SAFE_VERSION, runCommand, sceneWarnings, transcribeScene } from "./transcribe.mjs";

const here = dirname(fileURLToPath(import.meta.url));

export const RESULT_FILE = "make-video-result.json";
export const OUTPUT_FILE = "final.mp4";
const WIDTH = 720;
const HEIGHT = 1280;
const LANGUAGE = "es";
export const DEFAULTS = {
  transcribeTimeoutSec: 600, // por escena
  generateTimeoutSec: 120,
  lintTimeoutSec: 120,
  checkTimeoutSec: 2400, // medido en la VM (n = 1): 1119.5 s para explosion-halifax; igual que el default de Java
  renderTimeoutSec: 1800, // medido en la VM: 1014 s para tunguska con --workers 1
  maxDurationDiffSec: 0.25, // medido: 37.434 s de video contra 37.431 s esperados
};

const USAGE = `Uso: node src/make-video.mjs --story <archivo> --clips-dir <dir> --audio-dir <dir> --work <dir> [opciones]

  --cli-version <x.y.z>            versión de hyperframes (por defecto ${DEFAULT_CLI_VERSION})
  --hyperframes-cmd <json>         comando en lugar de ["npx","hyperframes@<versión>"] (pruebas), p. ej. '["node","fake.mjs"]'
                                   también por la variable MAKE_VIDEO_HYPERFRAMES_CMD
  --ffprobe-cmd <json>             comando en lugar de ["ffprobe"] (pruebas); o MAKE_VIDEO_FFPROBE_CMD
  --transcribe-timeout-sec <s>     por escena (por defecto ${DEFAULTS.transcribeTimeoutSec})
  --generate-timeout-sec <s>       (por defecto ${DEFAULTS.generateTimeoutSec})
  --lint-timeout-sec <s>           (por defecto ${DEFAULTS.lintTimeoutSec})
  --check-timeout-sec <s>          (por defecto ${DEFAULTS.checkTimeoutSec})
  --render-timeout-sec <s>         (por defecto ${DEFAULTS.renderTimeoutSec})
  --max-duration-diff-sec <s>      diferencia máxima entre el video y la suma de targetDuration (por defecto ${DEFAULTS.maxDurationDiffSec})
  --keep-work                      no borrar assets ni copias al terminar (para depurar)
  --skip-check                     no correr \`hyperframes check\` (queda en stages con skipped: true)
  --help`;

class UsageError extends Error {}

/** Falla de una etapa; `exitCode` y `timedOut` del proceso que la causó, si hubo uno. */
class StageError extends Error {
  constructor(message, { exitCode = null, timedOut = false } = {}) {
    super(message);
    this.exitCode = exitCode;
    this.timedOut = timedOut;
  }
}

const VALUE_FLAGS = new Set([
  "story", "clips-dir", "audio-dir", "work", "cli-version", "hyperframes-cmd", "ffprobe-cmd",
  "transcribe-timeout-sec", "generate-timeout-sec", "lint-timeout-sec", "check-timeout-sec", "render-timeout-sec",
  "max-duration-diff-sec",
]);
const BOOL_FLAGS = new Set(["keep-work", "skip-check", "help"]);

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith("--")) throw new UsageError(`Argumento inesperado: ${a}`);
    const k = a.slice(2);
    if (BOOL_FLAGS.has(k)) args[k] = true;
    else if (VALUE_FLAGS.has(k)) {
      const v = argv[++i];
      if (v === undefined || v.startsWith("--")) throw new UsageError(`Falta el valor de --${k}`);
      args[k] = v;
    } else throw new UsageError(`Opción desconocida: --${k}`);
  }
  return args;
}

function seconds(args, key, fallback) {
  if (args[key] === undefined) return fallback;
  const n = Number(args[key]);
  if (!Number.isFinite(n) || n <= 0) throw new UsageError(`--${key} debe ser un número positivo (recibido: ${args[key]})`);
  return n;
}

// También por variable de entorno: Java en Windows (ProcessBuilder) no escapa las comillas de un argumento,
// así que un JSON pasado como argumento llega roto (observado en el test de contrato Java<->Node).
function commandArray(args, key, envName, fallback) {
  const raw = args[key] ?? process.env[envName];
  if (raw === undefined || raw === "") return fallback;
  let v;
  try {
    v = JSON.parse(raw);
  } catch {
    throw new UsageError(`--${key} (o ${envName}) debe ser un arreglo JSON de textos`);
  }
  if (!Array.isArray(v) || v.length === 0 || !v.every((x) => typeof x === "string" && x !== "")) {
    throw new UsageError(`--${key} (o ${envName}) debe ser un arreglo JSON de textos no vacíos`);
  }
  return v;
}

const exists = (p) => stat(p).then(() => true, () => false);
const short = (s, n = 1000) => (s.length > n ? `${s.slice(0, n)}…` : s);
const lastLines = (s, n = 3) => String(s ?? "").trim().split(/\r?\n/).slice(-n).join(" | ");

/**
 * Deja `dst` apuntando a `src`: symlink si se puede (no duplica los clips) y, si falla (EPERM en Windows sin modo
 * desarrollador, por ejemplo), una copia. Devuelve "symlink" o "copy".
 */
export async function stageFile(src, dst, { link = symlink } = {}) {
  try {
    await link(resolve(src), dst, "file");
    return "symlink";
  } catch {
    await copyFile(src, dst);
    return "copy";
  }
}

/** Primer objeto JSON de la salida (la CLI con --json imprime uno, posiblemente con ruido de npx antes). */
export function parseJsonOutput(stdout) {
  const text = String(stdout ?? "");
  const start = text.indexOf("{");
  if (start < 0) return null;
  try {
    return JSON.parse(text.slice(start, text.lastIndexOf("}") + 1));
  } catch {
    return null;
  }
}

const findingText = (f) => `${f.code ?? "?"}: ${f.message ?? ""}`.trim();

/** Avisos y errores de la salida --json de lint (un reporte) o de check (varias secciones con su reporte). */
export function collectFindings(json, sections) {
  const reports = sections ? sections.map((k) => json?.[k]).filter(Boolean) : json ? [json] : [];
  const warnings = [];
  const errors = [];
  for (const r of reports) {
    for (const f of Array.isArray(r.findings) ? r.findings : []) {
      if (f.severity === "warning") warnings.push(findingText(f));
      else if (f.severity === "error") errors.push(findingText(f));
    }
  }
  return { warnings: [...new Set(warnings)], errors: [...new Set(errors)] };
}

// `check` repite el lint completo: sus hallazgos de lint ya se informan en la etapa lint.
const CHECK_SECTIONS = ["runtime", "layout", "motion", "contrast"];

export async function makeVideo(args) {
  const work = resolve(args.work);
  const storyPath = resolve(args.story);
  const clipsDir = resolve(args["clips-dir"]);
  const audioDir = resolve(args["audio-dir"]);
  const cliVersion = args["cli-version"] ?? DEFAULT_CLI_VERSION;
  if (!SAFE_VERSION.test(cliVersion)) throw new UsageError(`Versión de la CLI inválida: ${JSON.stringify(cliVersion)}`);
  const hf = commandArray(args, "hyperframes-cmd", "MAKE_VIDEO_HYPERFRAMES_CMD", ["npx", `hyperframes@${cliVersion}`]);
  const ffprobe = commandArray(args, "ffprobe-cmd", "MAKE_VIDEO_FFPROBE_CMD", ["ffprobe"]);
  const timeouts = {
    transcribe: seconds(args, "transcribe-timeout-sec", DEFAULTS.transcribeTimeoutSec) * 1000,
    generate: seconds(args, "generate-timeout-sec", DEFAULTS.generateTimeoutSec) * 1000,
    lint: seconds(args, "lint-timeout-sec", DEFAULTS.lintTimeoutSec) * 1000,
    check: seconds(args, "check-timeout-sec", DEFAULTS.checkTimeoutSec) * 1000,
    render: seconds(args, "render-timeout-sec", DEFAULTS.renderTimeoutSec) * 1000,
  };
  const maxDiff = seconds(args, "max-duration-diff-sec", DEFAULTS.maxDurationDiffSec);

  // La carpeta de trabajo debe ser nueva: nunca se borra nada que no haya creado este comando.
  if (await exists(work)) {
    if (!(await stat(work)).isDirectory()) throw new UsageError(`${work} existe y no es una carpeta`);
    if ((await readdir(work)).length > 0) throw new UsageError(`La carpeta de trabajo ${work} no está vacía`);
  }
  const logs = join(work, "logs");
  await mkdir(logs, { recursive: true });

  const env = { ...process.env, npm_config_yes: "true" }; // sin esto npx pregunta por la versión fijada y se cuelga
  const result = {
    status: "RUNNING",
    stage: null,
    error: null,
    storyId: null,
    startedAt: new Date().toISOString(),
    finishedAt: null,
    stages: [],
    warnings: [],
    output: null,
    outputCheck: null,
    assetMode: null,
    versions: { hyperframes: cliVersion, hyperframesCommand: hf, node: process.version },
  };
  const resultPath = join(work, RESULT_FILE);
  const saveResult = async () => {
    const tmp = `${resultPath}.tmp`;
    await writeFile(tmp, JSON.stringify(result, null, 2));
    // En Windows el rename puede fallar un instante si otro proceso está leyendo el archivo.
    for (let i = 0; ; i++) {
      try {
        await rename(tmp, resultPath);
        return;
      } catch (e) {
        if (i >= 10) throw e;
        await new Promise((r) => setTimeout(r, 100));
      }
    }
  };
  const warn = (stage, message) => result.warnings.push({ stage, message });
  const logOf = (stage) => join(logs, `${stage}.log`);
  const run = (command, cmdArgs, stage, timeoutMs, cwd = work) =>
    runCommand(command, cmdArgs, { cwd, env, timeoutMs, logFile: logOf(stage) });
  const runHf = (cmdArgs, stage) => run(hf[0], [...hf.slice(1), ...cmdArgs], stage, timeouts[stage]);
  const processFailure = (what, r, timeoutMs) => {
    if (r.timedOut) return new StageError(`${what} superó ${Math.round(timeoutMs / 1000)} s y se canceló`, { exitCode: r.exitCode, timedOut: true });
    return new StageError(`${what} terminó con código ${r.exitCode}. ${lastLines(r.stderr) || lastLines(r.stdout)}`.trim(), { exitCode: r.exitCode });
  };

  const runStage = async (name, fn) => {
    result.stage = name;
    await saveResult();
    console.log(`ETAPA ${name} INICIO`);
    const t0 = Date.now();
    const entry = { name, ms: 0, exitCode: null };
    try {
      const info = await fn();
      Object.assign(entry, info ?? {});
      entry.ms = Date.now() - t0;
      result.stages.push(entry);
      console.log(`ETAPA ${name} FIN ${entry.ms} ms`);
    } catch (e) {
      entry.ms = Date.now() - t0;
      if (e instanceof StageError) {
        entry.exitCode = e.exitCode;
        if (e.timedOut) entry.timedOut = true;
      }
      result.stages.push(entry);
      result.status = "FAILED";
      result.error = short(e.message);
      console.log(`ETAPA ${name} FIN con error`);
      throw e;
    }
  };

  let story;
  let timeline;
  let failed = null;
  try {
    await runStage("prepare", async () => {
      story = await loadStory(storyPath);
      result.storyId = typeof story?.id === "string" ? story.id : null;
      try {
        timeline = buildTimeline(story); // valida ids (se usan en rutas) y duraciones
      } catch (e) {
        throw new StageError(`Historia inválida: ${e.message}`);
      }
      // Todo lo que falte se informa junto, antes de gastar tiempo.
      const missing = [];
      for (const s of timeline.scenes) {
        const lacks = [];
        if (!(await exists(join(clipsDir, `${s.id}.mp4`)))) lacks.push("clip");
        if (!(await exists(join(audioDir, `${s.id}.mp3`)))) lacks.push("audio");
        if (lacks.length) missing.push(`${s.id} (${lacks.join(" y ")})`);
      }
      if (missing.length) throw new StageError(`Faltan archivos de ${missing.length} escena(s): ${missing.join(", ")}`);
      const probe = await run(ffprobe[0], [...ffprobe.slice(1), "-version"], "prepare", 30_000);
      if (probe.exitCode !== 0) throw new StageError(`ffprobe no está disponible (código ${probe.exitCode}); hace falta para validar el video`, { exitCode: probe.exitCode });

      const modes = new Set();
      for (const kind of ["clips", "audio"]) await mkdir(join(work, "input", kind), { recursive: true });
      for (const s of timeline.scenes) {
        modes.add(await stageFile(join(clipsDir, `${s.id}.mp4`), join(work, "input", "clips", `${s.id}.mp4`)));
        modes.add(await stageFile(join(audioDir, `${s.id}.mp3`), join(work, "input", "audio", `${s.id}.mp3`)));
      }
      result.assetMode = modes.size === 1 ? [...modes][0] : "mixed";
      return { exitCode: 0 };
    });

    await runStage("transcribe", async () => {
      const scenes = [];
      await mkdir(join(work, "tmp"), { recursive: true });
      for (const s of timeline.scenes) {
        let last = null;
        const runner = async (c, a, o) => (last = await runCommand(c, a, { ...o, logFile: logOf("transcribe") }));
        await writeFile(logOf("transcribe"), `\n===== ${s.id} =====\n`, { flag: "a" });
        try {
          const rec = await transcribeScene({
            sceneId: s.id,
            audioPath: join(audioDir, `${s.id}.mp3`), // el original, no el symlink
            outFile: join(work, "transcripts", `${s.id}.json`),
            cliVersion,
            language: LANGUAGE,
            timeoutMs: timeouts.transcribe,
            runner,
            workRoot: join(work, "tmp"),
            cliCommand: hf,
          });
          for (const w of sceneWarnings(rec, s, LANGUAGE)) warn("transcribe", w);
          scenes.push({ scene: s.id, ms: rec.ms, engine: rec.engine, wordCount: rec.wordCount });
        } catch (e) {
          throw new StageError(e.message, { exitCode: last?.exitCode ?? null, timedOut: last?.timedOut ?? false });
        }
      }
      return { exitCode: 0, scenes };
    });

    await runStage("generate", async () => {
      const r = await run(
        process.execPath,
        [join(here, "generate.mjs"), "--story", storyPath, "--transcripts", join(work, "transcripts"), "--assets", join(work, "input"), "--out", work, "--force"],
        "generate",
        timeouts.generate,
      );
      if (r.timedOut || r.exitCode !== 0) {
        const err = String(r.stderr).split(/\r?\n/).filter((l) => l.startsWith("ERROR ")).pop();
        if (err && !r.timedOut) throw new StageError(`generate: ${err.slice(6)}`, { exitCode: r.exitCode });
        throw processFailure("generate", r, timeouts.generate);
      }
      const report = JSON.parse(await readFile(join(work, "generation-report.json"), "utf8"));
      for (const w of report.warnings ?? []) warn("generate", w);
      return { exitCode: r.exitCode };
    });

    for (const [stage, sections] of [["lint", null], ["check", CHECK_SECTIONS]]) {
      if (stage === "check" && args["skip-check"]) {
        result.stages.push({ name: "check", ms: 0, exitCode: null, skipped: true });
        await saveResult();
        console.log("ETAPA check OMITIDA");
        continue;
      }
      await runStage(stage, async () => {
        const r = await runHf([stage, "--json"], stage);
        const json = parseJsonOutput(r.stdout);
        const { warnings, errors } = collectFindings(json, sections);
        if (r.timedOut) throw processFailure(stage, r, timeouts[stage]);
        // Solo fallan los errores: con avisos la CLI devuelve 0 (observado) y se siguen informando.
        if (r.exitCode !== 0 || json?.ok === false) {
          const detail = errors.length ? errors.slice(0, 5).join(" | ") : lastLines(r.stderr) || lastLines(r.stdout);
          throw new StageError(`${stage} encontró errores (código ${r.exitCode}): ${detail}`, { exitCode: r.exitCode });
        }
        if (!json) warn(stage, "no se pudo leer la salida JSON de la CLI; se tomó el código de salida 0 como aprobado");
        for (const w of warnings) warn(stage, w);
        return { exitCode: r.exitCode };
      });
    }

    await runStage("render", async () => {
      const r = await runHf(["render", "--output", OUTPUT_FILE, "--quality", "standard", "--workers", "1"], "render");
      if (r.timedOut || r.exitCode !== 0) throw processFailure("render", r, timeouts.render);
      if (!(await exists(join(work, OUTPUT_FILE)))) throw new StageError(`render terminó bien pero no dejó ${OUTPUT_FILE}`, { exitCode: r.exitCode });
      return { exitCode: r.exitCode };
    });

    await runStage("validate", async () => {
      const r = await run(
        ffprobe[0],
        [...ffprobe.slice(1), "-v", "error", "-show_entries", "stream=codec_type,width,height:format=duration", "-of", "json", join(work, OUTPUT_FILE)],
        "validate",
        60_000,
      );
      if (r.timedOut || r.exitCode !== 0) throw processFailure("ffprobe", r, 60_000);
      const json = parseJsonOutput(r.stdout);
      const streams = Array.isArray(json?.streams) ? json.streams : [];
      const video = streams.find((s) => s.codec_type === "video");
      const hasAudio = streams.some((s) => s.codec_type === "audio");
      const duration = Number(json?.format?.duration);
      const expected = timeline.totalDuration;
      result.outputCheck = {
        width: video?.width ?? null,
        height: video?.height ?? null,
        hasAudio,
        duration: Number.isFinite(duration) ? duration : null,
        expectedDuration: expected,
      };
      const problems = [];
      if (!video) problems.push("no tiene stream de video");
      else if (video.width !== WIDTH || video.height !== HEIGHT) problems.push(`mide ${video.width}x${video.height} y se esperaba ${WIDTH}x${HEIGHT}`);
      if (!hasAudio) problems.push("no tiene stream de audio");
      if (!Number.isFinite(duration)) problems.push("ffprobe no informó la duración");
      else if (Math.abs(duration - expected) > maxDiff) {
        problems.push(`dura ${duration.toFixed(3)} s y la suma de targetDuration es ${expected.toFixed(3)} s (diferencia > ${maxDiff} s)`);
      }
      if (problems.length) throw new StageError(`El video final no es válido: ${problems.join("; ")}`, { exitCode: r.exitCode });
      return { exitCode: r.exitCode };
    });

    result.status = "COMPLETED";
    result.stage = null;
    result.output = OUTPUT_FILE;
  } catch (e) {
    failed = e;
  }

  // Limpieza: lo pesado y regenerable se va; en fallo, solo los medios (para poder leer index.html y los logs).
  if (!args["keep-work"]) {
    const remove = ["input", "assets", "tmp"];
    if (!failed) remove.push("fonts");
    for (const d of remove) {
      try {
        await rm(join(work, d), { recursive: true, force: true, maxRetries: 8, retryDelay: 250 });
      } catch (e) {
        warn("cleanup", `no se pudo borrar ${d}/ (${e.code ?? e.message})`);
      }
    }
  }
  result.finishedAt = new Date().toISOString();
  await saveResult();
  if (failed) {
    console.error(`ERROR ${result.error}`);
    return 1;
  }
  console.log(`OK  ${join(work, OUTPUT_FILE)}`);
  return 0;
}

export async function main(argv = process.argv.slice(2)) {
  let args;
  try {
    args = parseArgs(argv);
    if (args.help) {
      console.log(USAGE);
      return 0;
    }
    for (const k of ["story", "clips-dir", "audio-dir", "work"]) if (!args[k]) throw new UsageError(`Falta --${k}`);
    return await makeVideo(args);
  } catch (e) {
    if (e instanceof UsageError) {
      console.error(`ERROR ${e.message}\n\n${USAGE}`);
      return 2;
    }
    throw e;
  }
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().then(
    (code) => process.exit(code),
    (e) => {
      console.error(`ERROR ${e.message}`);
      process.exit(1);
    },
  );
}
