<script setup lang="ts">
import { h, onMounted, ref } from 'vue';
import { NButton, NInputNumber, NSelect, NSwitch } from 'naive-ui';
import PageHeader from '@/components/business/page-header.vue';
import { listFeeConfig, updateFeeConfig } from '@/service/api/feeConfig';
import { unwrap } from '@/service/http';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { listOf } from '@/utils/adminTable';

defineOptions({ name: 'wallet_fee-config' });
const canWrite = hasPerm('user.write');
const rows = ref<Record<string, any>[]>([]);
const savingId = ref<string | number | null>(null);
const SCENE: Record<string, string> = {
  WITHDRAW: '提现',
  TRANSFER_PLATFORM: '转账',
  RED_PACKET_SEND: '发红包',
  EXCHANGE: '闪兑',
  LIVE_TIP: '打赏'
};
const feeOptions = [
  { label: '无', value: 'NONE' },
  { label: '按比例', value: 'PERCENT' },
  { label: '固定金额', value: 'FIXED' }
];

function val(row: Record<string, any>, snake: string, camel: string) {
  return row[snake] ?? row[camel];
}
function setVal(row: Record<string, any>, snake: string, camel: string, value: unknown) {
  if (snake in row || !(camel in row)) row[snake] = value;
  if (camel in row || !(snake in row)) row[camel] = value;
}

async function load() {
  rows.value = listOf(unwrap(await listFeeConfig()));
}
onMounted(load);
async function save(row: Record<string, any>) {
  savingId.value = row.id;
  try {
    await updateFeeConfig(row.id, {
      fee_type: val(row, 'fee_type', 'feeType'),
      fee_value: val(row, 'fee_value', 'feeValue'),
      min_fee: val(row, 'min_fee', 'minFee'),
      max_fee: val(row, 'max_fee', 'maxFee'),
      enabled: !!val(row, 'enabled', 'enabled')
    });
    window.$message?.success('已保存');
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    savingId.value = null;
  }
}

const columns = [
  { title: '场景', key: 'scene', width: 100, render: (row: Record<string, any>) => SCENE[String(row.scene)] || row.scene },
  { title: '币种', key: 'currency', width: 90 },
  {
    title: '计费方式',
    key: 'fee_type',
    width: 140,
    render: (row: Record<string, any>) =>
      h(NSelect, {
        value: val(row, 'fee_type', 'feeType'),
        options: feeOptions,
        size: 'small',
        disabled: !canWrite,
        onUpdateValue: (v: string) => setVal(row, 'fee_type', 'feeType', v)
      })
  },
  {
    title: '费率',
    key: 'fee_value',
    width: 140,
    render: (row: Record<string, any>) =>
      h(NInputNumber, {
        value: val(row, 'fee_value', 'feeValue'),
        size: 'small',
        disabled: !canWrite,
        onUpdateValue: (v: number | null) => setVal(row, 'fee_value', 'feeValue', v)
      })
  },
  {
    title: '最低',
    key: 'min_fee',
    width: 140,
    render: (row: Record<string, any>) =>
      h(NInputNumber, {
        value: val(row, 'min_fee', 'minFee'),
        size: 'small',
        disabled: !canWrite,
        onUpdateValue: (v: number | null) => setVal(row, 'min_fee', 'minFee', v)
      })
  },
  {
    title: '最高',
    key: 'max_fee',
    width: 140,
    render: (row: Record<string, any>) =>
      h(NInputNumber, {
        value: val(row, 'max_fee', 'maxFee'),
        size: 'small',
        disabled: !canWrite,
        onUpdateValue: (v: number | null) => setVal(row, 'max_fee', 'maxFee', v)
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
    <PageHeader title="手续费配置" />
    <NButton class="mb-12px" @click="load">刷新</NButton>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1000" :columns="columns" :data="rows" />
  </div>
</template>
