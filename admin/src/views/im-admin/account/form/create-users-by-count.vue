<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import type { FormRules } from "element-plus";
import type { AdminCreateByCountBody } from "@/api/im-user";
import {
  loginPasswordPolicyError,
  LOGIN_PASSWORD_PLACEHOLDER
} from "../utils/loginPasswordPolicy";

type CreateUsersByCountFormInline = {
  password: string;
  count: number;
};

const props = defineProps<{
  formInline: CreateUsersByCountFormInline;
}>();

const ruleFormRef = ref();
const model = reactive<CreateUsersByCountFormInline>({ ...props.formInline });

watch(
  () => props.formInline,
  v => {
    Object.assign(model, v);
  },
  { deep: true }
);

const formRules: FormRules = {
  password: [
    { required: true, message: "请输入统一登录密码", trigger: "blur" },
    {
      validator: (_rule, value, callback) => {
        const err = loginPasswordPolicyError(value);
        if (err) return callback(new Error(err));
        callback();
      },
      trigger: "blur"
    }
  ],
  count: [
    { required: true, message: "请输入生成数量", trigger: "blur" },
    {
      validator: (_rule, value, callback) => {
        const n = Number(value);
        if (!Number.isFinite(n) || !Number.isInteger(n) || n < 1 || n > 100) {
          callback(new Error("数量须为 1～100 的整数"));
          return;
        }
        callback();
      },
      trigger: "blur"
    }
  ]
};

function getRef() {
  return ruleFormRef.value;
}

function syncToOutbound() {
  Object.assign(props.formInline, model);
}

function getPayload(): AdminCreateByCountBody {
  return {
    password: String(model.password),
    count: Math.floor(Number(model.count))
  };
}

defineExpose({ getRef, syncToOutbound, getPayload });
</script>

<template>
  <div class="px-1 pb-2">
    <el-form ref="ruleFormRef" :model="model" :rules="formRules" label-width="96px">
      <el-form-item label="登录密码" prop="password">
        <el-input
          v-model="model.password"
          type="password"
          show-password
          clearable
          :placeholder="LOGIN_PASSWORD_PLACEHOLDER"
          autocomplete="new-password"
        />
      </el-form-item>
      <el-form-item label="生成数量" prop="count">
        <el-input-number
          v-model="model.count"
          :min="1"
          :max="100"
          :step="1"
          controls-position="right"
          class="w-full!"
        />
      </el-form-item>
    </el-form>
  </div>
</template>
