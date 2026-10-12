package com.aishorts.engine.clips;

import com.aishorts.engine.difficulty.TierRecommendation;
import com.aishorts.engine.domain.GenerationTier;
import com.aishorts.engine.domain.Scene;
import com.aishorts.engine.domain.SceneRole;
import com.aishorts.engine.domain.Story;
import com.aishorts.engine.higgsfield.EstimateResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class ClipStoreTest {

    @TempDir
    Path dir;

    /** Downloader falso: por URL devuelve bytes o falla; cuenta las llamadas. */
    private static final class FakeDownloader implements ClipDownloader {
        final Map<String, byte[]> bodies = new HashMap<>();
        final Map<String, String> failures = new HashMap<>();
        final List<String> calls = new ArrayList<>();

        @Override
        public void download(String url, Path destination) {
            calls.add(url);
            String failure = failures.get(url);
            if (failure != null) {
                throw new ClipException(failure);
            }
            byte[] body = bodies.get(url);
            if (body == null) {
                throw new ClipException("sin cuerpo configurado para " + url);
            }
            try {
                Files.write(destination, body);
            } catch (IOException e) {
                throw new ClipException("no se pudo escribir", e);
            }
        }
    }

    private static Scene scene(String id, SceneRole role, int order, String assetUrl) {
        Scene scene = new Scene(id, role, order, "narración", "prompt", Duration.ofSeconds(5));
        scene.proposeTier(new TierRecommendation(GenerationTier.STANDARD, 40, "test"));
        scene.approvePrompt();
        scene.recordCostEstimate(new EstimateResponse(new BigDecimal("0.35"), "USD", "m", Map.of()), "m");
        scene.approveCost();
        scene.startGeneration("req-" + id, "https://status/" + id);
        if (assetUrl != null) {
            scene.completeGeneration(assetUrl);
        }
        return scene;
    }

    private static Story story(Scene... scenes) {
        return new Story("s1", "tema", "título", List.of(scenes));
    }

    private ClipStore store(FakeDownloader downloader) {
        return new ClipStore(dir.resolve("clips"), downloader, 720, 1280, 0);
    }

    private static List<String> names(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files.map(p -> p.getFileName().toString()).sorted().toList();
        }
    }

    @Test
    void doesNothingUntilEverySceneIsCompleted() throws IOException {
        FakeDownloader downloader = new FakeDownloader();
        Story story = story(
                scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"),
                scene("s1-explicacion", SceneRole.EXPLICACION, 2, null));   // sigue en cola

        ClipSyncResult result = store(downloader).syncIfAllCompleted(story);

        assertThat(result.attempted()).isFalse();
        assertThat(downloader.calls).isEmpty();
        assertThat(story.scenes().get(0).hasLocalClip()).isFalse();
        // ni siquiera se crea el directorio de clips.
        assertThat(Files.exists(dir.resolve("clips"))).isFalse();
    }

    @Test
    void storesEveryClipOnceAllScenesAreCompleted() throws IOException {
        FakeDownloader downloader = new FakeDownloader();
        downloader.bodies.put("https://cdn/1.mp4", Mp4Fixtures.valid());
        downloader.bodies.put("https://cdn/2.mp4", Mp4Fixtures.of(720, 1280).moovLast().build());
        Story story = story(
                scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"),
                scene("s1-explicacion", SceneRole.EXPLICACION, 2, "https://cdn/2.mp4"));

        ClipSyncResult result = store(downloader).syncIfAllCompleted(story);

        assertThat(result.attempted()).isTrue();
        assertThat(result.storedSceneIds()).containsExactly("s1-gancho", "s1-explicacion");
        assertThat(result.failedSceneIds()).isEmpty();
        assertThat(story.scenes().get(0).localClipPath()).isEqualTo("s1-gancho.mp4");
        assertThat(story.scenes().get(1).localClipPath()).isEqualTo("s1-explicacion.mp4");
        // solo los .mp4 finales: ningún .part residual.
        assertThat(names(dir.resolve("clips"))).containsExactly("s1-explicacion.mp4", "s1-gancho.mp4");
    }

    @Test
    void doesNotDownloadAgainWhatIsAlreadyStoredAndValid() {
        FakeDownloader downloader = new FakeDownloader();
        downloader.bodies.put("https://cdn/1.mp4", Mp4Fixtures.valid());
        Story story = story(scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"));
        ClipStore store = store(downloader);
        store.syncIfAllCompleted(story);

        ClipSyncResult second = store.syncIfAllCompleted(story);

        assertThat(downloader.calls).hasSize(1);
        assertThat(second.storedSceneIds()).isEmpty();
        assertThat(second.alreadyPresent()).containsExactly("s1-gancho");
    }

    @Test
    void aFailedDownloadDoesNotBlockTheOthersAndIsRetryable() {
        FakeDownloader downloader = new FakeDownloader();
        downloader.bodies.put("https://cdn/1.mp4", Mp4Fixtures.valid());
        downloader.failures.put("https://cdn/2.mp4", "El servidor respondió HTTP 403 al pedir el clip.");
        Story story = story(
                scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"),
                scene("s1-explicacion", SceneRole.EXPLICACION, 2, "https://cdn/2.mp4"));
        ClipStore store = store(downloader);

        ClipSyncResult first = store.syncIfAllCompleted(story);

        assertThat(first.storedSceneIds()).containsExactly("s1-gancho");
        assertThat(first.failedSceneIds().get("s1-explicacion")).contains("HTTP 403");
        Scene failed = story.scenes().get(1);
        assertThat(failed.hasLocalClip()).isFalse();
        assertThat(failed.localClipFailureReason()).contains("HTTP 403");
        // la generación sigue COMPLETED con su URL: nada se pierde.
        assertThat(failed.generatedAssetUrl()).isEqualTo("https://cdn/2.mp4");

        // reintento: la URL vuelve a andar; solo se descarga la que faltaba.
        downloader.failures.clear();
        downloader.bodies.put("https://cdn/2.mp4", Mp4Fixtures.valid());
        ClipSyncResult retry = store.syncIfAllCompleted(story);

        assertThat(retry.storedSceneIds()).containsExactly("s1-explicacion");
        assertThat(retry.alreadyPresent()).containsExactly("s1-gancho");
        assertThat(failed.hasLocalClip()).isTrue();
        assertThat(failed.localClipFailureReason()).isNull();
    }

    @Test
    void rejectsAClipWithTheWrongDimensionsAndLeavesNoFiles() throws IOException {
        FakeDownloader downloader = new FakeDownloader();
        downloader.bodies.put("https://cdn/1.mp4", Mp4Fixtures.of(1080, 1920).build());
        Story story = story(scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"));

        ClipSyncResult result = store(downloader).syncIfAllCompleted(story);

        assertThat(result.failedSceneIds().get("s1-gancho")).contains("1080x1920").contains("720x1280");
        assertThat(story.scenes().get(0).hasLocalClip()).isFalse();
        assertThat(names(dir.resolve("clips"))).isEmpty();
    }

    @Test
    void rejectsABodyThatIsNotAnMp4() throws IOException {
        FakeDownloader downloader = new FakeDownloader();
        downloader.bodies.put("https://cdn/1.mp4", "<html>expired</html>".getBytes());
        Story story = story(scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"));

        ClipSyncResult result = store(downloader).syncIfAllCompleted(story);

        assertThat(result.failedSceneIds().get("s1-gancho")).contains("No es un MP4");
        assertThat(names(dir.resolve("clips"))).isEmpty();
    }

    @Test
    void redownloadsWhenTheRecordedFileWentMissing() throws IOException {
        FakeDownloader downloader = new FakeDownloader();
        downloader.bodies.put("https://cdn/1.mp4", Mp4Fixtures.valid());
        Story story = story(scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"));
        ClipStore store = store(downloader);
        store.syncIfAllCompleted(story);
        Files.delete(dir.resolve("clips").resolve("s1-gancho.mp4"));

        ClipSyncResult result = store.syncIfAllCompleted(story);

        assertThat(result.storedSceneIds()).containsExactly("s1-gancho");
        assertThat(downloader.calls).hasSize(2);
    }

    @Test
    void ignoresAFileOnDiskThatTheSceneDoesNotHaveRegistered() throws IOException {
        // Podría ser de una generación anterior: no se adopta, se pisa con la descarga actual.
        Files.createDirectories(dir.resolve("clips"));
        byte[] stale = Mp4Fixtures.of(720, 1280).moovLast().build();
        Files.write(dir.resolve("clips").resolve("s1-gancho.mp4"), stale);
        FakeDownloader downloader = new FakeDownloader();
        byte[] fresh = Mp4Fixtures.valid();
        downloader.bodies.put("https://cdn/1.mp4", fresh);
        Story story = story(scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"));

        ClipSyncResult result = store(downloader).syncIfAllCompleted(story);

        assertThat(result.storedSceneIds()).containsExactly("s1-gancho");
        assertThat(java.util.Arrays.equals(Files.readAllBytes(dir.resolve("clips").resolve("s1-gancho.mp4")), fresh)).isTrue();
    }

    @Test
    void refusesToStartWhenThereIsNotEnoughFreeDisk() throws IOException {
        FakeDownloader downloader = new FakeDownloader();
        downloader.bodies.put("https://cdn/1.mp4", Mp4Fixtures.valid());
        Story story = story(scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"));
        ClipStore tight = new ClipStore(dir.resolve("clips"), downloader, 720, 1280, Long.MAX_VALUE);

        ClipSyncResult result = tight.syncIfAllCompleted(story);

        assertThat(result.failedSceneIds().get("s1-gancho")).contains("Poco espacio en disco");
        assertThat(downloader.calls).isEmpty();
        assertThat(names(dir.resolve("clips"))).isEmpty();
    }

    @Test
    void rejectsSceneIdsThatCannotBeASafeFileName() {
        FakeDownloader downloader = new FakeDownloader();
        downloader.bodies.put("https://cdn/1.mp4", Mp4Fixtures.valid());
        Story story = story(scene("../evil", SceneRole.GANCHO, 1, "https://cdn/1.mp4"));

        ClipSyncResult result = store(downloader).syncIfAllCompleted(story);

        assertThat(result.failedSceneIds().get("../evil")).contains("caracteres no permitidos");
        assertThat(downloader.calls).isEmpty();
    }

    @Test
    void deleteClipsRemovesTheFilesOfTheGivenScenesOnly() throws IOException {
        FakeDownloader downloader = new FakeDownloader();
        downloader.bodies.put("https://cdn/1.mp4", Mp4Fixtures.valid());
        downloader.bodies.put("https://cdn/2.mp4", Mp4Fixtures.valid());
        Story story = story(
                scene("s1-gancho", SceneRole.GANCHO, 1, "https://cdn/1.mp4"),
                scene("s1-explicacion", SceneRole.EXPLICACION, 2, "https://cdn/2.mp4"));
        ClipStore store = store(downloader);
        store.syncIfAllCompleted(story);

        // un id inexistente o inseguro no rompe nada.
        store.deleteClips(List.of("s1-gancho", "no-existe", "../x"));

        assertThat(names(dir.resolve("clips"))).containsExactly("s1-explicacion.mp4");
    }

    @Test
    void resolveRefusesPathsOutsideTheClipsDirectory() {
        ClipStore store = store(new FakeDownloader());

        org.assertj.core.api.Assertions.assertThatThrownBy(() -> store.resolve("../secret.mp4"))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("fuera del directorio");
    }
}
