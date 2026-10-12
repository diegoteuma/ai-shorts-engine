// ffprobe simulado para las pruebas de make-video. Variable de entorno FAKE_FFPROBE (JSON):
//   missing   simula que no está instalado (código 1 también con -version)
//   json      objeto a imprimir (por defecto: 720x1280 con video y audio y 37.434 s, lo medido en la VM con tunguska)
const cfg = JSON.parse(process.env.FAKE_FFPROBE ?? "{}");
if (cfg.missing) {
  console.error("ffprobe: no encontrado (simulado)");
  process.exit(1);
}
if (process.argv.includes("-version")) {
  console.log("ffprobe version simulada");
} else {
  const json = cfg.json ?? {
    streams: [{ codec_type: "video", width: 720, height: 1280 }, { codec_type: "audio" }],
    format: { duration: "37.434000" },
  };
  console.log(JSON.stringify(json));
}
