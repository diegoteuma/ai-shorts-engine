# AI Shorts Content Engine — backend (Spring Boot, Java 21)

Esqueleto del backend del piloto Tunguska. Java 21 + Spring Boot 3.

## Cómo correrlo

```bash
mvn compile

# Demo end-to-end contra un LLM y un Higgsfield falsos (sin red real):
mvn exec:java -Dexec.mainClass=com.aishorts.engine.demo.DemoRunner

# API REST con clientes reales (ver "API REST" más abajo para las
# variables de entorno requeridas):
mvn spring-boot:run
```

`DemoRunner` simula el prototipo Tunguska completo — desde el `StoryBrief`
(lo que vos ya aprobaste) hasta la generación — contra un LLM y un
Higgsfield falsos, sin llamar a ninguna red real. No pasa por el contexto de
Spring — es un `main()` plano, independiente de la API.

## Spring Boot + Jackson

El proyecto arrancó en un sandbox sin salida a Maven Central (así que
durante un tiempo usó `java.net.http.HttpClient` a mano y un parser JSON
propio, `com.aishorts.engine.json.Json`) pero ya está migrado a Spring Boot:
`spring-boot-starter-web` trae Jackson (reemplazó por completo a `Json`, que
ya no existe), Tomcat embebido y Spring MVC. El wiring de beans vive en
`com.aishorts.engine.config.EngineConfiguration`; el punto de entrada es
`com.aishorts.engine.AiShortsEngineApplication`.

## Estructura

```
com.aishorts.engine
├── domain/        Story, Scene y los enums de estado (SceneRole, StoryStatus,
│                   SceneApprovalStatus, SceneCostStatus, GenerationTier,
│                   GenerationStatus). Scene es dueña de su propio ciclo de
│                   vida: sus métodos de transición lanzan
│                   IllegalStateException si se llaman fuera de orden
│                   (ej. pedir costo sin prompt aprobado). Scene también
│                   guarda su targetDuration (Duration), que se recalcula
│                   cada vez que se revisa la narración.
│
├── duration/       DurationBudget (el rango objetivo, ej. 35-40s) y
│                   NarrationDurationEstimator: estima cuánto dura hablada
│                   una narración por conteo de palabras (2,6 palabras/seg
│                   en español neutro, configurable). Es una aproximación
│                   hasta que haya audio real de TTS — ver "Pendiente" abajo.
│
├── difficulty/     DifficultyFactors (las 4 señales de complejidad visual de
│                   una escena), SceneDifficultyScorer y su implementación
│                   por defecto: combina el peso narrativo del rol
│                   (SceneRole) con la complejidad visual para sugerir
│                   STANDARD o PREMIUM. Es solo una sugerencia — la decisión
│                   final es tuya, en la puerta de aprobación de prompts.
│
├── claude/         ClaudeMessagesClient: cliente HTTP de bajo nivel para la
│                   Messages API de Claude, usado por script/ (sendMessage,
│                   solo texto) y por drafts/ (createMessage: body completo
│                   con tools y bloques crudos, con timeout de lectura).
│                   ClaudeConfig guarda apiKey/model/baseUrl.
│
├── drafts/         Generador de historias, el paso PREVIO al pipeline:
│                   propuestas de tema y borradores escritos por Claude con
│                   búsqueda web, validados por el servidor (DraftValidator,
│                   DraftNormalizer) y revisados en la PUERTA 0. Al aprobar,
│                   la Story se crea por el mismo camino que POST /stories.
│                   Ver "Generador de historias" abajo.
│
├── tts/            TtsService (interfaz) y ElevenLabsTtsService: sintetiza
│                   el audio de narración vía el endpoint with-timestamps de
│                   ElevenLabs, que devuelve el audio y el alignment por
│                   carácter — de ahí sale la duración REAL de cada escena
│                   (Scene.attachNarrationAudio), reemplazando la estimación
│                   por palabras. Se llama después de la puerta 1 (prompt
│                   aprobado): es contenido pago, aunque sea de centavos.
│
├── script/         StoryBrief (lo que vos ya aprobaste: tema, título,
│                   hechos núcleo, restricciones editoriales, presupuesto de
│                   duración) → ScriptDraftingService → 6 SceneDraft.
│                   ClaudeScriptDraftingService le pide a Claude un array
│                   JSON de 6 escenas en el orden GANCHO..CIERRE y lo
│                   parsea de forma estricta. StoryDraftingService arma el
│                   Story final con las duraciones ya calculadas.
│
├── higgsfield/     HiggsfieldClient (interfaz) y HiggsfieldRestClient (la
│                   implementación real, con java.net.http). Confirmado
│                   contra el spec público (docs.higgsfield.ai/docs/openapi.json):
│                   NO existe ningún endpoint de estimate real, así que
│                   estimateCost calcula el costo localmente (ModelPricing +
│                   DurationPolicy, cargados en KnownHiggsfieldPricing —
│                   $/segundo y duraciones permitidas por modelo, enum fijo
│                   o rango continuo según el modelo) en vez de pegarle a la
│                   red. pollStatus lee el asset final anidado en
│                   video.url (nunca un campo plano) e interpreta
│                   explícitamente los 6 status reales (queued, in_progress,
│                   nsfw, failed, completed, canceled) — nsfw/canceled
│                   terminan la escena en FAILED, nunca quedan poleados
│                   indefinidamente. duration va como parámetro estructurado
│                   de la llamada (igual que aspect_ratio), nunca como texto
│                   del prompt; Seedance 2.0 además necesita
│                   "generate_audio": false o factura audio que después se
│                   descarta (STANDARD y PREMIUM comparten el mismo modelo).
│
├── approval/       StoryApprovalService: el orquestador de las dos puertas
│                   humanas, siempre por lote (la historia completa, no
│                   escena por escena):
│                     0. proposeTiersForReview  → arma la sugerencia por escena
│                     1. applyPromptDecisions   → PUERTA 1, aprobás/rechazás el lote
│                        (revisar la narración acá recalcula targetDuration, estimada)
│                     2. synthesizeNarrationForApprovedScenes → TTS real, duración
│                        pasa de estimada a la del audio (puede sacar la historia
│                        del presupuesto de tiempo — revisar antes de seguir)
│                     3. estimateCostsForApprovedScenes → calcula el costo
│                        (local, ver higgsfield/ arriba; usa la duración YA
│                        REAL como parámetro)
│                     4. applyCostDecisions     → PUERTA 2, aprobás/rechazás el lote
│                     5. generateApprovedScenes → el ÚNICO método que gasta y genera
│                   Un rechazo en una escena nunca bloquea a las demás del lote.
│
├── persistence/    StoryRepository (interfaz) y JsonFileStoryRepository:
│                   guardan/releen Story completas (todas sus Scene, con su
│                   estado de aprobación/costo/generación) como un .json por
│                   historia (con Jackson). Ver "Persistencia" abajo.
│
├── api/            StoryController (Spring MVC @RestController) expone
│                   StoryApprovalService por HTTP para operar el flujo de
│                   aprobación desde una UI. ApiExceptionHandler traduce
│                   errores a respuestas HTTP. Ver "API REST" abajo.
│
├── config/         EngineConfiguration: wiring de beans Spring (clientes
│                   reales, servicios, StoryRepository) a partir de
│                   variables de entorno.
│
└── demo/           DemoRunner + los Fake* (HiggsfieldClient, ScriptDraftingService,
                    TtsService): corren el flujo completo del
                    prototipo Tunguska sin tocar ninguna red real.
```

