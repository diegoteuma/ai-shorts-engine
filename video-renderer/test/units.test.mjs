import { test } from "node:test";
import assert from "node:assert/strict";
import { parseDuration } from "../src/duration.mjs";
import { buildTimeline } from "../src/story.mjs";
import { alignScene, similar } from "../src/align.mjs";
import { applyMinWordDuration, groupWords } from "../src/captions.mjs";
import { buildComposition, fillTemplate } from "../src/build.mjs";
import { fixture, readJson, template, loadTranscripts } from "./helpers.mjs";

// ---------- duración ----------
test("parseDuration entiende ISO-8601 de Java y números", () => {
  assert.equal(parseDuration("PT4.458S"), 4.458);
  assert.equal(parseDuration("PT1M2.5S"), 62.5);
  assert.equal(parseDuration("PT1H"), 3600);
  assert.equal(parseDuration(7.5), 7.5);
  for (const bad of ["", "4.5", "PT", "P1D", null, undefined, "PT-1S", NaN]) {
    assert.throws(() => parseDuration(bad), /Duración inválida/, String(bad));
  }
});

// ---------- línea de tiempo ----------
test("buildTimeline ordena por order y acumula inicios con 6 decimales", () => {
  const story = readJson(fixture("story.backend.json"));
  story.scenes.reverse();
  const { scenes, totalDuration } = buildTimeline(story);
  assert.deepEqual(scenes.map((s) => s.order), [1, 2, 3, 4, 5, 6]);
  assert.equal(scenes[0].start, 0);
  assert.equal(scenes[1].start, 4.458);
  assert.equal(totalDuration, 37.431);
});

test("buildTimeline rechaza ids inseguros, repetidos y duraciones inválidas", () => {
  const base = () => readJson(fixture("story.backend.json"));
  let s = base();
  s.scenes[0].id = "../etc/passwd";
  assert.throws(() => buildTimeline(s), /Id de escena inválido/);
  s = base();
  s.scenes[1].id = s.scenes[0].id;
  assert.throws(() => buildTimeline(s), /repetido/);
  s = base();
  s.scenes[2].order = s.scenes[1].order;
  assert.throws(() => buildTimeline(s), /Orden repetido/);
  s = base();
  s.scenes[0].targetDuration = "PT0S";
  assert.throws(() => buildTimeline(s), /no positiva/);
  s = base();
  s.scenes[0].narrationText = "  ";
  assert.throws(() => buildTimeline(s), /narrationText/);
  assert.throws(() => buildTimeline({ id: "x", scenes: [] }), /no tiene escenas/);
});

// ---------- alineación ----------
const asr = (list) => list.map(([text, start, end]) => ({ text, start, end }));

test("alignScene: mismo conteo -> por posición, usando el texto de la narración", () => {
  const { words, report } = alignScene({
    sceneId: "s",
    narrationText: "Rusia: 1908, ¿qué?",
    asrWords: asr([["Rusia.", 0, 0.4], ["1908", 0.4, 1], ["que", 1, 1.3]]),
    sceneDuration: 2,
  });
  assert.deepEqual(words.map((w) => w.text), ["Rusia:", "1908,", "¿qué?"]);
  assert.equal(report.mode, "positional");
  assert.equal(report.inferred, 0);
  assert.equal(report.textMismatches, 0);
});

test("alignScene: el ASR descartó una palabra -> se infiere con la primera mitad del hueco", () => {
  const { words, report } = alignScene({
    sceneId: "s",
    narrationText: "edificios y infraestructuras en un radio",
    asrWords: asr([["edificios", 0, 0.48], ["infraestructuras", 0.64, 1.44], ["en", 1.44, 1.52], ["un", 1.52, 1.6], ["radio", 1.6, 2]]),
    sceneDuration: 3,
  });
  assert.equal(report.mode, "lcs");
  assert.equal(report.inferred, 1);
  assert.deepEqual(words.map((w) => w.inferred), [false, true, false, false, false, false]);
  assert.equal(words[1].start, 0.48);
  assert.equal(words[1].end, 0.48 + (0.64 - 0.48) / 2);
});

test("alignScene: palabras extra del ASR se ignoran; faltantes al inicio y al final se acotan a la escena", () => {
  const extra = alignScene({
    sceneId: "s",
    narrationText: "uno dos tres",
    asrWords: asr([["uno", 0, 0.5], ["eh", 0.5, 0.6], ["dos", 0.6, 1], ["tres", 1, 1.5]]),
    sceneDuration: 2,
  });
  assert.equal(extra.report.ignoredAsrWords, 1);
  assert.equal(extra.report.inferred, 0);

  const edges = alignScene({
    sceneId: "s",
    narrationText: "uno dos tres cuatro cinco seis siete ocho nueve diez",
    asrWords: asr([["dos", 0.5, 1], ["tres", 1, 1.5], ["cuatro", 1.5, 2], ["cinco", 2, 2.5], ["seis", 2.5, 3], ["siete", 3, 3.5], ["ocho", 3.5, 4], ["nueve", 4, 4.5]]),
    sceneDuration: 6,
  });
  assert.equal(edges.report.inferred, 2);
  assert.equal(edges.words[0].start, 0);
  assert.equal(edges.words[0].end, 0.25); // primera de dos partes entre 0 y 0.5 (k=1 -> mitad)
  assert.equal(edges.words[9].start, 4.5);
  assert.ok(edges.words[9].end <= 6);
});

