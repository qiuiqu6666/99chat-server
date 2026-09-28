<script setup lang="ts">
import { h, onMounted, ref } from 'vue';
import { NButton, NInputNumber, NSwitch } from 'naive-ui';
import PageHeader from '@/components/business/page-header.vue';
import { listLimitConfig, updateLimitConfig } from '@/service/api/limitConfig';
import { unwrap } from '@/service/http';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { listOf } from '@/utils/adminTable';

defineOptions({ name: 'wallet_limit-config' });
const canWrite = hasPerm('user.write');
const rows = ref<Record<string, any>[]>([]);
const savingId = ref<string | number | null>(null);
const SCENE: Record<string, string> = {
  TRANSFER: '转账',
  RED_PACKET: '红包',
  WITHDRAW: '提现',
  EXCHANGE: '闪兑',
  LIVE_TIP: '打赏'
};

function val(row: Record<string, any>, snake: string, camel: string) {
  return row[snake] ?? row[camel];
}
function setVal(row: Record<string, any>, snake: string, camel: string, value: unknown) {
  if (snake in row || !(camel in row)) row[snake] = value;
  if (camel in row || !(snake in row)) row[camel] = value;
}

async function load() {
  rows.value = listOf(unwrap(await listLimitConfig()));
}
onMounted(load);
async function save(row: Record<string, any>) {
  savingId.value = row.id;
  try {
    await updateLimitConfig(row.id, {
      per_tx_max: val(row, 'per_tx_max', 'perTxMax'),
      daily_max: val(row, 'daily_max', 'dailyMax'),
      enabled: !!row.enabled
    });
    window.$message?.success('已保存');
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    savingId.value = null;
  }
}

const columns = [
  {
    title: '场景',
    key: 'scene',
    width: 100,
    render: (row: Record<string, any>) => row.scene_label || row.sceneLabel || SCENE[String(row.scene)] || row.scene
  },
  { title: '币种', key: 'currency', width: 90, render: (row: Record<string, any>) => row.currency_label || row.currencyLabel || row.currency },
  {
    title: '单笔上限',
    key: 'per_tx_max',
    width: 160,
    render: (row: Record<string, any>) =>
      h(NInputNumber, {
        value: val(row, 'per_tx_max', 'perTxMax'),
        size: 'small',
        disabled: !canWrite,
        onUpdateValue: (v: number | null) => setVal(row, 'per_tx_max', 'perTxMax', v)
      })
  },
  {
    title: '每日上限',
    key: 'daily_max',
    width: 160,
    render: (row: Record<string, any>) =>
      h(NInputNumber, {
        value: val(row, 'daily_max', 'dailyMax'),
        size: 'small',
        disabled: !canWrite,
        onUpdateValue: (v: number | null) => setVal(row, 'daily_max', 'dailyMax', v)
      })
  },
  {
    title: '启用',
    key: 'enabled',
    width: 80,
    render: (row: Record<string, any>) =>
      h(NSwitch, { value: !!row.enabled, disabled: !canWrite, onUpdateValue: (v: boolean) => (row.enabled = v) })
  },
  {
    title: '操作',
    key: 'op',
    width: 90,
    render: (row: Record<string, any>) =>
      canWrite
        ? h(NButton, { size: 'small', type: 'primary', loading: savingId.value === row.id, onClick: () => save(row) }, { default: () => '保存' })
        : '—'
  }
];
</script>
<template>
  <div class="p-16px">
    <PageHeader title="限额配置" subtitle="配置转账、红包等场景的单笔与每日累计上限" />
    <NButton class="mb-12px" @click="load">刷新</NButton>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="760" :columns="columns" :data="rows" />
  </div>
</template>
