package com.aishorts.engine.clips;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Validación estructural mínima de un MP4 (ISO-BMFF) sin dependencias ni
 * ffprobe: recorre las cajas de primer nivel, exige que empiece con
 * {@code ftyp}, que haya {@code moov} y {@code mdat}, y que las cajas
 * cuadren EXACTAMENTE con el largo del archivo (una descarga truncada deja
 * un {@code mdat} que declara más bytes de los que hay). Del {@code moov}
 * saca el ancho/alto de la primera pista de video ({@code hdlr = vide}),
 * intercambiándolos si la matriz del {@code tkhd} indica rotación de 90°/270°.
 *
 * Qué NO verifica: que los códecs sean decodificables ni que los frames estén
 * íntegros (para eso haría falta decodificar). Es un filtro contra HTML de
 * error, archivos truncados y clips de tamaño equivocado, no una garantía de
 * que el render los acepte.
 */
public final class Mp4Probe {

    /** Tope del moov que se carga en memoria (los clips de pocos segundos tienen unos KB). */
    private static final long MAX_MOOV_BYTES = 16L * 1024 * 1024;

    public record Info(int width, int height, long fileSize) {
    }

    private Mp4Probe() {
    }

    public static Info probe(Path file) {
        try (FileChannel ch = FileChannel.open(file, StandardOpenOption.READ)) {
            return probe(ch);
        } catch (IOException e) {
            throw new ClipException("No se pudo leer el MP4: " + e.getMessage(), e);
        }
    }

    private static Info probe(FileChannel ch) throws IOException {
        long size = ch.size();
        if (size < 16) {
            throw new ClipException("El archivo es demasiado chico para ser un MP4 (" + size + " bytes).");
        }
        long pos = 0;
        boolean first = true;
        boolean sawMdat = false;
        int[] dims = null;

        while (pos < size) {
            if (size - pos < 8) {
                throw new ClipException("MP4 truncado: sobran " + (size - pos) + " bytes que no forman una caja.");
            }
            ByteBuffer head = readFully(ch, pos, 16 > size - pos ? (int) (size - pos) : 16);
            long boxLen = Integer.toUnsignedLong(head.getInt(0));
            String type = fourcc(head, 4);
            if (first) {
                // Antes de fiarse del tamaño: un HTML de error (p. ej. URL vencida con HTTP 200) lee como una caja absurda.
                if (!type.equals("ftyp")) {
                    throw new ClipException("No es un MP4: la primera caja es '" + printable(type)
                            + "' en lugar de 'ftyp' (¿página de error o archivo equivocado?).");
                }
                first = false;
            }
            long headerLen = 8;
            if (boxLen == 1) {
                if (head.limit() < 16) {
                    throw new ClipException("MP4 truncado en la cabecera de la caja '" + type + "'.");
                }
                boxLen = head.getLong(8);
                headerLen = 16;
            } else if (boxLen == 0) {
                boxLen = size - pos;
            }
            if (boxLen < headerLen || pos + boxLen > size || boxLen < 0) {
                throw new ClipException("MP4 truncado o corrupto: la caja '" + type + "' declara " + boxLen
                        + " bytes pero quedan " + (size - pos) + ".");
            }
            if (type.equals("moov")) {
                long payload = boxLen - headerLen;
                if (payload > MAX_MOOV_BYTES) {
                    throw new ClipException("La caja 'moov' es sospechosamente grande (" + payload + " bytes).");
                }
                ByteBuffer moov = readFully(ch, pos + headerLen, (int) payload);
                dims = videoDimensions(moov, 0, moov.limit());
            } else if (type.equals("mdat")) {
                sawMdat = true;
            }
            pos += boxLen;
        }
        if (dims == null) {
            throw new ClipException("El MP4 no tiene 'moov' con una pista de video.");
        }
        if (!sawMdat) {
            throw new ClipException("El MP4 no tiene datos de media ('mdat').");
        }
        return new Info(dims[0], dims[1], size);
    }

