// Agrupa las palabras alineadas en frases de subtítulo (sin cruzar un corte de escena) y emite el HTML y
// el JS de GSAP (basado en el componente de registro caption-highlight de HyperFrames).
// La estructura del JS emitido es la de la prueba validada del 2026-10-08.

export const DEFAULTS = {
  maxGroupWords: 4,
  gapBreakSec: 0.15, // un silencio mayor a esto corta la frase
  holdSec: 0.35, // cuánto se mantiene la frase tras su última palabra
  handoffSec: 0.05, // margen antes de que empiece la frase siguiente
  minWordSec: 0, // 0 = sin cambios; >0 alarga el resaltado de palabras muy cortas ("y", "de")
};

const round6 = (x) => +x.toFixed(6);

/**
 * Alarga el fin de las palabras más cortas que `minWordSec` hasta `start + minWordSec`, sin pasar del inicio de
 * la palabra siguiente de la misma escena (ni del fin de la escena). No modifica la entrada.
 */
export function applyMinWordDuration(words, sceneBounds, minWordSec) {
  if (!(minWordSec > 0)) return words.map((w) => ({ ...w }));
  return words.map((w, i) => {
    if (w.end - w.start >= minWordSec) return { ...w };
    const next = words[i + 1];
    const limit = next && next.scene === w.scene ? next.start : sceneBounds[w.scene].end;
    return { ...w, end: round6(Math.max(w.end, Math.min(w.start + minWordSec, limit))) };
  });
}

export function groupWords(words, sceneBounds, options = {}) {
  const o = { ...DEFAULTS, ...options };
  const groups = [];
  let current = [];
  const flush = () => {
    if (current.length) groups.push(current);
    current = [];
  };
  for (let i = 0; i < words.length; i++) {
    const w = words[i];
    const prev = words[i - 1];
    const sceneChanged = prev && prev.scene !== w.scene;
    const bigGap = prev && !sceneChanged && w.start - prev.end > o.gapBreakSec;
    if (sceneChanged || bigGap || current.length >= o.maxGroupWords) flush();
    current.push(w);
  }
  flush();

  // Ventana de cada frase: desde su primera palabra hasta min(fin + hold, inicio de la siguiente - handoff, fin de escena).
  return groups.map((g, gi) => {
    const start = g[0].start;
    const lastWord = g[g.length - 1];
    const sceneEnd = sceneBounds[lastWord.scene].end;
    const nextStart = gi + 1 < groups.length ? groups[gi + 1][0].start : sceneEnd;
    const end = Math.min(lastWord.end + o.holdSec, nextStart - o.handoffSec, sceneEnd);
    return { words: g, start, end: Math.max(end, lastWord.end + 0.05) };
  });
}

// Texto que va dentro de un <script>: JSON con "<" escapado para que nada pueda cerrar el script.
const jsonForScript = (value) => JSON.stringify(value).replace(/</g, "\\u003c");
// Texto dentro de un comentario "//": sin "<" ni saltos de línea.
const forComment = (s) => s.replace(/</g, "&lt;").replace(/[\r\n\u2028\u2029]/g, " ");

export function renderCaptions(groupWindows) {
  let html = "";
  let js = "";

  groupWindows.forEach((g, gi) => {
    const groupId = "hl-grp-" + gi;
    html += `      <div class="hl-group" id="${groupId}"></div>\n`;

    js += `\n  // Group ${gi}: "${forComment(g.words.map((w) => w.text).join(" "))}" [${g.start.toFixed(3)}, ${g.end.toFixed(3)}] scene=${g.words[0].scene}\n`;
    js += `  (function(){\n`;
    js += `    var grp = document.getElementById("${groupId}");\n`;
    js += `    var groupWords = ${jsonForScript(g.words.map((w) => ({ text: w.text, start: +w.start.toFixed(6), end: +w.end.toFixed(6) })))};\n`;
    js += `    var groupText = groupWords.map(function(w){return w.text.toUpperCase();}).join(" ");\n`;
    js += `    var computedSize = fitFontSize(groupText, CAPTION_BASE_FONT, "800", "Montserrat", CAPTION_MAX_WIDTH);\n`;
    js += `    groupWords.forEach(function(w, i){\n`;
    js += `      var wordEl = document.createElement("span");\n`;
    js += `      wordEl.className = "hl-word";\n`;
    js += `      wordEl.id = "${groupId}-w" + i;\n`;
    js += `      wordEl.style.fontSize = computedSize + "px";\n`;
    js += `      var bgEl = document.createElement("span");\n`;
    js += `      bgEl.className = "hl-word-bg";\n`;
    js += `      bgEl.id = "${groupId}-bg" + i;\n`;
    js += `      var textEl = document.createElement("span");\n`;
    js += `      textEl.className = "hl-word-text";\n`;
    js += `      textEl.textContent = w.text.toUpperCase();\n`;
    js += `      wordEl.appendChild(bgEl);\n`;
    js += `      wordEl.appendChild(textEl);\n`;
    js += `      grp.appendChild(wordEl);\n`;
    js += `    });\n`;
    js += `    tl.set(grp, { visibility: "visible" }, ${g.start.toFixed(6)});\n`;
    js += `    tl.fromTo(grp, { opacity: 0 }, { opacity: 1, duration: 0.12, ease: "power2.out" }, ${g.start.toFixed(6)});\n`;
    js += `    groupWords.forEach(function(w, i){\n`;
    js += `      var bgEl = document.getElementById("${groupId}-bg" + i);\n`;
    js += `      var wordEl = document.getElementById("${groupId}-w" + i);\n`;
    js += `      tl.to(bgEl, { opacity: 1, scaleX: 1, duration: 0.15, ease: "power2.out" }, w.start);\n`;
    js += `      tl.to(wordEl, { filter: "brightness(1.05)", duration: 0.08, ease: "power2.out" }, w.start);\n`;
    js += `      tl.to(wordEl, { filter: "brightness(1)", duration: 0.16, ease: "power2.out" }, w.start + 0.08);\n`;
    js += `      tl.to(bgEl, { opacity: 0, scaleX: 1.02, duration: 0.1, ease: "power2.in" }, w.end);\n`;
    js += `      tl.set(bgEl, { scaleX: 0 }, w.end + 0.1);\n`;
    js += `    });\n`;
    js += `    tl.to(grp, { opacity: 0, duration: 0.1, ease: "power2.in" }, ${(g.end - 0.1).toFixed(6)});\n`;
    js += `    tl.set(grp, { opacity: 0, visibility: "hidden" }, ${g.end.toFixed(6)});\n`;
    js += `  })();\n`;
  });

  return { html, js };
}
