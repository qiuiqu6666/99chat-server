import dayjs from "dayjs";

/** Unix 毫秒或秒的纯数字字段：按整数位数区分（≤10 位视为秒，否则视为毫秒）；微秒再 ÷1000 */
function coerceUnixToMs(raw: unknown): number | undefined {
  if (raw === null || raw === undefined || raw === "") return undefined;
  let n: number;
  if (typeof raw === "number") {
    if (!Number.isFinite(raw)) return undefined;
    n = raw;
  } else if (typeof raw === "string") {
    const s = raw.trim();
    if (!/^-?\d+(?:\.\d+)?(?:e[+-]?\d+)?$/i.test(s)) return undefined;
    n = Number(s);
    if (!Number.isFinite(n)) return undefined;
  } else return undefined;

  const a = Math.abs(n);
  if (a >= 1e16) return undefined;

  /** 微秒量级（常见于部分 JSON 序列化） */
  if (a >= 1e15) return Math.round(n / 1000);

  const intPart = Math.trunc(a);
  const digits = intPart === 0 ? 1 : Math.floor(Math.log10(intPart)) + 1;
  if (digits <= 10) return Math.round(n * 1000);
  return Math.round(n);
}

export function formatAdminUnixTime(ts: unknown, fallbackFormat = false): string {
  if (ts === null || ts === undefined || ts === "") return "—";

  const ms =
    coerceUnixToMs(ts) ??
    (typeof ts === "object" && ts !== null && "msg_time2" in (ts as object)
      ? coerceUnixToMs((ts as Record<string, unknown>).msg_time2)
      : undefined);

  if (ms !== undefined) {
    const d = dayjs(ms);
    if (d.isValid()) return d.format("YYYY-MM-DD HH:mm:ss");
  }

  if (!fallbackFormat) return String(ts);
  const d = dayjs(ts as string | number);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : String(ts);
}
