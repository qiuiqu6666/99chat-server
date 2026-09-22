/** 「用户设置」弹窗表单（与后端对齐时可扩展字段） */
export interface ImUserSettingsFormInline {
  id: number;
  uid: string;
  nickname: string;
  phone: string;
  signature: string;
  /** `1` 正常，`0` 禁用（与 `GET /api/v1/users` 的 `user_status` 一致） */
  status: number;
  inWhitelist: boolean;
  /** 运维备注 */
  adminRemark: string;
}
