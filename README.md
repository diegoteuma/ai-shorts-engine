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
│                   Messages API de Claude, usado por script/ (el único
│                   consumidor de texto-LLM del proyecto). ClaudeConfig
│                   guarda apiKey/model/baseUrl.
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

## Montaje y subtítulos (manuales)

El pipeline termina en COMPLETED por escena. El montaje y los subtítulos se
hacen manualmente con los clips (generatedAssetUrl), el audio
(narrationAudioPath) y el texto (narrationText) de GET /stories/{id}.

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
POST   /stories/{id}/reset-generation   resetAllScenesForRegeneration / resetScenesForRegeneration
```

El endpoint `poll-generation` tapa un hueco real que encontré armando esto:
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
