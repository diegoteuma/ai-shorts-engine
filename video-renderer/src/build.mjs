// Núcleo puro (sin E/S): Story + transcripciones + plantilla -> index.html de la composición.
import { buildTimeline } from "./story.mjs";
import { convertNumerals } from "./numerals.mjs";
import { alignScene, tokenize } from "./align.mjs";
import { applyMinWordDuration, groupWords, renderCaptions } from "./captions.mjs";
import { round6 } from "./duration.mjs";

export const DEFAULT_GSAP_SRC = "https://cdn.jsdelivr.net/npm/gsap@3.14.2/dist/gsap.min.js";

const escapeHtml = (s) =>
  s.replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;").replace(/"/g, "&quot;");

/** Un <video> y un <audio> por escena. Los videos van primero, igual que en la composición validada. */
// El lint de HyperFrames (duplicate_audio_track) compara con floats y sin tolerancia: dos audios pegados en la misma pista
// (inicio 11.75 + duración 6.548 = 18.298000000000002 > 18.298) se marcan como traslape de ~2e-15 s. Acortar 1 microsegundo el
// final de cada audio evita ese falso aviso sin efecto audible. En las pruebas de equivalencia se usa 0.
export const DEFAULT_AUDIO_END_MARGIN_SEC = 0.000001;

export function renderMedia(scenes, { width, height, audioEndMarginSec = DEFAULT_AUDIO_END_MARGIN_SEC }) {
  let videos = "";
  let audios = "";
  scenes.forEach((s, i) => {
    videos += `      <video
        id="v-${s.id}"
        class="clip"
        src="assets/clips/${s.id}.mp4"
        data-start="${s.start.toFixed(6)}"
        data-duration="${s.duration.toFixed(6)}"
        data-media-start="0"
        data-track-index="0"
        muted
        playsinline
        style="position:absolute; left:0; top:0; width:${width}px; height:${height}px; object-fit:cover; z-index:${i + 1}"
      ></video>
`;
    audios += `      <audio
        id="a-${s.id}"
        src="assets/audio/${s.id}.mp3"
        data-start="${s.start.toFixed(6)}"
        data-duration="${Math.max(0, s.duration - audioEndMarginSec).toFixed(6)}"
        data-track-index="10"
        data-volume="1"
      ></audio>
`;
  });
  return videos + audios;
}

/** Sustituye {{CLAVE}} en una sola pasada (los valores no se vuelven a escanear). Falla si falta una clave. */
export function fillTemplate(template, values) {
  const used = new Set();
  const out = template.replace(/\{\{([A-Z_]+)\}\}/g, (_, key) => {
    if (!(key in values)) throw new Error(`La plantilla usa {{${key}}} pero no se le dio valor.`);
    used.add(key);
    return String(values[key]);
  });
  for (const key of Object.keys(values)) {
    if (!used.has(key)) throw new Error(`Se dio valor a {{${key}}} pero la plantilla no lo usa.`);
  }
  return out;
}

/**
 * @param story               Story tal como la devuelve GET /stories/{id}
 * @param transcriptsByScene  { [sceneId]: [{text,start,end}, ...] } (tiempos locales a la escena)
 * @param template            contenido de template/index.template.html
 */
/**
 * Alinea una escena y convierte cifras. Dos caminos:
 *  A) alinear las palabras habladas ("diez", "mil ochocientos quince.") y fusionar DESPUÉS en una sola palabra;
 *  B) convertir ANTES ("10", "1815.") y alinear contra el ASR, por si este escribe dígitos (Parakeet suele hacerlo).
 * Gana B solo si deja estrictamente menos palabras con tiempo inferido (empate -> A, el comportamiento original).
 * Si B falla (alineación no confiable) se usa A.
 */
