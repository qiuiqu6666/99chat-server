import { http } from "@/utils/http";

export type ImCurrencyItem = {
  code: string;
  name: string;
  logo_url?: string | null;
  platform_coin?: boolean;
  deposit_enabled?: boolean;
  withdraw_enabled?: boolean;
  sort_order?: number;
  enabled?: boolean;
  withdraw_fee_applicable?: boolean;
  withdraw_fee_type?: string;
  withdraw_fee_value?: number;
  withdraw_fee_min?: number | null;
  withdraw_fee_max?: number | null;
  withdraw_fee_enabled?: boolean;
  withdraw_fee_label?: string;
  updated_at?: string | null;
};

export type ImCurrencyListResponse = {
  items: ImCurrencyItem[];
  total: number;
};

export type ImCurrencyUpdateBody = {
  name?: string;
  logo_url?: string;
  platform_coin?: boolean;
  deposit_enabled?: boolean;
  withdraw_enabled?: boolean;
  sort_order?: number;
  enabled?: boolean;
  withdraw_fee_type?: "NONE" | "FIXED" | "PERCENT";
  withdraw_fee_value?: number;
  withdraw_fee_min?: number | null;
  withdraw_fee_max?: number | null;
  withdraw_fee_enabled?: boolean;
};

export type ImCurrencyLogoUploadResponse = {
  ok?: boolean;
  logo_url?: string;
  object_key?: string;
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

function mapCurrency(raw: Record<string, unknown>): ImCurrencyItem {
  return {
    code: String(raw.code ?? ""),
    name: String(raw.name ?? ""),
    logo_url: raw.logo_url == null ? null : String(raw.logo_url ?? raw.logoUrl ?? ""),
    platform_coin: Boolean(raw.platform_coin ?? raw.platformCoin),
    deposit_enabled: Boolean(raw.deposit_enabled ?? raw.depositEnabled),
    withdraw_enabled: Boolean(raw.withdraw_enabled ?? raw.withdrawEnabled),
    sort_order: Number(raw.sort_order ?? raw.sortOrder ?? 0),
    enabled: raw.enabled == null ? true : Boolean(raw.enabled),
    withdraw_fee_applicable: Boolean(raw.withdraw_fee_applicable ?? raw.withdrawFeeApplicable),
    withdraw_fee_type: String(raw.withdraw_fee_type ?? raw.withdrawFeeType ?? "NONE"),
    withdraw_fee_value: Number(raw.withdraw_fee_value ?? raw.withdrawFeeValue ?? 0),
    withdraw_fee_min:
      raw.withdraw_fee_min == null && raw.withdrawFeeMin == null
        ? null
        : Number(raw.withdraw_fee_min ?? raw.withdrawFeeMin),
    withdraw_fee_max:
      raw.withdraw_fee_max == null && raw.withdrawFeeMax == null
        ? null
        : Number(raw.withdraw_fee_max ?? raw.withdrawFeeMax),
    withdraw_fee_enabled: Boolean(raw.withdraw_fee_enabled ?? raw.withdrawFeeEnabled),
    withdraw_fee_label: String(raw.withdraw_fee_label ?? raw.withdrawFeeLabel ?? "—"),
    updated_at: raw.updated_at == null ? null : String(raw.updated_at ?? raw.updatedAt ?? "")
  };
}

export async function getCurrencyList() {
  const raw = unwrap(await http.request<Record<string, unknown>>("get", "/api/v1/currencies"));
  const itemsRaw = Array.isArray(raw.items) ? raw.items : [];
  return {
    items: itemsRaw.map(it => mapCurrency((it ?? {}) as Record<string, unknown>)),
    total: Number(raw.total ?? itemsRaw.length)
  } as ImCurrencyListResponse;
}

export function updateCurrency(code: string, data: ImCurrencyUpdateBody) {
  return http.request<ImCurrencyItem>("put", `/api/v1/currencies/${encodeURIComponent(code)}`, {
    data
  });
}

export function microToUsdt(micro: number) {
  return micro / 1_000_000;
}

export function usdtToMicro(usdt: number) {
  return Math.round(usdt * 1_000_000);
}

export function bpsToPercent(bps: number) {
  return bps / 100;
}

export function percentToBps(percent: number) {
  return Math.round(percent * 100);
}

export function uploadCurrencyLogo(file: File) {
  const formData = new FormData();
  formData.append("file", file);
  return http.request<ImCurrencyLogoUploadResponse>("post", "/api/v1/currencies/upload-logo", {
    data: formData,
    headers: { "Content-Type": "multipart/form-data" }
  });
}