## Reglas que el código hace cumplir, no solo documenta

- **Nunca se genera sin dos aprobaciones explícitas.** `Scene.startGeneration`
  lanza excepción si el costo no está `APPROVED`. `Scene.recordCostEstimate`
  lanza excepción si el prompt no está `APPROVED`. Y `StoryApprovalService`
  solo llama a esos métodos desde los pasos correspondientes del flujo.
- **La aprobación es por lote (la historia completa), no por escena suelta.**
  `applyPromptDecisions` y `applyCostDecisions` reciben una lista de
  decisiones para todas las escenas de la historia de una sola vez.
- **Un rechazo no frena al resto.** `BatchResult` reporta éxitos y fallas por
  separado; el resto de las escenas del lote sigue su curso.
- **El tier (estándar/premium) es una sugerencia, no una decisión automática
  del sistema.** `proposeTiersForReview` calcula y adjunta la sugerencia;
  quien la confirma (o la cambia) sos vos, al aprobar el prompt.
- **La duración total se valida contra tu presupuesto (35-40s) en todo
  momento**, no solo al armar el borrador: revisar una narración en la
  puerta 1 recalcula `Scene.targetDuration` y por lo tanto
  `Story.totalTargetDuration()`.

## Generador de historias (PUERTA 0)

Un paso **previo** al pipeline: Claude (con búsqueda web) propone temas y
escribe el borrador; vos lo revisás; recién al aprobarlo se crea la Story.
De ahí en adelante el pipeline sigue **exactamente igual** (`/tiers`,
PUERTA 1, narración, costos, PUERTA 2, generación). Nada de este paso llama
a Higgsfield ni a ElevenLabs.

```
POST /story-drafts/proposals        {focus?}  -> 5 temas candidatos (no guarda nada; búsqueda web, máx. 3)
POST /story-drafts                  {topic, rejectedDraftId?}  -> borrador PENDING_REVIEW en data/drafts/<id>.json
GET  /story-drafts                  resumen (id, título, tema, status, # violaciones duras/blandas)
GET  /story-drafts/{id}             borrador completo (claims, fuentes, what-if, violaciones, researchedUrls, usage)
PATCH /story-drafts/{id}            {title?, narrationEdits?, promptEdits?} -> edita a mano un PENDING_REVIEW, recalcula y revalida (sin Claude)
POST /story-drafts/{id}/decision    PUERTA 0: {decision: APPROVE|REJECT, note?, narrationEdits?, promptEdits?, acknowledgeUnverified?}
         │
         └─ APPROVE -> Story nueva en data/stories (= POST /stories) -> POST /stories/{id}/tiers -> ...
```

