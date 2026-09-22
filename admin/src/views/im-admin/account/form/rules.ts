import type { FormRules } from "element-plus";

/** 用户设置弹窗校验 */
export const imUserSettingsRules: FormRules<Record<string, any>> = {
  nickname: [
    { required: true, message: "请输入昵称", trigger: "blur" },
    { min: 1, max: 32, message: "昵称长度 1～32 字符", trigger: "blur" }
  ],
  phone: [
    {
      validator: (_rule, value, callback) => {
        const v = typeof value === "string" ? value.trim() : "";
        if (v === "") {
          callback();
          return;
        }
        if (!/^1[3-9]\d{9}$/.test(v)) {
          callback(new Error("请输入 11 位大陆手机号或留空"));
          return;
        }
        callback();
      },
      trigger: "blur"
    }
  ],
  status: [{ required: true, message: "请选择账号状态", trigger: "change" }],
  adminRemark: [{ max: 200, message: "备注不超过 200 字", trigger: "blur" }]
};
