import { http } from "@/utils/http";

export type LifePaymentOrderItem = {
  order_no: string;
  client_order_id?: string | null;
  user_id?: string | null;
  service_type?: string | null;
  amount?: number | string | null;
  pay_method?: string | null;
  platform_pay_status?: string | null;
  plugin_status?: string | null;
  order_status?: string | null;
  account_no?: string | null;
  provider_name?: string | null;
  city_name?: string | null;
  execution_status?: string | null;
  receipt?: string | null;
  task_no?: string | null;
  paid_at?: string | null;
  created_at?: string | null;
  updated_at?: string | null;
};

export type LifePaymentTaskItem = {
  task_no: string;
  order_no?: string | null;
  query_no?: string | null;
  service_type?: string | null;
  task_action?: string | null;
  payment_status?: string | null;
  status?: string | null;
  attempt_count?: number;
  max_attempts?: number;
  locked_by?: string | null;
  locked_at?: string | null;
  heartbeat_at?: string | null;
  last_error_code?: string | null;
  last_error_message?: string | null;
  payload_json?: Record<string, unknown> | null;
  created_at?: string | null;
  updated_at?: string | null;
  finished_at?: string | null;
};

export type LifePaymentLogItem = {
  created_at?: string | null;
  actor_type?: string | null;
  actor_id?: string | null;
  action?: string | null;
  message?: string | null;
  task_no?: string | null;
  service_type?: string | null;
};

export type LifePaymentWorkerItem = {
  worker_id: string;
  device_id?: string | null;
  device_name?: string | null;
  support_service_types?: string[];
  status?: string | null;
  app_version?: string | null;
  last_online_at?: string | null;
  last_heartbeat_at?: string | null;
  last_offline_at?: string | null;
};

export type LifePaymentProviderItem = {
  provider_code: string;
  service_type?: string | null;
  country_code?: string | null;
  province_name?: string | null;
  city_name?: string | null;
  city_code?: string | null;
  provider_name?: string | null;
  provider_alias?: string | null;
  source?: string | null;
  enabled?: boolean;
  last_captured_at?: string | null;
  updated_at?: string | null;
};

export type LifePaymentPageResponse<T> = {
  items: T[];
  total: number;
  page: number;
  page_size: number;
  has_more?: boolean;
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

function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function asList<T>(raw: Record<string, unknown>): T[] {
  const items = raw.items ?? raw.list ?? raw.records;
  return Array.isArray(items) ? (items as T[]) : [];
}

function pageOf<T>(raw: Record<string, unknown>, page: number, pageSize: number): LifePaymentPageResponse<T> {
  const items = asList<T>(raw);
  return {
    items,
    total: num(raw.total, items.length),
    page: num(raw.page, page),
    page_size: num(raw.page_size ?? raw.pageSize, pageSize),
    has_more: Boolean(raw.has_more ?? raw.hasMore)
  };
}

export async function getLifePaymentOrders(params: {
  page?: number;
  page_size?: number;
  user_uid?: string;
  order_no?: string;
  service_type?: string;
  order_status?: string;
}) {
  const raw = unwrap(
    await http.request<Record<string, unknown>>("get", "/api/v1/life-payments/orders", { params })
  );
  return pageOf<LifePaymentOrderItem>(raw, params.page ?? 1, params.page_size ?? 20);
}

export async function getLifePaymentOrder(orderNo: string) {
  return unwrap(
    await http.request<Record<string, unknown>>(
      "get",
      `/api/v1/life-payments/orders/${encodeURIComponent(orderNo)}`
    )
  ) as unknown as LifePaymentOrderItem;
}

export async function getLifePaymentOrderLogs(orderNo: string) {
  const raw = unwrap(
    await http.request<Record<string, unknown>>(
      "get",
      `/api/v1/life-payments/orders/${encodeURIComponent(orderNo)}/logs`
    )
  );
  return { items: asList<LifePaymentLogItem>(raw) };
}

export function markLifePaymentOrderManual(orderNo: string, reason?: string) {
  return http.request(
    "post",
    `/api/v1/life-payments/orders/${encodeURIComponent(orderNo)}/manual`,
    { data: { reason: reason || undefined } }
  );
}

export async function getLifePaymentTasks(params: {
  page?: number;
  page_size?: number;
  task_no?: string;
  order_no?: string;
  service_type?: string;
  status?: string;
}) {
  const raw = unwrap(
    await http.request<Record<string, unknown>>("get", "/api/v1/life-payments/tasks", { params })
  );
  return pageOf<LifePaymentTaskItem>(raw, params.page ?? 1, params.page_size ?? 20);
}

export async function getLifePaymentTask(taskNo: string) {
  return unwrap(
    await http.request<Record<string, unknown>>(
      "get",
      `/api/v1/life-payments/tasks/${encodeURIComponent(taskNo)}`
    )
  ) as unknown as LifePaymentTaskItem;
}

export function retryLifePaymentTask(taskNo: string, reason?: string) {
  return http.request(
    "post",
    `/api/v1/life-payments/tasks/${encodeURIComponent(taskNo)}/retry`,
    { data: { reason: reason || undefined } }
  );
}

export async function getLifePaymentWorkers(params?: { status?: string }) {
  const raw = unwrap(
    await http.request<Record<string, unknown>>("get", "/api/v1/life-payments/workers", { params })
  );
  return { items: asList<LifePaymentWorkerItem>(raw) };
}

export async function getLifePaymentProviders(params: {
  page?: number;
  page_size?: number;
  service_type?: string;
  city_name?: string;
  city_code?: string;
  keyword?: string;
  enabled?: boolean | string;
}) {
  const raw = unwrap(
    await http.request<Record<string, unknown>>("get", "/api/v1/life-payments/providers", { params })
  );
  return pageOf<LifePaymentProviderItem>(raw, params.page ?? 1, params.page_size ?? 20);
}

export function setLifePaymentProviderEnabled(providerCode: string, enabled: boolean) {
  return http.request(
    "post",
    `/api/v1/life-payments/providers/${encodeURIComponent(providerCode)}/enabled`,
    { data: { enabled } }
  );
}

export function importLifePaymentProviders(data: {
  service_type: string;
  source?: string;
  items: Array<{
    city_name: string;
    city_code?: string;
    provider_name: string;
    provider_code?: string;
    province_name?: string;
  }>;
}) {
  return http.request<Record<string, unknown>>("post", "/api/v1/life-payments/providers/import", {
    data
  });
}