- **System prompt** = `src/main/resources/prompts/story-generator-rules.md`
  (sin modificar; si falta, el arranque falla) + un bloque de contexto
  (modo, fecha de hoy, búsqueda web, `existingStories` de `data/stories` y
  de los borradores no rechazados).
- **Streaming**: las llamadas al generador usan `"stream": true` (SSE) sobre
  HTTP/1.1. Una llamada con búsqueda web tarda minutos y, sin streaming, la
  conexión queda sin tráfico; en una red con inspección TLS se observó que
  la cortaban (`Connection reset`). Con streaming llegan eventos y pings
  mientras Claude trabaja. Si estás detrás de inspección TLS, Java además
  necesita confiar en la CA corporativa: arrancá con
  `-Djavax.net.ssl.trustStoreType=Windows-ROOT`.
- **Borrador**: una pasada con búsqueda web (maneja `pause_turn` reenviando
  los bloques intactos) → JSON del primer `{` al último `}` → normalización →
  validación. Si hay violaciones **duras**, UN reintento sin herramientas con
  el JSON anterior y las violaciones como feedback (las URLs consultadas en
  la primera pasada se conservan). Si vuelve a fallar, el borrador se guarda
  igual, con las violaciones visibles.
- **El servidor no le cree al modelo**: recalcula palabras, segundos
  (2.65 palabras/s), `checks`, la fecha de consulta y el tier de cada fuente
  (por dominio, listas configurables; Wikipedia sirve para orientarse pero no
  cuenta). Una URL que no salió de la búsqueda deja la claim `SIN_VERIFICAR`;
  una claim de las escenas 1-3 con menos de 2 fuentes A/B de dominios
  distintos queda con confianza `BAJA`.
- **Reglas duras** (bloquean la aprobación): 6 escenas GANCHO..CIERRE con
  order 1-6, slug válido y libre, id de escena `<slug>-<rol>`, 92-104 palabras,
  cada escena 3-8 s, claims referenciadas existentes (también las del
  what-if), escenas 1-3 con claims HECHO/ESTIMACION, escenas 4-5 con what-if y
  claims HIPOTESIS, y `visualPrompt` sin palabras prohibidas ni bloques de
  negativos. **Blandas** (avisos): palabras fuera de la tabla por rol, falta
  el ancla de estilo, URL no consultada, fuentes débiles, tema repetido,
  dominio sin tier, slug renombrado.
- **Nunca se sobrescribe** un borrador ni una historia: si el slug choca, el
  servidor lo renombra (`slug-2`, con los ids de escena re-prefijados) y lo
  avisa.
- **PUERTA 0**: REJECT exige nota (se le pasa a Claude si regenerás con
  `rejectedDraftId`). APPROVE aplica las ediciones, recalcula y revalida; con
  violaciones duras responde 422 y no crea nada. Si quedan claims
  `SIN_VERIFICAR` o de confianza `BAJA`, exige `acknowledgeUnverified: true`.
  La Story se arma con `StoryDraftingService.draftStory` (el mismo método de
  POST /stories) pero con las escenas revisadas en vez de volver a llamar a
  Claude, y solo lleva los campos del pipeline.

> **La verificación humana en la PUERTA 0 es obligatoria.** Que una claim
> diga `VERIFICADO` solo significa que su URL apareció en los resultados de
> búsqueda, no que la página diga lo que el modelo afirma. Abrí las fuentes
> de las escenas 1-3 y confirmá cifras, fechas y lugares antes de aprobar.

**Costo**: la búsqueda web cuesta **10 USD por cada 1.000 búsquedas**, más
los tokens normales (los resultados de búsqueda cuentan como tokens de
entrada). Un borrador usa como máximo 10 búsquedas (≈ 0,10 USD) más tokens,
y las propuestas como máximo 3. Cada borrador guarda su consumo real en
`usage` (`inputTokens`, `outputTokens`, `webSearchRequests`).

Variables de entorno nuevas (todas opcionales, con default):

```
CLAUDE_MODEL                              claude-sonnet-5-5 (también lo usa POST /stories)
CLAUDE_WEB_SEARCH_ENABLED                 true   (false: sin búsqueda; todas las fuentes quedan SIN_VERIFICAR)
CLAUDE_WEB_SEARCH_MAX_USES                10     (tope de búsquedas por borrador)
CLAUDE_WEB_SEARCH_MAX_USES_PROPOSALS      3      (tope de búsquedas por pedido de propuestas)
CLAUDE_WEB_SEARCH_TOOL_VERSION            web_search_20260318
CLAUDE_WEB_SEARCH_FALLBACK_TOOL_VERSION   web_search_20250305 (se usa con allowed_callers [direct] si el modelo rechaza la anterior)
CLAUDE_READ_TIMEOUT_SECONDS               180    (máximo sin recibir eventos del stream; no limita la duración total)
DRAFTS_DIR                                ./data/drafts (ignorado por git)
```

