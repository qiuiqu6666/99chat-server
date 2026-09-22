import { http } from "@/utils/http";

export type SplashItem = {
  id: string;
  version: string;
  enabled: boolean;
  image_url?: string | null;
  image_md5?: string | null;
  content_type?: string | null;
  width?: number | null;
  height?: number | null;
  bytes?: number | null;
  fit?: string | null;
  start_at?: string | null;
  end_at?: string | null;
  min_app_version?: string | null;
  platforms?: string | null;
  channels?: string | null;
  created_by?: string | null;
  created_at?: string | null;
  updated_at?: string | null;
};

export type SplashUpdateBody = {
  enabled?: boolean;
  fit?: string;
  start_at?: string | null;
  end_at?: string | null;
  clear_start_at?: boolean;
  clear_end_at?: boolean;
  min_app_version?: string | null;
  platforms?: string | null;
  channels?: string | null;
};

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
  for (const v of [raw.items, raw.list, raw.records, raw.rows, raw.data]) {
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
  return v === true || v === 1 || v === "1" || v === "true";
}

function mapItem(raw: Record<string, unknown>): SplashItem {
  return {
    id: String(raw.id ?? ""),
    version: String(raw.version ?? ""),
    enabled: raw.enabled == null ? true : bool(raw.enabled),
    image_url: str(raw.image_url ?? raw.imageUrl),
    image_md5: str(raw.image_md5 ?? raw.imageMd5),
    content_type: str(raw.content_type ?? raw.contentType),
    width: num(raw.width),
    height: num(raw.height),
    bytes: num(raw.bytes),
    fit: str(raw.fit),
    start_at: str(raw.start_at ?? raw.startAt),
    end_at: str(raw.end_at ?? raw.endAt),
    min_app_version: str(raw.min_app_version ?? raw.minAppVersion),
    platforms: str(raw.platforms),
    channels: str(raw.channels),
    created_by: str(raw.created_by ?? raw.createdBy),
    created_at: str(raw.created_at ?? raw.createdAt),
    updated_at: str(raw.updated_at ?? raw.updatedAt)
  };
}

export async function getSplashList() {
  const raw = unwrap(await http.request<Record<string, unknown>>("get", "/api/v1/splash"));
  const arr = pickArray(raw);
  return {
    items: arr.map(it => mapItem((it ?? {}) as Record<string, unknown>)),
    total: Number(raw.total ?? arr.length)
  };
}

export type CreateSplashParams = {
  file: File;
  enabled?: boolean;
  fit?: string;
  start_at?: string;
  end_at?: string;
  min_app_version?: string;
  platforms?: string;
  channels?: string;
};

export async function createSplash(params: CreateSplashParams) {
  const formData = new FormData();
  formData.append("file", params.file);
  if (params.enabled != null) formData.append("enabled", String(params.enabled));
  if (params.fit) formData.append("fit", params.fit);
  if (params.start_at) formData.append("start_at", params.start_at);
  if (params.end_at) formData.append("end_at", params.end_at);
  if (params.min_app_version) formData.append("min_app_version", params.min_app_version);
  if (params.platforms) formData.append("platforms", params.platforms);
  if (params.channels) formData.append("channels", params.channels);
  const raw = unwrap(
    await http.request<Record<string, unknown>>("post", "/api/v1/splash", {
      data: formData,
      headers: { "Content-Type": "multipart/form-data" },
      timeout: 60000
    })
  );
  return mapItem(raw);
}

export async function updateSplash(id: string, data: SplashUpdateBody) {
  const raw = unwrap(
    await http.request<Record<string, unknown>>("put", `/api/v1/splash/${encodeURIComponent(id)}`, {
      data
    })
  );
  return mapItem(raw);
}

export function deleteSplash(id: string) {
  return http.request<Record<string, unknown>>("delete", `/api/v1/splash/${encodeURIComponent(id)}`);
}
