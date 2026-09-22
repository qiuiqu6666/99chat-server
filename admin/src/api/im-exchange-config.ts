import { http } from "@/utils/http";

export type ExchangeRatePreview = {
  base_usd_cny?: number | null;
  buy_cny_per_usdt?: number | null;
  sell_cny_per_usdt?: number | null;
  mid_cny_per_usdt?: number | null;
  example_one_usdt_to_platform_yuan?: number | null;
  fetched_at?: string | null;
  error?: string | null;
};

export type ExchangePlatformStats = {
  total_exchange_surplus_fen?: number;
  total_exchange_surplus_yuan?: number;
  total_fee_usdt_micro?: number;
  total_fee_platform_fen?: number;
};

export type ExchangeConfigResponse = {
  enabled: boolean;
  markup_bps: number;
  float_bps: number;
  min_withdraw_usdt_micro: number;
  updated_at?: string | null;
  frankfurter_url: string;
  exchange_rate_cache_seconds: number;
  rate_preview: ExchangeRatePreview;
  stats: ExchangePlatformStats;
};

export type ExchangeConfigUpdateBody = {
  enabled?: boolean;
  markup_bps?: number;
  float_bps?: number;
  min_withdraw_usdt_micro?: number;
  frankfurter_url?: string;
  exchange_rate_cache_seconds?: number;
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

function num(v: unknown, fallback = 0) {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function mapRatePreview(raw: Record<string, unknown>): ExchangeRatePreview {
  return {
    base_usd_cny: raw.base_usd_cny == null ? null : num(raw.base_usd_cny),
    buy_cny_per_usdt: raw.buy_cny_per_usdt == null ? null : num(raw.buy_cny_per_usdt),
    sell_cny_per_usdt: raw.sell_cny_per_usdt == null ? null : num(raw.sell_cny_per_usdt),
    mid_cny_per_usdt: raw.mid_cny_per_usdt == null ? null : num(raw.mid_cny_per_usdt),
    example_one_usdt_to_platform_yuan:
      raw.example_one_usdt_to_platform_yuan == null
        ? null
        : num(raw.example_one_usdt_to_platform_yuan),
    fetched_at: raw.fetched_at == null ? null : String(raw.fetched_at ?? raw.fetchedAt ?? ""),
    error: raw.error == null ? null : String(raw.error)
  };
}

function mapStats(raw: Record<string, unknown>): ExchangePlatformStats {
  return {
    total_exchange_surplus_fen: num(raw.total_exchange_surplus_fen ?? raw.totalExchangeSurplusFen),
    total_exchange_surplus_yuan: num(
      raw.total_exchange_surplus_yuan ?? raw.totalExchangeSurplusYuan
    ),
    total_fee_usdt_micro: num(raw.total_fee_usdt_micro ?? raw.totalFeeUsdtMicro),
    total_fee_platform_fen: num(raw.total_fee_platform_fen ?? raw.totalFeePlatformFen)
  };
}

function mapConfig(raw: Record<string, unknown>): ExchangeConfigResponse {
  const previewRaw =
    (raw.rate_preview as Record<string, unknown>) ??
    (raw.ratePreview as Record<string, unknown>) ??
    {};
  const statsRaw =
    (raw.stats as Record<string, unknown>) ?? {};
  return {
    enabled: raw.enabled === undefined ? true : Boolean(raw.enabled),
    markup_bps: num(raw.markup_bps ?? raw.markupBps),
    float_bps: num(raw.float_bps ?? raw.floatBps),
    min_withdraw_usdt_micro: num(raw.min_withdraw_usdt_micro ?? raw.minWithdrawUsdtMicro),
    updated_at: raw.updated_at == null ? null : String(raw.updated_at ?? raw.updatedAt ?? ""),
    frankfurter_url: String(raw.frankfurter_url ?? raw.frankfurterUrl ?? ""),
    exchange_rate_cache_seconds: num(
      raw.exchange_rate_cache_seconds ?? raw.exchangeRateCacheSeconds,
      300
    ),
    rate_preview: mapRatePreview(previewRaw),
    stats: mapStats(statsRaw)
  };
}

export async function getExchangeConfig() {
  const raw = unwrap(await http.request<Record<string, unknown>>("get", "/api/v1/wallet/exchange-config"));
  return mapConfig(raw);
}

export async function updateExchangeConfig(data: ExchangeConfigUpdateBody) {
  const raw = unwrap(
    await http.request<Record<string, unknown>>("put", "/api/v1/wallet/exchange-config", { data })
  );
  return mapConfig(raw);
}

/** micro(6位) → USDT 展示 */
export function microToUsdt(micro: number) {
  return micro / 1_000_000;
}

/** USDT → micro */
export function usdtToMicro(usdt: number) {
  return Math.round(usdt * 1_000_000);
}

/** bps → 百分比展示（100 bps = 1%） */
export function bpsToPercent(bps: number) {
  return bps / 100;
}

/** 百分比 → bps */
export function percentToBps(percent: number) {
  return Math.round(percent * 100);
}