El resto (palabras por segundo, rangos, palabras prohibidas, listas de
dominios tier A/B) está en `app.drafts` de `application.yml`. Si la búsqueda
web está deshabilitada en tu organización de Claude, los endpoints responden
502 con un mensaje que lo dice.

## Montaje y subtítulos (manuales)

El pipeline termina en COMPLETED por escena. El montaje y los subtítulos se
hacen manualmente con los clips (generatedAssetUrl), el audio
(narrationAudioPath) y el texto (narrationText) de GET /stories/{id}, o con
el endpoint de video final (abajo), que está deshabilitado por defecto.

## Video final (`video/`, deshabilitado por defecto)

> **No exponer a internet sin autenticación.** La API no tiene auth y este
> endpoint lanza procesos pesados (Node, Chrome, FFmpeg) durante ~45 min
> con `check` (medido una vez en la VM; ~27 min sin `check`, estimado restando
> esa etapa). Por
> eso viene apagado: sin `FINAL_VIDEO_ENABLED=true` no existen ni el servicio
> ni las rutas (404).

`POST /stories/{id}/final-video` valida todo de forma síncrona y, si está
bien, responde **202** y lanza en segundo plano **un único** proceso:
`node video-renderer/src/make-video.mjs`, que transcribe cada escena
(Parakeet vía `hyperframes transcribe`), genera la composición
(`generate.mjs`), corre `hyperframes lint` (y `check` solo si
`FINAL_VIDEO_CHECK_ENABLED=true`, ver abajo), renderiza con
`hyperframes@0.8.143 render --workers 1 --quality standard` y valida el MP4
con `ffprobe` (720×1280, video y audio, duración = suma de `targetDuration`
± 0.25 s). Java no reimplementa nada de HyperFrames: solo valida, lanza el
proceso, lee `make-video-result.json` y publica el MP4 con un move atómico.

| Ruta | Respuesta |
|---|---|
| `POST /stories/{id}/final-video` (cuerpo opcional `{"force": true}`) | 202 `{jobId, storyId, status, stage}` + `Location`. 404 historia inexistente. 409 si ya hay un render en curso **de cualquier historia** (trae su `jobId`), si ya existe el video y no se pasó `force`, o si hay escenas sin terminar (falta clip local, falta audio, generación no COMPLETED, poco disco, Node no disponible). 422 por datos inválidos (ids fuera de `[A-Za-z0-9_-]`, clip que no pasa `Mp4Probe` o no es 720×1280). 409/422 traen `scenes: {idEscena: motivo}` y no lanzan nada. |
| `GET /stories/{id}/final-video` | El trabajo vigente: `status` (PENDING, RUNNING, COMPLETED, FAILED), `stage` (en curso o la que falló), `stages` (ms y código de salida de cada etapa; `check` omitido trae `skipped: true`), `warnings`, `error`, `logTail` (últimos 4 KB del log de la etapa fallida), `output`. 404 si nunca hubo uno. |
| `GET /stories/{id}/final-video/file` | El MP4 (`video/mp4`, admite `Range`). 404 si el trabajo vigente no está COMPLETED. |

Reglas:
- **Un render a la vez** en todo el backend; dos POST simultáneos no pueden
  lanzar dos (lo decide una guarda dentro de un `synchronized`).
- Los clips salen de `CLIPS_DIR` (`<idEscena>.mp4`, los que guarda
  `poll-generation`) y el audio de `AUDIO_DIR/<idEscena>.mp3`. **No** se usa
  `narrationAudioPath` (es una ruta absoluta de la máquina que sintetizó) y
  **no** se descarga nada de Higgsfield.
- Solo fallan los errores: los avisos de `generate`, `lint` y `check` se
  devuelven en `warnings`.
- El MP4 nuevo reemplaza al anterior recién cuando está completo y validado.
  Si un re-render con `force` falla, el video anterior sigue en disco
  (`RENDERS_DIR/<id>/final.mp4`), pero `GET .../file` responde 404 porque el
  trabajo vigente quedó FAILED.
- Al arrancar, un trabajo que había quedado PENDING/RUNNING se marca FAILED
  ("interrumpido por reinicio"). No hay reintentos automáticos ni cola.
- En disco: `RENDERS_DIR/<idHistoria>/job.json`, `final.mp4` y
  `job-<idTrabajo>/` (historia pasada a Node, `make-video.log` y la carpeta de
  trabajo con `logs/<etapa>.log`). Un trabajo nuevo borra las `job-*` anteriores.

