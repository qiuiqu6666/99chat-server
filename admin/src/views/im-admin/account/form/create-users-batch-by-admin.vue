<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import type { FormRules } from "element-plus";
import type {
  AdminCreateUserBatchBody,
  AdminCreateUserBatchItemInput
} from "@/api/im-user";
import {
  loginPasswordPolicyError,
  LOGIN_PASSWORD_PLACEHOLDER
} from "../utils/loginPasswordPolicy";

type AdminCreateUsersBatchFormInline = {
  password: string;
  batchText: string;
};

const props = defineProps<{
  formInline: AdminCreateUsersBatchFormInline;
}>();

const MAX_BATCH = 100;
const ruleFormRef = ref();
const model = reactive<AdminCreateUsersBatchFormInline>({ ...props.formInline });

watch(
  () => props.formInline,
  v => {
    Object.assign(model, v);
  },
  { deep: true }
);

function parseBatchText(text: string): {
  users: AdminCreateUserBatchItemInput[];
  errors: string[];
} {
  const lines = String(text ?? "")
    .split(/\r?\n/g)
    .map(v => v.trim())
    .filter(Boolean);
  const users: AdminCreateUserBatchItemInput[] = [];
  const errors: string[] = [];

  if (lines.length > MAX_BATCH) {
    errors.push(`单次最多创建 ${MAX_BATCH} 个用户`);
    return { users, errors };
  }

  lines.forEach((line, index) => {
    const normalized = line.replace(/，/g, ",");
    const parts = normalized.split(",").map(v => v.trim());
    const rowNo = index + 1;

    if (parts.length < 1 || parts.length > 2) {
      errors.push(`第 ${rowNo} 行格式错误，应为：昵称 或 昵称,性别`);
      return;
    }

    const nickname = parts[0] ?? "";
    const sex = parts[1] || "2";

    if (!nickname) {
      errors.push(`第 ${rowNo} 行昵称不能为空`);
      return;
    }
    if (nickname.length > 32) {
      errors.push(`第 ${rowNo} 行昵称超过 32 个字符`);
      return;
    }
    if (!["0", "1", "2"].includes(sex)) {
      errors.push(`第 ${rowNo} 行性别只能为 0、1、2`);
      return;
    }

    users.push({ nickname, sex });
  });

  return { users, errors };
}

const parseResult = computed(() => parseBatchText(model.batchText));

const formRules: FormRules = {
  password: [
    { required: true, message: "请输入本批统一登录密码", trigger: "blur" },
    {
      validator: (_rule, value, callback) => {
        const err = loginPasswordPolicyError(value);
        if (err) return callback(new Error(err));
        callback();
      },
      trigger: "blur"
    }
  ],
  batchText: [
    { required: true, message: "请输入批量用户数据", trigger: "blur" },
    {
      validator: (_rule, value, callback) => {
        const v = String(value ?? "").trim();
        if (!v) return callback(new Error("请输入批量用户数据"));
        if (parseResult.value.errors.length > 0) {
          callback(new Error(parseResult.value.errors[0]));
          return;
        }
        if (parseResult.value.users.length <= 0) {
          callback(new Error("未解析到有效用户数据"));
          return;
        }
        callback();
      },
      trigger: ["blur", "change"]
    }
  ]
};

function getRef() {
  return ruleFormRef.value;
}

function syncToOutbound() {
  Object.assign(props.formInline, model);
}

function getPayload(): AdminCreateUserBatchBody {
  const { users } = parseBatchText(model.batchText);
  return {
    password: String(model.password),
    users
  };
}

defineExpose({ getRef, syncToOutbound, getPayload });
</script>

<template>
  <div class="px-1 pb-2">
    <el-form ref="ruleFormRef" :model="model" :rules="formRules" label-width="112px">
      <el-form-item label="统一密码" prop="password">
        <el-input
          v-model="model.password"
          type="password"
          show-password
          clearable
          :placeholder="LOGIN_PASSWORD_PLACEHOLDER"
          autocomplete="new-password"
        />
      </el-form-item>
      <el-alert
        :closable="false"
        type="info"
        class="mb-3!"
        title="每行一个用户，格式：昵称 或 昵称,性别。性别：1=男，0=女，2=未知（默认）；本批共用上方统一密码。"
      />
      <el-form-item label="用户列表" prop="batchText">
        <el-input
          v-model="model.batchText"
          type="textarea"
          :autosize="{ minRows: 10, maxRows: 18 }"
          placeholder="用户1,1&#10;用户2,0&#10;用户3"
        />
      </el-form-item>
      <div class="flex flex-wrap items-center gap-4 text-sm">
        <el-text type="info">已解析：{{ parseResult.users.length }} 条</el-text>
        <el-text v-if="parseResult.errors.length > 0" type="danger">
          {{ parseResult.errors[0] }}
        </el-text>
      </div>
    </el-form>
  </div>
</template>
