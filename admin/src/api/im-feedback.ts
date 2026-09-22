import { http } from "@/utils/http";

export type ImFeedbackItem = {
  id: string;
  user_uid?: string | null;
  nickname?: string | null;
  phone?: string | null;
  category?: string | null;
  content: string;
  contact?: string | null;
  images?: string[];
  status?: string | null;
  status_label?: string | null;
  reply?: string | null;
  client_version?: string | null;
  created_at?: string | null;
  updated_at?: string | null;
};

export type ImFeedbackListParams = {
  page?: number;
  page_size?: number;
  keyword?: string;
  user_uid?: string;
  status?: string;
};

export type ImFeedbackListResponse = {
  items: ImFeedbackItem[];
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
  const candidates = [raw.items, raw.list, raw.records, raw.rows, raw.feedbacks, raw.data];
  for (const v of candidates) {
    if (Array.isArray(v)) return v;
  }
  return [];
}

function parseImages(raw: Record<string, unknown>): string[] {
  const v = raw.images ?? raw.image_urls ?? raw.imageUrls ?? raw.attachments;
  if (Array.isArray(v)) {
    return v.map(it => {
      if (typeof it === "string") return it;
      if (it && typeof it === "object") {
        const row = it as Record<string, unknown>;
        return String(row.url ?? row.oss_url ?? row.previewUrl ?? row.preview_url ?? "");
      }
      return "";
    }).filter(Boolean);
  }
  if (typeof v === "string" && v.trim()) {
    try {
      const arr = JSON.parse(v);
      if (Array.isArray(arr)) return arr.map(String).filter(Boolean);
    } catch (_err) {
      return v.split(/[，,\n]/g).map(s => s.trim()).filter(Boolean);
    }
  }
  return [];
}

function mapFeedback(raw: Record<string, unknown>): ImFeedbackItem {
  return {
    id: String(raw.id ?? raw.feedback_id ?? raw.feedbackId ?? ""),
    user_uid: str(raw.user_uid ?? raw.userUid ?? raw.uid),
    nickname: str(raw.nickname ?? raw.user_nickname ?? raw.userNickname),
    phone: str(raw.phone ?? raw.phone_num ?? raw.phoneNum),
    category: str(raw.category ?? raw.type),
    content: String(raw.content ?? raw.message ?? raw.desc ?? raw.description ?? ""),
    contact: str(raw.contact ?? raw.contact_info ?? raw.contactInfo),
    images: parseImages(raw),
    status: str(raw.status ?? raw.state),
    status_label: str(raw.status_label ?? raw.statusLabel),
    reply: str(raw.reply ?? raw.reply_content ?? raw.replyContent),
    client_version: str(raw.client_version ?? raw.clientVersion),
    created_at: str(raw.created_at ?? raw.createdAt ?? raw.create_time ?? raw.createTime),
    updated_at: str(raw.updated_at ?? raw.updatedAt ?? raw.update_time ?? raw.updateTime)
  };
}

const FEEDBACK_LIST_URLS = [
  "/api/v1/feedback",
  "/api/v1/feedback/list",
  "/api/v1/feedbacks",
  "/api/v1/user-feedback/list"
];

function isMissingEndpoint(err: unknown) {
  const ax = err as { response?: { status?: number } };
  return [404, 405, 501].includes(Number(ax?.response?.status));
}

export async function getFeedbackList(params: ImFeedbackListParams) {
  let lastErr: unknown;
  for (const url of FEEDBACK_LIST_URLS) {
    try {
      const raw = unwrap(await http.request<Record<string, unknown>>("get", url, { params }));
      const itemsRaw = pickArray(raw);
      return {
        items: itemsRaw.map(it => mapFeedback((it ?? {}) as Record<string, unknown>)),
        total: num(raw.total ?? raw.count, itemsRaw.length),
        page: num(raw.page, params.page ?? 1),
        page_size: num(raw.page_size ?? raw.pageSize, params.page_size ?? 10),
        source: url
      } as ImFeedbackListResponse;
    } catch (err: unknown) {
      lastErr = err;
      if (!isMissingEndpoint(err)) throw err;
    }
  }
  return {
    items: [],
    total: 0,
    page: params.page ?? 1,
    page_size: params.page_size ?? 10,
    source: "missing"
  } as ImFeedbackListResponse;
}

export function updateFeedbackStatus(id: string, data: { status: string; reply?: string }) {
  return http.request<Record<string, unknown>>(
    "post",
    `/api/v1/feedback/${encodeURIComponent(id)}/status`,
    { data }
  );
}
