/** 管理端资金密码：固定 6 位纯数字 */
export const FUND_PASSWORD_LEN = 6;

export function fundPasswordPolicyError(v: unknown): string | null {
  const s = v == null ? "" : String(v).trim();
  if (!s) return "请输入资金密码";
  if (!/^\d{6}$/.test(s)) return "资金密码须为固定 6 位数字";
  return null;
}

export const FUND_PASSWORD_PLACEHOLDER = "固定 6 位数字";

/** 输入框内仅保留数字并截断至 6 位 */
export function sanitizeFundPasswordInput(v: unknown): string {
  return String(v ?? "")
    .replace(/\D/g, "")
    .slice(0, FUND_PASSWORD_LEN);
}
