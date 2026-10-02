package com.aishorts.engine.montage;

import java.nio.file.Path;

/** Lo que deja en disco un montaje: el video final y las dos pistas .srt. */
public record MontageResult(Path videoPath, Path srtEsPath, Path srtEnPath, Path workDir) {
}
