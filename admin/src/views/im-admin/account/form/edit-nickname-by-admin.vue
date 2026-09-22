<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import type { FormRules } from "element-plus";
import { imUserSettingsRules } from "./rules";
import type { ImAdminEditNicknameFormInline } from "../utils/adminEditNicknameTypes";

const props = defineProps<{
  formInline: ImAdminEditNicknameFormInline;
}>();

const ruleFormRef = ref();
const model = reactive<ImAdminEditNicknameFormInline>({ ...props.formInline });

watch(
  () => props.formInline,
  v => {
    Object.assign(model, v);
  },
  { deep: true }
);

const formRules: FormRules = {
  nickname: imUserSettingsRules.nickname ?? []
};

function getRef() {
  return ruleFormRef.value;
}

function syncToOutbound() {
  Object.assign(props.formInline, model);
}

defineExpose({ getRef, syncToOutbound });
</script>

<template>
  <div class="im-edit-nickname px-1 pb-2">
    <el-form ref="ruleFormRef" :model="model" :rules="formRules" label-width="88px">
      <el-text size="small" type="info" class="mb-3! block">
        目标：{{ formInline.nickname }}（{{ formInline.uid }}）
      </el-text>
      <el-form-item label="新昵称" prop="nickname">
        <el-input
          v-model="model.nickname"
          maxlength="32"
          show-word-limit
          clearable
          placeholder="1～32 字符"
        />
      </el-form-item>
      <el-text size="small" type="info">
        保存后将同步更新 IM 资料昵称。
      </el-text>
    </el-form>
  </div>
</template>
