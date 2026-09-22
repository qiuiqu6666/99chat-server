import { http } from "@/utils/http";
import { unwrapApiData } from "@/utils/chat99AdminApi";
import { displayDeviceModel } from "@/utils/deviceModelDisplay";
import type { ImUserUid } from "@/api/im-user";

export type AdminDeviceListParams = {
  page?: number;
  page_size?: number;
  user_uid?: ImUserUid;
  device_id?: string;
  device_fingerprint?: string;
  keyword?: string;
  system_type?: string;
  app_version?: string;
  login_ip?: string;
  status?: string;
  sort?: string;
};

export type AdminDeviceItem = {
  id: string;
  user_uid: ImUserUid;
  nickname?: string | null;
  device_id?: string | null;
  device_fingerprint?: string | null;
  device_name?: string | null;
  device_model?: string | null;
  brand?: string | null;
  system_type?: string | null;
  system_version?: string | null;
  app_version?: string | null;
  language?: string | null;
  timezone?: string | null;
  network_type?: string | null;
  carrier?: string | null;
  push_token_masked?: string | null;
  first_login_time?: string | number | null;
  last_login_time?: string | number | null;
  last_seen_at?: string | number | null;
  last_heartbeat_at?: string | number | null;
  online_until?: string | number | null;
  last_login_ip?: string | null;
  last_login_region?: string | null;
  status?: string | number | null;
  is_banned?: boolean;
  is_online?: boolean;
  raw?: Record<string, unknown>;
};

export type AdminDeviceListResponse = {
  items: AdminDeviceItem[];
  total: number;
  page: number;
  page_size: number;
};

function str(v: unknown): string | null {
  if (v == null || v === "") return null;
  return String(v);
}

function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function bool(v: unknown): boolean {
  return v === true || v === 1 || v === "1" || v === "true";
}

export function normalizeDeviceItem(raw: Record<string, unknown>): AdminDeviceItem {
  const id = String(
    raw.id ?? raw.device_record_id ?? raw.deviceRecordId ?? raw.device_id ?? raw.deviceId ?? ""
  );
  return {
    id,
    user_uid: String(raw.user_uid ?? raw.userUid ?? raw.uid ?? ""),
    nickname: str(raw.nickname ?? raw.user_nickname ?? raw.userNickname),
    device_id: str(raw.device_id ?? raw.deviceId),
    device_fingerprint: str(
      raw.device_fingerprint ?? raw.deviceFingerprint ?? raw.fingerprint ?? raw.hardware_id
    ),
    device_name: str(raw.device_name ?? raw.deviceName ?? raw.name),
    device_model: displayDeviceModel(
      str(raw.system_type ?? raw.systemType ?? raw.os ?? raw.platform),
      str(raw.device_model ?? raw.deviceModel ?? raw.model)
    ),
    brand: str(raw.brand),
    system_type: str(raw.system_type ?? raw.systemType ?? raw.os ?? raw.platform),
    system_version: str(raw.system_version ?? raw.systemVersion ?? raw.os_version),
    app_version: str(raw.app_version ?? raw.appVersion),
    language: str(raw.language),
    timezone: str(raw.timezone ?? raw.time_zone),
    network_type: str(raw.network_type ?? raw.networkType),
    carrier: str(raw.carrier),
    push_token_masked: str(raw.push_token_masked ?? raw.pushTokenMasked),
    first_login_time: (raw.first_login_time ?? raw.firstLoginTime ?? raw.created_at) as string | number | null,
    last_login_time: (raw.last_login_time ?? raw.lastLoginTime) as string | number | null,
    last_seen_at: (raw.last_seen_at ?? raw.lastSeenAt ?? raw.last_active_at ?? raw.lastActiveAt ?? raw.updated_at) as string | number | null,
    last_heartbeat_at: (raw.last_heartbeat_at ?? raw.lastHeartbeatAt ?? raw.heartbeat_at ?? raw.heartbeatAt) as string | number | null,
    online_until: (raw.online_until ?? raw.onlineUntil ?? raw.presence_expires_at ?? raw.presenceExpiresAt) as string | number | null,
    last_login_ip: str(raw.last_login_ip ?? raw.lastLoginIp ?? raw.login_ip),
    last_login_region: str(raw.last_login_region ?? raw.lastLoginRegion ?? raw.region),
    status: (raw.status ?? raw.device_status ?? raw.deviceStatus) as string | number | null,
    is_banned: bool(raw.is_banned ?? raw.isBanned ?? raw.banned),
    is_online: bool(raw.is_online ?? raw.isOnline ?? raw.online),
    raw
  };
}

