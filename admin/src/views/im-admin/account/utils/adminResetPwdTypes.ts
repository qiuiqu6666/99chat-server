/** 管理端单独重置登录/资金密码弹窗 */
export type ImAdminResetPwdKind = "login" | "transaction";

export interface ImAdminResetPwdFormInline {
  id: string;
  uid: string;
  nickname: string;
  kind: ImAdminResetPwdKind;
  password: string;
  passwordConfirm: string;
}
