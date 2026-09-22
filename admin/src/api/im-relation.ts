import { http } from "@/utils/http";
import type { ImUserUid } from "@/api/im-user";

export type AdminRelationListParams = {
  user_uid: ImUserUid;
  page?: number;
  page_size?: number;
  keyword?: string;
};

export type AdminRelationListResponse = {
  items: Record<string, unknown>[];
  total: number;
  page: number;
  page_size: number;
  truncated?: boolean;
  hint?: string;
};

function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function normalizePaged(raw: Record<string, unknown>): AdminRelationListResponse {
  const items = Array.isArray(raw.items) ? (raw.items as Record<string, unknown>[]) : [];
  return {
    items,
    total: num(raw.total, items.length),
    page: num(raw.page, 1),
    page_size: num(raw.page_size ?? raw.pageSize, 20),
    truncated: raw.truncated === true,
    hint: typeof raw.hint === "string" ? raw.hint : undefined
  };
}

/** `GET /api/v1/relations/friends` */
export async function getUserFriends(params: AdminRelationListParams) {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/relations/friends",
    {
      params: {
        user_uid: params.user_uid,
        page: params.page,
        page_size: params.page_size,
        keyword: params.keyword?.trim() || undefined
      }
    }
  );
  return normalizePaged(raw);
}

/** `GET /api/v1/relations/groups` */
export async function getUserGroups(params: AdminRelationListParams) {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/relations/groups",
    {
      params: {
        user_uid: params.user_uid,
        page: params.page,
        page_size: params.page_size,
        keyword: params.keyword?.trim() || undefined
      }
    }
  );
  return normalizePaged(raw);
}

/** `GET /api/v1/relations/same-ip` */
export async function getRelationsSameIp(params: {
  user_uid: ImUserUid;
  ip?: string;
  page?: number;
  page_size?: number;
}) {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/relations/same-ip",
    {
      params: {
        user_uid: params.user_uid,
        ip: params.ip?.trim() || undefined,
        page: params.page,
        page_size: params.page_size
      }
    }
  );
  return normalizePaged(raw);
}

/** `GET /api/v1/relations/same-device`（user_uid / device_id 至少填一项） */
export async function getRelationsSameDevice(params: {
  user_uid?: ImUserUid;
  device_id?: string;
  page?: number;
  page_size?: number;
}) {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/relations/same-device",
    {
      params: {
        user_uid: params.user_uid?.trim() || undefined,
        device_id: params.device_id?.trim() || undefined,
        page: params.page,
        page_size: params.page_size
      }
    }
  );
  return normalizePaged(raw);
}
