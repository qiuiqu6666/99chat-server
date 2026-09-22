import { http } from "@/utils/http";

export type AnnouncementContentType = "text" | "image" | "video";
export type AnnouncementScope = "all" | "uids";

export type ImAnnouncementSendBody = {
  content_type: AnnouncementContentType;
  content?: string;
  image_url?: string;
  preview_url?: string;
  thumb_url?: string;
  width?: number;
  height?: number;
  image_size?: number;
  preview_size?: number;
  thumb_size?: number;
  thumb_width?: number;
  thumb_height?: number;
  video_url?: string;
  video_size?: number;
  video_second?: number;
  scope: AnnouncementScope;
  user_uids?: string[];
  scheduled_at?: string;
};

export type ImAnnouncementSendResponse = {
  ok?: boolean;
  content_type?: string;
  scope?: string;
  sent_count?: number;
  announcement_ids?: string[];
  im_push_queued?: boolean;
  scheduled?: boolean;
  scheduled_at?: string;
};

export type ImAnnouncementImageUploadResponse = {
  ok?: boolean;
  image_url?: string;
  preview_url?: string;
  thumb_url?: string;
  width?: number;
  height?: number;
  image_size?: number;
  preview_size?: number;
  thumb_size?: number;
  thumb_width?: number;
  thumb_height?: number;
  object_key?: string;
};

export type ImAnnouncementVideoUploadResponse = {
  ok?: boolean;
  video_url?: string;
  thumb_url?: string;
  video_size?: number;
  object_key?: string;
};

export function sendImAnnouncement(data: ImAnnouncementSendBody) {
  return http.request<ImAnnouncementSendResponse>("post", "/api/v1/announcements/send", {
    data
  });
}

export function uploadAnnouncementImage(file: File) {
  const formData = new FormData();
  formData.append("file", file);
  return http.request<ImAnnouncementImageUploadResponse>(
    "post",
    "/api/v1/announcements/upload-image",
    {
      data: formData,
      headers: { "Content-Type": "multipart/form-data" }
    }
  );
}

export function uploadAnnouncementVideo(file: File) {
  const formData = new FormData();
  formData.append("file", file);
  return http.request<ImAnnouncementVideoUploadResponse>(
    "post",
    "/api/v1/announcements/upload-video",
    {
      data: formData,
      headers: { "Content-Type": "multipart/form-data" }
    }
  );
}

export type ImAnnouncementLogItem = {
  id: string;
  content_type?: string | null;
  content_type_label?: string | null;
  content_summary?: string | null;
  preview_url?: string | null;
  thumb_url?: string | null;
  media_url?: string | null;
  scope_type?: string | null;
  scope_label?: string | null;
  target_user_id?: string | null;
  status?: string | null;
  status_label?: string | null;
  im_push_status?: string | null;
  im_push_status_label?: string | null;
  created_by?: string | null;
  publish_at?: string | null;
  created_at?: string | null;
  updated_at?: string | null;
};

export type ImAnnouncementListParams = {
  page?: number;
  page_size?: number;
  keyword?: string;
  content_type?: AnnouncementContentType | "";
  scope_type?: "global" | "personal" | "";
  target_user_id?: string;
  im_push_status?: string;
  status?: string;
  created_by?: string;
};

export type ImAnnouncementListResponse = {
  items: ImAnnouncementLogItem[];
  total: number;
  page: number;
  page_size: number;
  source?: string;
};

function str(v: unknown): string | null {
  if (v == null || v === "") return null;
  return String(v);
}

function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
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

function pickArray(raw: Record<string, unknown>): unknown[] {
  const candidates = [raw.items, raw.list, raw.records, raw.rows, raw.announcements, raw.data];
  for (const v of candidates) {
    if (Array.isArray(v)) return v;
  }
  return [];
}

function mapAnnouncementLog(raw: Record<string, unknown>): ImAnnouncementLogItem {
  return {
    id: String(raw.id ?? raw.announcement_id ?? raw.announcementId ?? ""),
    content_type: str(raw.content_type ?? raw.contentType),
    content_type_label: str(raw.content_type_label ?? raw.contentTypeLabel),
    content_summary: str(raw.content_summary ?? raw.contentSummary ?? raw.summary),
    preview_url: str(raw.preview_url ?? raw.previewUrl),
    thumb_url: str(raw.thumb_url ?? raw.thumbUrl),
    media_url: str(raw.media_url ?? raw.mediaUrl),
    scope_type: str(raw.scope_type ?? raw.scopeType),
    scope_label: str(raw.scope_label ?? raw.scopeLabel),
    target_user_id: str(raw.target_user_id ?? raw.targetUserId),
    status: str(raw.status),
    status_label: str(raw.status_label ?? raw.statusLabel),
    im_push_status: str(raw.im_push_status ?? raw.imPushStatus),
    im_push_status_label: str(raw.im_push_status_label ?? raw.imPushStatusLabel),
    created_by: str(raw.created_by ?? raw.createdBy),
    publish_at: str(raw.publish_at ?? raw.publishAt),
    created_at: str(raw.created_at ?? raw.createdAt),
    updated_at: str(raw.updated_at ?? raw.updatedAt)
  };
}

export async function getAnnouncementList(params: ImAnnouncementListParams) {
  const raw = unwrap(
    await http.request<Record<string, unknown>>("get", "/api/v1/announcements", { params })
  );
  const itemsRaw = pickArray(raw);
  return {
    items: itemsRaw.map(it => mapAnnouncementLog((it ?? {}) as Record<string, unknown>)),
    total: num(raw.total ?? raw.count, itemsRaw.length),
    page: num(raw.page, params.page ?? 1),
    page_size: num(raw.page_size ?? raw.pageSize, params.page_size ?? 10),
    source: "/api/v1/announcements"
  } as ImAnnouncementListResponse;
}
