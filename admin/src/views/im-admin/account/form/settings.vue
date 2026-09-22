<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import ReCol from "@/components/ReCol";
import type { ImUserSettingsFormInline } from "../utils/types";
import { imUserSettingsRules } from "./rules";

const props = defineProps<{
  formInline: ImUserSettingsFormInline;
}>();

const ruleFormRef = ref();
const model = reactive<ImUserSettingsFormInline>({ ...props.formInline });

watch(
  () => props.formInline,
  v => Object.assign(model, v),
  { deep: true }
);

function getRef() {
  return ruleFormRef.value;
}

function syncToOutbound() {
  Object.assign(props.formInline, model);
}

defineExpose({ getRef, syncToOutbound });
</script>

<template>
  <el-form
    ref="ruleFormRef"
    :model="model"
    :rules="imUserSettingsRules"
    label-width="110px"
    class="pr-4"
  >
    <el-alert
      type="info"
      :closable="false"
      class="mb-4!"
      title="重置登录密码、资金密码请在列表中对应操作入口办理。余额请在列表中点击「调整余额」。"
    />

    <el-row :gutter="24">
      <re-col :value="24" :xs="24" :sm="24">
        <el-form-item label="IM号">
          <el-input v-model="model.uid" disabled />
        </el-form-item>
      </re-col>
      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="昵称" prop="nickname">
          <el-input
            v-model="model.nickname"
            maxlength="32"
            show-word-limit
            clearable
            placeholder="用户对外展示昵称"
          />
        </el-form-item>
      </re-col>
      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="手机号" prop="phone">
          <el-input
            v-model="model.phone"
            maxlength="11"
            clearable
            placeholder="11 位手机号"
          />
        </el-form-item>
      </re-col>

      <re-col :value="24" :xs="24" :sm="24">
        <el-form-item label="个性签名">
          <el-input
            v-model="model.signature"
            type="textarea"
            :rows="2"
            maxlength="120"
            show-word-limit
            placeholder="可不填"
          />
        </el-form-item>
      </re-col>

      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="账号状态" prop="status">
          <el-select v-model="model.status" class="w-full!">
            <el-option label="正常" :value="1" />
            <el-option label="禁用" :value="0" />
          </el-select>
        </el-form-item>
      </re-col>
      <re-col :value="12" :xs="24" :sm="24">
        <el-form-item label="白名单">
          <el-switch
            v-model="model.inWhitelist"
            inline-prompt
            active-text="是"
            inactive-text="否"
          />
        </el-form-item>
      </re-col>

      <re-col :value="24" :xs="24" :sm="24">
        <el-form-item label="运维备注" prop="adminRemark">
          <el-input
            v-model="model.adminRemark"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="对内备注"
          />
        </el-form-item>
      </re-col>
    </el-row>
  </el-form>
</template>
