package com.aishorts.engine.clips;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * Construye en memoria MP4 mínimos (ftyp + moov/trak/tkhd+hdlr + mdat) para los
 * tests, sin binarios en el repo. Son estructuralmente válidos para
 * {@link Mp4Probe}; NO son decodificables (el mdat son bytes de relleno).
 */
public final class Mp4Fixtures {

    private boolean moovFirst = true;
    private boolean rotate90 = false;
    private boolean includeMoov = true;
    private boolean includeMdat = true;
    private boolean largeMdatHeader = false;
    private String handler = "vide";
    /** null = sin caja mdhd (como los fixtures anteriores a la duración). */
    private Integer mdhdVersion;
    private long mdhdTimescale;
    private long mdhdDuration;
    private final int width;
    private final int height;

    private Mp4Fixtures(int width, int height) {
        this.width = width;
        this.height = height;
    }

    public static Mp4Fixtures of(int width, int height) {
        return new Mp4Fixtures(width, height);
    }

    /** Un MP4 válido y típico: 720x1280, moov al frente. */
    public static byte[] valid() {
        return of(720, 1280).build();
    }

    public Mp4Fixtures moovLast() {
        this.moovFirst = false;
        return this;
    }

    public Mp4Fixtures rotated90() {
        this.rotate90 = true;
        return this;
    }

    public Mp4Fixtures withoutMoov() {
        this.includeMoov = false;
        return this;
    }

    public Mp4Fixtures withoutMdat() {
        this.includeMdat = false;
        return this;
    }

    public Mp4Fixtures largeMdatHeader() {
        this.largeMdatHeader = true;
        return this;
    }

    public Mp4Fixtures audioOnly() {
        this.handler = "soun";
        return this;
    }

    /** Agrega un mdhd (versión 0 o 1) con esa duración en unidades de timescale. */
    public Mp4Fixtures mediaDuration(int version, long timescale, long duration) {
        this.mdhdVersion = version;
        this.mdhdTimescale = timescale;
        this.mdhdDuration = duration;
        return this;
    }

    public byte[] build() {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.write(box("ftyp", concat(ascii("isom"), new byte[]{0, 0, 2, 0}, ascii("isom"), ascii("mp41"))));
            byte[] moov = includeMoov ? moov() : new byte[0];
            byte[] mdat = includeMdat ? mdat() : new byte[0];
            if (moovFirst) {
                out.write(moov);
                out.write(mdat);
            } else {
                out.write(mdat);
                out.write(moov);
            }
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private byte[] moov() throws IOException {
        byte[] tkhd = box("tkhd", tkhdPayload());
        byte[] hdlr = box("hdlr", hdlrPayload());
        byte[] mdia = box("mdia", mdhdVersion == null ? hdlr : concat(box("mdhd", mdhdPayload()), hdlr));
        byte[] trak = box("trak", concat(tkhd, mdia));
        return box("moov", trak);
    }

    private byte[] mdat() throws IOException {
        byte[] media = new byte[64];
        for (int i = 0; i < media.length; i++) {
            media[i] = (byte) i;
        }
        if (!largeMdatHeader) {
            return box("mdat", media);
        }
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(1);
        out.write(ascii("mdat"));
        out.writeLong(16L + media.length);
        out.write(media);
        return bos.toByteArray();
    }

    private byte[] tkhdPayload() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0);          // version 0 + flags
        out.writeInt(0);          // creation
        out.writeInt(0);          // modification
        out.writeInt(1);          // track id
        out.writeInt(0);          // reserved
        out.writeInt(1000);       // duration
        out.writeLong(0);         // reserved
        out.writeShort(0);        // layer
        out.writeShort(0);        // alternate group
        out.writeShort(0);        // volume
        out.writeShort(0);        // reserved
        int one = 0x00010000;
        int[] matrix = rotate90
                ? new int[]{0, one, 0, -one, 0, 0, 0, 0, 0x40000000}
                : new int[]{one, 0, 0, 0, one, 0, 0, 0, 0x40000000};
        for (int m : matrix) {
            out.writeInt(m);
        }
        out.writeInt(width << 16);
        out.writeInt(height << 16);
        return bos.toByteArray();
    }

    private byte[] mdhdPayload() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(mdhdVersion << 24);  // version + flags
        if (mdhdVersion == 1) {
            out.writeLong(0);             // creation
            out.writeLong(0);             // modification
            out.writeInt((int) mdhdTimescale);
            out.writeLong(mdhdDuration);
        } else {
            out.writeInt(0);              // creation
            out.writeInt(0);              // modification
            out.writeInt((int) mdhdTimescale);
            out.writeInt((int) mdhdDuration);
        }
        out.writeShort(0x55C4);           // language "und"
        out.writeShort(0);                // pre_defined
        return bos.toByteArray();
    }

    private byte[] hdlrPayload() throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(0);                 // version + flags
        out.writeInt(0);                 // pre_defined
        out.write(ascii(handler));       // handler_type
        out.write(new byte[12]);         // reserved
        out.writeByte(0);                // name (vacío)
        return bos.toByteArray();
    }

    static byte[] box(String type, byte[] payload) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        out.writeInt(8 + payload.length);
        out.write(ascii(type));
        out.write(payload);
        return bos.toByteArray();
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }

    private static byte[] concat(byte[]... parts) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        for (byte[] p : parts) {
            bos.write(p);
        }
        return bos.toByteArray();
    }
}
