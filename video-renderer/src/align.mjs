// Alinea las palabras de narrationText (texto verdadero) con los tiempos del ASR (tiempos verdaderos).
//
// - Si la cantidad de palabras coincide, se alinea por posición (comportamiento original validado).
// - Si no coincide (el ASR descartó o agregó palabras), se alinea por subsecuencia común más larga
//   con comparación tolerante (sin tildes, mayúsculas ni puntuación). Las palabras de la narración
//   sin pareja reciben un tiempo INFERIDO repartiendo el hueco entre las palabras vecinas
//   (inferred: true) y se informan en el reporte. Si la alineación no es confiable, se falla.

const normalize = (s) =>
  s
    .toLowerCase()
    .normalize("NFD")
    .replace(/\p{M}/gu, "")
    .replace(/[^\p{L}\p{N}]/gu, "");

function levenshtein(a, b) {
  const prev = Array.from({ length: b.length + 1 }, (_, j) => j);
  for (let i = 1; i <= a.length; i++) {
    let diag = prev[0];
    prev[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const tmp = prev[j];
      prev[j] = Math.min(prev[j] + 1, prev[j - 1] + 1, diag + (a[i - 1] === b[j - 1] ? 0 : 1));
      diag = tmp;
    }
  }
  return prev[b.length];
}

/** Dos palabras "son la misma": iguales al normalizar, o casi iguales si son largas (ASR con 1 letra distinta). */
export function similar(a, b) {
  const x = normalize(a);
  const y = normalize(b);
  if (x === "" || y === "") return false;
  if (x === y) return true;
  return x.length >= 5 && y.length >= 5 && levenshtein(x, y) <= 1;
}

export function tokenize(narrationText) {
  return narrationText.split(/\s+/).filter(Boolean);
}

function validateAsr(asrWords, sceneId) {
  if (!Array.isArray(asrWords)) throw new Error(`${sceneId}: la transcripción debe ser un arreglo de palabras.`);
  let last = 0;
  asrWords.forEach((w, i) => {
    if (!w || typeof w.text !== "string" || !Number.isFinite(w.start) || !Number.isFinite(w.end)) {
      throw new Error(`${sceneId}: palabra ${i} de la transcripción sin text/start/end numéricos.`);
    }
    if (w.end < w.start) throw new Error(`${sceneId}: palabra ${i} ("${w.text}") con end < start.`);
    if (w.start < last - 1e-9) throw new Error(`${sceneId}: palabras fuera de orden en la posición ${i} ("${w.text}").`);
    last = w.start;
  });
}

/**
 * @returns {{ words: {text,start,end,inferred}[], report: object }} tiempos LOCALES a la escena.
 */
export function alignScene({ sceneId, narrationText, tokens: preTokens, asrWords, sceneDuration, minMatchedRatio = 0.7 }) {
  validateAsr(asrWords, sceneId);
  // `tokens` (opcional) permite pasar la narración ya tokenizada (p. ej. con cifras ya convertidas, que pueden llevar U+00A0).
  const tokens = preTokens ?? tokenize(narrationText);
  const n = tokens.length;
  const m = asrWords.length;

  // 1) Mismo conteo: por posición.
  if (n === m) {
    const words = tokens.map((text, i) => ({ text, start: asrWords[i].start, end: asrWords[i].end, inferred: false }));
    const textMismatches = tokens.filter((t, i) => !similar(t, asrWords[i].text)).length;
    return {
      words,
      report: {
        sceneId,
        mode: "positional",
        narrationWordCount: n,
        asrWordCount: m,
        inferred: 0,
        ignoredAsrWords: 0,
        textMismatches,
      },
    };
  }

  // 2) Distinto conteo: subsecuencia común más larga.
  const dp = Array.from({ length: n + 1 }, () => new Array(m + 1).fill(0));
  for (let i = n - 1; i >= 0; i--) {
    for (let j = m - 1; j >= 0; j--) {
      dp[i][j] = similar(tokens[i], asrWords[j].text)
        ? 1 + dp[i + 1][j + 1]
        : Math.max(dp[i + 1][j], dp[i][j + 1]);
    }
  }
  const match = new Array(n).fill(-1);
  for (let i = 0, j = 0; i < n && j < m; ) {
    if (similar(tokens[i], asrWords[j].text) && dp[i][j] === 1 + dp[i + 1][j + 1]) {
      match[i] = j;
      i++;
      j++;
    } else if (dp[i + 1][j] >= dp[i][j + 1]) {
      i++;
    } else {
      j++;
    }
  }
  const matched = match.filter((j) => j >= 0).length;
  if (matched / n < minMatchedRatio) {
    throw new Error(
      `${sceneId}: alineación no confiable (${matched}/${n} palabras emparejadas; la narración tiene ${n} y la ` +
        `transcripción ${m}). No se adivina: revisar la transcripción.`,
    );
  }

  const words = new Array(n);
  for (let i = 0; i < n; i++) {
    if (match[i] >= 0) {
      const a = asrWords[match[i]];
      words[i] = { text: tokens[i], start: a.start, end: a.end, inferred: false };
    }
  }
  // Palabras sin pareja: repartir el hueco entre las vecinas (k palabras -> k+1 partes iguales; las palabras
  // ocupan las primeras k partes).
  for (let i = 0; i < n; ) {
    if (words[i]) {
      i++;
      continue;
    }
    let j = i;
    while (j < n && !words[j]) j++;
    const k = j - i;
    const left = i > 0 ? words[i - 1].end : 0;
    const right = j < n ? asrWords[match[j]].start : sceneDuration;
    const gap = Math.max(0, right - left);
    const slot = gap / (k + 1);
    for (let t = 0; t < k; t++) {
      words[i + t] = { text: tokens[i + t], start: left + t * slot, end: left + (t + 1) * slot, inferred: true };
    }
    i = j;
  }

  return {
    words,
    report: {
      sceneId,
      mode: "lcs",
      narrationWordCount: n,
      asrWordCount: m,
      inferred: n - matched,
      ignoredAsrWords: m - matched,
      textMismatches: 0,
    },
  };
}
