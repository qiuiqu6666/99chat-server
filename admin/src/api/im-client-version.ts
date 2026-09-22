import { http } from "@/utils/http";

export type ClientPlatform = "android" | "ios" | "web" | string;

export type ClientVersionItem = {
  id: string;
  platform: ClientPlatform;
  version: string;
  version_code?: number | null;
  min_version?: string | null;
  min_version_code?: number | null;
  update_type?: string | null;
  download_url?: string | null;
  changelog?: string | null;
  enabled?: boolean;
  gray_percent?: number | null;
  created_at?: string | null;
  published_at?: string | null;
};

export type ClientVersionListParams = {
  page?: number;
  page_size?: number;
  platform?: string;
  keyword?: string;
  enabled?: string;
};

function isMissingEndpoint(err: unknown) {
  const ax = err as { response?: { status?: number } };
  return [404, 405, 501].includes(Number(ax?.response?.status));
}

function unwrap(raw: unknown): Record<string, unknown> {
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    const r = raw as Record<string, unknown>;
    if (r.data && typeof r.data === "object" && !Array.isArray(r.data)) {
      return r.data as Record<string, unknown>;
    }
    return r;
  }
  return {};
}

function pickArray(raw: Record<string, unknown>) {
  for (const v of [raw.items, raw.list, raw.records, raw.rows, raw.versions, raw.data]) {
    if (Array.isArray(v)) return v;
  }
  return [];
}

function str(v: unknown): string | null {
  if (v == null || v === "") return null;
  return String(v);
}

function num(v: unknown): number | null {
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function bool(v: unknown): boolean {
  return v === true || v === 1 || v === "1" || v === "true" || v === "enabled";
}

function mapItem(raw: Record<string, unknown>): ClientVersionItem {
  return {
    id: String(raw.id ?? raw.version_id ?? raw.versionId ?? `${raw.platform ?? ""}-${raw.version ?? ""}`),
    platform: String(raw.platform ?? raw.client_type ?? raw.os ?? "android"),
    version: String(raw.version ?? raw.version_name ?? raw.versionName ?? ""),
    version_code: num(raw.version_code ?? raw.versionCode ?? raw.code),
    min_version: str(raw.min_version ?? raw.minVersion),
    min_version_code: num(raw.min_version_code ?? raw.minVersionCode),
    update_type: str(raw.update_type ?? raw.updateType ?? raw.force_update ?? raw.forceUpdate),
    download_url: str(raw.download_url ?? raw.downloadUrl ?? raw.url),
    changelog: str(raw.changelog ?? raw.update_desc ?? raw.updateDesc ?? raw.remark),
    enabled: raw.enabled == null ? true : Boolean(raw.enabled),
    gray_percent: num(raw.gray_percent ?? raw.grayPercent),
    created_at: str(raw.created_at ?? raw.createdAt ?? raw.create_time),
    published_at: str(raw.published_at ?? raw.publishedAt ?? raw.publish_time)
  };
}

export async function getClientVersions(params: ClientVersionListParams) {
  try {
    const raw = unwrap(await http.request<Record<string, unknown>>("get", "/api/v1/client-versions", { params }));
    const arr = pickArray(raw);
    return {
      items: arr.map(it => mapItem((it ?? {}) as Record<string, unknown>)),
      total: Number(raw.total ?? raw.count ?? arr.length),
      page: Number(raw.page ?? params.page ?? 1),
      page_size: Number(raw.page_size ?? raw.pageSize ?? params.page_size ?? 20)
    };
  } catch (err: unknown) {
    if (isMissingEndpoint(err)) {
      return { items: [], total: 0, page: params.page ?? 1, page_size: params.page_size ?? 20 };
    }
    throw err;
  }
}

export function createClientVersion(data: Partial<ClientVersionItem>) {
  return http.request<Record<string, unknown>>("post", "/api/v1/client-versions", { data });
}

export function updateClientVersion(id: string, data: Partial<ClientVersionItem>) {
  return http.request<Record<string, unknown>>("put", `/api/v1/client-versions/${encodeURIComponent(id)}`, { data });
}

export function deleteClientVersion(id: string) {
  return http.request<Record<string, unknown>>("delete", `/api/v1/client-versions/${encodeURIComponent(id)}`);
}
