import type { AdminUserListItem } from "@/api/im-user";
import { walletAvailableMinor } from "@/api/im-user";

/** 管理端展示/调账币种：后台只展示 USDT + CNY；TRX 不进入后台列表 */
export type WalletCurrencyCode = "USDT" | "CNY";

export type WalletCurrencyMeta = {
  code: WalletCurrencyCode;
  label: string;
  decimals: number;
  /** 展示前缀，如 CNY 用 ¥ */
  prefix: string;
};

export const WALLET_CURRENCY_OPTIONS: WalletCurrencyMeta[] = [
  { code: "USDT", label: "USDT", decimals: 2, prefix: "$" },
  { code: "CNY", label: "CNY（人民币）", decimals: 2, prefix: "¥" }
];

/** 链上/API 币种码 → 管理端展示币种（99/PLATFORM 视为 CNY） */
export function normalizeWalletCurrencyCode(
  code: string | undefined | null
): WalletCurrencyCode {
  const c = String(code ?? "")
    .trim()
    .toUpperCase();
  if (c === "99" || c === "PLATFORM") return "CNY";
  if (c === "CNY") return "CNY";
  return "USDT";
}

export function formatWalletCurrencyLabel(code: string | undefined | null): string {
  return getWalletCurrencyMeta(normalizeWalletCurrencyCode(code)).code;
}

export function getWalletCurrencyMeta(
  code: string | undefined | null
): WalletCurrencyMeta {
  const found = WALLET_CURRENCY_OPTIONS.find(c => c.code === code);
  return found ?? WALLET_CURRENCY_OPTIONS[0];
}

export type WalletBalanceSnapshot = Partial<
  Record<WalletCurrencyCode, { available: number; frozen: number }>
>;

function parseAmount(v: unknown): number {
  if (v == null || v === "") return Number.NaN;
  const n = Number(v);
  return Number.isFinite(n) ? n : Number.NaN;
}

/** 从列表/详情 profile 解析各币种可用余额 */
export function buildWalletBalanceSnapshot(
  item: AdminUserListItem | Record<string, unknown>
): WalletBalanceSnapshot {
  const raw = item as Record<string, unknown>;
  const map = raw.wallet_balances ?? raw.walletBalances;
  const frozenMap = raw.wallet_frozen_by_currency ?? raw.walletFrozenByCurrency;

  const readBal = (code: WalletCurrencyCode): number => {
    if (map != null && typeof map === "object" && !Array.isArray(map)) {
      const m = map as Record<string, unknown>;
      const v = m[code] ?? m[code.toLowerCase()];
      const n = parseAmount(v);
      if (Number.isFinite(n)) return n;
    }
    if (code === "USDT") {
      return parseAmount(raw.wallet_balance ?? raw.walletBalance);
    }
  if (code === "CNY") {
      return parseAmount(
        raw.cny_balance ?? raw.cnyBalance ?? raw.wallet_cny_balance
      );
    }
    return Number.NaN;
  };

  const readFrozen = (code: WalletCurrencyCode): number => {
    if (frozenMap != null && typeof frozenMap === "object" && !Array.isArray(frozenMap)) {
      const m = frozenMap as Record<string, unknown>;
      const n = parseAmount(m[code] ?? m[code.toLowerCase()]);
      if (Number.isFinite(n)) return n;
    }
    if (code === "USDT") {
      return parseAmount(raw.wallet_frozen_amount ?? raw.walletFrozenAmount);
    }
    return 0;
  };

  const snap: WalletBalanceSnapshot = {};
  for (const { code } of WALLET_CURRENCY_OPTIONS) {
    const balance = readBal(code);
    const frozen = readFrozen(code);
    const available = Number.isFinite(balance)
      ? code === "USDT"
        ? walletAvailableMinor(
            String(balance),
            Number.isFinite(frozen) ? String(frozen) : "0"
          )
        : Math.round(Math.max(balance - (Number.isFinite(frozen) ? frozen : 0), 0) * 1e6) /
          1e6
      : 0;
    snap[code] = {
      available: Math.max(0, available),
      frozen: Number.isFinite(frozen) ? Math.max(0, frozen) : 0
    };
  }
  return snap;
}

export function getAvailableBalanceForCurrency(
  snap: WalletBalanceSnapshot,
  code: WalletCurrencyCode
): number {
  const row = snap[code];
  return row && Number.isFinite(row.available) ? row.available : 0;
}

export function formatWalletCurrencyAmount(
  code: WalletCurrencyCode | string,
  amount: unknown
): string {
  const meta = getWalletCurrencyMeta(normalizeWalletCurrencyCode(code));
  const n = Number(amount);
  if (!Number.isFinite(n)) {
    return meta.prefix ? `${meta.prefix}0.00` : `0 ${meta.code}`;
  }
  const text = n.toLocaleString("zh-CN", {
    minimumFractionDigits: meta.decimals,
    maximumFractionDigits: meta.decimals
  });
  if (meta.prefix) return `${meta.prefix}${text}`;
  return `${text} ${meta.code}`;
}

export function roundWalletAmount(code: WalletCurrencyCode, amount: number): number {
  const d = getWalletCurrencyMeta(code).decimals;
  const factor = 10 ** d;
  return Math.round(amount * factor) / factor;
}

export function formatWalletAmountForApi(
  code: WalletCurrencyCode,
  amount: number
): string {
  return roundWalletAmount(code, amount).toFixed(getWalletCurrencyMeta(code).decimals);
}

/** 各币种地址：USDT 走链上地址，CNY 为平台内账户；TRX 后台不展示 */
export function resolveWalletAddressForCurrency(
  code: WalletCurrencyCode,
  item: AdminUserListItem | Record<string, unknown>
): string {
  const raw = item as Record<string, unknown>;
  const tron = String(
    raw.deposit_address ??
      raw.depositAddress ??
      raw.trx_address ??
      raw.trxAddress ??
      ""
  ).trim();
  if (code === "USDT") return tron || "—";
  return "—";
}
