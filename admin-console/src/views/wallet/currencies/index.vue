<script setup lang="ts">
import { h, onMounted, ref } from 'vue';
import { NAvatar, NButton, NInput, NInputNumber, NSwitch, NUpload } from 'naive-ui';
import PageHeader from '@/components/business/page-header.vue';
import { listCurrencies, updateCurrency, uploadCurrencyLogo } from '@/service/api/currencies';
import { unwrap } from '@/service/http';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { DEFAULT_AVATAR, listOf } from '@/utils/adminTable';

defineOptions({ name: 'wallet_currencies' });
const canWrite = hasPerm('user.write');
const rows = ref<Record<string, any>[]>([]);
const saving = ref('');

async function load() {
  rows.value = listOf(unwrap(await listCurrencies()));
}
onMounted(load);

async function save(row: Record<string, any>) {
  saving.value = String(row.code);
  try {
    await updateCurrency(String(row.code), row);
    window.$message?.success('已保存');
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    saving.value = '';
  }
}
async function onLogo(row: Record<string, any>, file: File) {
  try {
    const res = unwrap(await uploadCurrencyLogo(file));
    row.logo_url = res.url || res.logo || row.logo_url;
    window.$message?.success('已上传');
  } catch (e) {
    window.$message?.error(errMessage(e));
  }
  return false;
}

const columns = [
  { title: '币种', key: 'code', width: 90 },
  {
    title: '图标',
    key: 'logo_url',
    width: 72,
    render: (row: Record<string, any>) => h(NAvatar, { round: true, size: 32, src: row.logo_url || DEFAULT_AVATAR, fallbackSrc: DEFAULT_AVATAR })
  },
  {
    title: '名称',
    key: 'name',
    width: 160,
    render: (row: Record<string, any>) =>
      h(NInput, { value: row.name, size: 'small', disabled: !canWrite, onUpdateValue: (v: string) => (row.name = v) })
  },
  { title: '平台币', key: 'platform_coin', width: 90, render: (row: Record<string, any>) => (row.platform_coin ? '是' : '否') },
  {
    title: '充值',
    key: 'deposit_enabled',
    width: 80,
    render: (row: Record<string, any>) =>
      h(NSwitch, { value: !!row.deposit_enabled, disabled: !canWrite, onUpdateValue: (v: boolean) => (row.deposit_enabled = v) })
  },
  {
    title: '提现',
    key: 'withdraw_enabled',
    width: 80,
    render: (row: Record<string, any>) =>
      h(NSwitch, { value: !!row.withdraw_enabled, disabled: !canWrite, onUpdateValue: (v: boolean) => (row.withdraw_enabled = v) })
  },
  {
    title: '启用',
    key: 'enabled',
    width: 80,
    render: (row: Record<string, any>) =>
      h(NSwitch, { value: !!row.enabled, disabled: !canWrite, onUpdateValue: (v: boolean) => (row.enabled = v) })
  },
  {
    title: '排序',
    key: 'sort_order',
    width: 110,
    render: (row: Record<string, any>) =>
      h(NInputNumber, { value: row.sort_order, size: 'small', disabled: !canWrite, onUpdateValue: (v: number | null) => (row.sort_order = v) })
  },
  { title: '手续费', key: 'withdraw_fee_label', ellipsis: { tooltip: true } },
  {
    title: '操作',
    key: 'op',
    width: 180,
    render: (row: Record<string, any>) =>
      h('div', { class: 'flex gap-8px' }, [
        canWrite
          ? h(NUpload, { showFileList: false, accept: 'image/*', onBeforeUpload: ({ file }: { file: { file: File } }) => onLogo(row, file.file) }, {
              default: () => h(NButton, { size: 'small' }, { default: () => '上传图标' })
            })
          : null,
        canWrite
          ? h(NButton, { size: 'small', type: 'primary', loading: saving.value === row.code, onClick: () => save(row) }, { default: () => '保存' })
          : null
      ])
  }
];
</script>
<template>
  <div class="p-16px">
    <PageHeader title="币种管理" />
    <NButton class="mb-12px" @click="load">刷新</NButton>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1100" :columns="columns" :data="rows" />
  </div>
</template>
