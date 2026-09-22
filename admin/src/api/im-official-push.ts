import { http } from "@/utils/http";

export type OfficialPushScope = "all" | "uids" | "tag" | "platform";

export type OfficialPushAccount = {
  id: string;
  name: string;
  avatar_url?: string | null;
  status?: string | null;
  remark?: string | null;
};

export type OfficialPushTask = {
  id: string;
  title: string;
  content: string;
  account_id?: string | null;
  account_name?: string | null;
  scope: OfficialPushScope | string;
  target_count?: number | null;
  success_count?: number | null;
  fail_count?: number | null;
  status?: string | null;
  created_at?: string | null;
  scheduled_at?: string | null;
  sent_at?: string | null;
  creator?: string | null;
};

export type OfficialPushListParams = {
  page?: number;
  page_size?: number;
  keyword?: string;
  status?: string;
};

export type OfficialPushListResponse = {
  items: OfficialPushTask[];
  total: number;
  page: number;
  page_size: number;
  source?: string;
};

export type OfficialPushCreateBody = {
  title: string;
  content: string;
  account_id?: string;
  scope: OfficialPushScope;
  user_uids?: string[];
  scheduled_at?: string;
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
  const candidates = [
    raw.items,
    raw.list,
    raw.records,
    raw.rows,
    raw.tasks,
    raw.data
  ];
  for (const v of candidates) {
    if (Array.isArray(v)) return v;
  }
  return [];
}

function mapTask(raw: Record<string, unknown>): OfficialPushTask {
  return {
    id: String(raw.id ?? raw.task_id ?? raw.taskId ?? raw.push_id ?? raw.pushId ?? ""),
    title: String(raw.title ?? raw.subject ?? ""),
    content: String(raw.content ?? raw.body ?? raw.message ?? ""),
    account_id: str(raw.account_id ?? raw.accountId ?? raw.official_account_id ?? raw.officialAccountId),
    account_name: str(raw.account_name ?? raw.accountName ?? raw.official_name ?? raw.officialName),
    scope: String(raw.scope ?? raw.target_scope ?? raw.targetScope ?? "all"),
    target_count: raw.target_count != null || raw.targetCount != null ? num(raw.target_count ?? raw.targetCount) : null,
    success_count: raw.success_count != null || raw.successCount != null ? num(raw.success_count ?? raw.successCount) : null,
    fail_count: raw.fail_count != null || raw.failCount != null ? num(raw.fail_count ?? raw.failCount) : null,
    status: str(raw.status ?? raw.state),
    created_at: str(raw.created_at ?? raw.createdAt),
    scheduled_at: str(raw.scheduled_at ?? raw.scheduledAt),
    sent_at: str(raw.sent_at ?? raw.sentAt),
    creator: str(raw.creator ?? raw.created_by ?? raw.createdBy)
  };
}

function mapAccount(raw: Record<string, unknown>): OfficialPushAccount {
  return {
    id: String(raw.id ?? raw.account_id ?? raw.accountId ?? raw.uid ?? ""),
    name: String(raw.name ?? raw.nickname ?? raw.account_name ?? raw.accountName ?? "官方账号"),
    avatar_url: str(raw.avatar_url ?? raw.avatarUrl ?? raw.avatar),
    status: str(raw.status ?? raw.state),
    remark: str(raw.remark ?? raw.description)
  };
}

export async function getOfficialPushTasks(params: OfficialPushListParams) {
  const raw = unwrap(
    await http.request<Record<string, unknown>>("get", "/api/v1/official-push/tasks", { params })
  );
  const itemsRaw = pickArray(raw);
  return {
    items: itemsRaw.map(it => mapTask((it ?? {}) as Record<string, unknown>)),
    total: num(raw.total ?? raw.count, itemsRaw.length),
    page: num(raw.page, params.page ?? 1),
    page_size: num(raw.page_size ?? raw.pageSize, params.page_size ?? 10),
    source: "official-push"
  } as OfficialPushListResponse;
}

export async function getOfficialPushAccounts() {
  const raw = unwrap(
    await http.request<Record<string, unknown>>("get", "/api/v1/official-push/accounts")
  );
  const itemsRaw = pickArray(raw);
  return itemsRaw.map(it => mapAccount((it ?? {}) as Record<string, unknown>));
}

export function createOfficialPushTask(data: OfficialPushCreateBody) {
  return http.request<Record<string, unknown>>("post", "/api/v1/official-push/tasks", { data });
}

export function retryOfficialPushTask(id: string) {
  return http.request<Record<string, unknown>>("post", `/api/v1/official-push/tasks/${encodeURIComponent(id)}/retry`);
}

export function cancelOfficialPushTask(id: string) {
  return http.request<Record<string, unknown>>("post", `/api/v1/official-push/tasks/${encodeURIComponent(id)}/cancel`);
}
