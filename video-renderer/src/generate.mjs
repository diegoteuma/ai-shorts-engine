#!/usr/bin/env node
// Genera el proyecto HyperFrames de una historia: index.html + assets + fuente + reportes.
//
//   node src/generate.mjs --story data/stories/tunguska.json \
//        --transcripts transcripts --assets assets --out build/tunguska
//
// --story acepta un archivo o una URL (por ejemplo http://localhost:8080/stories/tunguska).
// --transcripts: carpeta con <idEscena>.json por escena (arreglo de {text,start,end}, tiempos locales a la escena).
// --assets: carpeta con clips/<idEscena>.mp4 y audio/<idEscena>.mp3.
import { spawnSync } from "node:child_process";
import { copyFile, mkdir, readFile, stat, writeFile } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import { buildComposition, DEFAULT_GSAP_SRC } from "./build.mjs";
import { loadStory } from "./story.mjs";

const here = dirname(fileURLToPath(import.meta.url));
const root = resolve(here, "..");

const USAGE = `Uso: node src/generate.mjs --story <archivo|url> --transcripts <dir> --assets <dir> --out <dir> [opciones]

Opciones:
  --title <texto>             título del <title> (por defecto, el de la historia)
  --width <px> --height <px>  tamaño del lienzo (por defecto 720x1280, el tamaño nativo de los clips)
  --gsap-src <url|ruta>       origen de GSAP (por defecto ${DEFAULT_GSAP_SRC})
  --min-word-sec <s>          duración mínima del resaltado de cada palabra (por defecto 0 = sin cambios)
  --max-inferred-ratio <r>    fracción máxima de palabras con tiempo inferido (por defecto 0.15)
  --numerals <auto|off>       auto (por defecto): cifras en palabras -> dígitos en los subtítulos ("diez de abril de mil ochocientos quince" -> "10 de abril de 1815"); off: se muestra el texto tal cual
  --no-check-media            no revisar duración/tamaño de clips y audios con ffprobe
  --force                     permitir sobrescribir un --out que ya tiene index.html
  --render                    después de generar, correr hyperframes lint, check y render (NO probado en este repo)
  --help`;

const VALUE_FLAGS = new Set([
  "story", "transcripts", "assets", "out", "title", "width", "height", "gsap-src", "min-word-sec", "max-inferred-ratio", "numerals",
]);
const BOOL_FLAGS = new Set(["no-check-media", "force", "render", "help"]);

function parseArgs(argv) {
  const args = {};
  for (let i = 0; i < argv.length; i++) {
    const a = argv[i];
    if (!a.startsWith("--")) throw new Error(`Argumento inesperado: ${a}`);
    const key = a.slice(2);
    if (BOOL_FLAGS.has(key)) args[key] = true;
    else if (VALUE_FLAGS.has(key)) {
      const v = argv[++i];
      if (v === undefined || v.startsWith("--")) throw new Error(`Falta el valor de --${key}`);
      args[key] = v;
    } else throw new Error(`Opción desconocida: --${key}`);
  }
  return args;
}

const exists = async (p) => stat(p).then(() => true, () => false);

function number(args, key, fallback) {
  if (args[key] === undefined) return fallback;
  const n = Number(args[key]);
  if (!Number.isFinite(n)) throw new Error(`--${key} debe ser un número (recibido: ${args[key]})`);
  return n;
}

function probe(path) {
  const r = spawnSync(
    "ffprobe",
    ["-v", "error", "-select_streams", "v:0", "-show_entries", "stream=width,height:format=duration", "-of", "json", path],
    { encoding: "utf8" },
  );
  if (r.error || r.status !== 0) return null;
  try {
    const j = JSON.parse(r.stdout);
    const stream = j.streams?.[0] ?? {};
    return { duration: Number(j.format?.duration), width: stream.width, height: stream.height };
  } catch {
    return null;
  }
}

