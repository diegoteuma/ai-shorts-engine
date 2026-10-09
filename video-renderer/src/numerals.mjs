// Cifras en los subtítulos: "diez de abril de mil ochocientos quince" -> "10 de abril de 1815".
//
// Por qué existe: las reglas editoriales del generador de historias piden escribir números y fechas "de forma que se lean
// sin ambigüedad", así que `narrationText` (lo que se sintetiza) puede traer cifras en palabras. Para leer, los subtítulos
// quedan mejor con dígitos. Se convierte DESPUÉS de alinear con el ASR: una cifra de varias palabras habladas pasa a ser una
// sola palabra de subtítulo que dura desde el inicio de la primera hasta el final de la última.
//
// Reglas (conservadoras; todo lo convertido se informa para poder revisarlo):
//  - Se convierte una secuencia de palabras numéricas si su valor es >= 10, salvo "mil" suelto ("mil personas" se queda).
//  - Cualquier valor 1-31 seguido de "de <mes>" (fecha) se convierte, también el 8 de "ocho de agosto".
//  - Si la secuencia va seguida de "millón/millones/billón/billones" y vale >= 2: "ochenta millones" -> "80 millones".
//  - "un", "una", "uno" sueltos NUNCA se convierten (son artículos casi siempre); tampoco los dígitos 0-9 sueltos.
//  - Una secuencia no gramatical ("cinco cinco", "treinta cinco") se deja tal cual.
//  - Un año (4 cifras) se escribe sin separador. De 5 cifras en adelante se agrupa de a tres con espacio duro (U+00A0).
// Limitaciones: solo cardinales en español; no convierte ordinales, fracciones ni decimales ("tres coma cinco").

const UNITS = { uno: 1, un: 1, una: 1, dos: 2, tres: 3, cuatro: 4, cinco: 5, seis: 6, siete: 7, ocho: 8, nueve: 9 };
const SPECIAL = {
  diez: 10, once: 11, doce: 12, trece: 13, catorce: 14, quince: 15, dieciseis: 16, diecisiete: 17, dieciocho: 18, diecinueve: 19,
  veinte: 20, veintiuno: 21, veintiun: 21, veintiuna: 21, veintidos: 22, veintitres: 23, veinticuatro: 24, veinticinco: 25,
  veintiseis: 26, veintisiete: 27, veintiocho: 28, veintinueve: 29,
};
const TENS = { treinta: 30, cuarenta: 40, cincuenta: 50, sesenta: 60, setenta: 70, ochenta: 80, noventa: 90 };
const HUNDREDS = {
  cien: 100, ciento: 100, doscientos: 200, doscientas: 200, trescientos: 300, trescientas: 300, cuatrocientos: 400, cuatrocientas: 400,
  quinientos: 500, quinientas: 500, seiscientos: 600, seiscientas: 600, setecientos: 700, setecientas: 700,
  ochocientos: 800, ochocientas: 800, novecientos: 900, novecientas: 900,
};
const SCALES = new Set(["millon", "millones", "billon", "billones"]);
const MONTHS = new Set(["enero", "febrero", "marzo", "abril", "mayo", "junio", "julio", "agosto", "septiembre", "setiembre", "octubre", "noviembre", "diciembre"]);

const isNumberWord = (c) => c in UNITS || c in SPECIAL || c in TENS || c in HUNDREDS || c === "mil" || c === "cero";

/** Separa puntuación inicial/final y devuelve el núcleo en minúscula y sin tildes. */
export function splitToken(text) {
  const m = /^([^\p{L}\p{N}]*)(.*?)([^\p{L}\p{N}]*)$/su.exec(text);
  const core = m[2].toLowerCase().normalize("NFD").replace(/\p{M}/gu, "");
  return { lead: m[1], core, trail: m[3] };
}

/** Valor de un grupo de 0-999 hecho de palabras, o null si no es gramatical. */
function parseGroup(cores) {
  let i = 0;
  let val = 0;
  if (cores.length === 0) return null;
  if (cores[i] in HUNDREDS) val += HUNDREDS[cores[i++]];
  if (i < cores.length) {
    const c = cores[i];
    if (c in SPECIAL) {
      val += SPECIAL[c];
      i++;
    } else if (c in TENS) {
      val += TENS[c];
      i++;
      if (i < cores.length && cores[i] === "y") {
        if (i + 1 >= cores.length || !(cores[i + 1] in UNITS)) return null;
        val += UNITS[cores[i + 1]];
        i += 2;
      }
    } else if (c in UNITS) {
      val += UNITS[c];
      i++;
    } else return null;
  }
  return i === cores.length ? val : null;
}

