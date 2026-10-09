// Carga de la Story (archivo guardado o GET /stories/{id}) y cálculo de la línea de tiempo.
//
// Fuente de verdad de las duraciones: `targetDuration` del backend (sale del alignment de ElevenLabs).
// NO usar la duración de la cabecera del mp3 (ffprobe format=duration): sobreestima entre ~30 y 60 ms.
import { readFile } from "node:fs/promises";
import { parseDuration, round6 } from "./duration.mjs";

// Los ids terminan en nombres de archivo y atributos HTML: se restringen a un conjunto seguro.
const SAFE_ID = /^[A-Za-z0-9][A-Za-z0-9_-]*$/;

export async function loadStory(source) {
  let text;
  if (/^https?:\/\//i.test(source)) {
    const res = await fetch(source);
    if (!res.ok) throw new Error(`GET ${source} respondió ${res.status}`);
    text = await res.text();
  } else {
    text = await readFile(source, "utf8");
  }
  try {
    return JSON.parse(text);
  } catch (e) {
    throw new Error(`La historia no es JSON válido (${source}): ${e.message}`);
  }
}

/**
 * Ordena las escenas por `order` y calcula inicio y duración de cada una.
 * `durationOverrides` ({ [sceneId]: segundos }) permite reemplazar duraciones (útil para pruebas).
 */
export function buildTimeline(story, { durationOverrides = {} } = {}) {
  if (!story || typeof story !== "object") throw new Error("Historia vacía o inválida.");
  if (typeof story.id !== "string" || !SAFE_ID.test(story.id)) {
    throw new Error(`Id de historia inválido: ${JSON.stringify(story.id)}`);
  }
  if (!Array.isArray(story.scenes) || story.scenes.length === 0) {
    throw new Error("La historia no tiene escenas.");
  }

  const ordered = [...story.scenes].sort((a, b) => a.order - b.order);
  const seenIds = new Set();
  const seenOrders = new Set();
  let cursor = 0;
  const scenes = [];

  for (const s of ordered) {
    if (typeof s.id !== "string" || !SAFE_ID.test(s.id)) {
      throw new Error(`Id de escena inválido: ${JSON.stringify(s.id)}`);
    }
    if (seenIds.has(s.id)) throw new Error(`Id de escena repetido: ${s.id}`);
    if (seenOrders.has(s.order)) throw new Error(`Orden repetido (${s.order}) en la escena ${s.id}`);
    seenIds.add(s.id);
    seenOrders.add(s.order);
    if (typeof s.narrationText !== "string" || s.narrationText.trim() === "") {
      throw new Error(`La escena ${s.id} no tiene narrationText.`);
    }
    const duration = durationOverrides[s.id] ?? parseDuration(s.targetDuration);
    if (!(duration > 0)) throw new Error(`La escena ${s.id} tiene una duración no positiva (${duration}).`);

    const start = round6(cursor);
    scenes.push({
      id: s.id,
      role: s.role ?? null,
      order: s.order,
      narrationText: s.narrationText,
      start,
      duration: round6(duration),
    });
    cursor = round6(start + duration);
  }

  return { scenes, totalDuration: round6(cursor) };
}