    /** Devuelve {ancho, alto} de la primera pista 'vide' dentro de [start, end) del moov, o null. */
    private static int[] videoDimensions(ByteBuffer buf, int start, int end) {
        int pos = start;
        while (pos + 8 <= end) {
            long len = Integer.toUnsignedLong(buf.getInt(pos));
            String type = fourcc(buf, pos + 4);
            int header = 8;
            if (len == 1) {
                len = buf.getLong(pos + 8);
                header = 16;
            } else if (len == 0) {
                len = end - pos;
            }
            if (len < header || pos + len > end) {
                throw new ClipException("Caja '" + type + "' corrupta dentro de 'moov'.");
            }
            if (type.equals("trak")) {
                int[] dims = trackDimensions(buf, pos + header, (int) (pos + len));
                if (dims != null) {
                    return dims;
                }
            }
            pos += (int) len;
        }
        return null;
    }

    private static int[] trackDimensions(ByteBuffer buf, int start, int end) {
        int[] dims = null;
        boolean video = false;
        int pos = start;
        while (pos + 8 <= end) {
            long len = Integer.toUnsignedLong(buf.getInt(pos));
            String type = fourcc(buf, pos + 4);
            if (len < 8 || pos + len > end) {
                throw new ClipException("Caja '" + type + "' corrupta dentro de 'trak'.");
            }
            int payload = pos + 8;
            int boxEnd = (int) (pos + len);
            if (type.equals("tkhd")) {
                dims = parseTkhd(buf, payload, boxEnd);
            } else if (type.equals("mdia")) {
                video = isVideoHandler(buf, payload, boxEnd);
            }
            pos = boxEnd;
        }
        return video ? dims : null;
    }

    private static int[] parseTkhd(ByteBuffer buf, int payload, int end) {
        if (payload + 4 > end) {
            throw new ClipException("Caja 'tkhd' demasiado corta.");
        }
        int version = buf.get(payload) & 0xFF;
        int matrix = payload + (version == 1 ? 52 : 40);
        int widthPos = matrix + 36;
        if (widthPos + 8 > end) {
            throw new ClipException("Caja 'tkhd' demasiado corta.");
        }
        int a = buf.getInt(matrix);
        int b = buf.getInt(matrix + 4);
        int w = buf.getInt(widthPos) >>> 16;
        int h = buf.getInt(widthPos + 4) >>> 16;
        boolean rotated = a == 0 && b != 0;
        return rotated ? new int[]{h, w} : new int[]{w, h};
    }

    private static boolean isVideoHandler(ByteBuffer buf, int start, int end) {
        int pos = start;
        while (pos + 8 <= end) {
            long len = Integer.toUnsignedLong(buf.getInt(pos));
            String type = fourcc(buf, pos + 4);
            if (len < 8 || pos + len > end) {
                throw new ClipException("Caja '" + type + "' corrupta dentro de 'mdia'.");
            }
            if (type.equals("hdlr") && pos + 8 + 12 <= pos + len) {
                return fourcc(buf, pos + 8 + 8).equals("vide");
            }
            pos += (int) len;
        }
        return false;
    }

    private static ByteBuffer readFully(FileChannel ch, long position, int length) throws IOException {
        ByteBuffer buf = ByteBuffer.allocate(length);
        long p = position;
        while (buf.hasRemaining()) {
            int n = ch.read(buf, p);
            if (n < 0) {
                throw new ClipException("MP4 truncado: fin de archivo inesperado.");
            }
            p += n;
        }
        buf.flip();
        return buf;
    }

    private static String fourcc(ByteBuffer buf, int index) {
        byte[] bytes = new byte[4];
        for (int i = 0; i < 4; i++) {
            bytes[i] = buf.get(index + i);
        }
        return new String(bytes, StandardCharsets.ISO_8859_1);
    }

    private static String printable(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            sb.append(c >= 32 && c < 127 ? c : '?');
        }
        return sb.toString();
    }
}
