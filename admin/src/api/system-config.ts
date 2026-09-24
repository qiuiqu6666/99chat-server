import { http } from "@/utils/http";

function unwrap<T>(raw: unknown): T {
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    const r = raw as Record<string, unknown>;
    if (r.data && typeof r.data === "object" && !Array.isArray(r.data)) {
      return r.data as T;
    }
    return raw as T;
  }
  return raw as T;
}

export type PlatformConfig = {
  website: string;
  email: string;
  customer_service_url: string;
  feedback_prefix: string;
  max_feedback_screenshots: number;
  max_feedback_content_length: number;
};

export type PushBusinessConfig = {
  push_enabled: boolean;
  skip_when_online: boolean;
  voip_push_enabled: boolean;
  jpush_enabled: boolean;
  jpush_app_key: string;
  jpush_master_secret: string;
  jpush_base_url: string;
  im_callback_enabled: boolean;
  chat_push_enabled: boolean;
  callback_token: string;
  allowed_sdk_app_ids: string;
  chat_push_skip_when_online: boolean;
  skip_sender_ids: string;
  max_group_members_per_push: number;
  dedup_ttl_hours: number;
  group_create_limit_enabled: boolean;
  group_join_limit_max: number;
  group_join_limit_max_community: number;
  group_create_limit_max_community: number;
  group_create_limit_enforce: boolean;
  group_create_limit_log_only: boolean;
  group_create_limit_use_im_count_fallback: boolean;
  community_create_price_currency: string;
  community_create_price_minor: number;
  pay_pin_max_failures: number;
  pay_pin_lock_minutes: number;
  red_packet_expire_hours: number;
  min_deposit_usdt_micro: number;
  deposit_confirmations: number;
  deposit_mode: string;
  deposit_mnemonic_configured: boolean;
  hot_wallet_configured: boolean;
  trongrid_api_key: string;
  wallet_limits?: WalletLimitItem[];
};

export type WalletLimitItem = {
  id: number;
  scene: string;
  currency: string;
  scene_label?: string;
  currency_label?: string;
  per_tx_max: number;
  daily_max: number;
  enabled: boolean;
};

export type WalletLimitFormRow = WalletLimitItem & {
  per_tx_display: number;
  daily_display: number;
};

export type InfrastructureItem = {
  key: string;
  label: string;
  set: boolean;
  preview: string;
  secret: boolean;
};

export async function getPlatformConfig() {
  const raw = await http.request<unknown>("get", "/api/v1/admin/system-config/platform");
  return unwrap<PlatformConfig>(raw);
}

export async function updatePlatformConfig(data: Partial<PlatformConfig>) {
  const raw = await http.request<unknown>("put", "/api/v1/admin/system-config/platform", {
    data
  });
  return unwrap<PlatformConfig>(raw);
}

export async function getPushBusinessConfig() {
  const raw = await http.request<unknown>("get", "/api/v1/admin/system-config/push-business");
  return unwrap<PushBusinessConfig>(raw);
}

export async function updatePushBusinessConfig(data: Record<string, unknown>) {
  const raw = await http.request<unknown>("put", "/api/v1/admin/system-config/push-business", {
    data
  });
  return unwrap<PushBusinessConfig>(raw);
}

export async function getInfrastructureConfig() {
  const raw = await http.request<unknown>("get", "/api/v1/admin/system-config/infrastructure");
  return unwrap<{ items: InfrastructureItem[] }>(raw);
}

export async function updateInfrastructureKey(key: string, value: string) {
  const raw = await http.request<unknown>(
    "put",
    `/api/v1/admin/system-config/infrastructure/${encodeURIComponent(key)}`,
    { data: { value } }
  );
  return unwrap<InfrastructureItem>(raw);
}

/** micro(6位) → USDT */
export function microToUsdt(micro: number) {
  return micro / 1_000_000;
}

/** USDT → micro */
export function usdtToMicro(usdt: number) {
  return Math.round(usdt * 1_000_000);
}

/** fen → 元（99币） */
export function fenToYuan(fen: number) {
  return fen / 100;
}

/** 元 → fen */
export function yuanToFen(yuan: number) {
  return Math.round(yuan * 100);
}

export function isUsdtCurrency(currency: string) {
  return currency.toUpperCase() === "USDT";
}

export function limitRawToDisplay(currency: string, raw: number) {
  return isUsdtCurrency(currency) ? microToUsdt(raw) : fenToYuan(raw);
}

export function limitDisplayToRaw(currency: string, display: number) {
  return isUsdtCurrency(currency) ? usdtToMicro(display) : yuanToFen(display);
}

export function mapWalletLimitRows(items: WalletLimitItem[] = []): WalletLimitFormRow[] {
  return items.map(item => ({
    ...item,
    per_tx_display: limitRawToDisplay(item.currency, item.per_tx_max),
    daily_display: limitRawToDisplay(item.currency, item.daily_max)
  }));
}
