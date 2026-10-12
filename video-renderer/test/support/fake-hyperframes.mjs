// CLI de hyperframes simulada para las pruebas de make-video (sin red ni Chrome).
// Se configura con la variable de entorno FAKE_HF (JSON):
//   calls             archivo donde anotar cada llamada (una línea JSON: { args, cwd })
//   transcripts       carpeta con <idEscena>.json que devuelve `transcribe`
//   transcribeGarbage `transcribe` devuelve palabras que no tienen nada que ver con la narración
//   exit              { transcribe|lint|check|render: código } para simular fallos
//   lint, check       objeto JSON a imprimir con --json (por defecto: aprobado sin hallazgos)
//   renderSleepSec    `render` lanza un nieto que duerme y duerme también (para probar el timeout)
//   pidFile           dónde anota el pid del nieto
//   renderNoOutput    `render` termina bien sin escribir el archivo
//   renderFrom        `render` copia este archivo como salida (lo usa el test de contrato Java<->Node)
// Formatos tomados de la CLI real 0.8.143 (ver make-video.mjs).
import { spawn } from "node:child_process";
import { appendFileSync, copyFileSync, readFileSync, writeFileSync } from "node:fs";
import { basename, join } from "node:path";

const cfg = JSON.parse(process.env.FAKE_HF ?? "{}");
const args = process.argv.slice(2);
const cmd = args[0];
if (cfg.calls) appendFileSync(cfg.calls, JSON.stringify({ args, cwd: process.cwd() }) + "\n");

const code = cfg.exit?.[cmd];
const ok = (report) => ({ ok: true, errorCount: 0, warningCount: 0, infoCount: 0, findings: [], ...report });

if (cmd === "transcribe") {
  if (code) {
    console.log(JSON.stringify({ ok: false, error: "simulado: sin red hacia huggingface.co" }));
    process.exit(code);
  }
  const audio = args[1];
  const dir = args[args.indexOf("--dir") + 1];
  const id = basename(audio).replace(/\.mp3$/, "");
  const words = cfg.transcribeGarbage
    ? Array.from({ length: 12 }, (_, i) => ({ text: `zzz${i}`, start: i * 0.3, end: i * 0.3 + 0.2 }))
    : JSON.parse(readFileSync(join(cfg.transcripts, `${id}.json`), "utf8"));
  const path = join(dir, "transcript.json");
  writeFileSync(path, JSON.stringify(words));
  console.error("progreso simulado");
  console.log(JSON.stringify({ ok: true, engine: "parakeet", model: "fake", detectedLanguage: null, wordCount: words.length, durationSeconds: 1, speechOnsetSeconds: null, transcriptPath: path }));
} else if (cmd === "lint" || cmd === "check") {
  const report = cfg[cmd] ?? (cmd === "lint" ? ok() : { ok: true, lint: ok(), runtime: ok(), layout: ok(), motion: ok(), contrast: ok() });
  console.log(JSON.stringify(report, null, 2));
  console.error(`${cmd} simulado`);
  process.exit(code ?? 0);
} else if (cmd === "render") {
  console.log("render simulado: capturando cuadros");
  if (cfg.renderSleepSec) {
    const child = spawn(process.execPath, ["-e", `setTimeout(() => {}, ${cfg.renderSleepSec * 1000})`], { stdio: "ignore" });
    writeFileSync(cfg.pidFile, String(child.pid));
    setTimeout(() => {}, cfg.renderSleepSec * 1000);
  } else {
    if (code) {
      console.error("render simulado: Chrome se cayó");
      process.exit(code);
    }
    const out = args[args.indexOf("--output") + 1];
    if (cfg.renderFrom) copyFileSync(cfg.renderFrom, out);
    else if (!cfg.renderNoOutput) writeFileSync(out, "mp4 simulado");
  }
} else {
  console.error(`comando simulado desconocido: ${cmd}`);
  process.exit(9);
}
