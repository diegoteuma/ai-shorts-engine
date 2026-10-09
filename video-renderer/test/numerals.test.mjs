import { test } from "node:test";
import assert from "node:assert/strict";
import { convertNumerals, parseSpanishCardinal, formatNumber, splitToken } from "../src/numerals.mjs";
import { buildComposition } from "../src/build.mjs";
import { fixture, readJson, template, loadTranscripts } from "./helpers.mjs";

const W = (text, i, inferred = false) => ({ text, start: i * 0.3, end: i * 0.3 + 0.25, inferred });
const run = (sentence, mode = "auto") => {
  const words = sentence.split(" ").map((t, i) => W(t, i));
  const r = convertNumerals(words, { mode });
  return { text: r.words.map((w) => w.text).join(" "), ...r };
};
const NB = " ";

test("la fecha del caso real: 'el diez de abril de mil ochocientos quince' -> 'el 10 de abril de 1815'", () => {
  const r = run("el diez de abril de mil ochocientos quince");
  assert.equal(r.text, "el 10 de abril de 1815");
  assert.deepEqual(r.conversions, [{ from: "diez", to: "10" }, { from: "mil ochocientos quince", to: "1815" }]);
});

test("una cifra de varias palabras habladas dura desde el inicio de la primera hasta el final de la última", () => {
  const words = "de mil ochocientos quince".split(" ").map((t, i) => W(t, i));
  words[2].inferred = true;
  const { words: out } = convertNumerals(words);
  assert.equal(out.length, 2);
  assert.equal(out[1].text, "1815");
  assert.equal(out[1].start, words[1].start);
  assert.equal(out[1].end, words[3].end);
  assert.equal(out[1].inferred, true); // si alguna palabra fundida era inferida, la cifra también
});

test("parseSpanishCardinal: casos válidos con tildes ya normalizadas", () => {
  const p = (s) => parseSpanishCardinal(s.split(" "));
  assert.equal(p("treinta y cinco"), 35);
  assert.equal(p("veintidos"), 22);
  assert.equal(p("ciento veinte mil"), 120000);
  assert.equal(p("dos mil veinticuatro"), 2024);
  assert.equal(p("mil novecientos ocho"), 1908);
  assert.equal(p("quinientos mil"), 500000);
  assert.equal(p("cien"), 100);
  assert.equal(p("cero"), 0);
  for (const bad of ["cinco cinco", "treinta cinco", "mil mil", "un mil", "treinta y", "y cinco", "cuatrocientos cuatrocientos"]) {
    assert.equal(p(bad), null, bad);
  }
});

test("millones y fechas: 'ochenta millones' -> '80 millones', 'ocho de agosto' -> '8 de agosto'", () => {
  assert.equal(run("derribó ochenta millones de árboles").text, "derribó 80 millones de árboles");
  assert.equal(run("el ocho de agosto de mil novecientos cuarenta y cinco").text, "el 8 de agosto de 1945");
  assert.equal(run("dos mil millones de años").text, "2000 millones de años");
});

test("lo que NO se convierte: dígitos sueltos, un/una/uno, mil suelto, un millón, secuencias no gramaticales", () => {
  assert.equal(run("tres kilómetros y cinco metros").text, "tres kilómetros y cinco metros");
  assert.equal(run("una explosión y un objeto, uno solo").text, "una explosión y un objeto, uno solo");
  assert.equal(run("mil personas y mil millones").text, "mil personas y mil millones");
  assert.equal(run("un millón de árboles").text, "un millón de árboles");
  assert.equal(run("cinco cinco treinta cinco").text, "cinco cinco treinta cinco");
  assert.equal(run("hasta tres de mayo no").text, "hasta 3 de mayo no"); // fecha: el único caso <10 que sí se convierte
});

