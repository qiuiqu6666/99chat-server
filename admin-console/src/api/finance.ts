import { request, unwrap } from "./http";

export function listLedger(params: Record<string, unknown>) {
  return request("get", "/wallet/ledger", { params });
}
export function listRecharges(params: Record<string, unknown>) {
  return request("get", "/recharges", { params });
}
export function listWithdraws(params: Record<string, unknown>) {
  return request("get", "/withdraws", { params });
}
export function listTransfers(params: Record<string, unknown>) {
  return request("get", "/transfers", { params });
}
export function listExchanges(params: Record<string, unknown>) {
  return request("get", "/exchanges", { params });
}
export function listRedPackets(params: Record<string, unknown>) {
  return request("get", "/red-packets", { params });
}

export function approveWithdraw(
  orderNo: string,
  data?: { remark?: string; totp_code?: string }
) {
  return request("post", `/withdraws/${encodeURIComponent(orderNo)}/approve`, { data });
}

export function rejectWithdraw(
  orderNo: string,
  data?: { remark?: string; totp_code?: string }
) {
  return request("post", `/withdraws/${encodeURIComponent(orderNo)}/reject`, { data });
}

export function manualCompleteWithdraw(
  orderNo: string,
  data: { tx_id: string; remark?: string; totp_code?: string }
) {
  return request("post", `/withdraws/${encodeURIComponent(orderNo)}/manual-complete`, { data });
}

export async function withdrawTotpStatus() {
  const raw = await request<Record<string, unknown>>("get", "/withdraws/audit-totp/status");
  const data = unwrap(raw);
  return {
    configured: Boolean(data.configured),
    withdraw_mode: String(data.withdraw_mode ?? data.withdrawMode ?? "")
  };
}

export async function withdrawTotpSetup() {
  const raw = await request<Record<string, unknown>>("post", "/withdraws/audit-totp/setup");
  const data = unwrap(raw);
  return {
    otpauth_uri: String(data.otpauth_uri ?? data.otpauthUri ?? ""),
    secret: String(data.secret ?? "")
  };
}

export function withdrawTotpConfirm(data: { totp_code: string }) {
  return request("post", "/withdraws/audit-totp/confirm", { data });
}

export function withdrawTotpReset(data: { totp_code: string }) {
  return request("post", "/withdraws/audit-totp/reset", { data });
}
