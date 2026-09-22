import { http } from "@/utils/http";

import type { ImUserUid } from "@/api/im-user";

export type PaginationSortParams = {
  page?: number;
  page_size?: number;
  sort?: string;
};

export type FinanceListParams = PaginationSortParams & {
  user_uid?: ImUserUid;
  keyword?: string;
  status?: string;
  transaction_type?: string;
  biz_type?: string;
  currency?: string;
};

function isMissingEndpoint(err: unknown) {
  const ax = err as { response?: { status?: number } };
  return [404, 405, 501].includes(Number(ax?.response?.status));
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

function pickArray(raw: Record<string, unknown>) {
  const candidates = [
    raw.items,
    raw.list,
    raw.records,
    raw.rows,
    raw.transactions,
    raw.orders,
    raw.data
  ];
  for (const v of candidates) {
    if (Array.isArray(v)) return v;
  }
  return [];
}

function normalizeList(raw: unknown, params?: PaginationSortParams, source?: string) {
  const body = unwrap(raw);
  const items = pickArray(body);
  return {
    ...body,
    items,
    total: Number(body.total ?? body.count ?? items.length),
    page: Number(body.page ?? params?.page ?? 1),
    page_size: Number(body.page_size ?? body.pageSize ?? params?.page_size ?? 20),
    source
  } as Record<string, unknown>;
}

async function getFirstAvailableList(
  urls: string[],
  params: Record<string, unknown>
) {
  let lastErr: unknown;
  for (const url of urls) {
    try {
      const raw = await http.request<Record<string, unknown>>("get", url, { params });
      return normalizeList(raw, params, url);
    } catch (err: unknown) {
      lastErr = err;
      if (!isMissingEndpoint(err)) throw err;
    }
  }
  if (lastErr) {
    return normalizeList({ items: [], total: 0 }, params, "missing");
  }
  return normalizeList({ items: [], total: 0 }, params, "missing");
}

export async function getRedPacketsApi(
  params: PaginationSortParams & {
    sender_uid?: ImUserUid;
    group_id?: string;
    status?: string;
    involves_user_uid?: ImUserUid;
    keyword?: string;
  }
) {
  return getFirstAvailableList(["/api/v1/red-packets"], params as Record<string, unknown>);
}

export async function getRedPacketReceivesApi(
  params: PaginationSortParams & { packet_id?: string; receiver_uid?: ImUserUid }
) {
  return getFirstAvailableList(
    ["/api/v1/red-packets/receives", "/api/v1/red-packets/claims"],
    params as Record<string, unknown>
  );
}

export async function getWalletTransfersApi(
  params: PaginationSortParams & {
    user_uid?: ImUserUid;
    transaction_type?: string;
    status?: string;
    keyword?: string;
  }
) {
  return getFirstAvailableList(
    ["/api/v1/transfers", "/api/v1/wallet/transfers"],
    params as Record<string, unknown>
  );
}

export async function getWalletExchangesApi(
  params: PaginationSortParams & {
    user_uid?: ImUserUid;
    direction?: string;
    keyword?: string;
  }
) {
  return getFirstAvailableList(
    ["/api/v1/exchanges", "/api/v1/wallet/exchanges"],
    params as Record<string, unknown>
  );
}

export async function getWalletLedgerApi(
  params: FinanceListParams
) {
  const urls = params.user_uid
    ? ["/api/v1/wallet/transactions", "/api/v1/wallet/ledger"]
    : ["/api/v1/wallet/transactions", "/api/v1/wallet/ledger"];
  return getFirstAvailableList(urls, params as Record<string, unknown>);
}

export async function getRechargeLogsApi(params: FinanceListParams) {
  return getFirstAvailableList(["/api/v1/recharges"], params as Record<string, unknown>);
}

export async function getWithdrawLogsApi(params: FinanceListParams) {
  return getFirstAvailableList(["/api/v1/withdraws"], params as Record<string, unknown>);
}

export async function getRechargeWithdrawLogsApi(params: FinanceListParams) {
  if (params.biz_type === "提现") return getWithdrawLogsApi(params);
  return getRechargeLogsApi(params);
}

export function pickStr(obj: Record<string, unknown>, keys: string[]) {
  for (const k of keys) {
    const v = obj[k];
    if (v != null && `${v}` !== "") return String(v);
  }
  return "";
}

/** 波场链交易哈希 → Tronscan 详情页（64 位 hex） */
const TRON_TX_HASH = /^[0-9a-fA-F]{64}$/;

export function tronscanTransactionUrl(txid: string): string | null {
  const hash = `${txid}`.trim();
  if (!hash || !TRON_TX_HASH.test(hash)) return null;
  return `https://tronscan.org/transaction/${hash}/overview`;
}

function pickTime(raw: Record<string, unknown>, keys: string[]) {
  for (const k of keys) {
    const v = raw[k];
    if (v == null || v === "") continue;
    if (typeof v === "number") return v;
    const n = Number(v);
    if (Number.isFinite(n) && n > 0) return n;
    return v;
  }
  return Number.NaN;
}

export function mapRedPacketListRow(raw: Record<string, unknown>) {
  const created = pickTime(raw, ["create_time", "created_at", "createdAt", "created_time"]);
  return {
    orderNo: pickStr(raw, ["id", "packet_id", "packet_no", "order_no", "orderNo"]),
    uid: pickStr(raw, ["sender_uid", "uid", "user_uid", "userUid"]),
    nickname: raw.nickname != null ? String(raw.nickname) : String(raw.sender_nickname ?? ""),
    packetType:
      raw.packet_type != null
        ? String(raw.packet_type)
        : raw.type != null
          ? String(raw.type)
          : "—",
    targetSummary: (() => {
      const summary = pickStr(raw, ["target_summary", "targetSummary", "remark", "memo"]);
      if (summary) return summary;
      if (raw.group_id != null && `${raw.group_id}` !== "") return `群 ${raw.group_id}`;
      const exclusiveUid = pickStr(raw, ["exclusive_user_id", "exclusiveUserId", "receiver_uid"]);
      if (exclusiveUid) {
        const nick = pickStr(raw, ["exclusive_nickname", "exclusiveNickname", "receiver_nickname"]);
        return nick || exclusiveUid;
      }
      return "—";
    })(),
    totalAmount:
      raw.total_amount != null
        ? String(raw.total_amount)
        : String(raw.amount ?? raw.totalAmount ?? ""),
    grabbedCount:
      typeof raw.receive_count === "number"
        ? raw.receive_count
        : typeof raw.grabbedCount === "number"
          ? raw.grabbedCount
          : Number(raw.received_count ?? raw.claimed_count ?? 0),
    packetCount:
      typeof raw.total_count === "number"
        ? raw.total_count
        : typeof raw.packetCount === "number"
          ? raw.packetCount
          : Number(raw.quantity ?? raw.count ?? 0),
    status:
      ({ "0": "进行中", "1": "已领完", "2": "已过期" } as Record<string, string>)[
        String(raw.status)
      ] ?? String(raw.status ?? raw.state ?? "—"),
    createdAt: typeof created === "number" ? created : Number(created),
    _raw: raw
  };
}

export function mapTransferListRow(raw: Record<string, unknown>) {
  const t =
    typeof raw.transaction_type !== "undefined"
      ? Number(raw.transaction_type)
      : Number(raw.type ?? Number.NaN);
  const create = pickTime(raw, ["create_time", "created_at", "createdAt", "created_time"]);
  const st =
    ({ "0": "待审核", "1": "成功", "2": "失败", "3": "已取消" } as Record<
      string,
      string
    >)[String(raw.status)] ?? String(raw.status ?? raw.state ?? "—");

  const fromUid =
    raw.from_uid ??
    raw.fromUid ??
    raw.payer_uid ??
    (t === 3 ? raw.user_uid : undefined) ??
    raw.sender_uid ??
    "";

  const toUid =
    raw.to_uid ??
    raw.toUid ??
    raw.payee_uid ??
    (t === 4 ? raw.user_uid : undefined) ??
    raw.receiver_uid ??
    "";

  return {
    tradeNo: pickStr(raw, ["transaction_no", "transactionNo", "id", "trade_no", "order_no"]),
    fromUid: fromUid !== "" ? String(fromUid) : "—",
    fromNickname:
      raw.from_nickname != null
        ? String(raw.from_nickname)
        : String(raw.related_nickname ?? raw.sender_nickname ?? "—"),
    toUid: toUid !== "" ? String(toUid) : "—",
    toNickname:
      raw.to_nickname != null
        ? String(raw.to_nickname)
        : String(raw.counterparty_nickname ?? raw.receiver_nickname ?? "—"),
    amount: raw.amount != null ? String(raw.amount) : String(raw.change_amount ?? ""),
    fee: raw.fee != null ? String(raw.fee) : "0",
    status: st,
    memo:
      raw.remark != null
        ? String(raw.remark)
        : raw.memo != null
          ? String(raw.memo)
          : String(raw.note ?? ""),
    createdAt: typeof create === "number" ? create : Number(create),
    transactionTypeNum: Number.isFinite(t) ? t : null,
    currency: String(raw.currency ?? raw.coin ?? raw.asset ?? "CNY"),
    balanceBefore: raw.balance_before ?? raw.before_balance ?? raw.beforeBalance,
    balanceAfter: raw.balance_after ?? raw.after_balance ?? raw.afterBalance,
    _raw: raw
  };
}

export function mapExchangeListRow(raw: Record<string, unknown>) {
  const create = pickTime(raw, ["create_time", "created_at", "createdAt", "created_time"]);
  return {
    orderNo: pickStr(raw, ["order_no", "orderNo", "id"]),
    uid: pickStr(raw, ["user_uid", "uid", "userUid"]),
    nickname: raw.nickname != null ? String(raw.nickname) : String(raw.user_nickname ?? "—"),
    directionLabel: pickStr(raw, ["direction_label", "directionLabel"]) || String(raw.direction ?? "—"),
    direction: pickStr(raw, ["direction"]),
    inputAmount: raw.input_amount != null ? String(raw.input_amount) : String(raw.inputAmount ?? ""),
    inputCurrency: String(raw.input_currency ?? raw.inputCurrency ?? "USDT"),
    outputAmount: raw.output_amount != null ? String(raw.output_amount) : String(raw.outputAmount ?? ""),
    outputCurrency: String(raw.output_currency ?? raw.outputCurrency ?? "CNY"),
    rate: raw.rate != null ? String(raw.rate) : "—",
    status: String(raw.status ?? "成功"),
    createdAt: typeof create === "number" ? create : Number(create),
    _raw: raw
  };
}

export function mapRechargeWithdrawRow(raw: Record<string, unknown>) {
  const create = pickTime(raw, ["create_time", "created_at", "createdAt", "created_time", "apply_time"]);
  const bizMap: Record<string, string> = { 充值: "充值", 提现: "提现", recharge: "充值", withdraw: "提现" };
  const biz = String(raw.biz_type ?? raw.bizType ?? raw.order_type ?? raw.type ?? "充值");
  const statusMap: Record<string, string> = {
    "0": "处理中",
    "1": "成功",
    "2": "失败",
    "3": "已取消",
    pending: "处理中",
    success: "成功",
    failed: "失败",
    rejected: "失败"
  };
  return {
    bizNo: pickStr(raw, ["biz_no", "bizNo", "order_no", "orderNo", "id"]),
    uid: pickStr(raw, ["user_uid", "uid", "userUid"]),
    nickname: raw.nickname != null ? String(raw.nickname) : String(raw.user_nickname ?? ""),
    bizType: bizMap[biz] ?? biz,
    channel: String(raw.channel ?? raw.pay_channel ?? raw.chain ?? "—"),
    amount: Number(raw.amount ?? raw.money ?? 0),
    status: statusMap[String(raw.status ?? raw.state)] ?? String(raw.status ?? raw.state ?? "成功"),
    externalOrderNo: pickStr(raw, ["external_order_no", "externalOrderNo", "txid", "hash", "trade_no"]),
    createdAt: typeof create === "number" ? create : Number(create),
    address: pickStr(raw, ["address", "to_address", "withdraw_address", "recharge_address"]),
    _raw: raw
  };
}
