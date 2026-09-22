/** 管理端登录密码：≥8 位，须同时含英文字母与数字（上限 128 与后端一致） */
export const LOGIN_PASSWORD_MIN_LEN = 8;
export const LOGIN_PASSWORD_MAX_LEN = 128;

export function loginPasswordPolicyError(v: unknown): string | null {
  const s = v == null ? "" : String(v);
  if (!s.trim()) return "请输入登录密码";
  if (s.length < LOGIN_PASSWORD_MIN_LEN || s.length > LOGIN_PASSWORD_MAX_LEN) {
    return `登录密码须为 ${LOGIN_PASSWORD_MIN_LEN}～${LOGIN_PASSWORD_MAX_LEN} 位`;
  }
  if (!/[a-zA-Z]/.test(s) || !/\d/.test(s)) {
    return "登录密码须同时包含英文字母与数字";
  }
  return null;
}

export const LOGIN_PASSWORD_PLACEHOLDER = "不少于 8 位，须含英文字母与数字";
