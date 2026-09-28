<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import PageHeader from "@/components/business/page-header.vue";
import { getInfrastructure, getPlatformConfig, getPushBusiness, putInfrastructure, putPlatformConfig, putPushBusiness } from "@/service/api/systemConfig";
import { unwrap } from "@/service/http";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { labelOf } from "@/utils/adminTable";
defineOptions({ name: "system_config" });
const canEdit = computed(() => hasPerm("system.config") || hasPerm("admin.manage"));
const saving = ref(false);
const platform = reactive<Record<string, any>>({});
const push = reactive<Record<string, unknown>>({});
const infra = ref<Record<string, unknown>[]>([]);
const infraEdit = reactive({ visible: false, key: "", label: "", value: "" });
async function load() {
  Object.assign(platform, unwrap(await getPlatformConfig()));
  Object.assign(push, unwrap(await getPushBusiness()));
  const data = unwrap(await getInfrastructure());
  const items = (data.items || data.list || []) as Record<string, unknown>[];
  infra.value = Array.isArray(items) ? items : Object.entries(data).map(([key, value]) => ({ key, value: String(value) }));
}
onMounted(load);
async function savePlatform() {
  if (!canEdit.value) return;
  saving.value = true;
  try { await putPlatformConfig(platform); window.$message?.success("已保存"); }
  catch (e) { window.$message?.error(errMessage(e)); }
  finally { saving.value = false; }
}
async function savePush() {
  if (!canEdit.value) return;
  saving.value = true;
  try { await putPushBusiness(push); window.$message?.success("已保存"); }
  catch (e) { window.$message?.error(errMessage(e)); }
  finally { saving.value = false; }
}
function openInfra(row: Record<string, unknown>) {
  infraEdit.key = String(row.key || "");
  infraEdit.label = String(row.label || row.key || "");
  infraEdit.value = String(row.value ?? "");
  infraEdit.visible = true;
}
const pushFields = computed(() =>
  Object.entries(push)
    .filter(([, value]) => !Array.isArray(value) && (value == null || typeof value !== "object"))
    .map(([key, value]) => ({ key, label: labelOf(key), bool: typeof value === "boolean" }))
);
const limitRows = computed(() =>
  (Array.isArray(push.wallet_limits) ? (push.wallet_limits as Record<string, unknown>[]) : []).map(row => ({
    ...row,
    enabled_text: row.enabled ? "是" : "否"
  }))
);
const infraColumns = [
  { title: "配置项", key: "label", width: 220 },
  { title: "已配置", key: "set", width: 90, render: (row: Record<string, unknown>) => (row.set ? "是" : "否") },
  { title: "预览", key: "preview" }
];

async function saveInfra() {
  saving.value = true;
  try { await putInfrastructure(infraEdit.key, infraEdit.value); infraEdit.visible = false; window.$message?.success("已保存"); await load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
  finally { saving.value = false; }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="平台配置" subtitle="需 system.config 或 admin.manage" />
    <NCard title="平台" class="mb-12px">
      <NForm>
        <NFormItem label="官网"><NInput v-model:value="platform.website" :disabled="!canEdit" /></NFormItem>
        <NFormItem label="邮箱"><NInput v-model:value="platform.email" :disabled="!canEdit" /></NFormItem>
        <NFormItem label="客服地址"><NInput v-model:value="platform.customer_service_url" :disabled="!canEdit" /></NFormItem>
        <NFormItem label="反馈前缀"><NInput v-model:value="platform.feedback_prefix" :disabled="!canEdit" /></NFormItem>
        <NFormItem label="反馈截图上限"><NInputNumber v-model:value="platform.max_feedback_screenshots" :disabled="!canEdit" /></NFormItem>
        <NFormItem label="反馈字数上限"><NInputNumber v-model:value="platform.max_feedback_content_length" :disabled="!canEdit" /></NFormItem>
        <NButton type="primary" :loading="saving" :disabled="!canEdit" @click="savePlatform">保存</NButton>
      </NForm>
    </NCard>
    <NCard title="推送与业务" class="mb-12px">
      <div v-for="field in pushFields" :key="field.key" class="mt-8px flex items-center gap-12px">
        <span class="w-220px shrink-0">{{ field.label }}</span>
        <NSwitch v-if="field.bool" :value="!!push[field.key]" :disabled="!canEdit" @update:value="v => (push[field.key] = v)" />
        <NInput v-else :value="String(push[field.key] ?? '')" :disabled="!canEdit" @update:value="v => (push[field.key] = v)" />
      </div>
      <NDataTable v-if="limitRows.length" class="mt-12px" bordered striped :single-line="false" size="small" :columns="[{ title: '场景', key: 'scene_label' }, { title: '币种', key: 'currency' }, { title: '单笔上限', key: 'per_tx_max' }, { title: '每日上限', key: 'daily_max' }, { title: '启用', key: 'enabled_text' }]" :data="limitRows" />
      <NButton class="mt-12px" type="primary" :loading="saving" :disabled="!canEdit" @click="savePush">保存</NButton>
    </NCard>
    <NCard title="基础设施">
      <NDataTable bordered striped :single-line="false" size="small" :columns="infraColumns" :data="infra" :row-props="row => ({ style: canEdit ? 'cursor:pointer' : '', onClick: () => canEdit && openInfra(row) })" />
    </NCard>
    <NModal v-model:show="infraEdit.visible" preset="card" :title="`更新 ${infraEdit.label}`" style="width:520px">
      <NInput v-model:value="infraEdit.value" type="textarea" />
      <NSpace class="mt-12px" justify="end"><NButton @click="infraEdit.visible=false">取消</NButton><NButton type="primary" :loading="saving" @click="saveInfra">保存</NButton></NSpace>
    </NModal>
  </div>
</template>