export function normalizeDeviceListResponse(raw: Record<string, unknown>): AdminDeviceListResponse {
  const data = unwrapApiData(raw);
  const rows = Array.isArray(data.items)
    ? data.items
    : Array.isArray(data.list)
      ? data.list
      : [];
  return {
    items: rows.map(row => normalizeDeviceItem(row as Record<string, unknown>)),
    total: num(data.total, rows.length),
    page: num(data.page, 1),
    page_size: num(data.page_size ?? data.pageSize, 20)
  };
}

export async function getAdminDevices(params: AdminDeviceListParams) {
  const raw = await http.request<Record<string, unknown>>("get", "/api/v1/devices", {
    params
  });
  return normalizeDeviceListResponse(raw);
}

export async function getAdminDeviceDetail(id: string) {
  const raw = await http.request<Record<string, unknown>>("get", `/api/v1/devices/${encodeURIComponent(id)}`);
  const data = unwrapApiData(raw);
  return normalizeDeviceItem(data);
}

export async function getAdminDeviceSameUsers(id: string, params: { page?: number; page_size?: number } = {}) {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    `/api/v1/devices/${encodeURIComponent(id)}/same-users`,
    { params }
  );
  return unwrapApiData(raw) as { items?: Record<string, unknown>[]; total?: number; page?: number; page_size?: number };
}

export const banAdminDevice = (id: string, data: { remark?: string } = {}) =>
  http.request<Record<string, unknown>>("post", `/api/v1/devices/${encodeURIComponent(id)}/ban`, { data });

export const unbanAdminDevice = (id: string, data: { remark?: string } = {}) =>
  http.request<Record<string, unknown>>("post", `/api/v1/devices/${encodeURIComponent(id)}/unban`, { data });

export const kickAdminDevice = (id: string, data: { remark?: string } = {}) =>
  http.request<Record<string, unknown>>("post", `/api/v1/devices/${encodeURIComponent(id)}/kick`, { data });


const DEVICE_ONLINE_STALE_MS = 90 * 1000;

function parseDevicePresenceTime(value: unknown): number {
  if (value == null || value === "") return Number.NaN;
  if (typeof value === "number") {
    const ms = value < 1e12 ? value * 1000 : value;
    return Number.isFinite(ms) ? ms : Number.NaN;
  }
  const text = String(value).trim();
  if (!text) return Number.NaN;
  if (/^\d+$/.test(text)) {
    const n = Number(text);
    const ms = n < 1e12 ? n * 1000 : n;
    return Number.isFinite(ms) ? ms : Number.NaN;
  }
  const normalized = text.includes("T") ? text : text.replace(" ", "T");
  const t = Date.parse(normalized);
  return Number.isFinite(t) ? t : Number.NaN;
}

export function isAdminDeviceActuallyOnline(row: AdminDeviceItem): boolean {
  if (!row.is_online) return false;
  const until = parseDevicePresenceTime(row.online_until);
  if (Number.isFinite(until)) return until > Date.now();
  const t = parseDevicePresenceTime(row.last_heartbeat_at ?? row.last_seen_at);
  if (!Number.isFinite(t)) return false;
  return Date.now() - t <= DEVICE_ONLINE_STALE_MS;
}