async function main() {
  const args = parseArgs(process.argv.slice(2));
  if (args.help) {
    console.log(USAGE);
    return;
  }
  for (const k of ["story", "transcripts", "assets", "out"]) {
    if (!args[k]) throw new Error(`Falta --${k}\n\n${USAGE}`);
  }

  const out = resolve(args.out);
  if (!args.force && (await exists(join(out, "index.html")))) {
    throw new Error(`${out}/index.html ya existe. Usar --force para sobrescribir.`);
  }

  const story = await loadStory(args.story);
  const template = await readFile(join(root, "template", "index.template.html"), "utf8");

  // Transcripciones por escena (el id ya fue validado por buildTimeline dentro de buildComposition,
  // pero se leen antes: validar el id acá también evita construir rutas con valores no verificados).
  const transcriptsByScene = {};
  for (const s of story.scenes ?? []) {
    if (typeof s.id !== "string" || !/^[A-Za-z0-9][A-Za-z0-9_-]*$/.test(s.id)) {
      throw new Error(`Id de escena inválido: ${JSON.stringify(s.id)}`);
    }
    const file = join(resolve(args.transcripts), `${s.id}.json`);
    if (!(await exists(file))) throw new Error(`Falta la transcripción ${file}`);
    const parsed = JSON.parse(await readFile(file, "utf8"));
    transcriptsByScene[s.id] = Array.isArray(parsed) ? parsed : parsed.words;
  }

  const options = {
    width: number(args, "width", 720),
    height: number(args, "height", 1280),
    minWordSec: number(args, "min-word-sec", 0),
    maxInferredRatio: number(args, "max-inferred-ratio", 0.15),
    gsapSrc: args["gsap-src"] ?? DEFAULT_GSAP_SRC,
    numerals: args.numerals ?? "auto",
    ...(args.title ? { title: args.title } : {}),
  };

  const result = buildComposition({ story, transcriptsByScene, template, options });
  const warnings = [...result.warnings];

  // Archivos de medios: deben existir todos.
  const assets = resolve(args.assets);
  const mediaChecks = [];
  for (const s of result.scenes) {
    const clip = join(assets, "clips", `${s.id}.mp4`);
    const audio = join(assets, "audio", `${s.id}.mp3`);
    for (const f of [clip, audio]) {
      if (!(await exists(f))) throw new Error(`Falta el archivo ${f}`);
    }
    if (!args["no-check-media"]) {
      const c = probe(clip);
      const a = probe(audio);
      if (!c || !a) {
        mediaChecks.push({ scene: s.id, skipped: "ffprobe no disponible o falló" });
        continue;
      }
      const check = { scene: s.id, sceneDuration: s.duration, clipDuration: c.duration, clipSize: `${c.width}x${c.height}`, audioHeaderDuration: a.duration };
      if (c.duration < s.duration - 0.001) {
        warnings.push(`${s.id}: el clip dura ${c.duration.toFixed(3)} s, menos que la escena (${s.duration.toFixed(3)} s). Se desconoce cómo lo trata HyperFrames; revisar el resultado.`);
      }
      if (c.width !== options.width || c.height !== options.height) {
        warnings.push(`${s.id}: el clip mide ${c.width}x${c.height} y el lienzo ${options.width}x${options.height} (se recorta/escala con object-fit: cover).`);
      }
      // La cabecera del mp3 sobreestima 30-60 ms: solo se avisa de diferencias grandes (audio equivocado).
      if (Math.abs(a.duration - s.duration) > 0.15) {
        warnings.push(`${s.id}: la cabecera del audio dura ${a.duration.toFixed(3)} s y targetDuration ${s.duration.toFixed(3)} s (diferencia > 0.15 s): ¿audio equivocado?`);
      }
      mediaChecks.push(check);
    }
  }

  // Escritura del proyecto.
  await mkdir(join(out, "assets", "clips"), { recursive: true });
  await mkdir(join(out, "assets", "audio"), { recursive: true });
  await mkdir(join(out, "fonts"), { recursive: true });
  for (const s of result.scenes) {
    await copyFile(join(assets, "clips", `${s.id}.mp4`), join(out, "assets", "clips", `${s.id}.mp4`));
    await copyFile(join(assets, "audio", `${s.id}.mp3`), join(out, "assets", "audio", `${s.id}.mp3`));
  }
  await copyFile(join(root, "fonts", "Montserrat-800.woff2"), join(out, "fonts", "Montserrat-800.woff2"));
  await writeFile(join(out, "index.html"), result.html);
  await writeFile(join(out, "words.global.json"), JSON.stringify(result.words, null, 2));
  await writeFile(
    join(out, "generation-report.json"),
    JSON.stringify(
      {
        story: { id: story.id, title: story.title },
        durationSource: "targetDuration (backend)",
        totalDuration: result.totalDuration,
        scenes: result.scenes,
        captionGroups: result.groups,
        alignment: result.alignmentReport,
        numeralConversions: result.numeralConversions,
        mediaChecks,
        warnings,
        options,
      },
      null,
      2,
    ),
  );

  console.log(`OK  ${out}/index.html`);
  console.log(`    escenas: ${result.scenes.length}, duración total: ${result.totalDuration.toFixed(3)} s, palabras: ${result.words.length}, grupos de subtítulos: ${result.groups.length}`);
  for (const c of result.numeralConversions) console.log(`CIFRA ${c.scene}: "${c.from}" -> "${c.to}"`);
  for (const w of warnings) console.log(`AVISO ${w}`);

  if (args.render) {
    const shell = process.platform === "win32";
    for (const cmd of [["lint"], ["check"], ["render", "--output", "final.mp4", "--quality", "standard"]]) {
      console.log(`\n$ npx hyperframes ${cmd.join(" ")}`);
      const r = spawnSync("npx", ["hyperframes", ...cmd], { cwd: out, stdio: "inherit", shell });
      if (r.status !== 0) throw new Error(`"npx hyperframes ${cmd[0]}" terminó con código ${r.status}`);
    }
  }
}

main().catch((e) => {
  console.error(`ERROR ${e.message}`);
  process.exit(1);
});
