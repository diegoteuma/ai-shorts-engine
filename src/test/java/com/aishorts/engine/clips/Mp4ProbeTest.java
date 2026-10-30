package com.aishorts.engine.clips;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class Mp4ProbeTest {

    @TempDir
    Path dir;

    private Path write(String name, byte[] bytes) throws IOException {
        Path file = dir.resolve(name);
        Files.write(file, bytes);
        return file;
    }

    @Test
    void readsDimensionsOfAValidMp4() throws IOException {
        Mp4Probe.Info info = Mp4Probe.probe(write("ok.mp4", Mp4Fixtures.valid()));

        assertThat(info.width()).isEqualTo(720);
        assertThat(info.height()).isEqualTo(1280);
    }

    @Test
    void readsTheVideoTrackDurationFromMdhd_version0And1() throws IOException {
        Mp4Probe.Info v0 = Mp4Probe.probe(write("v0.mp4", Mp4Fixtures.of(720, 1280).mediaDuration(0, 15360, 76800).build()));
        Mp4Probe.Info v1 = Mp4Probe.probe(write("v1.mp4", Mp4Fixtures.of(720, 1280).mediaDuration(1, 24, 193).moovLast().build()));

        assertThat(v0.durationSeconds()).isEqualTo(5.0);
        assertThat(v1.durationSeconds()).isCloseTo(8.041667, org.assertj.core.data.Offset.offset(1e-6));
    }

    @Test
    void withoutMdhdTheDurationIsNull_andTheRestStillWorks() throws IOException {
        Mp4Probe.Info info = Mp4Probe.probe(write("sin-mdhd.mp4", Mp4Fixtures.valid()));

        assertThat(info.durationSeconds()).isNull();
        assertThat(info.width()).isEqualTo(720);
    }

    @Test
    void rejectsAnMdhdWithZeroTimescale() throws IOException {
        Path file = write("ts0.mp4", Mp4Fixtures.of(720, 1280).mediaDuration(0, 0, 100).build());

        assertThatThrownBy(() -> Mp4Probe.probe(file)).isInstanceOf(ClipException.class).hasMessageContaining("mdhd");
    }

    /** Duraciones de la pista de video medidas con ffprobe (stream=duration) sobre los clips versionados de tunguska. */
    @Test
    void theRealTunguskaClipsMatchFfprobe() {
        java.util.Map<String, Double> ffprobe = java.util.Map.of(
                "gancho", 5.0, "explicacion", 7.0, "contexto", 8.0,
                "giro", 7.041667, "consecuencia", 8.041667, "cierre", 5.0);
        ffprobe.forEach((role, expected) -> assertThat(
                Mp4Probe.probe(Path.of("data", "clips", "tunguska-" + role + ".mp4")).durationSeconds())
                .as(role).isCloseTo(expected, org.assertj.core.data.Offset.offset(1e-6)));
    }

    @Test
    void acceptsMoovAfterMdat() throws IOException {
        // Sin faststart el moov va al final; es lo que producen muchos encoders.
        Mp4Probe.Info info = Mp4Probe.probe(write("moov-last.mp4", Mp4Fixtures.of(720, 1280).moovLast().build()));

        assertThat(info.width()).isEqualTo(720);
        assertThat(info.height()).isEqualTo(1280);
    }

    @Test
    void swapsDimensionsWhenTheMatrixRotatesNinetyDegrees() throws IOException {
        // Un clip 1280x720 con matriz de 90° se muestra como 720x1280.
        Mp4Probe.Info info = Mp4Probe.probe(write("rot.mp4", Mp4Fixtures.of(1280, 720).rotated90().build()));

        assertThat(info.width()).isEqualTo(720);
        assertThat(info.height()).isEqualTo(1280);
    }

    @Test
    void acceptsSixtyFourBitBoxHeaders() throws IOException {
        Mp4Probe.Info info = Mp4Probe.probe(write("large.mp4", Mp4Fixtures.of(720, 1280).largeMdatHeader().build()));

        assertThat(info.width()).isEqualTo(720);
    }

    @Test
    void rejectsATruncatedFile() throws IOException {
        byte[] full = Mp4Fixtures.of(720, 1280).moovLast().build();
        byte[] cut = Arrays.copyOf(full, full.length - 40);

        assertThatThrownBy(() -> Mp4Probe.probe(write("cut.mp4", cut)))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("truncado");
    }

    @Test
    void rejectsADownloadCutInsideMdatWhenMoovIsFirst() throws IOException {
        // Caso realista de descarga cortada: con faststart el moov está completo y lo que falta es media.
        // Solo el chequeo de que cada caja cabe en el archivo lo detecta (el mdat se saltea por tamaño).
        byte[] full = Mp4Fixtures.valid();
        byte[] cut = Arrays.copyOf(full, full.length - 10);

        assertThatThrownBy(() -> Mp4Probe.probe(write("cut-mdat.mp4", cut)))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("'mdat' declara");
    }

    @Test
    void rejectsAnHtmlErrorPage() throws IOException {
        byte[] html = "<html><body>403 Forbidden - signed URL expired</body></html>".getBytes(StandardCharsets.UTF_8);

        assertThatThrownBy(() -> Mp4Probe.probe(write("page.mp4", html)))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("No es un MP4");
    }

    @Test
    void rejectsTinyAndEmptyFiles() throws IOException {
        assertThatThrownBy(() -> Mp4Probe.probe(write("tiny.mp4", new byte[5])))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("demasiado chico");
        assertThatThrownBy(() -> Mp4Probe.probe(write("empty.mp4", new byte[0])))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("demasiado chico");
    }

    @Test
    void rejectsAFileWithoutMoov() throws IOException {
        assertThatThrownBy(() -> Mp4Probe.probe(write("nomoov.mp4", Mp4Fixtures.of(720, 1280).withoutMoov().build())))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("moov");
    }

    @Test
    void rejectsAFileWithoutMdat() throws IOException {
        assertThatThrownBy(() -> Mp4Probe.probe(write("nomdat.mp4", Mp4Fixtures.of(720, 1280).withoutMdat().build())))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("mdat");
    }

    @Test
    void rejectsAFileWithoutAVideoTrack() throws IOException {
        assertThatThrownBy(() -> Mp4Probe.probe(write("audio.mp4", Mp4Fixtures.of(720, 1280).audioOnly().build())))
                .isInstanceOf(ClipException.class)
                .hasMessageContaining("pista de video");
    }
}
