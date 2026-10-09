# video-renderer

Genera el **video final 9:16 con subtítulos de palabra resaltada** de una historia del backend, usando [HyperFrames](https://github.com/heygen-com/hyperframes) (Apache-2.0). Es la **Fase 1** de la decisión provisional del 2026-10-08 (ver `claude/estado-y-proximos-pasos.md`).

Vive **fuera del backend Java** a propósito: el backend termina cuando cada escena está `COMPLETED` y la narración sintetizada. Este generador no usa ningún LLM ni agente: es código determinista que reproduce la composición validada de la prueba "tunguska" (variante A, 720×1280).

## Estado: qué está verificado y qué no

| Cosa | Estado |
|---|---|
| Reproduce **byte a byte** la composición validada, con las duraciones de referencia | Verificado (`npm test`, 43 pruebas, 0 fallos) |
| Con las duraciones del backend (ms) queda dentro de 2 ms de la validada | Verificado (prueba automatizada) |
| Corrida real con los datos de `data/` (clips, audios, `tunguska.json`) | Verificado: genera el proyecto sin errores |
| `npx hyperframes lint` sobre el proyecto generado (0.8.141) | Verificado: 0 errores, 1 aviso (`composition_file_too_large`, 1164 líneas, igual que la validada) |
| `render` del proyecto generado | Lo corriste tú en Windows: compiló (720×1280, 37.431 s, 1123 fotogramas) y, tras corregir un problema con los mp3 de tu copia, terminó bien. **Pendiente de anotar:** qué lo corrigió y la revisión visual del MP4 |
| `src/transcribe.mjs` (ASR por escena) | Probado con una CLI **simulada** (10 pruebas) y contra la CLI **real** solo en su ruta de fallo (sin red a huggingface.co: código 1 y `{"ok":false,"error":...}`). **El camino de éxito con Parakeet real no se ejecutó en este repo** |
| Transcripciones reales vs. las de las pruebas | El agente de la prueba las comparó (6 escenas, CLI 0.8.141 vs 0.8.143): mismo texto y 0.0 ms de diferencia. Es un informe suyo, no lo reproduje |
| El flag `--render` de este CLI | **No ejecutado** (solo revisado estáticamente) |
| Segunda historia distinta de "tunguska" | **No probado** |
| Ejecución en GCP | **No probado** |

## Requisitos

- **Generador:** Node.js 22 o superior. No tiene dependencias npm.
- **Render** (aparte): HyperFrames CLI (probado con 0.8.141), FFmpeg y Chrome Headless Shell (`npx hyperframes browser ensure`, o `HYPERFRAMES_BROWSER_PATH` apuntando a un Chrome ya instalado, según el propio mensaje de la herramienta; o Docker). `npx hyperframes doctor` lista lo que falta.
- HyperFrames muestra un aviso de telemetría anónima; se desactiva con `hyperframes telemetry disable` (según el propio aviso). Conviene hacerlo en servidores.

## Uso

```bash
# 1. La historia (cualquiera de las dos)
curl http://localhost:8080/stories/tunguska > tunguska.json     # o pasar la URL directamente a --story

# 2. Tiempos por palabra: una transcripción por escena en transcripts/<idEscena>.json
#    (arreglo de { "text", "start", "end" } con tiempos en segundos LOCALES a la escena).
#    Automático: node src/transcribe.mjs --story tunguska.json --assets assets --out transcripts
#    (usa hyperframes@0.8.143 con Parakeet; ver "Transcripciones" abajo)

# 3. Generar el proyecto
node src/generate.mjs --story tunguska.json --transcripts transcripts --assets assets --out build/tunguska
#   assets/ debe tener clips/<idEscena>.mp4 y audio/<idEscena>.mp3

# 4. Revisar y renderizar
cd build/tunguska
npx hyperframes lint
npx hyperframes check
npx hyperframes render --output final.mp4 --quality standard
```

Salida en `--out`: `index.html`, `assets/`, `fonts/Montserrat-800.woff2`, `words.global.json` y `generation-report.json` (duraciones usadas, alineación por escena, grupos de subtítulos, comprobaciones de medios y avisos). **Leer los avisos**: informan, por ejemplo, las palabras con tiempo inferido.

Opciones: `--numerals auto|off`, `--title`, `--width/--height` (por defecto 720×1280), `--gsap-src`, `--min-word-sec` (alarga el resaltado de palabras muy cortas; por defecto 0 = comportamiento validado), `--max-inferred-ratio`, `--no-check-media`, `--force`, `--render`.

## Transcripciones (`src/transcribe.mjs`)

Corre `npx hyperframes@<versión> transcribe <audio> --json --dir <carpeta temporal> --language es` una vez por escena y deja `transcripts/<idEscena>.json` más `transcribe-report.json`. Por qué así (evidencia del historial del agente de la prueba, salvo lo marcado):

- **Carpeta temporal por escena (`--dir`).** La CLI escribe siempre `transcript.json` con nombre fijo y lo sobrescribe en cada corrida; en la prueba eso perdió la transcripción de `gancho`. Con `--dir` el archivo cae en esa carpeta (probado por el agente con una carpeta vacía; no con una que ya sea un proyecto HyperFrames).
- **Versión fijada (`--cli-version`, por defecto 0.8.143).** Sin fijarla, npx toma la última y un cambio de formato rompería el paso en silencio.
- **Sin interactividad.** La primera vez que npx baja una versión nueva pregunta si instalar; sin respuesta el proceso queda colgado (ocurrió 5 min en la prueba y una escena quedó con una transcripción vieja). Se pasa `npm_config_yes=true`, sin entrada estándar y con tiempo máximo por escena (10 min).
- **Validación.** Se exige código 0, `ok: true`, `transcriptPath` dentro de la carpeta temporal de esa escena, `wordCount` igual al del archivo y palabras con `text/start/end` en orden. Se copian los bytes que escribió la CLI.
- **`durationSeconds` de la CLI no es el largo del mp3.** En las 6 escenas de tunguska coincide exactamente con el fin de la última palabra (múltiplo de 0.08 s) y queda 0.07-0.29 s por debajo de `targetDuration` por el silencio final. Solo se avisa si es **mayor** que `targetDuration` + 0.15 s. (Observación sobre un solo audio de prueba; la regla "fin de la última palabra" es inferida.)
- **Comparar corridas:** `node src/compare-transcripts.mjs transcripts transcripts-real [--tol-ms N] [--loose-text]` compara texto, número de palabras y tiempos (solo `text/start/end`) y sale con código 1 si hay diferencias. Con `--loose-text` ignora mayúsculas y puntuación (el ASR escribe "Rusia." donde el guion dice "Rusia:"; el generador muestra siempre `narrationText`, así que eso no afecta al video).
- **Idioma.** Según el `--help`, Parakeet detecta el idioma solo y `--language` solo aplica a Whisper. Si la CLI informa un idioma distinto del pedido se avisa; no se midió con escenas cortas.
- **Tiempos medidos por el agente (su máquina, modelo ya cargado):** ≈5.6 s por escena; la primera corrida tardó 58.6 s por la descarga de npm. En una máquina fría no se midió.
- Parakeet ocupa ~640 MB y se instala con `npx hyperframes models install parakeet` (lo hizo `doctor` en la prueba). En GCP no se probó.

## Cifras en los subtítulos (`src/numerals.mjs`)

Las reglas editoriales piden escribir números y fechas "de forma que se lean sin ambigüedad", así que `narrationText` puede traer "diez de abril de mil ochocientos quince" (así se sintetiza). Por defecto (`--numerals auto`) los subtítulos lo muestran como **"10 de abril de 1815"**. Se prueban dos alineaciones por escena y gana la que deja menos palabras con tiempo inferido (empate: la original). (A) alinear las palabras habladas y fusionarlas después en **una** palabra que dura desde el inicio de la primera hasta el final de la última; (B) convertir antes y alinear contra el ASR, por si este escribe dígitos (hipótesis sobre Parakeet, **sin verificar con datos reales** al escribir esto). Si A falla por no ser confiable y B sí, se usa B.

- Se convierte: secuencias de valor >= 10 ("veinte", "mil novecientos ocho", "ciento veinte mil"), cualquier día 1-31 seguido de "de <mes>" ("ocho de agosto" -> "8 de agosto") y las cifras antes de millón/millones ("ochenta millones" -> "80 millones").
- NO se convierte: dígitos sueltos 0-9 ("tres kilómetros"), "un/una/uno", "mil" suelto, "un millón", ni secuencias no gramaticales. Ordinales, fracciones y decimales no están soportados.
- Un año se escribe sin separador (**1815**, no 1.815); de 5 cifras en adelante se agrupa con espacio duro ("10 000").
- Todo lo convertido se imprime como `CIFRA ...` y queda en `generation-report.json` (`numeralConversions`) para revisarlo. `--numerals off` muestra el texto tal cual.
- Es un puente: lo estructural sería que el generador de historias emita un texto para voz y otro para pantalla.

## Aviso `duplicate_audio_track` del lint

El lint de HyperFrames (leído en su código, 0.8.143) compara con coma flotante y sin tolerancia: dos audios pegados en la misma pista (`11.75 + 6.548 = 18.298000000000002 > 18.298`) se marcan como traslape de ~2e-15 s según los decimales de cada historia (apareció en "tambora", no en "tunguska"). El generador acorta 1 microsegundo el final de cada audio (`data-duration`), sin efecto audible. Las pruebas de equivalencia con la composición validada usan margen 0.

## Decisiones de diseño

- **Duración de cada escena = `targetDuration` del backend** (alignment de ElevenLabs). No usar la cabecera del mp3: sobreestima entre ~30 y 60 ms.
- **El texto de los subtítulos es siempre `narrationText`**; la transcripción (ASR) solo aporta los tiempos. Con el mismo número de palabras se alinea por posición (como en la prueba validada); si no, por subsecuencia común tolerante a tildes y mayúsculas.
- **Una palabra que el ASR descartó** (como la "y" de `tunguska-consecuencia`) recibe un tiempo **inferido** (primera mitad del hueco entre sus vecinas) y se informa. Se aborta si hay más de 15 % de palabras inferidas o si menos de 70 % de una escena se logra emparejar: no se adivina.
- **El JS de subtítulos se emite con la misma estructura de la prueba validada** (un bloque por frase) para poder demostrar equivalencia byte a byte. Compactarlo (un solo bucle sobre datos) arreglaría el aviso de tamaño de lint, pero cambiaría un código ya validado: queda como mejora posterior.
- Los ids de escena (que terminan en nombres de archivo y atributos HTML) se restringen a `[A-Za-z0-9_-]`; el texto de la narración y el título se escapan para que no puedan cerrar el `<script>` ni romper el HTML (cubierto por pruebas).

## Limitaciones y riesgos conocidos

- **Dependencia de red en el render:** la plantilla carga GSAP desde jsDelivr (`--gsap-src`), igual que la composición validada. No se probó un render con la red cortada ni si HyperFrames lo resuelve de otro modo. Para un servidor sin salida, probar con una copia local de GSAP.
- **Palabras muy cortas:** el ASR usado (Parakeet) entregó tiempos en múltiplos de 80 ms, y una palabra corta fundida con su vecina puede quedar con un resaltado de ~80 ms casi invisible. `--min-word-sec 0.12` es una mitigación **no evaluada visualmente**.
- **Clips con pocos fotogramas clave (GOP de 5-8 s):** el compilador de HyperFrames avisó de riesgo de congelamiento al buscar dentro del clip; el agente de la prueba no observó ninguno. Sin resolver.
- **`index.html` grande** (~1200 líneas) por el diseño de emisión de arriba.
- **El proyecto generado contiene solo lo que este generador escribe.** La carpeta de la prueba original (`hf-tunguska/build/project-a`) puede tener archivos que `hyperframes init` crea y que acá no se generan. `lint` no los exigió; no se sabe si `render` los necesita.

## Pendiente

1. **Probar `src/transcribe.mjs` con Parakeet real** (camino de éxito) y comparar contra las transcripciones de referencia. Alternativa a evaluar para historias nuevas: guardar el alignment por palabra de ElevenLabs al sintetizar y no usar ASR (la CLI acepta importar un transcript .json/.srt/.vtt; se desconoce el formato JSON que espera).
2. Probar con una **segunda historia** y con **transcripciones reales**.
3. Probar el render en una **VM de GCP**.
4. Bajar la dependencia de red de GSAP si hace falta.
5. Resolver el aviso de tamaño de `lint` y los *sparse keyframes*.
