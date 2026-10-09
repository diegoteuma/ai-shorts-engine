import { readFileSync } from "node:fs";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
export const fixture = (...p) => join(here, "fixtures", ...p);
export const readJson = (path) => JSON.parse(readFileSync(path, "utf8"));
export const template = readFileSync(join(here, "..", "template", "index.template.html"), "utf8");
export const golden = readFileSync(fixture("expected", "index.html"), "utf8");

export const SCENE_IDS = [
  "tunguska-gancho",
  "tunguska-explicacion",
  "tunguska-contexto",
  "tunguska-giro",
  "tunguska-consecuencia",
  "tunguska-cierre",
];

export function loadTranscripts() {
  return Object.fromEntries(SCENE_IDS.map((id) => [id, readJson(fixture("transcripts", `${id}.json`))]));
}

/** Primera línea en la que difieren dos textos (para mensajes de error legibles). */
export function firstDiff(a, b) {
  const x = a.split("\n");
  const y = b.split("\n");
  for (let i = 0; i < Math.max(x.length, y.length); i++) {
    if (x[i] !== y[i]) return `línea ${i + 1}:\n  obtenido : ${x[i]}\n  esperado : ${y[i]}`;
  }
  return "(sin diferencias)";
}
