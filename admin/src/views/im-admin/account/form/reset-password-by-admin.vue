<script setup lang="ts">
import { reactive, ref, watch, computed } from "vue";
import type { FormRules } from "element-plus";
import type { ImAdminResetPwdFormInline } from "../utils/adminResetPwdTypes";
import {
  loginPasswordPolicyError,
  LOGIN_PASSWORD_PLACEHOLDER
} from "../utils/loginPasswordPolicy";
import {
  fundPasswordPolicyError,
  FUND_PASSWORD_PLACEHOLDER,
  sanitizeFundPasswordInput
} from "../utils/fundPasswordPolicy";

const props = defineProps<{
  formInline: ImAdminResetPwdFormInline;
}>();

const ruleFormRef = ref();
const model = reactive<ImAdminResetPwdFormInline>({ ...props.formInline });

watch(
  () => props.formInline,
  v => {
    Object.assign(model, v);
  },
  { deep: true }
);

const isLogin = computed(() => props.formInline.kind === "login");

const formRules = computed<FormRules>(() => ({
  password: [
    {
      required: true,
      message: isLogin.value ? "请输入新登录密码" : "请输入新资金密码",
      trigger: "blur"
    },
    {
      validator: (_rule, value, callback) => {
        const v = value == null ? "" : String(value).trim();
        if (!v) {
          callback(new Error(isLogin.value ? "请输入新登录密码" : "请输入新资金密码"));
          return;
        }
        if (isLogin.value) {
          const err = loginPasswordPolicyError(v);
          if (err) {
            callback(new Error(err));
            return;
          }
        } else {
          const err = fundPasswordPolicyError(v);
          if (err) {
            callback(new Error(err));
            return;
          }
        }
        callback();
      },
      trigger: "blur"
    }
  ],
  passwordConfirm: [
    { required: true, message: "请再次输入以确认", trigger: "blur" },
    {
      validator: (_rule, value, callback) => {
        const v = value == null ? "" : String(value).trim();
        if (v !== String(model.password ?? "").trim()) {
          callback(new Error("两次输入不一致"));
          return;
        }
        callback();
      },
      trigger: "blur"
    }
  ]
}));

function getRef() {
  return ruleFormRef.value;
}

function syncToOutbound() {
  Object.assign(props.formInline, model);
}

function onFundPasswordInput(
  field: "password" | "passwordConfirm",
  val: string
) {
  model[field] = sanitizeFundPasswordInput(val);
}

defineExpose({ getRef, syncToOutbound });
</script>

<template>
  <div class="im-reset-pwd px-1 pb-2">
    <el-form ref="ruleFormRef" :model="model" :rules="formRules" label-width="112px">
      <el-text size="small" type="info" class="mb-3! block">
        目标：{{ model.nickname }}（{{ model.uid }}）
      </el-text>
      <el-form-item :label="isLogin ? '新登录密码' : '新资金密码'" prop="password">
        <el-input
          v-model="model.password"
          type="password"
          show-password
          clearable
          :placeholder="isLogin ? LOGIN_PASSWORD_PLACEHOLDER : FUND_PASSWORD_PLACEHOLDER"
          :maxlength="isLogin ? 128 : 6"
          autocomplete="new-password"
          @update:model-value="val => !isLogin && onFundPasswordInput('password', val)"
        />
      </el-form-item>
      <el-form-item label="确认密码" prop="passwordConfirm">
        <el-input
          v-model="model.passwordConfirm"
          type="password"
          show-password
          clearable
          :maxlength="isLogin ? 128 : 6"
          autocomplete="new-password"
          @update:model-value="val => !isLogin && onFundPasswordInput('passwordConfirm', val)"
        />
      </el-form-item>
    </el-form>
  </div>
</template>