export function alignSceneWithNumerals(scene, asr, numeralsMode) {
  const base = { sceneId: scene.id, narrationText: scene.narrationText, asrWords: asr, sceneDuration: scene.duration };
  const inferredOf = (ws) => ws.filter((w) => w.inferred).length;

  let resultA = null;
  let errorA = null;
  try {
    const a = alignScene(base);
    const convA = convertNumerals(a.words, { mode: numeralsMode });
    resultA = { words: convA.words, report: a.report, conv: convA };
    if (numeralsMode === "off" || convA.conversions.length === 0) return resultA;
  } catch (e) {
    errorA = e;
    if (numeralsMode === "off") throw e;
  }

  // Texto convertido sin tiempos: se usa un tiempo ficticio solo para reutilizar convertNumerals.
  const dummy = tokenize(scene.narrationText).map((text, i) => ({ text, start: i, end: i + 0.5, inferred: false }));
  const convTokens = convertNumerals(dummy, { mode: numeralsMode });
  if (convTokens.conversions.length === 0 && errorA) throw errorA;
  let b;
  try {
    b = alignScene({ ...base, tokens: convTokens.words.map((w) => w.text) });
  } catch {
    if (errorA) throw errorA; // ninguno de los dos caminos es confiable: se informa el error original
    return resultA;
  }
  const resultB = { words: b.words, report: b.report, conv: { words: b.words, conversions: convTokens.conversions } };
  if (!resultA || inferredOf(b.words) < inferredOf(resultA.words)) return resultB;
  return resultA;
}

export function buildComposition({ story, transcriptsByScene, template, options = {}, durationOverrides }) {
  const o = {
    width: 720,
    height: 1280,
    title: story.title,
    gsapSrc: DEFAULT_GSAP_SRC,
    minWordSec: 0,
    maxInferredRatio: 0.15,
    numerals: "auto",
    audioEndMarginSec: DEFAULT_AUDIO_END_MARGIN_SEC,
    ...options,
  };

  const { scenes, totalDuration } = buildTimeline(story, { durationOverrides });
  const warnings = [];
  const alignmentReport = [];
  const allWords = [];
  const numeralConversions = [];

  for (const scene of scenes) {
    const asr = transcriptsByScene[scene.id];
    if (!asr) throw new Error(`Falta la transcripción de la escena ${scene.id}.`);
    const { words, report, conv } = alignSceneWithNumerals(scene, asr, o.numerals);
    alignmentReport.push(report);
    if (report.textMismatches > 0) {
      warnings.push(`${scene.id}: ${report.textMismatches} palabra(s) de la transcripción difieren del texto (se usa el texto).`);
    }
    for (const c of conv.conversions) numeralConversions.push({ scene: scene.id, ...c });
    for (const w of conv.words) {
      if (w.end > scene.duration + 0.05) {
        warnings.push(`${scene.id}: "${w.text}" termina en ${w.end.toFixed(3)} s, después del fin de la escena (${scene.duration.toFixed(3)} s).`);
      }
      allWords.push({
        text: w.text,
        localStart: w.start,
        localEnd: w.end,
        start: +(scene.start + w.start).toFixed(6),
        end: +(scene.start + w.end).toFixed(6),
        inferred: w.inferred,
        scene: scene.id,
      });
    }
  }

  const inferredCount = allWords.filter((w) => w.inferred).length;
  const inferredRatio = allWords.length ? inferredCount / allWords.length : 0;
  if (inferredRatio > o.maxInferredRatio) {
    throw new Error(
      `Demasiadas palabras con tiempo inferido: ${inferredCount}/${allWords.length} ` +
        `(${(inferredRatio * 100).toFixed(1)} % > ${(o.maxInferredRatio * 100).toFixed(1)} %). Revisar la transcripción.`,
    );
  }
  if (inferredCount > 0) {
    warnings.push(`${inferredCount} palabra(s) con tiempo inferido (no medido): ${allWords.filter((w) => w.inferred).map((w) => `"${w.text}"`).join(", ")}.`);
  }

  const sceneBounds = Object.fromEntries(scenes.map((s) => [s.id, { end: round6(s.start + s.duration) }]));
  const timedWords = applyMinWordDuration(allWords, sceneBounds, o.minWordSec);
  const groupWindows = groupWords(timedWords, sceneBounds);
  const { html: captionDivs, js: captionJs } = renderCaptions(groupWindows);

  const html = fillTemplate(template, {
    TITLE: escapeHtml(o.title),
    WIDTH: o.width,
    HEIGHT: o.height,
    GSAP_SRC: escapeHtml(o.gsapSrc),
    TOTAL_DURATION: totalDuration.toFixed(6),
    MEDIA: renderMedia(scenes, o),
    CAPTION_DIVS: captionDivs,
    CAPTION_JS: captionJs,
  });

  return {
    html,
    scenes,
    totalDuration,
    words: timedWords,
    groups: groupWindows.map((g) => ({
      text: g.words.map((w) => w.text).join(" "),
      start: g.start,
      end: g.end,
      scene: g.words[0].scene,
    })),
    alignmentReport,
    numeralConversions,
    warnings,
  };
}
