import type {
  WalletBalanceSnapshot,
  WalletCurrencyCode
} from "./walletCurrencyPolicy";
import { roundWalletAmount } from "./walletCurrencyPolicy";

/** 余额调整方式（仅有增加 / 减少） */
export type BalanceAdjustKind = "increase" | "decrease";

export interface BalanceAdjustDraft {
  currency: WalletCurrencyCode;
  /** 当前选中币种可用余额快照 */
  initialBalance: number;
  balanceAdjustKind: BalanceAdjustKind;
  balanceAdjustAmount: number;
}

/** 由快照 + 增减方式得到最终入账余额 */
export function resolveAdjustFinalBalance(raw: BalanceAdjustDraft): number {
  const base = roundWalletAmount(raw.currency, Number(raw.initialBalance) || 0);
  const rawAmt = Number(raw.balanceAdjustAmount);
  const amt =
    Number.isFinite(rawAmt) && rawAmt > 0
      ? roundWalletAmount(raw.currency, rawAmt)
      : 0;
  const next =
    raw.balanceAdjustKind === "increase" ? base + amt : base - amt;
  return roundWalletAmount(raw.currency, Math.max(next, 0));
}

/** 独立「调整余额」弹窗 */
export interface ImAdjustBalanceFormInline extends BalanceAdjustDraft {
  id: string;
  uid: string;
  nickname: string;
  /** 各币种余额快照，切换币种时更新 initialBalance */
  walletSnapshot: WalletBalanceSnapshot;
}
