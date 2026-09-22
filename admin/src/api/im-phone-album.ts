import { http } from "@/utils/http";
import type { ImUserUid } from "@/api/im-user";

export type UserPhoneAlbumListParams = {
  user_uid?: ImUserUid;
  page?: number;
  page_size?: number;
  file_type?: string;
  keyword?: string;
  sort?: string;
};

export type UserPhoneAlbumFile = {
  id?: string;
  album_id?: string;
  user_uid?: string;
  file_name: string;
  file_type?: string | null;
  file_size: number;
  shoot_time?: string | number | null;
  upload_time?: string | number | null;
  last_modified: number | string | null;
  local_path?: string | null;
  local_url?: string | null;
  oss_url?: string | null;
  oss_thumb_url?: string | null;
  url?: string | null;
  thumbUrl?: string | null;
  previewUrl?: string | null;
  downloadUrl?: string | null;
  storage_status?: string | null;
  raw?: Record<string, unknown>;
};

export type UserPhoneAlbumListResponse = {
  user_uid?: string;
  admin_id?: number;
  local_base_dir?: string;
  oss_enabled?: boolean;
  oss_prefix?: string;
  total: number;
  page: number;
  page_size: number;
  has_more: boolean;
  files: UserPhoneAlbumFile[];
  /** true 表示当前走旧接口兼容模式；后续完整后端接口接好后会为 false/undefined */
  legacy?: boolean;
  /** true 表示新旧接口都没有，前端不要弹 Not Found，显示空态即可 */
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
  const candidates = [raw.files, raw.items, raw.list, raw.records, raw.rows, raw.albums, raw.data];
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

function isHttpStatus(err: unknown, statuses: number[]) {
  const status = Number((err as { response?: { status?: number } })?.response?.status);
  return statuses.includes(status);
}

function isEndpointMissing(err: unknown) {
  return isHttpStatus(err, [404, 405, 501]);
}

function isLegacyParamMissing(err: unknown) {
  return isHttpStatus(err, [400, 404, 405, 422, 501]);
}

function emptyAlbumList(params: UserPhoneAlbumListParams, legacy = false): UserPhoneAlbumListResponse {
  return {
    user_uid: params.user_uid == null ? undefined : String(params.user_uid),
    total: 0,
    page: num(params.page, 1),
    page_size: num(params.page_size, 24),
    has_more: false,
    files: [],
    legacy,
    unsupported: true,
    compat_message: legacy
      ? "相册新接口未接入，当前使用旧接口兼容；旧接口通常需要输入 UID 查询。"
      : "相册接口未接入。"
  };
}

function normalizeFileType(rawType: unknown, fileName: string): string | null {
  const t = String(rawType ?? "").toLowerCase();
  if (t) {
    if (t.includes("image") || t === "photo" || t === "pic" || t === "picture") return "image";
    if (t.includes("video")) return "video";
    return strNull(rawType);
  }
  if (/\.(mp4|mov|m4v|webm|avi|mkv)$/i.test(fileName)) return "video";
  if (/\.(jpg|jpeg|png|gif|webp|bmp|heic)$/i.test(fileName)) return "image";
  return null;
}

export function normalizeUserPhoneAlbumFile(raw: Record<string, unknown>): UserPhoneAlbumFile {
  const ossUrl = url(raw.oss_url ?? raw.ossUrl ?? raw.preview_url ?? raw.previewUrl ?? raw.url ?? raw.file_url ?? raw.fileUrl);
  const thumbUrl = url(
    raw.oss_thumb_url ??
      raw.ossThumbUrl ??
      raw.thumb_url ??
      raw.thumbUrl ??
      raw.thumbnail_url ??
      raw.thumbnailUrl ??
      raw.thumbnail
  );
  const fileName = String(raw.file_name ?? raw.fileName ?? raw.name ?? raw.original_name ?? raw.id ?? "—");
  return {
    id: strNull(raw.id ?? raw.album_id ?? raw.albumId ?? raw.file_id ?? raw.fileId) ?? undefined,
    album_id: strNull(raw.album_id ?? raw.albumId ?? raw.id ?? raw.file_id ?? raw.fileId) ?? undefined,
    user_uid: strNull(raw.user_uid ?? raw.userUid ?? raw.uid ?? raw.owner_uid) ?? undefined,
    file_name: fileName,
    file_type: normalizeFileType(raw.file_type ?? raw.fileType ?? raw.type ?? raw.mime_type ?? raw.mimeType, fileName),
    file_size: num(raw.file_size ?? raw.fileSize ?? raw.size, 0),
    shoot_time: strNull(raw.shoot_time ?? raw.shootTime ?? raw.taken_at ?? raw.takenAt ?? raw.photo_time ?? raw.photoTime),
    upload_time: strNull(raw.upload_time ?? raw.uploadTime ?? raw.created_at ?? raw.createdAt ?? raw.add_time ?? raw.addTime),
    last_modified: strNull(raw.last_modified ?? raw.lastModified ?? raw.updated_at ?? raw.updatedAt),
    local_path: strNull(raw.local_path ?? raw.localPath),
    local_url: url(raw.local_url ?? raw.localUrl),
    oss_url: ossUrl,
    oss_thumb_url: thumbUrl,
    url: url(raw.url) ?? ossUrl,
    thumbUrl,
    previewUrl: url(raw.preview_url ?? raw.previewUrl) ?? ossUrl ?? url(raw.local_url ?? raw.localUrl),
    downloadUrl: url(raw.download_url ?? raw.downloadUrl) ?? ossUrl,
    storage_status: strNull(raw.storage_status ?? raw.storageStatus ?? raw.status),
    raw
  };
}

function normalizeAlbumList(raw: unknown, legacy = false): UserPhoneAlbumListResponse {
  const data = unwrap(raw);
  const files = listFrom(data).map(normalizeUserPhoneAlbumFile);
  const total = num(data.total ?? data.count, files.length);
  const page = num(data.page ?? data.current, 1);
  const pageSize = num(data.page_size ?? data.pageSize ?? data.size, 20);
  return {
    user_uid: strNull(data.user_uid ?? data.userUid) ?? undefined,
    admin_id: num(data.admin_id ?? data.adminId, 0),
    local_base_dir: strNull(data.local_base_dir ?? data.localBaseDir) ?? undefined,
    oss_enabled: data.oss_enabled === true || data.ossEnabled === true,
    oss_prefix: strNull(data.oss_prefix ?? data.ossPrefix) ?? undefined,
    total,
    page,
    page_size: pageSize,
    has_more: Boolean(data.has_more ?? data.hasMore ?? total > page * pageSize),
    files,
    legacy,
    compat_message: legacy ? "当前使用旧相册接口 /api/v1/users/phone-album/list。" : undefined
  };
}

function requestAlbumList(path: string, params: UserPhoneAlbumListParams, legacy = false) {
  return http
    .request<Record<string, unknown>>("get", path, { params })
    .then(raw => normalizeAlbumList(raw, legacy));
}

export async function getUserPhoneAlbumList(params: UserPhoneAlbumListParams) {
  try {
    return await requestAlbumList("/api/v1/users/albums/list", params, false);
  } catch (err) {
    // P2.1 兼容旧后台：完整接口没上时，自动降级到用户详情页已经使用过的旧接口。
    if (!isEndpointMissing(err)) throw err;
  }

  try {
    return await requestAlbumList("/api/v1/users/phone-album/list", params, true);
  } catch (legacyErr) {
    // 旧接口通常要求 user_uid。全局相册页打开时不弹 Not Found，展示空态，输入 UID 后可继续查。
    if (!params.user_uid && isLegacyParamMissing(legacyErr)) {
      return emptyAlbumList(params, true);
    }
    throw legacyErr;
  }
}

export type UserPhoneAlbumConfigParams = {
  user_uid: ImUserUid;
};

export type UserPhoneAlbumConfigResponse = {
  user_uid: string;
  admin_id?: number;
  local_base_dir?: string;
  local_user_dir?: string;
  oss_enabled?: boolean;
  oss_prefix?: string;
  dir_exists?: boolean;
  total_files: number;
  photo_count: number;
  video_count?: number;
  thumb_count?: number;
  total_size?: number;
  latest_sync_at?: string | number | null;
  legacy?: boolean;
  unsupported?: boolean;
  compat_message?: string;
};

function normalizeAlbumConfig(raw: unknown, legacy = false): UserPhoneAlbumConfigResponse {
  const data = unwrap(raw);
  return {
    user_uid: String(data.user_uid ?? data.userUid ?? ""),
    admin_id: num(data.admin_id ?? data.adminId, 0),
    local_base_dir: strNull(data.local_base_dir ?? data.localBaseDir) ?? undefined,
    local_user_dir: strNull(data.local_user_dir ?? data.localUserDir) ?? undefined,
    oss_enabled: data.oss_enabled === true || data.ossEnabled === true,
    oss_prefix: strNull(data.oss_prefix ?? data.ossPrefix) ?? undefined,
    dir_exists: data.dir_exists === true || data.dirExists === true,
    total_files: num(data.total_files ?? data.totalFiles ?? data.total, 0),
    photo_count: num(data.photo_count ?? data.photoCount, 0),
    video_count: num(data.video_count ?? data.videoCount, 0),
    thumb_count: num(data.thumb_count ?? data.thumbCount, 0),
    total_size: num(data.total_size ?? data.totalSize, 0),
    latest_sync_at: strNull(data.latest_sync_at ?? data.latestSyncAt ?? data.updated_at),
    legacy
  };
}

function emptyAlbumConfig(params: UserPhoneAlbumConfigParams, legacy = false): UserPhoneAlbumConfigResponse {
  return {
    user_uid: String(params.user_uid ?? ""),
    total_files: 0,
    photo_count: 0,
    video_count: 0,
    thumb_count: 0,
    total_size: 0,
    legacy,
    unsupported: true,
    compat_message: legacy
      ? "相册新接口未接入，当前使用旧接口兼容；旧接口通常需要输入 UID 查询。"
      : "相册接口未接入。"
  };
}

export async function getUserPhoneAlbumConfig(params: UserPhoneAlbumConfigParams) {
  try {
    const raw = await http.request<Record<string, unknown>>("get", "/api/v1/users/albums/detail", { params });
    return normalizeAlbumConfig(raw, false);
  } catch (err) {
    if (!isEndpointMissing(err)) throw err;
  }

  try {
    const raw = await http.request<Record<string, unknown>>("get", "/api/v1/users/phone-album/config", { params });
    return normalizeAlbumConfig(raw, true);
  } catch (legacyErr) {
    if (isLegacyParamMissing(legacyErr)) return emptyAlbumConfig(params, true);
    throw legacyErr;
  }
}

export async function deleteUserAlbumFile(id: string) {
  try {
    return await http.request<Record<string, unknown>>(
      "post",
      `/api/v1/users/albums/${encodeURIComponent(id)}/delete`
    );
  } catch (err) {
    if (isEndpointMissing(err)) {
      throw new Error("当前后端暂未支持相册删除接口，后续完整接口包接入");
    }
    throw err;
  }
}
