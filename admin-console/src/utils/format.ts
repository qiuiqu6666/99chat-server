export function formatTime(v: unknown): string {
  if (v == null || v === "") return "—";
  if (typeof v === "number") {
    const ms = v < 1e12 ? v * 1000 : v;
    return new Date(ms).toLocaleString();
  }
  const s = String(v);
  const d = new Date(s);
  if (!Number.isNaN(d.getTime())) return d.toLocaleString();
  return s;
}

export function formatAmount(v: unknown): string {
  if (v == null || v === "") return "0";
  return String(v);
}

export function errMessage(err: unknown, fallback = "请求失败"): string {
  const e = err as {
    response?: { data?: { message?: string; error?: string } };
    message?: string;
  };
  return (
    e?.response?.data?.message ||
    e?.response?.data?.error ||
    e?.message ||
    fallback
  );
}
