import { http } from "@/utils/http";
import type { ImUserUid } from "@/api/im-user";

export type StorageFileListParams = {
  page?: number;
  page_size?: number;
  user_uid?: ImUserUid;
  keyword?: string;
  file_type?: string;
  storage_status?: string;
  sort?: string;
};

export type StorageFileItem = {
  id: string;
  file_id: string;
  user_uid: string | null;
  nickname: string | null;
  file_type: string | null;
  file_name: string | null;
  file_size: number;
  file_hash: string | null;
  storage_status: string | null;
  url: string | null;
  thumb_url: string | null;
  preview_url: string | null;
  download_url: string | null;
  created_at: string | number | null;
  raw: Record<string, unknown>;
};

export type StorageFileListResponse = {
  items: StorageFileItem[];
  total: number;
  page: number;
  page_size: number;
  unsupported?: boolean;
  compat_message?: string;
};

function unwrap(raw: unknown): Record<string, unknown> {
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    const obj = raw as Record<string, unknown>;
    if (obj.data && typeof obj.data === "object" && !Array.isArray(obj.data)) {
      return obj.data as Record<string, unknown>;
    }
    return obj;
  }
  return {};
}

function listFrom(raw: Record<string, unknown>): Record<string, unknown>[] {
  const candidates = [raw.items, raw.list, raw.records, raw.rows, raw.files, raw.data];
  for (const it of candidates) {
    if (Array.isArray(it)) return it as Record<string, unknown>[];
  }
  return [];
}

function strNull(v: unknown): string | null {
  if (v == null || v === "") return null;
  return String(v);
}

function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function url(v: unknown): string | null {
  const s = strNull(v)?.trim().replace(/^`+|`+$/g, "");
  return s || null;
}

function isEndpointMissing(err: unknown) {
  const status = Number((err as { response?: { status?: number } })?.response?.status);
  return [404, 405, 501].includes(status);
}

export function normalizeStorageFileItem(raw: Record<string, unknown>): StorageFileItem {
  const preview = url(raw.preview_url ?? raw.previewUrl ?? raw.url ?? raw.oss_url ?? raw.ossUrl);
  const download = url(raw.download_url ?? raw.downloadUrl ?? raw.url ?? raw.oss_url ?? raw.ossUrl);
  return {
    id: String(raw.id ?? raw.file_id ?? raw.fileId ?? ""),
    file_id: String(raw.file_id ?? raw.fileId ?? raw.id ?? ""),
    user_uid: strNull(raw.user_uid ?? raw.userUid ?? raw.uid),
    nickname: strNull(raw.nickname ?? raw.user_nickname ?? raw.userNickname),
    file_type: strNull(raw.file_type ?? raw.fileType ?? raw.type),
    file_name: strNull(raw.file_name ?? raw.fileName ?? raw.name),
    file_size: num(raw.file_size ?? raw.fileSize ?? raw.size, 0),
    file_hash: strNull(raw.file_hash ?? raw.fileHash ?? raw.hash),
    storage_status: strNull(raw.storage_status ?? raw.storageStatus ?? raw.status),
    url: url(raw.url ?? raw.oss_url ?? raw.ossUrl),
    thumb_url: url(raw.thumb_url ?? raw.thumbUrl ?? raw.oss_thumb_url ?? raw.ossThumbUrl),
    preview_url: preview,
    download_url: download,
    created_at: strNull(raw.created_at ?? raw.createdAt ?? raw.upload_time ?? raw.uploadTime),
    raw
  };
}

export function normalizeStorageFileListResponse(raw: unknown): StorageFileListResponse {
  const data = unwrap(raw);
  const items = listFrom(data).map(normalizeStorageFileItem);
  return {
    items,
    total: num(data.total ?? data.count, items.length),
    page: num(data.page ?? data.current, 1),
    page_size: num(data.page_size ?? data.pageSize ?? data.size, 20)
  };
}

function emptyStorageFiles(params: StorageFileListParams): StorageFileListResponse {
  return {
    items: [],
    total: 0,
    page: num(params.page, 1),
    page_size: num(params.page_size, 20),
    unsupported: true,
    compat_message: "文件管理功能尚未接入，暂无法查询或删除用户上传文件。"
  };
}

export async function getStorageFiles(params: StorageFileListParams) {
  try {
    const raw = await http.request<Record<string, unknown>>("get", "/api/v1/storage/files", { params });
    return normalizeStorageFileListResponse(raw);
  } catch (err) {
    // P2.1 只做兼容包：文件管理完整接口未上线时不弹 Not Found，显示空态。
    if (isEndpointMissing(err)) return emptyStorageFiles(params);
    throw err;
  }
}

export async function getStorageFileDetail(id: string) {
  return http.request<Record<string, unknown>>("get", `/api/v1/storage/files/${encodeURIComponent(id)}`);
}

export async function deleteStorageFile(id: string) {
  try {
    return await http.request<Record<string, unknown>>("post", `/api/v1/storage/files/${encodeURIComponent(id)}/delete`);
  } catch (err) {
    if (isEndpointMissing(err)) {
      throw new Error("当前后端暂未支持文件删除接口，后续完整接口包接入");
    }
    throw err;
  }
}
