package com.aishorts.engine.video;

import com.aishorts.engine.clips.ClipException;
import com.aishorts.engine.clips.ClipStore;
import com.aishorts.engine.clips.Mp4Probe;
import com.aishorts.engine.domain.GenerationStatus;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.persistence.SnapshotMapper;
import com.aishorts.engine.persistence.StoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Video final de una historia: valida precondiciones, lanza
 * {@code node video-renderer/src/make-video.mjs} en segundo plano y publica
 * el MP4 resultante.
 *
 * <ul>
 *   <li><b>Un render a la vez en todo el backend</b> (no por historia): la
 *       guarda {@code active} se decide dentro de {@code lock}, así dos POST
 *       simultáneos no pueden lanzar dos renders. El trabajo corre en el único
 *       hilo de {@code executor}.</li>
 *   <li>Las precondiciones son síncronas (antes del 202) y nunca lanzan nada
 *       si fallan: 409 para estado, 422 para datos, con la lista de escenas.</li>
 *   <li>El MP4 nuevo reemplaza al anterior con un move atómico y solo cuando
 *       está completo y validado: un render fallido no borra el video previo.</li>
 *   <li>Todos los ids se usan para armar rutas: se exige [A-Za-z0-9_-]+ y
 *       además que la ruta resuelta quede dentro de su carpeta.</li>
 * </ul>
 *
 * Disposición en disco (RENDERS_DIR/{idHistoria}/):
 * <pre>
 * job.json                  el trabajo vigente
 * final.mp4                 el último video completo
 * job-{idTrabajo}/story.json la historia tal como se le pasó a Node (SnapshotMapper)
 * job-{idTrabajo}/make-video.log  stdout/stderr del proceso de Node
 * job-{idTrabajo}/work/     carpeta de trabajo de make-video (logs por etapa, make-video-result.json)
 * </pre>
 * Al empezar un trabajo se borran las carpetas job-* anteriores de esa historia.
 */
public final class FinalVideoService {

