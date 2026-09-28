<script setup lang="ts">
import { computed, onMounted, reactive } from 'vue';
import PageHeader from '@/components/business/page-header.vue';
import { getExchangeConfig, putExchangeConfig } from '@/service/api/exchangeConfig';
import { unwrap } from '@/service/http';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { kvRows } from '@/utils/adminTable';

defineOptions({ name: 'wallet_exchange-config' });
const canWrite = hasPerm('user.write');
const form = reactive<Record<string, unknown>>({});
const preview = computed(() => kvRows((form.rate_preview as Record<string, unknown>) || {}));
const stats = computed(() => kvRows((form.stats as Record<string, unknown>) || {}));
const kvColumns = [
  { title: '项目', key: 'label', width: 180 },
  { title: '内容', key: 'value' }
];
async function load() {
  Object.assign(form, unwrap(await getExchangeConfig()));
}
onMounted(load);
async function save() {
  try {
    await putExchangeConfig({
      enabled: form.enabled,
      markup_bps: form.markup_bps,
      float_bps: form.float_bps,
      min_withdraw_usdt_micro: form.min_withdraw_usdt_micro,
      frankfurter_url: form.frankfurter_url,
      exchange_rate_cache_seconds: form.exchange_rate_cache_seconds
    });
    window.$message?.success('已保存');
    await load();
  } catch (e) {
    window.$message?.error(errMessage(e));
  }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="闪兑配置" />
    <NForm label-placement="left" label-width="140" class="max-w-640px">
      <NFormItem label="启用"><NSwitch v-model:value="form.enabled" :disabled="!canWrite" /></NFormItem>
      <NFormItem label="加点（基点）"><NInputNumber v-model:value="form.markup_bps" :disabled="!canWrite" class="w-full" /></NFormItem>
      <NFormItem label="浮动（基点）"><NInputNumber v-model:value="form.float_bps" :disabled="!canWrite" class="w-full" /></NFormItem>
      <NFormItem label="最低提现 USDT"><NInputNumber v-model:value="form.min_withdraw_usdt_micro" :disabled="!canWrite" class="w-full" /></NFormItem>
      <NFormItem label="汇率缓存秒数"><NInputNumber v-model:value="form.exchange_rate_cache_seconds" :disabled="!canWrite" class="w-full" /></NFormItem>
      <NFormItem label="汇率源"><NInput v-model:value="form.frankfurter_url" :disabled="!canWrite" /></NFormItem>
      <NButton type="primary" :disabled="!canWrite" @click="save">保存</NButton>
    </NForm>
    <NCard title="当前汇率" class="mt-16px">
      <NDataTable bordered striped :single-line="false" size="small" :columns="kvColumns" :data="preview" />
    </NCard>
    <NCard title="累计" class="mt-12px">
      <NDataTable bordered striped :single-line="false" size="small" :columns="kvColumns" :data="stats" />
    </NCard>
  </div>
</template>
