# Fixtures de prueba

- `expected/index.html`: composición HyperFrames validada el 2026-10-08 (prueba "tunguska", variante A 720x1280), tal como la produjo un agente de código. Es el **golden**.
- `story.reference.json`: la historia con las duraciones **decodificadas** (PCM) que usó el golden (por ejemplo `PT4.458231S`).
- `story.backend.json`: la historia con las duraciones tal como las guarda el backend (milisegundos, `PT4.458S`).
- `transcripts/*.json`: **reconstruidas a partir del golden**, no son la salida original de Parakeet (ese archivo no se conservó en el repositorio). Se les quitó la "y" de `tunguska-consecuencia` que el ASR había descartado, para ejercitar la rama de palabra inferida. Estas pruebas demuestran que la lógica de alineación, agrupado y plantilla reproduce el video validado; **no** prueban la calidad del ASR.