test("alignScene: se niega a adivinar si casi nada coincide, y valida la transcripción", () => {
  assert.throws(
    () =>
      alignScene({
        sceneId: "s",
        narrationText: "uno dos tres cuatro cinco seis",
        asrWords: asr([["alfa", 0, 1], ["beta", 1, 2]]),
        sceneDuration: 5,
      }),
    /alineación no confiable/,
  );
  assert.throws(() => alignScene({ sceneId: "s", narrationText: "uno", asrWords: [{ text: "uno" }], sceneDuration: 1 }), /sin text\/start\/end/);
  assert.throws(() => alignScene({ sceneId: "s", narrationText: "a b", asrWords: asr([["a", 1, 2], ["b", 0, 0.5]]), sceneDuration: 3 }), /fuera de orden/);
  assert.throws(() => alignScene({ sceneId: "s", narrationText: "a", asrWords: asr([["a", 2, 1]]), sceneDuration: 3 }), /end < start/);
});

test("similar ignora tildes, mayúsculas y puntuación, y tolera 1 letra en palabras largas", () => {
  assert.ok(similar("ÁRBOLES,", "arboles"));
  assert.ok(similar("¿Qué", "que"));
  assert.ok(similar("infraestructuras", "infraestructura"));
  assert.ok(!similar("de", "da"));
  assert.ok(!similar("—", "—"));
});

// ---------- agrupado ----------
const W = (text, start, end, scene = "a") => ({ text, start, end, scene });
const bounds = { a: { end: 10 }, b: { end: 20 } };

test("groupWords: corta por 4 palabras, por silencio grande y siempre en el corte de escena", () => {
  const words = [
    W("uno", 0, 0.2), W("dos", 0.2, 0.4), W("tres", 0.4, 0.6), W("cuatro", 0.6, 0.8), W("cinco", 0.8, 1), // 4 + 1
    W("seis", 2, 2.2), // silencio de 1 s -> nuevo grupo
    W("siete", 9.5, 9.8), // todavía escena a
    W("ocho", 10, 10.3, "b"), // corte de escena
  ];
  const groups = groupWords(words, bounds);
  assert.deepEqual(groups.map((g) => g.words.map((w) => w.text)), [["uno", "dos", "tres", "cuatro"], ["cinco"], ["seis"], ["siete"], ["ocho"]]);
  for (const g of groups) assert.ok(g.end <= bounds[g.words[0].scene].end + 1e-9);
});

test("applyMinWordDuration alarga palabras cortas sin pisar a la siguiente, y con 0 no cambia nada", () => {
  const words = [W("edificios", 0, 0.48), W("y", 0.48, 0.56), W("infraestructuras", 0.64, 1.44), W("de", 1.44, 1.46), W("fin", 1.46, 2)];
  const same = applyMinWordDuration(words, bounds, 0);
  assert.deepEqual(same, words);
  const out = applyMinWordDuration(words, bounds, 0.2);
  assert.equal(out[1].end, 0.64); // limitada por la palabra siguiente (0.48 + 0.2 = 0.68 > 0.64)
  assert.equal(out[3].end, 1.46); // limitada por la siguiente, que empieza en 1.46
  assert.equal(out[0].end, 0.48); // ya era suficientemente larga
  assert.equal(words[1].end, 0.56); // no muta la entrada
});

// ---------- plantilla y salida ----------
test("fillTemplate falla si falta o sobra una clave y no re-escanea los valores", () => {
  assert.equal(fillTemplate("a {{X}} b", { X: "{{Y}} $& $1" }), "a {{Y}} $& $1 b");
  assert.throws(() => fillTemplate("a {{X}}", {}), /{{X}}/);
  assert.throws(() => fillTemplate("a", { X: "1" }), /no lo usa/);
});

test("un narrationText hostil no puede cerrar el <script> ni romper el HTML", () => {
  const story = readJson(fixture("story.backend.json"));
  story.title = 'Título <b>"x"</b> & co';
  story.scenes[0].narrationText = "Hola </script><img src=x onerror=alert(1)> mundo";
  const transcripts = loadTranscripts();
  transcripts["tunguska-gancho"] = [
    { text: "Hola", start: 0, end: 1 },
    { text: "</script><img", start: 1, end: 2 },
    { text: "src=x", start: 2, end: 2.5 },
    { text: "onerror=alert(1)>", start: 2.5, end: 3 },
    { text: "mundo", start: 3, end: 4 },
  ];
  const { html } = buildComposition({ story, transcriptsByScene: transcripts, template });
  const scriptBlock = html.slice(html.indexOf("<script>\n      var CANVAS_W"));
  assert.equal((scriptBlock.match(/<\/script>/g) ?? []).length, 1, "solo debe existir el </script> final de la plantilla");
  assert.ok(!html.includes("<img src=x"));
  assert.ok(html.includes("<title>Título &lt;b&gt;&quot;x&quot;&lt;/b&gt; &amp; co</title>"));
});

test("sin transcripción de una escena, o con demasiadas palabras inferidas, la generación falla", () => {
  const story = readJson(fixture("story.backend.json"));
  const t = loadTranscripts();
  delete t["tunguska-cierre"];
  assert.throws(() => buildComposition({ story, transcriptsByScene: t, template }), /Falta la transcripción de la escena tunguska-cierre/);

  const t2 = loadTranscripts();
  // se le quitan 3 de las 10 palabras de una escena -> 3/98 ≈ 3 % (pasa); con umbral 1 % debe fallar
  t2["tunguska-cierre"] = t2["tunguska-cierre"].slice(0, 7);
  assert.throws(
    () => buildComposition({ story, transcriptsByScene: t2, template, options: { maxInferredRatio: 0.01 } }),
    /Demasiadas palabras con tiempo inferido/,
  );
  assert.doesNotThrow(() => buildComposition({ story, transcriptsByScene: t2, template }));
});