test("puntuación: se conserva al inicio y al final de la cifra, y una coma separa cifras distintas", () => {
  assert.equal(run("fue en mil ochocientos quince, cuando").text, "fue en 1815, cuando");
  assert.equal(run("¿veinte? sí").text, "¿20? sí");
  assert.equal(run("diez, veinte y treinta").text, "10, 20 y 30");
  assert.equal(run("Diez mil personas").text, `10${NB}000 personas`);
  assert.equal(run("dieciséis años").text, "16 años");
  assert.equal(run("veintiún años").text, "21 años");
});

test("formatNumber y splitToken", () => {
  assert.equal(formatNumber(1815), "1815");
  assert.equal(formatNumber(9999), "9999");
  assert.equal(formatNumber(10000), `10${NB}000`);
  assert.equal(formatNumber(1234567), `1${NB}234${NB}567`);
  assert.deepEqual(splitToken("¿Quince,"), { lead: "¿", core: "quince", trail: "," });
  assert.deepEqual(splitToken("Veintidós."), { lead: "", core: "veintidos", trail: "." });
});

test("modo off no toca nada y un modo desconocido falla", () => {
  assert.equal(run("el diez de abril de mil ochocientos quince", "off").text, "el diez de abril de mil ochocientos quince");
  assert.throws(() => run("x", "siempre"), /Modo de cifras desconocido/);
});

test("de punta a punta: la fecha en palabras sale en dígitos en el HTML y queda informada", () => {
  const story = readJson(fixture("story.backend.json"));
  story.scenes[2].narrationText = "Esto ocurrió el diez de abril de mil ochocientos quince, en Indonesia.";
  const transcripts = loadTranscripts();
  const tokens = ["Esto", "ocurrió", "el", "diez", "de", "abril", "de", "mil", "ochocientos", "quince", "en", "Indonesia"];
  transcripts[story.scenes[2].id] = tokens.map((text, i) => ({ text, start: i * 0.4, end: i * 0.4 + 0.4 }));
  const { html, numeralConversions, words } = buildComposition({ story, transcriptsByScene: transcripts, template, options: { title: "t" } });
  assert.deepEqual(numeralConversions.map((c) => c.to), ["10", "1815,"]);
  assert.ok(html.includes("1815,"));
  assert.ok(!html.includes("ochocientos"));
  const merged = words.find((w) => w.text === "1815,");
  assert.equal(merged.localStart, 7 * 0.4);
  assert.equal(+merged.localEnd.toFixed(6), +(9 * 0.4 + 0.4).toFixed(6));

  const off = buildComposition({ story, transcriptsByScene: transcripts, template, options: { title: "t", numerals: "off" } });
  assert.ok(off.html.includes("ochocientos"));
});

test("las historias sin cifras en palabras (tunguska) no cambian: el golden sigue idéntico con numerals=auto", () => {
  const { numeralConversions } = buildComposition({
    story: readJson(fixture("story.reference.json")),
    transcriptsByScene: loadTranscripts(),
    template,
    options: { title: "t" },
  });
  assert.deepEqual(numeralConversions, []);
});

// ---- falso aviso duplicate_audio_track del lint de HyperFrames (leído en su código, hyperframes 0.8.143) ----
function lintAudioOverlaps(html) {
  // misma lógica que lintDuplicateAudioTracks: floats, comparación estricta, misma pista
  const tracks = [];
  for (const m of html.matchAll(/<audio\b[^>]*>/gi)) {
    const attr = (n) => new RegExp(`\\b${n}\\s*=\\s*["']([^"']+)["']`, "i").exec(m[0])?.[1];
    const start = parseFloat(attr("data-start"));
    tracks.push({ track: parseInt(attr("data-track-index"), 10), start, end: start + parseFloat(attr("data-duration")) });
  }
  const found = [];
  for (let i = 0; i < tracks.length; i++)
    for (let j = i + 1; j < tracks.length; j++)
      if (tracks[i].track === tracks[j].track && tracks[i].start < tracks[j].end && tracks[j].start < tracks[i].end) found.push([i, j]);
  return found;
}

