import { http } from "@/utils/http";

const cache = new Map<string, string>();
const inflight = new Map<string, Promise<string>>();

export function normalizeIp(ip: unknown): string | null {
  if (ip == null || ip === "") return null;
  const text = String(ip).trim();
  if (!text || !/^[0-9a-fA-F:.]+$/.test(text)) return null;
  return text;
}

export function isPrivateIp(ip: string): boolean {
  if (ip === "127.0.0.1" || ip === "0.0.0.0" || ip === "::1") return true;
  if (ip.startsWith("10.") || ip.startsWith("192.168.") || ip.startsWith("169.254.")) {
    return true;
  }
  if (ip.startsWith("172.")) {
    const second = Number(ip.split(".")[1]);
    if (Number.isFinite(second) && second >= 16 && second <= 31) return true;
  }
  const lower = ip.toLowerCase();
  return lower.startsWith("fc") || lower.startsWith("fd") || lower.startsWith("fe80");
}

/** 根据 IP 解析地区（优先缓存；内网 IP 返回「内网」） */
export async function resolveIpRegion(ip: unknown): Promise<string> {
  const normalized = normalizeIp(ip);
  if (!normalized) return "—";
  if (isPrivateIp(normalized)) return "内网";
  const hit = cache.get(normalized);
  if (hit) return hit;

  const pending = inflight.get(normalized);
  if (pending) return pending;

  const task = (async () => {
    try {
      const raw = await http.request<{ ip?: string; region?: string }>(
        "get",
        "/api/v1/geoip",
        { params: { ip: normalized } }
      );
      const region =
        raw?.region && String(raw.region).trim() ? String(raw.region).trim() : "—";
      cache.set(normalized, region);
      return region;
    } catch {
      cache.set(normalized, "—");
      return "—";
    } finally {
      inflight.delete(normalized);
    }
  })();

  inflight.set(normalized, task);
  return task;
}

/** 批量解析 IP → 地区映射 */
export async function resolveIpRegions(
  ips: Array<string | null | undefined>
): Promise<Record<string, string>> {
  const unique = [
    ...new Set(
      ips
        .map(normalizeIp)
        .filter((ip): ip is string => Boolean(ip))
    )
  ];
  const out: Record<string, string> = {};
  await Promise.all(
    unique.map(async ip => {
      out[ip] = await resolveIpRegion(ip);
    })
  );
  return out;
}

/** 表格展示：优先后端 region，否则用 IP 解析结果 */
export function displayIpRegion(
  ip: unknown,
  regionByIp: Record<string, string>,
  backendRegion?: unknown
): string {
  const backend =
    backendRegion == null || backendRegion === ""
      ? ""
      : String(backendRegion).trim();
  if (backend) return backend;

  const normalized = normalizeIp(ip);
  if (!normalized) return "—";
  if (isPrivateIp(normalized)) return "内网";
  return regionByIp[normalized] ?? "…";
}
