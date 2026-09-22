import { http } from "@/utils/http";
import type { ImUserUid } from "@/api/im-user";

export type ContactBookListParams = {
  page?: number;
  page_size?: number;
  user_uid?: ImUserUid;
  keyword?: string;
  contact_phone?: string;
  contact_name?: string;
  hit_platform_user?: string | number | boolean;
  sort?: string;
};

export type ContactBookItem = {
  id: string;
  user_uid: string;
  user_nickname: string | null;
  contact_name: string | null;
  contact_phone: string | null;
  contact_phone_masked: string | null;
  contact_remark: string | null;
  source: string | null;
  is_platform_user: boolean;
  related_uid: string | null;
  first_uploaded_at: string | number | null;
  last_updated_at: string | number | null;
  raw: Record<string, unknown>;
};

export type ContactBookListResponse = {
  items: ContactBookItem[];
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
  const candidates = [raw.items, raw.list, raw.records, raw.rows, raw.contacts, raw.data];
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

function bool(v: unknown): boolean {
  return v === true || v === 1 || v === "1" || v === "true" || v === "yes";
}

function maskPhone(v: unknown): string | null {
  const s = strNull(v);
  if (!s) return null;
  if (s.includes("*")) return s;
  const digits = s.replace(/\D/g, "");
  if (digits.length < 7) return s;
  return `${digits.slice(0, 3)}****${digits.slice(-4)}`;
}

function isEndpointMissing(err: unknown) {
  const status = Number((err as { response?: { status?: number } })?.response?.status);
  return [404, 405, 501].includes(status);
}

function emptyContactBookList(params: ContactBookListParams): ContactBookListResponse {
  return {
    items: [],
    total: 0,
    page: num(params.page, 1),
    page_size: num(params.page_size, 20),
    unsupported: true,
    compat_message: "通讯录接口暂未接入，列表为空。"
  };
}

export function normalizeContactBookItem(raw: Record<string, unknown>): ContactBookItem {
  const contactPhone = strNull(
    raw.contact_phone ?? raw.contactPhone ?? raw.phone ?? raw.mobile ?? raw.contact_mobile ?? raw.contactMobile
  );
  return {
    id: String(raw.id ?? raw.contact_id ?? raw.contactId ?? raw.row_id ?? raw.rowId ?? ""),
    user_uid: String(raw.user_uid ?? raw.userUid ?? raw.uid ?? raw.owner_uid ?? ""),
    user_nickname: strNull(raw.user_nickname ?? raw.userNickname ?? raw.nickname ?? raw.owner_nickname),
    contact_name: strNull(raw.contact_name ?? raw.contactName ?? raw.name ?? raw.display_name ?? raw.displayName),
    contact_phone: contactPhone,
    contact_phone_masked:
      strNull(raw.contact_phone_masked ?? raw.contactPhoneMasked ?? raw.phone_masked ?? raw.phoneMasked) ??
      maskPhone(contactPhone),
    contact_remark: strNull(raw.contact_remark ?? raw.contactRemark ?? raw.remark ?? raw.memo),
    source: strNull(raw.source ?? raw.contact_source ?? raw.contactSource),
    is_platform_user: bool(
      raw.is_platform_user ?? raw.isPlatformUser ?? raw.hit_platform_user ?? raw.hitPlatformUser ?? raw.registered
    ),
    related_uid: strNull(raw.related_uid ?? raw.relatedUid ?? raw.platform_uid ?? raw.platformUid ?? raw.match_uid ?? raw.matchUid),
    first_uploaded_at: strNull(raw.first_uploaded_at ?? raw.firstUploadedAt ?? raw.uploaded_at ?? raw.created_at ?? raw.createdAt),
    last_updated_at: strNull(raw.last_updated_at ?? raw.lastUpdatedAt ?? raw.updated_at ?? raw.updatedAt ?? raw.sync_time),
    raw
  };
}

export function normalizeContactBookListResponse(raw: unknown): ContactBookListResponse {
  const data = unwrap(raw);
  const items = listFrom(data).map(normalizeContactBookItem);
  return {
    items,
    total: num(data.total ?? data.count, items.length),
    page: num(data.page ?? data.current, 1),
    page_size: num(data.page_size ?? data.pageSize ?? data.size, 20)
  };
}

async function requestContactBook(path: string, params: ContactBookListParams) {
  const raw = await http.request<Record<string, unknown>>("get", path, { params });
  return normalizeContactBookListResponse(raw);
}

export async function getContactBookList(params: ContactBookListParams) {
  try {
    return await requestContactBook("/api/v1/users/contact-book/list", params);
  } catch (err) {
    if (isEndpointMissing(err)) return emptyContactBookList(params);
    throw err;
  }
}

export async function searchContactBook(params: ContactBookListParams) {
  try {
    return await requestContactBook("/api/v1/users/contact-book/search", params);
  } catch (err) {
    if (isEndpointMissing(err)) {
      try {
        return await requestContactBook("/api/v1/users/contact-book/list", params);
      } catch (listErr) {
        if (isEndpointMissing(listErr)) return emptyContactBookList(params);
        throw listErr;
      }
    }
    throw err;
  }
}