test("duraciones como las de tambora (11.75 + 6.548 = 18.298000000000002): sin margen el lint ve un traslape falso; con el margen por defecto no", () => {
  const story = readJson(fixture("story.backend.json"));
  const ms = [4.45, 7.3, 6.548, 6.802, 6.2, 4.274];
  story.scenes.sort((a, b) => a.order - b.order).forEach((s, i) => (s.targetDuration = `PT${ms[i]}S`));
  const common = { story, transcriptsByScene: loadTranscripts(), template };
  const raw = buildComposition({ ...common, options: { title: "t", audioEndMarginSec: 0 } }).html;
  const fixed = buildComposition({ ...common, options: { title: "t" } }).html;
  assert.ok(11.75 + 6.548 > 18.298, "premisa: la suma en coma flotante queda por encima");
  assert.ok(lintAudioOverlaps(raw).length > 0, "sin margen debería reproducir el falso traslape");
  assert.deepEqual(lintAudioOverlaps(fixed), []);
});

// ---- Alineación con cifras (ASR que escribe dígitos vs ASR que escribe palabras) ----
import { alignSceneWithNumerals } from "../src/build.mjs";

const asrFrom = (arr) => arr.map((t, i) => ({ text: t, start: i * 0.4, end: i * 0.4 + 0.3 }));
const scene = (narrationText) => ({ id: "s", narrationText, duration: 10 });
const NARR = "Fue el diez de abril de mil ochocientos quince, y todo cambió.";

test("ASR con dígitos ('10', '1815,'): se convierte antes y las cifras salen con tiempo MEDIDO", () => {
  const asr = asrFrom(["Fue", "el", "10", "de", "abril", "de", "1815,", "y", "todo", "cambió."]);
  const r = alignSceneWithNumerals(scene(NARR), asr, "auto");
  assert.deepEqual(r.words.map((w) => w.text), ["Fue", "el", "10", "de", "abril", "de", "1815,", "y", "todo", "cambió."]);
  assert.equal(r.words.filter((w) => w.inferred).length, 0);
  assert.equal(r.words[2].start, 0.8);
  assert.deepEqual(r.conv.conversions, [{ from: "diez", to: "10" }, { from: "mil ochocientos quince,", to: "1815," }]);
});

test("ASR con palabras: se mantiene el camino original (alinear y fusionar después)", () => {
  const asr = asrFrom("Fue el diez de abril de mil ochocientos quince y todo cambió".split(" "));
  const r = alignSceneWithNumerals(scene(NARR), asr, "auto");
  assert.equal(r.words.filter((w) => w.inferred).length, 0);
  assert.equal(r.words[6].text, "1815,");
  assert.equal(r.words[6].start, 6 * 0.4);
  assert.equal(r.words[6].end, 8 * 0.4 + 0.3);
});

test("cifras de 5 dígitos con espacio duro alinean contra '10000' del ASR", () => {
  const asr = asrFrom(["Murieron", "10000", "personas."]);
  const r = alignSceneWithNumerals(scene("Murieron diez mil personas."), asr, "auto");
  assert.equal(r.words[1].text, `10${NB}000`);
  assert.equal(r.words.filter((w) => w.inferred).length, 0);
});

test("modo off y escenas sin cifras no cambian de camino", () => {
  const asr = asrFrom("Fue el diez de abril".split(" "));
  const off = alignSceneWithNumerals(scene("Fue el diez de abril"), asr, "off");
  assert.equal(off.words[2].text, "diez");
  assert.deepEqual(off.conv.conversions, []);
});

test("si ni A ni B son confiables, se informa el error de alineación (no se adivina)", () => {
  const asr = asrFrom(["nada", "que", "ver"]);
  assert.throws(() => alignSceneWithNumerals(scene(NARR), asr, "auto"), /alineación no confiable/);
});
