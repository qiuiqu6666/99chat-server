import { http } from "@/utils/http";

export type AdminLoginLogsParams = {
  page?: number;
  page_size?: number;
  sort?: string;
  admin_user_id?: string;
  username?: string;
  success?: string;
  ip?: string;
  login_at_from?: string;
  login_at_to?: string;
};

export type AdminLoginLogItem = {
  id?: number | string;
  admin_user_id?: string | null;
  username_attempted?: string | null;
  success?: number | boolean | string | null;
  ip?: string | null;
  geo_address?: string | null;
  user_agent?: string | null;
  fail_reason?: string | null;
  login_at?: number | string | null;
  admin_username?: string | null;
  admin_display_name?: string | null;
};

export type AdminAuditLogsParams = {
  page?: number;
  page_size?: number;
  sort?: string;
  admin_user_id?: string;
  action?: string;
  resource_type?: string;
  resource_id?: string;
  created_from?: string;
  created_to?: string;
};

export type AdminAuditLogItem = {
  id?: number | string;
  admin_user_id?: string | null;
  action?: string | null;
  resource_type?: string | null;
  resource_id?: string | null;
  detail?: unknown;
  ip?: string | null;
  geo_address?: string | null;
  created_at?: number | string | null;
  admin_username?: string | null;
  admin_display_name?: string | null;
};

type ListResponse<T> = {
  source?: string;
  items: T[];
  total: number;
  page?: number;
  page_size?: number;
  has_more?: boolean;
};

function unwrapList<T>(raw: unknown): ListResponse<T> {
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    const r = raw as Record<string, unknown>;
    const nested =
      r.data && typeof r.data === "object" && !Array.isArray(r.data)
        ? (r.data as Record<string, unknown>)
        : r;
    const arr = nested.items ?? nested.list ?? nested.records ?? [];
    return {
      source: String(nested.source ?? ""),
      items: Array.isArray(arr) ? (arr as T[]) : [],
      total: Number(nested.total ?? nested.count ?? 0),
      page: Number(nested.page ?? 1),
      page_size: Number(nested.page_size ?? nested.pageSize ?? 20),
      has_more: Boolean(nested.has_more ?? nested.hasMore)
    };
  }
  return { items: [], total: 0 };
}

export async function getAdminLoginLogsApi(params: AdminLoginLogsParams) {
  const raw = await http.request<unknown>("get", "/api/v1/admin/login-logs", { params });
  return unwrapList<AdminLoginLogItem>(raw);
}

export async function getAdminAuditLogsApi(params: AdminAuditLogsParams) {
  const raw = await http.request<unknown>("get", "/api/v1/admin/audit-logs", { params });
  return unwrapList<AdminAuditLogItem>(raw);
}
