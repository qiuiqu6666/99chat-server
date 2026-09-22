export function pickList(raw: unknown): { items: Record<string, unknown>[]; total: number } {
  const obj = (raw || {}) as Record<string, unknown>;
  const data =
    obj.data && typeof obj.data === "object" && !Array.isArray(obj.data)
      ? (obj.data as Record<string, unknown>)
      : obj;
  const items = (data.items || data.list || data.rows || []) as Record<string, unknown>[];
  return {
    items: Array.isArray(items) ? items : [],
    total: Number(data.total ?? items.length ?? 0)
  };
}

export function str(row: Record<string, unknown>, ...keys: string[]) {
  for (const k of keys) {
    const v = row[k];
    if (v != null && v !== "" && v !== "—") return String(v);
  }
  return "";
}

export function nickText(row: Record<string, unknown>, ...keys: string[]) {
  return str(row, ...keys) || "—";
}

/** 按当前展示的 UID 取对应昵称（账变 from/to 双向字段）。 */
export function nicknameForUid(row: Record<string, unknown>, uid: string) {
  if (!uid) return "—";
  const fromUid = str(row, "from_uid", "fromUid");
  const toUid = str(row, "to_uid", "toUid");
  if (uid === fromUid) return nickText(row, "from_nickname", "fromNickname");
  if (uid === toUid) return nickText(row, "to_nickname", "toNickname");
  return nickText(row, "nickname", "from_nickname", "fromNickname", "to_nickname", "toNickname");
}

export function amountText(v: unknown) {
  const n = Number(v);
  if (!Number.isFinite(n)) return v == null || v === "" ? "—" : String(v);
  return n.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 6 });
}

/** 与后端 AdminFinanceService.resolveTransactionType / TRANSACTION_TYPE_LABELS 对齐 */
export const LEDGER_TYPE_OPTIONS = [
  { value: "1", label: "充值/入账" },
  { value: "2", label: "提现/出账" },
  { value: "3", label: "转出" },
  { value: "4", label: "转入" },
  { value: "5", label: "红包发出" },
  { value: "6", label: "红包领取" },
  { value: "7", label: "退回/兑换等" }
] as const;

const LEDGER_TYPE_LABELS: Record<string, string> = Object.fromEntries(
  LEDGER_TYPE_OPTIONS.map(o => [o.value, o.label])
);

export function ledgerTypeLabel(v: unknown): string {
  if (v == null || v === "") return "—";
  const key = String(v);
  return LEDGER_TYPE_LABELS[key] || key;
}

/** 与后端 AdminFinanceService.statusLabel 对齐 */
export const LEDGER_STATUS_OPTIONS = [
  { value: "0", label: "待审核" },
  { value: "1", label: "成功" },
  { value: "2", label: "失败" },
  { value: "3", label: "已取消" }
] as const;

const LEDGER_STATUS_LABELS: Record<string, string> = Object.fromEntries(
  LEDGER_STATUS_OPTIONS.map(o => [o.value, o.label])
);

export function ledgerStatusLabel(v: unknown): string {
  if (v == null || v === "") return "—";
  const key = String(v);
  return LEDGER_STATUS_LABELS[key] || key;
}

export function currencyLabel(v: unknown): string {
  if (v == null || v === "") return "—";
  const c = String(v);
  if (c === "99" || c.toUpperCase() === "PLATFORM") return "平台币";
  if (c.toUpperCase() === "USDT") return "USDT";
  if (c.toUpperCase() === "TRX") return "TRX";
  if (c.toUpperCase() === "CNY") return "CNY";
  return c;
}

/** 与后端 WalletCurrency 存储精度一致：USDT/TRX 为 micro(1e-6)，平台币/CNY 为分(1e-2)。 */
export function walletAmountScale(currency: unknown): number {
  const c = String(currency ?? "").toUpperCase();
  if (c === "USDT" || c === "TRX") return 1_000_000;
  if (c === "99" || c === "PLATFORM" || c === "CNY") return 100;
  return 1;
}

export function walletAmountUnit(currency: unknown): string {
  const c = String(currency ?? "").toUpperCase();
  if (c === "USDT") return "USDT";
  if (c === "TRX") return "TRX";
  if (c === "99" || c === "PLATFORM") return "元";
  if (c === "CNY") return "元";
  return "";
}

export function walletAmountFromRaw(currency: unknown, raw: unknown): number {
  const n = Number(raw);
  if (!Number.isFinite(n)) return 0;
  return n / walletAmountScale(currency);
}

export function walletAmountToRaw(currency: unknown, display: unknown): number {
  const n = Number(display);
  if (!Number.isFinite(n) || n < 0) return 0;
  return Math.round(n * walletAmountScale(currency));
}

export function walletAmountPrecision(currency: unknown): number {
  const c = String(currency ?? "").toUpperCase();
  if (c === "USDT" || c === "TRX") return 6;
  if (c === "99" || c === "PLATFORM" || c === "CNY") return 2;
  return 6;
}

export const WALLET_LIMIT_SCENE_GROUPS = [
  {
    scene: "TRANSFER",
    title: "转账限额",
    description: "用户 C2C 转账时的单笔与每日累计上限（含手续费计入每日额度）。"
  },
  {
    scene: "RED_PACKET",
    title: "红包限额",
    description: "用户发红包时的单笔与每日累计上限（含手续费计入每日额度）。"
  },
  {
    scene: "WITHDRAW",
    title: "提现限额",
    description: "链上提现的单笔与每日累计上限。"
  },
  {
    scene: "LIVE_TIP",
    title: "直播打赏限额",
    description: "群直播打赏的单笔与每日累计上限。"
  }
] as const;

export function walletLimitSceneLabel(scene: unknown): string {
  const key = String(scene ?? "").toUpperCase();
  const hit = WALLET_LIMIT_SCENE_GROUPS.find(g => g.scene === key);
  return hit?.title ?? (key || "—");
}
