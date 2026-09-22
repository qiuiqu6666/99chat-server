<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import type { FormRules } from "element-plus";
import type { AdminCreateUserBody } from "@/api/im-user";
import { loginPasswordPolicyError, LOGIN_PASSWORD_PLACEHOLDER } from "../utils/loginPasswordPolicy";

type AdminCreateUserFormInline = {
  nickname: string;
  password: string;
  sex: string;
};

const props = defineProps<{
  formInline: AdminCreateUserFormInline;
}>();

const ruleFormRef = ref();
const model = reactive<AdminCreateUserFormInline>({ ...props.formInline });

watch(
  () => props.formInline,
  v => {
    Object.assign(model, v);
  },
  { deep: true }
);

const formRules: FormRules = {
  nickname: [
    { required: true, message: "请输入昵称", trigger: "blur" },
    {
      validator: (_rule, value, callback) => {
        const v = value == null ? "" : String(value).trim();
        if (!v) return callback(new Error("请输入昵称"));
        if (v.length > 32) return callback(new Error("昵称最多 32 个字符"));
        callback();
      },
      trigger: "blur"
    }
  ],
  password: [
    { required: true, message: "请输入登录密码", trigger: "blur" },
    {
      validator: (_rule, value, callback) => {
        const err = loginPasswordPolicyError(value);
        if (err) return callback(new Error(err));
        callback();
      },
      trigger: "blur"
    }
  ],
  sex: [
    {
      validator: (_rule, value, callback) => {
        const v = String(value ?? "2");
        if (!["0", "1", "2"].includes(v)) {
          callback(new Error("性别只能为 0、1、2"));
          return;
        }
        callback();
      },
      trigger: "change"
    }
  ]
};

function getRef() {
  return ruleFormRef.value;
}

function syncToOutbound() {
  Object.assign(props.formInline, model);
}

function getPayload(): AdminCreateUserBody {
  return {
    nickname: model.nickname.trim(),
    password: String(model.password),
    sex: String(model.sex || "2")
  };
}

defineExpose({ getRef, syncToOutbound, getPayload });
</script>

<template>
  <div class="px-1 pb-2">
    <el-form ref="ruleFormRef" :model="model" :rules="formRules" label-width="88px">
      <el-form-item label="昵称" prop="nickname">
        <el-input v-model="model.nickname" maxlength="32" show-word-limit clearable />
      </el-form-item>
      <el-form-item label="密码" prop="password">
        <el-input
          v-model="model.password"
          type="password"
          show-password
          clearable
          :placeholder="LOGIN_PASSWORD_PLACEHOLDER"
          autocomplete="new-password"
        />
      </el-form-item>
      <el-form-item label="性别" prop="sex">
        <el-radio-group v-model="model.sex">
          <el-radio-button value="2">未知</el-radio-button>
          <el-radio-button value="1">男</el-radio-button>
          <el-radio-button value="0">女</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <el-alert
        :closable="false"
        type="info"
        title="创建后自动分配 IM 号与 TRON 充值地址；使用 IM 号 + 密码登录。手机号、资金密码由用户后续自行绑定/设置。"
      />
    </el-form>
  </div>
</template>
