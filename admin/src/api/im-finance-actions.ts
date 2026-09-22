import { http } from "@/utils/http";

export type FinanceCurrency = "USDT" | "CNY";

export type FinanceActionResponse = {
  ok?: boolean;
  user_uid?: string;
  currency?: FinanceCurrency | string;
  amount?: string;
  balance_before?: string;
  balance_after?: string;
  transaction_no?: string;
  order_no?: string;
  status?: string;
  traceId?: string;
};

function idempotencyHeaders(key?: string) {
  const k = key || `${Date.now()}-${Math.random().toString(16).slice(2)}`;
  return {
    "X-Idempotency-Key": k,
    "X-Trace-Id": k
  } as Record<string, string>;
}

async function postFirstAvailable<T>(
  urls: string[],
  data: Record<string, unknown>,
  idemKey?: string
): Promise<T> {
  let lastErr: unknown;
  for (const url of urls) {
    try {
      return await http.request<T>("post", url, {
        data,
        headers: idempotencyHeaders(idemKey)
      });
    } catch (err: unknown) {
      lastErr = err;
      const status = Number((err as { response?: { status?: number } })?.response?.status);
      if (![404, 405, 501].includes(status)) throw err;
    }
  }
  throw lastErr;
}

export function adminWalletAdjustApi(data: {
  user_uid: string;
  currency: FinanceCurrency;
  direction: "add" | "subtract";
  amount: string;
  remark?: string;
  idempotencyKey?: string;
}) {
  return postFirstAvailable<FinanceActionResponse>(
    ["/api/v1/wallet/adjust", "/api/v1/users/wallet/balance-adjust"],
    data,
    data.idempotencyKey
  );
}

export function adminWalletFreezeApi(data: {
  user_uid: string;
  currency: FinanceCurrency;
  amount: string;
  order_no?: string;
  remark?: string;
  idempotencyKey?: string;
}) {
  return postFirstAvailable<FinanceActionResponse>(
    ["/api/v1/wallet/freeze"],
    data,
    data.idempotencyKey
  );
}

export function adminWalletUnfreezeApi(data: {
  user_uid: string;
  currency: FinanceCurrency;
  amount: string;
  order_no?: string;
  remark?: string;
  idempotencyKey?: string;
}) {
  return postFirstAvailable<FinanceActionResponse>(
    ["/api/v1/wallet/unfreeze"],
    data,
    data.idempotencyKey
  );
}

export function adminRechargeManualSuccessApi(
  orderNo: string,
  data: { amount?: string; currency?: FinanceCurrency; txid?: string; remark?: string; idempotencyKey?: string }
) {
  return postFirstAvailable<FinanceActionResponse>(
    [`/api/v1/recharges/${encodeURIComponent(orderNo)}/manual-success`],
    data,
    data.idempotencyKey
  );
}

export function adminRechargeRejectApi(
  orderNo: string,
  data: { remark?: string; idempotencyKey?: string } = {}
) {
  return postFirstAvailable<FinanceActionResponse>(
    [`/api/v1/recharges/${encodeURIComponent(orderNo)}/reject`],
    data,
    data.idempotencyKey
  );
}

export function adminWithdrawApproveApi(
  orderNo: string,
  data: { remark?: string; totp_code?: string; idempotencyKey?: string } = {}
) {
  return postFirstAvailable<FinanceActionResponse>(
    [`/api/v1/withdraws/${encodeURIComponent(orderNo)}/approve`],
    data,
    data.idempotencyKey
  );
}

export function adminWithdrawRejectApi(
  orderNo: string,
  data: { remark?: string; totp_code?: string; idempotencyKey?: string } = {}
) {
  return postFirstAvailable<FinanceActionResponse>(
    [`/api/v1/withdraws/${encodeURIComponent(orderNo)}/reject`],
    data,
    data.idempotencyKey
  );
}

export function adminWithdrawMarkPaidApi(
  orderNo: string,
  data: { txid?: string; remark?: string; idempotencyKey?: string } = {}
) {
  return postFirstAvailable<FinanceActionResponse>(
    [`/api/v1/withdraws/${encodeURIComponent(orderNo)}/mark-paid`],
    data,
    data.idempotencyKey
  );
}

export function adminWithdrawMarkFailedApi(
  orderNo: string,
  data: { remark?: string; idempotencyKey?: string } = {}
) {
  return postFirstAvailable<FinanceActionResponse>(
    [`/api/v1/withdraws/${encodeURIComponent(orderNo)}/mark-failed`],
    data,
    data.idempotencyKey
  );
}

export function adminWithdrawRefundApi(
  orderNo: string,
  data: { remark?: string; idempotencyKey?: string } = {}
) {
  return postFirstAvailable<FinanceActionResponse>(
    [`/api/v1/withdraws/${encodeURIComponent(orderNo)}/refund`],
    data,
    data.idempotencyKey
  );
}

export type WithdrawAuditTotpStatus = {
  configured?: boolean;
};

export type WithdrawAuditTotpSetup = {
  otpauth_uri?: string;
  secret?: string;
};

export function adminWithdrawAuditTotpStatusApi() {
  return http.request<WithdrawAuditTotpStatus>("get", "/api/v1/withdraws/audit-totp/status");
}

export function adminWithdrawAuditTotpSetupApi() {
  return http.request<WithdrawAuditTotpSetup>("post", "/api/v1/withdraws/audit-totp/setup");
}

export function adminWithdrawAuditTotpConfirmApi(data: { totp_code: string }) {
  return http.request<WithdrawAuditTotpStatus>("post", "/api/v1/withdraws/audit-totp/confirm", {
    data
  });
}

export function adminWithdrawAuditTotpResetApi(data: { totp_code: string }) {
  return http.request<WithdrawAuditTotpStatus>("post", "/api/v1/withdraws/audit-totp/reset", {
    data
  });
}
