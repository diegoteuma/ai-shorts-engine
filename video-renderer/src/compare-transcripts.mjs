#!/usr/bin/env node
// Compara dos carpetas de transcripciones (<idEscena>.json): palabras, texto y tiempos (solo text/start/end).
//   node src/compare-transcripts.mjs <carpetaA> <carpetaB> [--tol-ms 0] [--loose-text]
// --loose-text ignora mayúsculas y puntuación en el texto (el ASR escribe "Rusia." donde el guion dice "Rusia:"); los tiempos se comparan igual.
// Sale con código 1 si hay alguna diferencia. Sirve para verificar que una nueva corrida de ASR reproduce otra.
import { readdir, readFile } from "node:fs/promises";
import { join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const loose = (t) => String(t).toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "");

export function compareWords(a, b, tolMs = 0, looseText = false) {
  const same = looseText ? (x, y) => loose(x) === loose(y) : (x, y) => x === y;
  const diffs = [];
  if (a.length !== b.length) diffs.push(`distinto número de palabras (${a.length} vs ${b.length})`);
  const n = Math.min(a.length, b.length);
  let maxMs = 0;
  for (let i = 0; i < n; i++) {
    if (!same(a[i].text, b[i].text)) diffs.push(`palabra ${i}: ${JSON.stringify(a[i].text)} vs ${JSON.stringify(b[i].text)}`);
    for (const k of ["start", "end"]) {
      const d = Math.abs(a[i][k] - b[i][k]) * 1000;
      maxMs = Math.max(maxMs, d);
      if (d > tolMs + 1e-6) diffs.push(`palabra ${i} (${JSON.stringify(a[i].text)}) ${k}: ${a[i][k]} vs ${b[i][k]} (${d.toFixed(1)} ms)`);
    }
  }
  return { diffs, maxMs };
}

export async function compareDirs(dirA, dirB, tolMs = 0, looseText = false) {
  const list = async (d) => (await readdir(d)).filter((f) => f.endsWith(".json") && f !== "transcribe-report.json").sort();
  const [fa, fb] = [await list(dirA), await list(dirB)];
  const result = { scenes: [], ok: true };
  for (const f of new Set([...fa, ...fb])) {
    if (!fa.includes(f) || !fb.includes(f)) {
      result.scenes.push({ file: f, diffs: [`solo existe en ${fa.includes(f) ? "A" : "B"}`], maxMs: null });
      result.ok = false;
      continue;
    }
    const [a, b] = await Promise.all([join(dirA, f), join(dirB, f)].map(async (p) => JSON.parse(await readFile(p, "utf8"))));
    const { diffs, maxMs } = compareWords(a, b, tolMs, looseText);
    result.scenes.push({ file: f, words: [a.length, b.length], diffs, maxMs });
    if (diffs.length) result.ok = false;
  }
  return result;
}

if (process.argv[1] && resolve(process.argv[1]) === fileURLToPath(import.meta.url)) {
  const args = process.argv.slice(2);
  const looseIdx = args.indexOf("--loose-text");
  const looseText = looseIdx >= 0;
  if (looseText) args.splice(looseIdx, 1);
  const tolIdx = args.indexOf("--tol-ms");
  const tol = tolIdx >= 0 ? Number(args.splice(tolIdx, 2)[1]) : 0;
  if (args.length !== 2 || !Number.isFinite(tol)) {
    console.error("Uso: node src/compare-transcripts.mjs <carpetaA> <carpetaB> [--tol-ms N] [--loose-text]");
    process.exit(2);
  }
  compareDirs(resolve(args[0]), resolve(args[1]), tol, looseText).then((r) => {
    for (const s of r.scenes) {
      console.log(`${s.diffs.length ? "DIFF" : "OK  "} ${s.file}${s.words ? ` (${s.words[0]}/${s.words[1]} palabras, desfase máx ${s.maxMs.toFixed(1)} ms)` : ""}`);
      for (const d of s.diffs.slice(0, 5)) console.log(`      ${d}`);
    }
    console.log(r.ok ? "Las transcripciones coinciden." : "HAY DIFERENCIAS.");
    process.exit(r.ok ? 0 : 1);
  }).catch((e) => { console.error(`ERROR ${e.message}`); process.exit(2); });
}
