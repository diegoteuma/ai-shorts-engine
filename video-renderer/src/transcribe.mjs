#!/usr/bin/env node
// Transcribe el audio de cada escena con `hyperframes transcribe` y deja transcripts/<idEscena>.json.
//
//   node src/transcribe.mjs --story ..\data\stories\tunguska.json --assets ..\data --out transcripts
//
// Qué se sabe (evidencia del historial del agente de la prueba, CLI 0.8.141 y 0.8.143, Windows) y qué no:
//  - El comando real fue: npx hyperframes transcribe <audio.mp3> --language es --json
//  - La CLI escribe SIEMPRE un archivo llamado transcript.json, sin importar el nombre del audio, y cada
//    corrida lo sobrescribe. Con --dir <carpeta> lo escribe en esa carpeta (probado con una carpeta vacía).
//    Por eso cada escena se transcribe en su propia carpeta temporal: no puede quedar un archivo viejo.
//  - Una versión de npx nunca descargada pide confirmación interactiva y deja el proceso colgado:
//    se pasa npm_config_yes=true, no se le da entrada estándar y hay un tiempo máximo por escena.
//  - La salida estándar con --json es un objeto con ok, engine, model, detectedLanguage, wordCount,
//    durationSeconds, speechOnsetSeconds y transcriptPath. El progreso va a stderr.
//  - Con Parakeet el idioma se detecta solo (según el --help, --language solo aplica a Whisper).
// No probado: ejecución en Linux/GCP, ni --dir contra una carpeta que ya sea un proyecto HyperFrames.
import { spawn } from "node:child_process";
import { copyFile, mkdir, mkdtemp, readFile, rm, stat, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { dirname, isAbsolute, join, relative, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { loadStory, buildTimeline } from "./story.mjs";

export const DEFAULT_CLI_VERSION = "0.8.143"; // la última con la que se probó la transcripción
export const DEFAULT_TIMEOUT_MS = 10 * 60 * 1000; // incluye una posible primera descarga de npm y del modelo
const SAFE_VERSION = /^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/;

/** Comillas para cmd.exe (solo cuando se usa shell en Windows). */
function quoteWin(arg) {
  if (/[\r\n"%^&|<>]/.test(arg)) throw new Error(`Argumento no permitido en Windows: ${JSON.stringify(arg)}`);
  return /[\s]/.test(arg) ? `"${arg}"` : arg;
}

/**
 * Ejecuta un comando sin entrada estándar y con tiempo máximo. Devuelve { exitCode, stdout, stderr, timedOut, ms }.
 * Se puede reemplazar en las pruebas (parámetro `runner`).
 */
export function runCommand(command, args, { cwd, env, timeoutMs }) {
  return new Promise((resolvePromise) => {
    const started = Date.now();
    const win = process.platform === "win32";
    const child = win
      ? spawn([command, ...args].map(quoteWin).join(" "), { cwd, env, shell: true, stdio: ["ignore", "pipe", "pipe"], windowsHide: true })
      : spawn(command, args, { cwd, env, stdio: ["ignore", "pipe", "pipe"] });
    let stdout = "";
    let stderr = "";
    let timedOut = false;
    const cap = (s, chunk) => (s + chunk).slice(-200_000); // no acumular sin límite
    child.stdout.on("data", (d) => (stdout = cap(stdout, d)));
    child.stderr.on("data", (d) => (stderr = cap(stderr, d)));
    const timer = setTimeout(() => {
      timedOut = true;
      if (win) spawn("taskkill", ["/pid", String(child.pid), "/t", "/f"], { stdio: "ignore" });
      else child.kill("SIGKILL");
    }, timeoutMs);
    child.on("error", (e) => {
      clearTimeout(timer);
      resolvePromise({ exitCode: -1, stdout, stderr: `${stderr}\n${e.message}`, timedOut, ms: Date.now() - started });
    });
    child.on("close", (code) => {
      clearTimeout(timer);
      resolvePromise({ exitCode: code ?? -1, stdout, stderr, timedOut, ms: Date.now() - started });
    });
  });
}

/** Última línea de stdout que sea un objeto JSON con la clave "ok". */
export function parseCliResult(stdout) {
  const lines = String(stdout).split(/\r?\n/).map((l) => l.trim()).filter(Boolean);
  for (let i = lines.length - 1; i >= 0; i--) {
    if (!lines[i].startsWith("{")) continue;
    try {
      const obj = JSON.parse(lines[i]);
      if (obj && typeof obj === "object" && "ok" in obj) return obj;
    } catch {
      /* seguir buscando */
    }
  }
  return null;
}

/** Valida el arreglo de palabras de la transcripción (el formato que consume align.mjs). */
export function validateTranscript(words, where) {
  if (!Array.isArray(words)) throw new Error(`${where}: la transcripción no es un arreglo de palabras`);
  if (words.length === 0) throw new Error(`${where}: la transcripción está vacía`);
  let prev = -Infinity;
  words.forEach((w, i) => {
    if (!w || typeof w.text !== "string" || !Number.isFinite(w.start) || !Number.isFinite(w.end)) {
      throw new Error(`${where}: la palabra ${i} no tiene text/start/end válidos`);
    }
    if (w.end < w.start) throw new Error(`${where}: la palabra ${i} (${JSON.stringify(w.text)}) tiene end < start`);
    if (w.start < prev - 1e-9) throw new Error(`${where}: la palabra ${i} (${JSON.stringify(w.text)}) está fuera de orden`);
    prev = w.start;
  });
}

/** Borra la carpeta temporal; en Windows un antivirus o un proceso que acaba de salir puede retenerla un instante. */
const defaultRemoveDir = (dir) => rm(dir, { recursive: true, force: true, maxRetries: 8, retryDelay: 250 });

const isInside = (parent, child) => {
  const rel = relative(resolve(parent), resolve(child));
  return rel !== "" && !rel.startsWith("..") && !isAbsolute(rel);
};

/**
 * Transcribe un audio en una carpeta temporal propia. No toca `outFile` si algo falla.
 * Devuelve el registro de la escena para el reporte.
 */
export async function transcribeScene({ sceneId, audioPath, outFile, cliVersion, language, engine, timeoutMs, runner = runCommand, workRoot, removeDir = defaultRemoveDir }) {
  if (!SAFE_VERSION.test(cliVersion)) throw new Error(`Versión de la CLI inválida: ${JSON.stringify(cliVersion)}`);
  const work = await mkdtemp(join(workRoot ?? tmpdir(), `hf-transcribe-${sceneId}-`));
  let record;
  let cleanupWarning = null;
  try {
    const args = ["hyperframes@" + cliVersion, "transcribe", resolve(audioPath), "--json", "--dir", work];
    if (language) args.push("--language", language);
    if (engine) args.push("--engine", engine);
    const env = { ...process.env, npm_config_yes: "true" }; // evita la pregunta interactiva de npx
    // cwd = el actual, NO la carpeta temporal: un proceso con esa carpeta como directorio de trabajo la bloquea en Windows.
    const r = await runner("npx", args, { cwd: process.cwd(), env, timeoutMs });
    const tail = (r.stderr || "").trim().split(/\r?\n/).slice(-3).join(" | ");

    if (r.timedOut) throw new Error(`${sceneId}: la transcripción superó ${Math.round(timeoutMs / 1000)} s y se canceló. ${tail}`);
    const result = parseCliResult(r.stdout);
    // Observado con la CLI real (0.8.143) sin red hacia huggingface.co: código 1 y stdout {"ok":false,"error":"..."}.
    const why = (x) => [x?.error, x?.reason, x?.install ? `Sugerencia de la CLI: ${x.install}` : null].filter(Boolean).join(" | ");
    if (r.exitCode !== 0) {
      throw new Error(`${sceneId}: hyperframes terminó con código ${r.exitCode}. ${why(result) || tail}`.trim());
    }

    if (!result) throw new Error(`${sceneId}: la salida de hyperframes no contiene un objeto JSON con "ok". stdout: ${String(r.stdout).slice(0, 300)}`);
    if (result.ok !== true) throw new Error(`${sceneId}: hyperframes informó ok=${result.ok}. ${why(result)}`.trim());
    if (typeof result.transcriptPath !== "string" || !result.transcriptPath) {
      throw new Error(`${sceneId}: la salida no trae transcriptPath`);
    }
    // El archivo debe estar en la carpeta temporal de ESTA escena: así nunca se lee un transcript.json viejo.
    if (!isInside(work, result.transcriptPath)) {
      throw new Error(`${sceneId}: transcriptPath (${result.transcriptPath}) queda fuera de la carpeta temporal de la escena (${work}); no se usa.`);
    }
    const raw = await readFile(result.transcriptPath, "utf8");
    let words;
    try {
      words = JSON.parse(raw);
    } catch (e) {
      throw new Error(`${sceneId}: transcript.json no es JSON válido (${e.message})`);
    }
    validateTranscript(words, sceneId);
    if (Number.isFinite(result.wordCount) && result.wordCount !== words.length) {
      throw new Error(`${sceneId}: wordCount informado (${result.wordCount}) distinto de las palabras del archivo (${words.length})`);
    }

    await mkdir(dirname(outFile), { recursive: true });
    await copyFile(result.transcriptPath, outFile); // se conservan los bytes que escribió la CLI
    record = {
      scene: sceneId,
      ms: r.ms,
      engine: result.engine ?? null,
      model: result.model ?? null,
      detectedLanguage: result.detectedLanguage ?? null,
      wordCount: words.length,
      durationSeconds: result.durationSeconds ?? null,
      speechOnsetSeconds: result.speechOnsetSeconds ?? null,
    };
  } finally {
    // Un fallo al borrar la carpeta temporal nunca debe tapar el resultado (ni un error real anterior).
    try {
      await removeDir(work);
    } catch (e) {
      cleanupWarning = `no se pudo borrar la carpeta temporal ${work} (${e.code ?? e.message}); se puede borrar a mano`;
    }
  }
  if (cleanupWarning) record.cleanupWarning = cleanupWarning;
  return record;
}

const USAGE = `Uso: node src/transcribe.mjs --story <archivo|url> --assets <dir> --out <dir> [opciones]

  --assets <dir>        carpeta con audio/<idEscena>.mp3
  --out <dir>           dónde dejar <idEscena>.json (por defecto: transcripts)
  --cli-version <x.y.z> versión de hyperframes a usar (por defecto ${DEFAULT_CLI_VERSION})
  --language <código>   se pasa a la CLI (por defecto es; con Parakeet se ignora, según el --help)
  --engine <motor>      auto | parakeet | whisper (por defecto: lo decide la CLI)
  --timeout-sec <s>     tiempo máximo por escena (por defecto ${DEFAULT_TIMEOUT_MS / 1000})
  --force               sobrescribir transcripciones existentes
  --help`;

function parseArgs(argv) {
  const values = new Set(["story", "assets", "out", "cli-version", "language", "engine", "timeout-sec"]);
  const bools = new Set(["force", "help"]);
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith("--")) throw new Error(`Argumento inesperado: ${a}`);
    const k = a.slice(2);
    if (bools.has(k)) args[k] = true;
    else if (values.has(k)) {
      const v = argv[++i];
      if (v === undefined || v.startsWith("--")) throw new Error(`Falta el valor de --${k}`);
      args[k] = v;
    } else throw new Error(`Opción desconocida: --${k}`);
  }
  return args;
}

const exists = (p) => stat(p).then(() => true, () => false);

export async function main(argv = process.argv.slice(2), runner = runCommand) {
  const args = parseArgs(argv);
  if (args.help) {
    console.log(USAGE);
    return;
  }
  for (const k of ["story", "assets"]) if (!args[k]) throw new Error(`Falta --${k}\n\n${USAGE}`);
  const outDir = resolve(args.out ?? "transcripts");
  const cliVersion = args["cli-version"] ?? DEFAULT_CLI_VERSION;
  const language = args.language ?? "es";
  const timeoutMs = args["timeout-sec"] ? Number(args["timeout-sec"]) * 1000 : DEFAULT_TIMEOUT_MS;
  if (!Number.isFinite(timeoutMs) || timeoutMs < 5000) throw new Error("--timeout-sec debe ser un número >= 5");

  const story = await loadStory(args.story);
  const { scenes } = buildTimeline(story); // valida ids (nombres de archivo) y duraciones

  // Comprobar todo antes de gastar tiempo: audios presentes y salidas libres.
  const jobs = [];
  for (const s of scenes) {
    const audio = join(resolve(args.assets), "audio", `${s.id}.mp3`);
    const out = join(outDir, `${s.id}.json`);
    if (!(await exists(audio))) throw new Error(`Falta el audio ${audio}`);
    if (!args.force && (await exists(out))) throw new Error(`${out} ya existe. Usar --force para sobrescribir.`);
    jobs.push({ scene: s, audio, out });
  }

  const records = [];
  const warnings = [];
  for (const j of jobs) { // en serie a propósito: el ASR local ya usa varios hilos y los errores se leen mejor
    console.log(`... ${j.scene.id}`);
    const rec = await transcribeScene({
      sceneId: j.scene.id,
      audioPath: j.audio,
      outFile: j.out,
      cliVersion,
      language,
      engine: args.engine,
      timeoutMs,
      runner,
    });
    // En las 6 escenas de tunguska la "durationSeconds" de la CLI coincide EXACTAMENTE con el fin de la última palabra
    // (múltiplo de 0.08 s), no con el largo del mp3: queda 0.07-0.29 s por debajo de targetDuration por el silencio final.
    // Por eso solo es sospechoso que sea MAYOR que targetDuration (palabras que terminan después del audio).
    if (Number.isFinite(rec.durationSeconds) && rec.durationSeconds > j.scene.duration + 0.15) {
      warnings.push(`${j.scene.id}: la CLI ubica palabras hasta ${rec.durationSeconds} s pero targetDuration es ${j.scene.duration} s (más de 0.15 s después del final): ¿audio equivocado?`);
    }
    if (rec.detectedLanguage && language && rec.detectedLanguage !== language) {
      warnings.push(`${j.scene.id}: idioma detectado "${rec.detectedLanguage}" distinto del pedido "${language}".`);
    }
    if (rec.cleanupWarning) warnings.push(`${j.scene.id}: ${rec.cleanupWarning}`);
    records.push(rec);
    console.log(`OK  ${j.scene.id}: ${rec.wordCount} palabras en ${(rec.ms / 1000).toFixed(1)} s (${rec.engine ?? "?"})`);
  }

  await writeFile(
    join(outDir, "transcribe-report.json"),
    JSON.stringify({ cliVersion, language, engineRequested: args.engine ?? "auto", scenes: records, warnings }, null, 2),
  );
  for (const w of warnings) console.log(`AVISO ${w}`);
  console.log(`Listo: ${records.length} transcripciones en ${outDir}`);
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  main().catch((e) => {
    console.error(`ERROR ${e.message}`);
    process.exit(1);
  });
}
