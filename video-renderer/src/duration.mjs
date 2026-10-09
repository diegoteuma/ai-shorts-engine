// Duraciones: el backend serializa java.time.Duration.toString() (por ejemplo "PT4.458S", "PT1M2.5S").

export const round6 = (x) => +x.toFixed(6);

const ISO = /^PT(?:(\d+(?:\.\d+)?)H)?(?:(\d+(?:\.\d+)?)M)?(?:(\d+(?:\.\d+)?)S)?$/;

/** Convierte una duración ISO-8601 (o un número de segundos) a segundos. */
export function parseDuration(value) {
  if (typeof value === "number" && Number.isFinite(value)) return value;
  if (typeof value !== "string") {
    throw new Error(`Duración inválida: ${JSON.stringify(value)}`);
  }
  const m = ISO.exec(value.trim());
  if (!m || (m[1] === undefined && m[2] === undefined && m[3] === undefined)) {
    throw new Error(`Duración inválida (se esperaba ISO-8601 tipo "PT4.458S"): ${JSON.stringify(value)}`);
  }
  return (Number(m[1]) || 0) * 3600 + (Number(m[2]) || 0) * 60 + (Number(m[3]) || 0);
}
