import { test } from "node:test";
import assert from "node:assert/strict";
import { buildComposition } from "../src/build.mjs";
import { fixture, readJson, template, golden, loadTranscripts, firstDiff } from "./helpers.mjs";

const TITLE = "Tunguska — prueba vertical";

test("con las duraciones de referencia reproduce byte a byte la composición validada (video A)", () => {
  const { html, warnings } = buildComposition({
    story: readJson(fixture("story.reference.json")),
    transcriptsByScene: loadTranscripts(),
    template,
    options: { title: TITLE, audioEndMarginSec: 0 }, // 0: reproduce exactamente el archivo validado por el agente
  });
  assert.equal(html, golden, firstDiff(html, golden));
  // La única palabra con tiempo inferido es la "y" de consecuencia (el ASR la había descartado).
  assert.equal(warnings.length, 1);
  assert.match(warnings[0], /1 palabra\(s\) con tiempo inferido.*"y"/);
});

test("con las duraciones del backend (milisegundos) el resultado es equivalente dentro de 2 ms", () => {
  const out = buildComposition({
    story: readJson(fixture("story.backend.json")),
    transcriptsByScene: loadTranscripts(),
    template,
    options: { title: TITLE },
  });
  const ref = buildComposition({
    story: readJson(fixture("story.reference.json")),
    transcriptsByScene: loadTranscripts(),
    template,
    options: { title: TITLE },
  });

  assert.ok(Math.abs(out.totalDuration - ref.totalDuration) < 0.002, `total ${out.totalDuration} vs ${ref.totalDuration}`);
  assert.equal(out.groups.length, ref.groups.length);
  out.groups.forEach((g, i) => {
    assert.equal(g.text, ref.groups[i].text);
    assert.equal(g.scene, ref.groups[i].scene);
    assert.ok(Math.abs(g.start - ref.groups[i].start) < 0.002, `grupo ${i} inicio ${g.start} vs ${ref.groups[i].start}`);
    assert.ok(Math.abs(g.end - ref.groups[i].end) < 0.002, `grupo ${i} fin ${g.end} vs ${ref.groups[i].end}`);
  });
  assert.equal(out.words.length, 99);
});

test("el tamaño de la composición coincide con el golden (el aviso de lint por archivo grande sigue pendiente)", () => {
  const { html } = buildComposition({
    story: readJson(fixture("story.reference.json")),
    transcriptsByScene: loadTranscripts(),
    template,
    options: { title: TITLE, audioEndMarginSec: 0 },
  });
  assert.equal(html.split("\n").length, golden.split("\n").length);
});
