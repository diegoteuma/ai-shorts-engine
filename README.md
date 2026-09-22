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
Higgsfield falsos, sin llamar a ninguna red real, y al final escribe
`tunguska-demo.es.srt` y `tunguska-demo.en.srt`. No pasa por el contexto de
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
│                   Messages API de Claude, compartido por script/ y
│                   captions/ (los dos únicos consumidores de texto-LLM del
│                   proyecto). ClaudeConfig guarda apiKey/model/baseUrl.
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
│                   del prompt; Kling 3.0 Standard (PREMIUM) además necesita
│                   "sound": "off" o factura audio que después se descarta.
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
├── captions/       CaptionTranslationService: traduce las 6 narraciones
│                   (español) a inglés en una sola llamada, para la pista de
│                   subtítulos en inglés — el audio del video sigue siendo
│                   español, esto es solo texto para leer.
│                   ClaudeCaptionTranslationService es la implementación real.
│
├── subtitles/      StorySubtitleBuilder arma las dos pistas .srt (español e
│                   inglés) con EXACTAMENTE el mismo timing por escena, para
│                   que queden sincronizadas con el mismo video. SrtGenerator
│                   es el formateador de bajo nivel (timing -> texto .srt).
│
├── montage/        VideoMontageBuilder arma el video final: baja los clips
│                   de Higgsfield (AssetDownloader), le pone a cada uno el
│                   audio de narración de su escena, concatena las 6 en
│                   orden y quema encima la pista .srt en español (escala y
│                   rellena a 1080x1920). Tres pasos separados a propósito
│                   (mux por escena -> concat -> quemado), cada uno con su
│                   archivo intermedio, para poder diagnosticar cuál falló.
│                   FfmpegRunner invoca el binario vía ProcessBuilder.
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
                    CaptionTranslationService): corren el flujo completo del
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

## Decisión de subtítulos (ya tomada)

Quemados en español (coincide con el audio narrado, así que solo hace falta
renderizar una vez) + pistas `.srt` en español e inglés subidas aparte
(accesibilidad, SEO, alcance en inglés). Motivo: YouTube confirmó que su
auto-activación de subtítulos al mutear **no aplica a Shorts**, así que una
pista `.srt` sin quemar no la ve nadie que scrollee mudo — que es la mayoría
en formato corto vertical. `StorySubtitleBuilder` arma ambas pistas
sincronizadas y `VideoMontageBuilder` (ver abajo) hace el quemado en sí.

## Montaje/quemado (ya implementado, con ffmpeg)

`VideoMontageBuilder` (paquete `montage/`) arma el video final en 3 pasos,
cada uno con su archivo intermedio en `workDir` para poder inspeccionar cuál
falló si algo sale mal:

1. **Por escena**: baja el clip generado (`AssetDownloader`, HTTP real vía
   `java.net.http.HttpClient`) y le pega el audio de narración de esa escena
   (`-map 0:v:0 -map 1:a:0 -c:a aac -shortest`).
2. **Concat**: une las 6 escenas en orden narrativo con el demuxer `concat`
   de ffmpeg.
3. **Quemado**: escala/rellena a 1080x1920 (`scale=...force_original_aspect_ratio=decrease,pad=...`)
   y quema la pista `.srt` en español encima (`subtitles=...`, requiere
   `libass` — confirmado que el ffmpeg de este sandbox lo tiene compilado).

Esto se probó de verdad, no solo compilando: `VideoMontageBuilderFfmpegTest`
(paquete `montage/`, en `src/test`) genera clips y audios sintéticos con
`ffmpeg -f lavfi` (colores sólidos + tonos senoidales), los sirve por un
servidor HTTP local para ejercitar `AssetDownloader` de punta a punta, y
corre `VideoMontageBuilder.build(...)` real contra `Scene`/`Story` reales —
no un script de shell aparte. Confirma que sale un .mp4 de 1080x1920 (vía
`ffprobe`) y cubre específicamente el caso de la comilla simple de más
abajo. Necesita ffmpeg/ffprobe instalados, así que está deshabilitado por
default — correr con `RUN_FFMPEG_TESTS=true mvn test` (ver "Tests" abajo).

Un hallazgo concreto de esa prueba: el filtro `subtitles=` de ffmpeg
re-parsea su propio argumento `filename` con una segunda pasada de escapeo,
y **una comilla simple literal en la ruta no sobrevive ninguna combinación
de backslash-escaping** (desaparece silenciosamente y ffmpeg termina
buscando un archivo que no existe) — los dos puntos y los espacios sí se
escapan bien. En vez de perseguir un escapeo perfecto para una ruta
arbitraria, `VideoMontageBuilder` elimina el problema de raíz: antes de
quemar, copia el `.srt` a un nombre fijo y controlado (`captions.srt`)
dentro de `workDir`, así el argumento del filtro nunca depende de un
nombre de archivo externo.