Variables (todas con default, en `engine.final-video` de `application.yml`):
`FINAL_VIDEO_ENABLED` (false), `RENDERS_DIR` (`./data/renders`),
`VIDEO_RENDERER_DIR` (`./video-renderer`), `NODE_COMMAND` (`node`),
`FINAL_VIDEO_MIN_FREE_BYTES` (1 GiB, **suposición sin medir**),
`FINAL_VIDEO_KEEP_WORK` (false; true conserva assets para depurar),
`FINAL_VIDEO_CHECK_ENABLED` (false; ver "`check` deshabilitado" abajo) y los
timeouts en segundos `FINAL_VIDEO_TRANSCRIBE_TIMEOUT_SECONDS` (600, por
escena), `FINAL_VIDEO_GENERATE_TIMEOUT_SECONDS` (120),
`FINAL_VIDEO_LINT_TIMEOUT_SECONDS` (120), `FINAL_VIDEO_CHECK_TIMEOUT_SECONDS`
(2400) y `FINAL_VIDEO_RENDER_TIMEOUT_SECONDS` (1800). Los de `check` y
`render` se apoyan en **una sola** corrida completa en la VM (ver "Medir en la
VM" abajo): `check` tardó 1119.5 s (93 % del timeout anterior de 1200 s), así
que se subió a 2400 (~2.1× lo medido); `render` tardó 1382.7 s (77 % de 1800)
y se deja igual, pero si algún render supera ~1500 s hay que reconsiderarlo.
Con n = 1 son hipótesis a revalidar, no valores garantizados. El tope de todo
el proceso de Node es la suma de las etapas que corren más 5 min (con 6
escenas: 6240 s sin `check`, 8340 s con `check`).

**`check` deshabilitado por defecto.** En la VM `check` tardó 1119.5 s de
2722.9 s (41 %, n = 1) y no encontró nada. La composición sale siempre de la
misma plantilla (`generate.mjs`, con pruebas golden), así que lo que `check`
valida en layout y motion depende de la plantilla, no de cada historia. Con
`FINAL_VIDEO_CHECK_ENABLED=false` Node corre con `--skip-check` y `check`
queda en `stages` como `{"name":"check","ms":0,"skipped":true}`. `lint` y la
validación del MP4 con `ffprobe` siguen corriendo siempre. Lo que se pierde:
- **Contraste WCAG por historia**: subtítulos sobre un clip claro. Ya era solo
  un aviso (nunca hacía fallar el trabajo).
- `clip_media_fit` y errores de runtime (imagen rota, HTTP) antes del render:
  sin `check` aparecerían como un render fallido o malo, más tarde.
- La red de seguridad ante cambios de plantilla: **correr `check` en
  desarrollo** (`npx hyperframes@0.8.143 check` en la carpeta generada, ~47 s
  en Windows) cada vez que cambien la plantilla o `generate.mjs`, o poner
  `FINAL_VIDEO_CHECK_ENABLED=true` para una corrida puntual en la VM.

Requisitos del servidor: Node ≥ 22, FFmpeg (`ffprobe` en el PATH), Chrome
Headless Shell para HyperFrames y acceso a `registry.npmjs.org` y, la primera
vez, a `huggingface.co` (descarga del modelo Parakeet).

**Prerrequisito de despliegue: el modelo de transcripción.** Sin él, el
trabajo falla en `transcribe` con `whisper_unavailable` (en ~6 s). El remedio
que imprime la propia CLI es instalarlo una vez, desde `video-renderer/`, con
el **mismo usuario** que corre el backend y **sin sudo** (necesita salida a
internet la primera vez):

```bash
cd video-renderer
npx hyperframes@0.8.143 models install parakeet
```

```bash
FINAL_VIDEO_ENABLED=true ... mvn spring-boot:run

curl -i -X POST localhost:8080/stories/tunguska/final-video          # 202 + jobId
curl -s localhost:8080/stories/tunguska/final-video                  # estado, etapa, tiempos
curl -o tunguska.mp4 localhost:8080/stories/tunguska/final-video/file
curl -i -X POST -H 'Content-Type: application/json' -d '{"force":true}' \
     localhost:8080/stories/tunguska/final-video                     # rehacerlo
```

### Medir en la VM con el backend levantado

Primera corrida completa del endpoint en `ai-shorts-vm` (e2-small: 2 vCPU,
1964 MB, sin swap), **con la JVM del backend encendida**, historia
`explosion-halifax` (6 escenas, video final de 39.2 s). Trabajo COMPLETED.
**n = 1**: sirve para dimensionar, no para concluir.

| Etapa | Duración | Timeout entonces | Uso |
|---|---|---|---|
| transcribe (6 escenas) | 206.6 s (~34 s/escena) | 600 s/escena | ~6 % |
| generate | 6.1 s | 120 s | 5 % |
| lint | 6.6 s | 120 s | 5 % |
| check | 1119.5 s | 1200 s | 93 % |
| render | 1382.7 s | 1800 s | 77 % |
| validate | 0.1 s | — | — |

- Total: 2722.9 s (45.4 min); `check` es el 41 %. Se suponía que `check`
  escalaría como el render (~17× Windows, ~800 s); fue ~24× los 47 s de
  Windows (otra historia, tunguska).
- `check` no emitió avisos en esta corrida (sí `generate`: 4 palabras con
  tiempo inferido, y `lint`: `composition_file_too_large`, 1201 líneas). Con
  n = 1 no se puede concluir que `check` nunca encuentre nada.
- Memoria (monitor externo, no la mide el código): pico 1625 MB usados /
  mínimo 180 MB disponibles durante `render`; durante `check`, pico 1337 MB /
  mínimo 465 MB.
- Antes de esto solo se había medido el render suelto (1014 s para tunguska,
  419 MB mínimos disponibles).

Procedimiento para repetir la medición:

1. Levantar el backend con `FINAL_VIDEO_ENABLED=true` y una historia con los
   6 clips en `CLIPS_DIR` y los audios en `AUDIO_DIR`.
2. En otra terminal, muestrear la memoria durante todo el trabajo:
   `while true; do echo "$(date +%T) $(free -m | awk '/Mem:/ {print $7}')"; sleep 5; done | tee memoria.log`
   (columna "available", en MB).
3. `curl -X POST .../final-video` y esperar a que `GET .../final-video`
   quede COMPLETED o FAILED. Los tiempos por etapa (y por escena en
   `transcribe`) ya quedan en `stages` del estado.
4. Anotar el mínimo de `memoria.log`, los `stages` y `df -h` antes y después;
   con eso ajustar los timeouts y `FINAL_VIDEO_MIN_FREE_BYTES`.

## Persistencia (Fase 1 — sin nube, sin base de datos)

`Story`/`Scene` ya no viven solo en memoria: cualquier cosa que las orqueste
(la API REST, un script) puede guardarlas y volver a leerlas en una
ejecución futura.

- `domain/SceneSnapshot.java` y `domain/StorySnapshot.java`: una "foto"
  inmutable de TODO el estado de una escena/historia (estados de
  aprobación, costo, generación, rutas de archivos, notas de rechazo,
  timestamps implícitos en cada status). `Scene.toSnapshot()`/
  `Scene.fromSnapshot(...)` y `Story.toSnapshot()`/`Story.fromSnapshot(...)`
  convierten en los dos sentidos. La reconstrucción NO pasa por
  `approvePrompt`/`recordCostEstimate`/etc. — esos guards existen para
  hacer cumplir el orden de las dos puertas humanas durante una ejecución
  en vivo, no tienen sentido al releer un estado que ya fue válido antes.
- `persistence/StoryRepository.java`: la interfaz (`save`/`findById`/
  `findAll`) — ni `StoryApprovalService` ni `StoryDraftingService` saben que
  esto existe; guardar es responsabilidad de quien orquesta el flujo.
- `persistence/JsonFileStoryRepository.java`: la implementación de hoy — un
  archivo `.json` por historia (`dataDir/{id}.json`), escrito con Jackson
  (`ObjectMapper`, inyectado como bean de Spring) y con escritura atómica
  (archivo temporal + move) para que un corte a mitad de escritura nunca
  deje un archivo corrupto.

**Por qué esto y no una base de datos real todavía**: ver la charla sobre
GCP — para el volumen de un piloto (una historia a la vez, unas pocas por
semana) un archivo por historia alcanza y sobra, y esto corre hoy mismo sin
instalar ni configurar nada, sin cuenta de nube ni tarjeta de por medio.
Cuando el volumen lo justifique, `StoryRepository` es la interfaz que aísla
el cambio: la única clase a reemplazar es `JsonFileStoryRepository`, por
una implementación con Spring Data JPA (SQLite/Postgres) — agregar
`spring-data-jpa` ya no tiene ninguna barrera de red.

Se probó de punta a punta, no solo compilando: `StoryApiPersistenceRestartTest`
(paquete `demo/`, en `src/test`) crea una historia, la lleva a través de las
dos puertas y la generación completa vía la API REST real (`@SpringBootTest`,
`TestRestTemplate`, con los `Fake*` inyectados en vez de los clientes reales
— ver "Tests" abajo), abre una instancia NUEVA de `JsonFileStoryRepository`
apuntando al mismo directorio (simulando que el proceso se reinició) y
confirma que la historia se relee con exactamente el mismo estado — las 6
escenas generadas, con su audio y su asset de Higgsfield.

## API REST (`api/`)

`StoryApprovalService` (y la creación de historias vía `StoryDraftingService`)
ya se puede operar por HTTP, para el día en que el flujo de aprobación se
maneje desde una UI en vez de código Java directo. `StoryController` es un
`@RestController` de Spring MVC sobre Tomcat embebido.

Cada endpoint POST es **exactamente un paso** del pipeline, nunca más de
uno: la regla de "nunca encadenar automáticamente propuesta → generación"
se traduce acá en que no existe ningún endpoint que dispare más de un paso
por sí solo. Quien opera la UI decide cuándo llamar al siguiente.

```
POST   /stories                         crear (StoryBrief -> StoryDraftingService)
GET    /stories                         listar todas: {id, title, topic, progress} (un JSON corrupto aparece como {id, error})
GET    /stories/{id}                    ver el estado completo de una
POST   /stories/{id}/tiers              proposeTiersForReview
POST   /stories/{id}/prompt-decisions   applyPromptDecisions   (PUERTA 1)
POST   /stories/{id}/narration          synthesizeNarrationForApprovedScenes
POST   /stories/{id}/cost-estimates     estimateCostsForApprovedScenes
POST   /stories/{id}/cost-decisions     applyCostDecisions     (PUERTA 2)
POST   /stories/{id}/generate           generateApprovedScenes
POST   /stories/{id}/poll-generation    pollGenerationStatus
POST   /stories/{id}/reset-generation   resetAllScenesForRegeneration / resetScenesForRegeneration
```

El endpoint `poll-generation` tapa un hueco real que encontré armando esto:
`generateApprovedScenes` solo dispara la generación en Higgsfield y deja la
escena en `QUEUED` (es asincrónico) — no existía nada que después
preguntara "¿ya terminó?". Agregué `StoryApprovalService.pollGenerationStatus`
para eso: hay que llamarlo seguido (polling) hasta que la historia esté
`GENERATED`.

**Copia local de los clips.** Cuando `poll-generation` deja *todas* las
escenas `COMPLETED`, el controlador además descarga cada clip desde
`generatedAssetUrl` a `CLIPS_DIR/<idEscena>.mp4` (`./data/clips` por
defecto) y guarda la ruta relativa en `localClipPath` de la escena. La
respuesta trae un campo `clips` con lo guardado, lo que ya estaba y lo que
falló. Un fallo (URL vencida, archivo que no es MP4, tamaño distinto de
720x1280, poco disco) **no bloquea el poll**: la escena sigue `COMPLETED`,
con su URL y un `localClipFailureReason`, y se reintenta volviendo a llamar
a `poll-generation`. Cada clip se valida (estructura MP4, dimensiones) antes
de publicarse con un move atómico. `reset-generation` borra el clip local de
las escenas reseteadas. En `application.yml`, `clip-download.*` fija el tope
por clip, el timeout total y el disco libre mínimo.

Cada handler sigue siempre el mismo patrón: leer la `Story` guardada →
llamar un único método de `StoryApprovalService` → guardar el resultado →
devolver el estado actualizado. `StoryController` es routing puro;
`RequestParsing` convierte el JSON del body (deserializado por Jackson) en
los records del dominio (`PromptDecision`, `CostDecision`,
`DifficultyFactors`, `StoryBrief`) con errores 400 claros si falta un campo
— `ApiExceptionHandler` (`@RestControllerAdvice`) traduce esos `ApiError` (y
cualquier otra excepción) a la respuesta HTTP correspondiente.

### Correrla

`EngineConfiguration` arma los beans con clientes **reales** (a diferencia
de `DemoRunner`, que usa los `Fake*` a propósito) — llamar a los endpoints
de narración/costos/generación ahí sí gasta plata, porque para eso está.
Variables de entorno requeridas:

```
CLAUDE_API_KEY
HIGGSFIELD_BASE_URL, HIGGSFIELD_API_KEY_ID, HIGGSFIELD_API_KEY_SECRET
HIGGSFIELD_MODEL_STANDARD, HIGGSFIELD_MODEL_PREMIUM
ELEVENLABS_API_KEY, ELEVENLABS_VOICE_ID
```

Opcionales (con default): `API_PORT` (8080, mapeado a `server.port` en
`application.yml`), `DATA_DIR` (`./data/stories`), `AUDIO_DIR`
(`./data/audio`), `CLIPS_DIR` (`./data/clips`), `CLIP_MAX_BYTES` (64 MiB),
`CLIP_DOWNLOAD_TIMEOUT_SECONDS` (120), `CLIP_MIN_FREE_BYTES` (512 MiB),
`CLAUDE_MODEL` (`claude-sonnet-5-5`), las del video final (ver "Video
final" arriba; deshabilitado por defecto) y las del generador
de historias (ver "Generador de historias" arriba). Esto resuelve, de la forma más simple posible, el
pendiente de "manejo de credenciales" — variables de entorno alcanzan para
un piloto de una persona; un secret manager sería sobre-ingeniería hoy.

El modelo del piloto ya está confirmado contra la consola de Higgsfield y su
tarifa cargada en `KnownHiggsfieldPricing` — exportar
`HIGGSFIELD_MODEL_STANDARD` y `HIGGSFIELD_MODEL_PREMIUM` con este valor
exacto (tienen que coincidir con la clave de `KnownHiggsfieldPricing`
para que `estimateCost` le encuentre tarifa). STANDARD y PREMIUM apuntan
hoy al mismo modelo — Seedance 2.5 no aporta nada sobre 2.0 a 720p y es más
caro, así que se unificaron ambos tiers — el concepto de tier en el dominio
(puerta 1, `Scene.chosenTier`) sigue existiendo igual:

```
HIGGSFIELD_MODEL_STANDARD=bytedance/seedance-2.0/text-to-video   # Seedance 2.0, $0.35/s (720p, placeholder sin confirmar), duration ∈ [4,15]
HIGGSFIELD_MODEL_PREMIUM=bytedance/seedance-2.0/text-to-video    # mismo modelo que STANDARD (ver KnownHiggsfieldPricing)
```

```bash
CLAUDE_API_KEY=... CLAUDE_MODEL=... \
HIGGSFIELD_BASE_URL=... HIGGSFIELD_API_KEY_ID=... HIGGSFIELD_API_KEY_SECRET=... \
HIGGSFIELD_MODEL_STANDARD=... HIGGSFIELD_MODEL_PREMIUM=... \
ELEVENLABS_API_KEY=... ELEVENLABS_VOICE_ID=... \
mvn spring-boot:run
```

Se probó de punta a punta contra los mismos `Fake*` del demo (sin red real,
sin gastar) pero con persistencia en disco de verdad — ver
`StoryApiPersistenceRestartTest` en "Persistencia" arriba y "Tests" abajo.

## Tests

```bash
mvn test
```

- `domain/SceneTest.java`, `domain/StoryTest.java`: los guards de las dos
  puertas humanas (`IllegalStateException` fuera de orden), el camino feliz
  completo con snapshot/restore, y `Story.totalTargetDuration()`/
  `isWithinDurationBudget()`/`status()`.
- `higgsfield/HiggsfieldRestClientTest.java`: las tres correcciones contra
  el spec real de Higgsfield (estimateCost local sin red, `video.url`
  anidado, los 6 status reales incluido nsfw/canceled → FAILED) y el fix de
  `"sound": "off"` — contra un `HttpServer` local real, no mocks.
- `demo/StoryApiPersistenceRestartTest.java`: la API REST completa de punta
  a punta vía HTTP real (`@SpringBootTest`), con los `Fake*` inyectados por
  `@Primary` en vez de los clientes reales, más el reinicio de persistencia
  simulado (ver "Persistencia" arriba).
- `drafts/`: el generador de historias, con **cero red**. `DraftValidatorTest`
  tiene un test por cada regla dura y blanda (sobre un borrador "de oro" que
  las cumple todas); `ClaudeStoryDraftGeneratorTest` prueba `pause_turn`,
  citas, uso, el 400 de "web search no habilitada" y el fallback de versión
  contra un `HttpServer` local; los `*ApiTest` recorren la API con un
  `FakeStoryDraftGenerator` (`@Primary`) y Higgsfield, ElevenLabs y el
  guionado de POST /stories como `@MockBean`, verificando con
  `verifyNoInteractions` que el flujo nuevo nunca los toca.
- `video/`: el video final. `FinalVideoApiTest` recorre los endpoints por
  HTTP real con un `FakeProcessRunner` (sin Node): flujo feliz, fallo y
  timeout del proceso, cada precondición por separado, ids maliciosos, 409
  por video existente y por render en curso, `force`, dos POST simultáneos y
  la recuperación tras un reinicio. `FinalVideoDisabledApiTest` comprueba
  que por defecto no existen las rutas. `SystemProcessRunnerTest` usa la
  propia JVM como subproceso (código de salida, log, sin stdin y timeout que
  mata también al hijo). `FinalVideoNodeContractTest` corre el
  `make-video.mjs` real con la CLI de HyperFrames simulada (se omite si no
  hay `node` en el PATH).

## Pendiente / próximos pasos

- **Voz elegida**: `TtsConfig.voiceId` no tiene default — hay que elegir una
  voz en español neutro de la librería de ElevenLabs y poner su id ahí.
  `NarrationDurationEstimator` (estimación por palabras) sigue existiendo
  para el borrador inicial, antes de la puerta 1 — recién después de
  aprobar el prompt se sintetiza audio real y la duración se refina.
- Confirmar el identificador de modelo de Claude vigente en `ClaudeConfig`
  contra docs.claude.com antes de desplegar.
- Definir aspect_ratio/resolution reales si van a variar por escena o por
  historia (hoy están hardcodeados a 9:16 en `buildGenerationParameters`).
- La API REST no tiene autenticación ni CORS todavía — vale para vos
  operándola local o desde una UI de confianza, no para exponerla en
  internet tal cual.
- Si el volumen crece, mover `JsonFileStoryRepository` a SQLite/Postgres, y
  recién ahí evaluar Cloud Storage para los assets (ver la charla sobre
  costos de GCP: en la fase de validación no hace falta).
