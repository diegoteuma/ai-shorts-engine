import { test } from "node:test";
import assert from "node:assert/strict";
import { buildComposition } from "../src/build.mjs";
import { buildTimeline } from "../src/story.mjs";
import { fixture, readJson, template, golden, loadTranscripts, firstDiff } from "./helpers.mjs";

const TITLE = "Tunguska — prueba vertical";

function compose(edit = () => {}) {
  const story = readJson(fixture("story.reference.json"));
  edit(story);
  return buildComposition({
    story,
    transcriptsByScene: loadTranscripts(),
    template,
    options: { title: TITLE, audioEndMarginSec: 0 },
  });
}

const sceneOf = (story, id) => story.scenes.find((s) => s.id === id);

/** Quita todo lo que agrega el rótulo de lugar (CSS, un div por escena, el bloque de JS). */
const stripLocation = (html) =>
  html
    .replace(/\n      \.location-label \{[\s\S]*?\n      \.location-label-box \{[\s\S]*?\n      \}\n/, "")
    .replace(/      <div class="location-label" id="loc-[^"]+"[^>]*><div class="location-label-box">.*<\/div><\/div>\n/g, "")
    .replace(/\n  \/\/ Rótulos de lugar[^\n]*\n(  \(function\(\)\{\n[\s\S]*?\n  \}\)\(\);\n)+/, "");

test("sin locationLabel (ausente, null, vacío) la composición es idéntica al golden", () => {
  for (const value of [undefined, null, "", "   "]) {
    const { html } = compose((s) => {
      if (value !== undefined) for (const scene of s.scenes) scene.locationLabel = value;
    });
    assert.equal(html, golden, `locationLabel=${JSON.stringify(value)}: ${firstDiff(html, golden)}`);
    assert.doesNotMatch(html, /location-label/);
  }
});

test("con rótulo en el GANCHO y el CONTEXTO: uno por escena, fijo de principio a fin, visible desde su primer fotograma", () => {
  const { html, scenes } = compose((s) => {
    sceneOf(s, "tunguska-gancho").locationLabel = "  SIBERIA · JUNIO 1908 ";
    sceneOf(s, "tunguska-contexto").locationLabel = "RUSIA · 1908";
  });
  const hook = scenes.find((s) => s.id === "tunguska-gancho");
  const ctx = scenes.find((s) => s.id === "tunguska-contexto");
  assert.equal(hook.locationLabel, "SIBERIA · JUNIO 1908");

  // El GANCHO empieza en 0: sin ocultar al inicio (ni por CSS ni por estilo propio).
  assert.match(html, /<div class="location-label" id="loc-tunguska-gancho"><div class="location-label-box">SIBERIA · JUNIO 1908<\/div><\/div>/);
  // El CONTEXTO arranca oculto y se muestra exactamente en su inicio.
  assert.match(html, /<div class="location-label" id="loc-tunguska-contexto" style="visibility: hidden"><div class="location-label-box">RUSIA · 1908<\/div><\/div>/);
  const js = html.slice(html.indexOf("// Rótulos de lugar"), html.indexOf("window.__timelines"));
  const t = (x) => x.toFixed(6).replace(".", "\\.");
  assert.doesNotMatch(js, new RegExp(`getElementById\\("loc-tunguska-gancho"\\)[\\s\\S]*?visibility: "visible"[\\s\\S]*?loc-tunguska-contexto`));
  assert.match(js, new RegExp(`tl\\.set\\(wrap, \\{ visibility: "hidden" \\}, ${t(hook.start + hook.duration)}\\);`));
  assert.match(js, new RegExp(`tl\\.set\\(wrap, \\{ visibility: "visible" \\}, ${t(ctx.start)}\\);`));
  assert.match(js, new RegExp(`tl\\.set\\(wrap, \\{ visibility: "hidden" \\}, ${t(ctx.start + ctx.duration)}\\);`));
  // Sin animación ni azar, y el texto nunca entra al <script>.
  assert.doesNotMatch(js, /Math\.random|Date\.now|opacity/);
  assert.doesNotMatch(js, /SIBERIA|RUSIA/);
  // Las escenas sin rótulo no agregan nada.
  assert.equal((html.match(/class="location-label"/g) ?? []).length, 2);
});

test("no se superpone con el texto del gancho ni con los subtítulos", () => {
  const { html } = compose((s) => {
    s.hookText = "EXPLOSIÓN SIN CRÁTER";
    sceneOf(s, "tunguska-gancho").locationLabel = "SIBERIA · JUNIO 1908";
  });
  const H = 1280;
  const W = 720;
  const labelCss = html.match(/\.location-label \{[\s\S]*?\}/)[0];
  const boxCss = html.match(/\.location-label-box \{[\s\S]*?\}/)[0];
  const labelTop = (parseFloat(labelCss.match(/top: ([\d.]+)%/)[1]) / 100) * H;
  const [padTop, , padBottom] = boxCss.match(/padding: (\d+)px (\d+)px (\d+)px/).slice(1).map(Number);
  // Tamaño máximo de la letra (fitFontSize solo achica): 0.036 x ancho.
  const labelBottom = labelTop + Math.round(W * 0.036) + padTop + padBottom;
  const hookTop = (parseFloat(html.match(/#hook-text \{[\s\S]*?top: ([\d.]+)%/)[1]) / 100) * H;
  const captionsBottomPct = parseFloat(html.match(/\.hl-group \{[\s\S]*?bottom: ([\d.]+)%/)[1]);
  assert.ok(labelBottom < hookTop, `el rótulo termina en ${labelBottom} px y el gancho empieza en ${hookTop} px`);
  assert.ok(labelTop < H * (1 - captionsBottomPct / 100) - 200, "el rótulo queda lejos de los subtítulos");
  // Una sola línea, por encima del gancho (z 11) y de los subtítulos (z 10).
  assert.match(boxCss, /white-space: nowrap/);
  assert.match(labelCss, /z-index: 12/);
});

test("con rótulo, el resto de la composición queda igual: solo se agregan sus piezas", () => {
  const { html } = compose((s) => {
    sceneOf(s, "tunguska-gancho").locationLabel = "SIBERIA · JUNIO 1908";
    sceneOf(s, "tunguska-contexto").locationLabel = "RUSIA · 1908";
  });
  const stripped = stripLocation(html);
  assert.equal(stripped, golden, firstDiff(stripped, golden));
});

test("un locationLabel hostil no puede cerrar el <script> ni inyectar HTML", () => {
  const { html } = compose((s) => (sceneOf(s, "tunguska-gancho").locationLabel = '</script><img src=x onerror="alert(1)"> & "X"'));
  assert.equal((html.match(/<\/script>/g) ?? []).length, (golden.match(/<\/script>/g) ?? []).length);
  assert.doesNotMatch(html, /<img/);
  assert.match(html, /&lt;\/script&gt;&lt;img src=x onerror=&quot;alert\(1\)&quot;&gt; &amp; &quot;X&quot;/);
});

test("un locationLabel que no es texto falla con un mensaje claro", () => {
  const s = readJson(fixture("story.reference.json"));
  sceneOf(s, "tunguska-gancho").locationLabel = 1960;
  assert.throws(() => buildTimeline(s), /tunguska-gancho tiene un locationLabel que no es texto/);
});