/** Valor (entero 1..999 999) de una secuencia de palabras numéricas, o null. `cero` solo vale solo. */
export function parseSpanishCardinal(cores) {
  if (cores.length === 1 && cores[0] === "cero") return 0;
  const k = cores.indexOf("mil");
  if (k === -1) return parseGroup(cores);
  if (cores.indexOf("mil", k + 1) !== -1) return null;
  const before = cores.slice(0, k);
  const after = cores.slice(k + 1);
  let thousands = 1;
  if (before.length) {
    thousands = parseGroup(before);
    if (thousands === null || thousands < 2) return null; // "un mil" no es español corriente
  }
  let rest = 0;
  if (after.length) {
    rest = parseGroup(after);
    if (rest === null) return null;
  }
  return thousands * 1000 + rest;
}

export function formatNumber(value) {
  const s = String(value);
  if (s.length <= 4) return s;
  return s.replace(/\B(?=(\d{3})+(?!\d))/g, " ");
}

/**
 * Convierte las cifras en palabras de UNA escena. `words`: [{ text, start, end, inferred }] ya alineadas.
 * @returns {{ words: object[], conversions: { from: string, to: string }[] }}
 */
export function convertNumerals(words, { mode = "auto" } = {}) {
  if (mode === "off") return { words, conversions: [] };
  if (mode !== "auto") throw new Error(`Modo de cifras desconocido: ${mode} (usar auto u off)`);

  const parts = words.map((w) => ({ w, ...splitToken(w.text) }));
  const out = [];
  const conversions = [];
  let i = 0;
  while (i < parts.length) {
    if (!isNumberWord(parts[i].core)) {
      out.push(parts[i].w);
      i++;
      continue;
    }
    // Extender la secuencia: sin puntuación entre palabras; "y" solo entre decenas y unidades.
    let j = i;
    while (j + 1 < parts.length) {
      const cur = parts[j];
      const nxt = parts[j + 1];
      if (cur.trail !== "" || nxt.lead !== "") break;
      if (isNumberWord(nxt.core)) {
        j++;
      } else if (nxt.core === "y" && cur.core in TENS && j + 2 < parts.length && parts[j + 2].core in UNITS && nxt.trail === "" && parts[j + 2].lead === "") {
        j += 2; // incluye la "y" y la unidad
      } else break;
    }
    // Un tramo no gramatical ("cinco cinco", "treinta cinco") se deja tal cual, entero.
    const end = j;
    const value = parseSpanishCardinal(parts.slice(i, end + 1).map((p) => p.core));
    if (value === null) {
      for (let k = i; k <= end; k++) out.push(parts[k].w);
      i = end + 1;
      continue;
    }
    const run = parts.slice(i, end + 1);
    const next = parts[end + 1];
    const afterNext = parts[end + 2];
    const onlyMil = run.length === 1 && run[0].core === "mil";
    const onlyArticleLike = run.length === 1 && run[0].core in { uno: 1, un: 1, una: 1 };
    const isDate = value >= 1 && value <= 31 && run[run.length - 1].trail === "" && next?.core === "de" && next.trail === "" && afterNext && MONTHS.has(afterNext.core);
    const beforeScale = next && SCALES.has(next.core) && run[run.length - 1].trail === "" && value >= 2 && !onlyMil;
    const convert = !onlyArticleLike && value > 0 && (isDate || beforeScale || (value >= 10 && !onlyMil));
    if (!convert) {
      for (const p of run) out.push(p.w);
      i = end + 1;
      continue;
    }
    const first = run[0];
    const last = run[run.length - 1];
    const text = `${first.lead}${formatNumber(value)}${last.trail}`;
    const merged = {
      ...first.w,
      text,
      start: first.w.start,
      end: last.w.end,
      inferred: run.some((p) => p.w.inferred),
    };
    out.push(merged);
    conversions.push({ from: run.map((p) => p.w.text).join(" "), to: text });
    i = end + 1;
  }
  return { words: out, conversions };
}