No está enchufado a `DemoRunner`: los `Fake*` de esa demo (Higgsfield, TTS)
devuelven bytes de relleno no reproducibles a propósito, para poder probar
el flujo de aprobaciones sin gastar ni depender de red real — correr
ffmpeg de verdad contra esos bytes fallaría siempre, sin decir nada sobre
si el montaje en sí funciona. Por eso el montaje se probó por separado,
contra medios sintéticos pero *reales* (reproducibles por ffmpeg).

Deliberadamente fuera de esta primera versión (no olvidado): transiciones
tipo crossfade entre escenas (`xfade`) y overlays de texto puntuales (ej.
la etiqueta "HIPÓTESIS" de la escena de GIRO) — agregan filtergraphs más
complejos que conviene verificar en una segunda vuelta.

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
GET    /stories                         listar todas
GET    /stories/{id}                    ver el estado completo de una
POST   /stories/{id}/tiers              proposeTiersForReview
POST   /stories/{id}/prompt-decisions   applyPromptDecisions   (PUERTA 1)
POST   /stories/{id}/narration          synthesizeNarrationForApprovedScenes
POST   /stories/{id}/cost-estimates     estimateCostsForApprovedScenes
POST   /stories/{id}/cost-decisions     applyCostDecisions     (PUERTA 2)
POST   /stories/{id}/generate           generateApprovedScenes
POST   /stories/{id}/poll-generation    pollGenerationStatus
```

El último endpoint tapa un hueco real que encontré armando esto:
`generateApprovedScenes` solo dispara la generación en Higgsfield y deja la
escena en `QUEUED` (es asincrónico) — no existía nada que después
preguntara "¿ya terminó?". Agregué `StoryApprovalService.pollGenerationStatus`
para eso: hay que llamarlo seguido (polling) hasta que la historia esté
`GENERATED`.

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
CLAUDE_API_KEY, CLAUDE_MODEL
HIGGSFIELD_BASE_URL, HIGGSFIELD_API_KEY_ID, HIGGSFIELD_API_KEY_SECRET
HIGGSFIELD_MODEL_STANDARD, HIGGSFIELD_MODEL_PREMIUM
ELEVENLABS_API_KEY, ELEVENLABS_VOICE_ID
```

Opcionales (con default): `API_PORT` (8080, mapeado a `server.port` en
`application.yml`), `DATA_DIR` (`./data/stories`), `AUDIO_DIR`
(`./data/audio`). Esto resuelve, de la forma más simple posible, el
pendiente de "manejo de credenciales" — variables de entorno alcanzan para
un piloto de una persona; un secret manager sería sobre-ingeniería hoy.

Los dos modelos del piloto ya están confirmados contra el spec real de
Higgsfield y sus tarifas cargadas en `KnownHiggsfieldPricing` — exportar
`HIGGSFIELD_MODEL_STANDARD` y `HIGGSFIELD_MODEL_PREMIUM` con estos valores
exactos (tienen que coincidir con las claves de `KnownHiggsfieldPricing`
para que `estimateCost` les encuentre tarifa):

```
HIGGSFIELD_MODEL_STANDARD=kling-video/v2.5-turbo/pro/text-to-video   # Kling 2.5 Turbo Pro, $0.021/s, duration ∈ {5,10}
HIGGSFIELD_MODEL_PREMIUM=kling-video/v3.0/std/text-to-video          # Kling 3.0 Standard, $0.0714/s, duration ∈ [3,15]
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
mvn test                       # todo salvo el test de ffmpeg (ver abajo)
RUN_FFMPEG_TESTS=true mvn test # suite completa, necesita ffmpeg/ffprobe instalados
```

- `domain/SceneTest.java`, `domain/StoryTest.java`: los guards de las dos
  puertas humanas (`IllegalStateException` fuera de orden), el camino feliz
  completo con snapshot/restore, y `Story.totalTargetDuration()`/
  `isWithinDurationBudget()`/`status()`.
- `higgsfield/HiggsfieldRestClientTest.java`: las tres correcciones contra
  el spec real de Higgsfield (estimateCost local sin red, `video.url`
  anidado, los 6 status reales incluido nsfw/canceled → FAILED) y el fix de
  `"sound": "off"` — contra un `HttpServer` local real, no mocks.
- `montage/VideoMontageBuilderFfmpegTest.java`: ffmpeg real contra medios
  sintéticos (ver "Montaje/quemado" arriba). Deshabilitado por default
  (`@EnabledIfEnvironmentVariable`) porque necesita ffmpeg/ffprobe instalados.
- `demo/StoryApiPersistenceRestartTest.java`: la API REST completa de punta
  a punta vía HTTP real (`@SpringBootTest`), con los `Fake*` inyectados por
  `@Primary` en vez de los clientes reales, más el reinicio de persistencia
  simulado (ver "Persistencia" arriba).

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