    private static final Logger log = LoggerFactory.getLogger(FinalVideoService.class);
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9_-]+");
    static final String JOB_FILE = "job.json";
    static final String FINAL_FILE = "final.mp4";
    static final String RESULT_FILE = "make-video-result.json";
    private static final int LOG_TAIL_BYTES = 4096;
    private static final Duration NODE_CHECK_TIMEOUT = Duration.ofSeconds(15);

    private final FinalVideoProperties props;
    private final StoryRepository repository;
    private final ClipStore clipStore;
    private final Path audioDir;
    private final ProcessRunner runner;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final int expectedWidth;
    private final int expectedHeight;
    private final Path rendersDir;
    private final Path rendererDir;

    private final Object lock = new Object();
    private FinalVideoJob active; // protegido por lock

    public FinalVideoService(FinalVideoProperties props, StoryRepository repository, ClipStore clipStore, Path audioDir,
                             ProcessRunner runner, ObjectMapper mapper, ExecutorService executor,
                             int expectedWidth, int expectedHeight) {
        this.props = props;
        this.repository = repository;
        this.clipStore = clipStore;
        this.audioDir = audioDir.toAbsolutePath().normalize();
        this.runner = runner;
        this.mapper = mapper;
        this.executor = executor;
        this.expectedWidth = expectedWidth;
        this.expectedHeight = expectedHeight;
        this.rendersDir = Path.of(props.rendersDir()).toAbsolutePath().normalize();
        this.rendererDir = Path.of(props.rendererDir()).toAbsolutePath().normalize();
    }

    // --- API del servicio ---------------------------------------------------------------------

    /** Valida y encola el render. Devuelve el trabajo PENDING; el resto pasa en segundo plano. */
    public FinalVideoJob start(String storyId, boolean force) {
        if (!SAFE_ID.matcher(storyId).matches()) {
            // Antes de ir al repositorio: este id termina en una ruta bajo RENDERS_DIR.
            throw new FinalVideoException(422, "Id de historia no permitido: '" + storyId + "' (solo letras, números, '_' y '-').");
        }
        Story story = repository.findById(storyId)
                .orElseThrow(() -> new FinalVideoException(404, "No existe una historia con id '" + storyId + "'."));
        requireSafeIds(story);

        synchronized (lock) {
            if (active != null) {
                throw new FinalVideoException(409, "Ya hay un render en curso (historia '" + active.storyId()
                        + "', trabajo " + active.id() + "). Se hace uno a la vez en todo el servidor.", Map.of(), active.id());
            }
            Path storyDir = storyDir(storyId);
            if (!force && Files.isRegularFile(storyDir.resolve(FINAL_FILE))) {
                throw new FinalVideoException(409, "La historia '" + storyId
                        + "' ya tiene un video final. Mandar {\"force\": true} para reemplazarlo.");
            }
            checkPreconditions(story);

            FinalVideoJob job = FinalVideoJob.pending(UUID.randomUUID().toString(), storyId, force);
            Path jobDir = storyDir.resolve("job-" + job.id());
            try {
                deletePreviousJobDirs(storyDir);
                Files.createDirectories(jobDir);
                Files.writeString(jobDir.resolve("story.json"),
                        mapper.writeValueAsString(SnapshotMapper.toMap(story.toSnapshot())), StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new FinalVideoException(500, "No se pudo preparar la carpeta del render: " + e.getMessage());
            }
            save(job);
            active = job;
            int sceneCount = story.scenes().size();
            try {
                executor.submit(() -> run(job, jobDir, sceneCount));
            } catch (RejectedExecutionException e) {
                active = null;
                save(job.failed(null, "No se pudo encolar el render (el servidor se está apagando).", null));
                throw new FinalVideoException(503, "No se pudo encolar el render (el servidor se está apagando).");
            }
            return job;
        }
    }

    /** El trabajo vigente; si está corriendo, con la etapa y los tiempos que lleva make-video. */
    public Optional<FinalVideoJob> current(String storyId) {
        if (!SAFE_ID.matcher(storyId).matches()) {
            return Optional.empty();
        }
        Optional<FinalVideoJob> job = load(storyDir(storyId).resolve(JOB_FILE));
        if (job.isPresent() && job.get().status() == FinalVideoStatus.RUNNING) {
            Path work = storyDir(storyId).resolve("job-" + job.get().id()).resolve("work");
            Map<String, Object> result = readResult(work);
            if (result != null) {
                return Optional.of(job.get().withProgress(str(result.get("stage")), stagesOf(result), warningsOf(result)));
            }
        }
        return job;
    }

    /** El MP4 si el trabajo vigente está COMPLETED. */
    public Optional<Path> finalVideo(String storyId) {
        return current(storyId)
                .filter(j -> j.status() == FinalVideoStatus.COMPLETED)
                .map(j -> storyDir(storyId).resolve(FINAL_FILE))
                .filter(Files::isRegularFile);
    }

    /** Al arrancar: un trabajo PENDING/RUNNING guardado quedó cortado por el reinicio. */
    public void recoverInterruptedJobs() {
        if (!Files.isDirectory(rendersDir)) {
            return;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(rendersDir, Files::isDirectory)) {
            for (Path dir : dirs) {
                Optional<FinalVideoJob> job = load(dir.resolve(JOB_FILE));
                if (job.isPresent() && job.get().isActive()) {
                    log.warn("Trabajo de video final {} de '{}' quedó {}: se marca FAILED.", job.get().id(), job.get().storyId(), job.get().status());
                    save(job.get().failed(job.get().stage(), "Interrumpido por reinicio del servidor.", null));
                }
            }
        } catch (IOException e) {
            log.warn("No se pudieron revisar los trabajos de video final en '{}': {}", rendersDir, e.getMessage());
        }
    }

    // --- precondiciones -----------------------------------------------------------------------

    private void requireSafeIds(Story story) {
        Map<String, String> problems = new LinkedHashMap<>();
        if (!SAFE_ID.matcher(story.id()).matches()) {
            throw new FinalVideoException(422, "El id de historia '" + story.id()
                    + "' tiene caracteres no permitidos (solo letras, números, '_' y '-').");
        }
        for (Scene scene : story.scenes()) {
            if (scene.id() == null || !SAFE_ID.matcher(scene.id()).matches()) {
                problems.put(String.valueOf(scene.id()), "id con caracteres no permitidos (solo letras, números, '_' y '-')");
            }
        }
        if (!problems.isEmpty()) {
            throw new FinalVideoException(422, "Hay escenas con ids no permitidos.", problems, null);
        }
    }

    /** Junta TODOS los problemas antes de responder: 422 si hay alguno de datos, si no 409. */
    private void checkPreconditions(Story story) {
        Map<String, String> stateProblems = new LinkedHashMap<>();
        Map<String, String> dataProblems = new LinkedHashMap<>();
        if (story.scenes().isEmpty()) {
            throw new FinalVideoException(422, "La historia no tiene escenas.");
        }
        for (Scene scene : story.scenes()) {
            String id = scene.id();
            if (scene.targetDuration() == null || scene.targetDuration().isZero() || scene.targetDuration().isNegative()) {
                dataProblems.put(id, "sin targetDuration positiva");
                continue;
            }
            if (scene.generationStatus() != GenerationStatus.COMPLETED) {
                stateProblems.put(id, "la generación está " + scene.generationStatus() + ", no COMPLETED");
                continue;
            }
            if (!scene.hasLocalClip()) {
                stateProblems.put(id, "sin copia local del clip (volver a llamar a poll-generation)");
                continue;
            }
            // make-video busca <CLIPS_DIR>/<id>.mp4: la ruta registrada tiene que ser exactamente esa.
            Path clip;
            try {
                clip = clipStore.resolve(scene.localClipPath());
            } catch (ClipException e) {
                dataProblems.put(id, e.getMessage());
                continue;
            }
            Path expected = clipStore.resolve(id + ".mp4");
            if (!clip.equals(expected)) {
                dataProblems.put(id, "localClipPath '" + scene.localClipPath() + "' no es '" + id + ".mp4'");
                continue;
            }
            if (!Files.isRegularFile(clip)) {
                stateProblems.put(id, "falta el archivo del clip " + clip.getFileName() + " (volver a llamar a poll-generation)");
                continue;
            }
            try {
                Mp4Probe.Info info = Mp4Probe.probe(clip);
                if (info.width() != expectedWidth || info.height() != expectedHeight) {
                    dataProblems.put(id, "el clip es de " + info.width() + "x" + info.height() + " y se esperaba "
                            + expectedWidth + "x" + expectedHeight);
                    continue;
                }
            } catch (ClipException e) {
                dataProblems.put(id, "el clip no es un MP4 válido: " + e.getMessage());
                continue;
            }
            // Scene.narrationAudioPath es absoluta de la máquina que sintetizó: no se usa; se reconstruye.
            Path audio = insideOrNull(audioDir, id + ".mp3");
            if (audio == null || !Files.isRegularFile(audio)) {
                stateProblems.put(id, "falta el audio " + id + ".mp3 en AUDIO_DIR (llamar a narration)");
            }
        }
        if (!dataProblems.isEmpty()) {
            Map<String, String> all = new LinkedHashMap<>(dataProblems);
            all.putAll(stateProblems);
            throw new FinalVideoException(422, "Hay escenas con datos inválidos para el video final.", all, null);
        }
        if (!stateProblems.isEmpty()) {
            throw new FinalVideoException(409, "Hay escenas que todavía no están listas para el video final.", stateProblems, null);
        }

        Path script = makeVideoScript();
        if (!Files.isRegularFile(script)) {
            throw new FinalVideoException(409, "No se encuentra " + script + " (revisar VIDEO_RENDERER_DIR).");
        }
        try {
            Files.createDirectories(rendersDir);
            long usable = Files.getFileStore(rendersDir).getUsableSpace();
            if (usable < props.minFreeBytes()) {
                throw new FinalVideoException(409, "Poco espacio en disco: " + usable + " bytes libres en '" + rendersDir
                        + "', se exige un mínimo de " + props.minFreeBytes() + ".");
            }
        } catch (IOException e) {
            throw new FinalVideoException(500, "No se pudo revisar el disco de '" + rendersDir + "': " + e.getMessage());
        }
        ProcessRunner.Result node;
        try {
            node = runner.run(List.of(props.nodeCommand(), "--version"), rendererDir, Map.of(), NODE_CHECK_TIMEOUT,
                    rendersDir.resolve("node-check.log"));
        } catch (FinalVideoException e) {
            throw new FinalVideoException(409, "Node no se puede ejecutar ('" + props.nodeCommand() + "'): " + e.getMessage()
                    + " (revisar NODE_COMMAND).");
        }
        if (node.timedOut() || node.exitCode() != 0) {
            throw new FinalVideoException(409, "Node no responde a '--version' ('" + props.nodeCommand()
                    + "', código " + node.exitCode() + "); revisar NODE_COMMAND.");
        }
    }

    // --- ejecución en segundo plano -----------------------------------------------------------

    private void run(FinalVideoJob pending, Path jobDir, int sceneCount) {
        FinalVideoJob job = pending.running();
        try {
            save(job);
            job = execute(job, jobDir, sceneCount);
        } catch (RuntimeException e) {
            log.error("Falló el trabajo de video final {}", job.id(), e);
            job = job.failed(job.stage(), e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName(), null);
        } finally {
            try {
                save(job);
            } catch (RuntimeException e) {
                log.error("No se pudo guardar el estado final del trabajo {}", job.id(), e);
            }
            synchronized (lock) {
                active = null;
            }
        }
    }

    private FinalVideoJob execute(FinalVideoJob job, Path jobDir, int sceneCount) {
        Path work = jobDir.resolve("work");
        Path processLog = jobDir.resolve("make-video.log");
        FinalVideoProperties.Timeouts t = props.timeouts();
        List<String> command = new ArrayList<>(List.of(
                props.nodeCommand(), makeVideoScript().toString(),
                "--story", jobDir.resolve("story.json").toString(),
                "--clips-dir", clipStore.clipsDir().toAbsolutePath().normalize().toString(),
                "--audio-dir", audioDir.toString(),
                "--work", work.toString(),
                "--transcribe-timeout-sec", String.valueOf(t.transcribeSceneSeconds()),
                "--generate-timeout-sec", String.valueOf(t.generateSeconds()),
                "--lint-timeout-sec", String.valueOf(t.lintSeconds()),
                "--check-timeout-sec", String.valueOf(t.checkSeconds()),
                "--render-timeout-sec", String.valueOf(t.renderSeconds())));
        if (props.keepWorkDir()) {
            command.add("--keep-work");
        }
        if (!props.checkEnabled()) {
            command.add("--skip-check");
        }
        Duration timeout = props.totalTimeout(sceneCount);
        log.info("Video final de '{}' (trabajo {}): {}", job.storyId(), job.id(), command);

        ProcessRunner.Result r = runner.run(command, rendererDir, Map.of(), timeout, processLog);

        Map<String, Object> result = readResult(work);
        String stage = result != null ? str(result.get("stage")) : null;
        if (result != null) {
            job = job.withProgress(stage, stagesOf(result), warningsOf(result));
        }
        if (r.timedOut()) {
            return job.failed(stage, "make-video superó " + timeout.toSeconds() + " s y se canceló.", tailOf(work, stage, processLog));
        }
        if (r.exitCode() == 0 && result != null && "COMPLETED".equals(result.get("status"))) {
            return publish(job, work, processLog);
        }
        String error = result != null && result.get("error") != null
                ? str(result.get("error"))
                : "make-video terminó con código " + r.exitCode() + (r.exitCode() == 2 ? " (uso incorrecto)" : "")
                + " sin un resultado COMPLETED.";
        return job.failed(stage, error, tailOf(work, stage, processLog));
    }

    /** Valida el MP4 producido y lo mueve de forma atómica a RENDERS_DIR/{id}/final.mp4. */
    private FinalVideoJob publish(FinalVideoJob job, Path work, Path processLog) {
        Path produced = work.resolve(FINAL_FILE);
        try {
            Mp4Probe.Info info = Mp4Probe.probe(produced);
            if (info.width() != expectedWidth || info.height() != expectedHeight) {
                return job.failed("publish", "El video producido es de " + info.width() + "x" + info.height()
                        + " y se esperaba " + expectedWidth + "x" + expectedHeight + ".", tailOf(work, null, processLog));
            }
            Path target = storyDir(job.storyId()).resolve(FINAL_FILE);
            Files.move(produced, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (ClipException e) {
            return job.failed("publish", "El video producido no es un MP4 válido: " + e.getMessage(), tailOf(work, null, processLog));
        } catch (IOException e) {
            return job.failed("publish", "No se pudo mover el video a su lugar final: " + e.getMessage(), null);
        }
        return job.completed(job.storyId() + "/" + FINAL_FILE);
    }

    // --- archivos -------------------------------------------------------------------------------

    private Path storyDir(String storyId) {
        Path dir = insideOrNull(rendersDir, storyId);
        if (dir == null) {
            throw new FinalVideoException(422, "Id de historia no permitido: '" + storyId + "'.");
        }
        return dir;
    }

    private Path makeVideoScript() {
        return rendererDir.resolve("src").resolve("make-video.mjs");
    }

    /** {@code base/name} si es un nombre seguro y queda dentro de {@code base}; si no, null. */
    private static Path insideOrNull(Path base, String name) {
        if (name == null || !SAFE_ID.matcher(name.replaceFirst("\\.(mp3|mp4)$", "")).matches()) {
            return null;
        }
        Path resolved = base.resolve(name).normalize();
        return resolved.startsWith(base) && !resolved.equals(base) ? resolved : null;
    }

    private void deletePreviousJobDirs(Path storyDir) throws IOException {
        if (!Files.isDirectory(storyDir)) {
            return;
        }
        try (DirectoryStream<Path> dirs = Files.newDirectoryStream(storyDir, "job-*")) {
            for (Path dir : dirs) {
                if (Files.isDirectory(dir)) {
                    deleteRecursively(dir);
                }
            }
        }
    }

    private static void deleteRecursively(Path dir) throws IOException {
        try (Stream<Path> paths = Files.walk(dir)) {
            for (Path p : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(p);
            }
        }
    }

    private void save(FinalVideoJob job) {
        Path file = storyDir(job.storyId()).resolve(JOB_FILE);
        Path tmp = file.resolveSibling(JOB_FILE + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(tmp, mapper.writeValueAsString(job), StandardCharsets.UTF_8);
            moveReplacing(tmp, file);
        } catch (IOException e) {
            throw new FinalVideoException(500, "No se pudo guardar el trabajo de video final en '" + file + "': " + e.getMessage());
        }
    }

    /**
     * Move atómico con reintentos: en Windows reemplazar un archivo que otro hilo
     * tiene abierto (un GET leyendo job.json) falla con AccessDeniedException
     * durante ese instante (lo mostraron los tests). En Linux no pasa.
     */
    private static void moveReplacing(Path from, Path to) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (java.nio.file.AccessDeniedException e) {
                if (attempt >= 20) {
                    throw e;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /** Lee de una vez; reintenta si en Windows justo se está reemplazando el archivo (ver moveReplacing). */
    private static byte[] readRetrying(Path file) throws IOException {
        for (int attempt = 1; ; attempt++) {
            try {
                return Files.readAllBytes(file);
            } catch (java.nio.file.AccessDeniedException e) {
                if (attempt >= 20) {
                    throw e;
                }
                try {
                    Thread.sleep(25);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    private Optional<FinalVideoJob> load(Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            // Leer los bytes de una vez: el archivo queda abierto lo mínimo (ver moveReplacing).
            return Optional.of(mapper.readValue(readRetrying(file), FinalVideoJob.class));
        } catch (IOException e) {
            throw new FinalVideoException(500, "No se pudo leer '" + file + "': " + e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readResult(Path work) {
        Path file = work.resolve(RESULT_FILE);
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try {
            return mapper.readValue(readRetrying(file), Map.class);
        } catch (IOException e) {
            log.warn("No se pudo leer '{}': {}", file, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private static List<FinalVideoJob.StageTiming> stagesOf(Map<String, Object> result) {
        List<FinalVideoJob.StageTiming> stages = new ArrayList<>();
        if (result.get("stages") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    Map<String, Object> s = (Map<String, Object>) m;
                    stages.add(new FinalVideoJob.StageTiming(str(s.get("name")), longOrNull(s.get("ms")),
                            s.get("exitCode") instanceof Number n ? n.intValue() : null,
                            Boolean.TRUE.equals(s.get("timedOut")) ? Boolean.TRUE : null,
                            Boolean.TRUE.equals(s.get("skipped")) ? Boolean.TRUE : null));
                }
            }
        }
        return stages;
    }

    private static List<FinalVideoJob.Warning> warningsOf(Map<String, Object> result) {
        List<FinalVideoJob.Warning> warnings = new ArrayList<>();
        if (result.get("warnings") instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m) {
                    warnings.add(new FinalVideoJob.Warning(str(m.get("stage")), str(m.get("message"))));
                }
            }
        }
        return warnings;
    }

    /** Cola del log de la etapa que falló (work/logs/{etapa}.log) o, si no hay, la del proceso de Node. */
    private static String tailOf(Path work, String stage, Path processLog) {
        Path stageLog = stage != null && SAFE_ID.matcher(stage).matches() ? work.resolve("logs").resolve(stage + ".log") : null;
        Path file = stageLog != null && Files.isRegularFile(stageLog) ? stageLog : processLog;
        if (!Files.isRegularFile(file)) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long length = raf.length();
            int n = (int) Math.min(LOG_TAIL_BYTES, length);
            byte[] bytes = new byte[n];
            raf.seek(length - n);
            raf.readFully(bytes);
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return null;
        }
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static Long longOrNull(Object o) {
        return o instanceof Number n ? n.longValue() : null;
    }
}
