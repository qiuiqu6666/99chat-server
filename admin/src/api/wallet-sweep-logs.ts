import { http } from "@/utils/http";

export type WalletSweepLogItem = {
  id?: number;
  user_uid?: string;
  nickname?: string;
  from_address?: string;
  hot_wallet_address?: string | null;
  usdt_swept?: string;
  trx_swept?: string;
  usdt_tx_id?: string | null;
  trx_tx_id?: string | null;
  status?: string;
  trigger_type?: string;
  operator?: string | null;
  fail_reason?: string | null;
  created_at?: string | null;
};

export type WalletSweepLogListResponse = {
  items?: WalletSweepLogItem[];
  total?: number;
  page?: number;
  page_size?: number;
  has_more?: boolean;
};

export function getWalletSweepLogsApi(params: {
  page?: number;
  page_size?: number;
  keyword?: string;
  status?: string;
  trigger?: string;
  created_from?: string;
  created_to?: string;
}) {
  return http.request<WalletSweepLogListResponse>("get", "/api/v1/wallet/treasury/sweep-logs", {
    params
  });
}

export function tronscanTxUrl(txId: string): string | null {
  const id = `${txId}`.trim();
  if (!id) return null;
  return `https://tronscan.org/#/transaction/${id}`;
}

export function tronscanAddressUrl(address: string): string | null {
  const addr = `${address}`.trim();
  if (!addr) return null;
  return `https://tronscan.org/#/address/${addr}`;
}
