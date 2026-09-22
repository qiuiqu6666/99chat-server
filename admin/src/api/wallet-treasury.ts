import { http } from "@/utils/http";

export type TreasurySummary = {
  platform_usdt_total?: string;
  platform_trx_total?: string;
  chain_usdt_total?: string;
  chain_trx_total?: string;
  hot_wallet_address?: string | null;
  hot_wallet_usdt?: string;
  hot_wallet_trx?: string;
  wallet_count?: number;
  collect_ready?: boolean;
};

export type TreasuryWalletItem = {
  user_uid?: string;
  nickname?: string;
  tron_address?: string;
  platform_usdt?: string;
  platform_trx?: string;
  chain_usdt?: string | null;
  chain_trx?: string | null;
  chain_loaded?: boolean;
  chain_balance_at?: string | null;
};

export type TreasuryListResponse = {
  items?: TreasuryWalletItem[];
  total?: number;
  page?: number;
  page_size?: number;
  has_more?: boolean;
};

export type SweepActionResponse = {
  user_uid?: string;
  tron_address?: string;
  usdt_swept?: string;
  trx_swept?: string;
  usdt_tx_id?: string | null;
  trx_tx_id?: string | null;
  status?: string;
  message?: string | null;
};

export type CollectAllResponse = {
  success?: number;
  failed?: number;
  skipped?: number;
  results?: SweepActionResponse[];
  has_more?: boolean;
};

export function getWalletTreasurySummaryApi() {
  return http.request<TreasurySummary>("get", "/api/v1/wallet/treasury/summary");
}

export function getWalletTreasuryAddressesApi(params: {
  page?: number;
  page_size?: number;
  keyword?: string;
  refresh_chain?: boolean;
}) {
  return http.request<TreasuryListResponse>("get", "/api/v1/wallet/treasury/addresses", {
    params
  });
}

export function collectWalletTreasuryOneApi(userUid: string) {
  return http.request<SweepActionResponse>(
    "post",
    `/api/v1/wallet/treasury/collect/${encodeURIComponent(userUid)}`
  );
}

export function collectWalletTreasuryAllApi() {
  return http.request<CollectAllResponse>("post", "/api/v1/wallet/treasury/collect-all");
}

export function tronscanAddressUrl(address: string): string | null {
  const addr = `${address}`.trim();
  if (!addr) return null;
  return `https://tronscan.org/#/address/${addr}`;
}
