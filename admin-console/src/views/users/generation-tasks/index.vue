<script setup lang="ts">
import { computed, reactive, ref } from 'vue';
import PageHeader from '@/components/business/page-header.vue';
import { usePagedList } from '@/hooks/business/use-paged-list';
import { createGenerationTask, getGenerationTask, listGenerationTasks } from '@/service/api/users';
import { unwrap } from '@/service/http';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { buildAdminColumns } from '@/utils/adminTable';

defineOptions({ name: 'users_generation-tasks' });
const canWrite = () => hasPerm('user.write');
const { loading, rows, total, query, load } = usePagedList(listGenerationTasks);
const createVisible = ref(false);
const detailVisible = ref(false);
const busy = ref(false);
const form = reactive({ count: 10, sex: '' });
const detail = ref<Record<string, unknown>>({});
const columns = computed(() => buildAdminColumns(rows.value));
const detailItems = computed(() => {
  const items = (detail.value.items || detail.value.accounts || detail.value.users || detail.value.success || []) as unknown;
  if (!Array.isArray(items)) return [];
  return items.map(item => (item && typeof item === 'object' ? (item as Record<string, unknown>) : { user_uid: String(item ?? '') }));
});
const detailRow = computed(() => {
  const row: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(detail.value)) {
    if (value == null || Array.isArray(value) || typeof value === 'object') continue;
    row[key] = value;
  }
  return row;
});
function openCreate() {
  createVisible.value = true;
}
const detailColumns = computed(() => buildAdminColumns(Object.keys(detailRow.value).length ? [detailRow.value] : []));
const accountColumns = computed(() => buildAdminColumns(detailItems.value));
async function submit() {
  if (!canWrite()) return;
  busy.value = true;
  try {
    await createGenerationTask({ count: form.count, sex: form.sex || undefined });
    createVisible.value = false;
    window.$message?.success('已提交');
    load();
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    busy.value = false;
  }
}
async function openDetail(row: Record<string, unknown>) {
  const taskNo = String(row.task_no ?? row.taskNo ?? '');
  if (!taskNo) return;
  detail.value = unwrap(await getGenerationTask(taskNo));
  detailVisible.value = true;
}
function accountRows() {
  return detailItems.value.map(item => ({
    nickname: String(item.nickname ?? ''),
    uid: String(item.user_uid ?? item.uid ?? ''),
    password: String(item.password ?? '')
  })).filter(item => item.uid || item.password);
}
async function copyUids() {
  const text = accountRows().map(item => [item.nickname, item.uid, item.password].filter(Boolean).join('\t')).join('\n');
  await navigator.clipboard.writeText(text);
  window.$message?.success('已复制账号和密码');
}
function exportCsv() {
  const text = ['昵称,IM号,登录密码', ...accountRows().map(item => `${item.nickname},${item.uid},${item.password}`)].join('\n');
  const blob = new Blob([text], { type: 'text/csv' });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = 'generation-uids.csv';
  a.click();
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="账号生成任务">
      <NButton v-if="canWrite()" type="primary" @click="openCreate">新建任务</NButton>
    </PageHeader>
    <NSpace class="mb-12px">
      <NButton type="primary" @click="query.page = 1; load()">查询</NButton>
      <NButton @click="load()">刷新</NButton>
    </NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :columns="columns" :data="rows" :loading="loading" :row-props="(row: Record<string, unknown>) => ({ style: 'cursor:pointer', onClick: () => openDetail(row) })" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
    <NModal v-model:show="createVisible" preset="card" title="新建生成任务" style="width: 440px">
      <NForm>
        <NFormItem label="数量"><NInputNumber v-model:value="form.count" :min="1" /></NFormItem>
        <div class="mb-12px text-13px text-#64748b">每个账号单独生成 8 位字母和数字密码，提交后在任务详情里查看。</div>
        <NFormItem label="性别"><NInput v-model:value="form.sex" /></NFormItem>
      </NForm>
      <NSpace justify="end"><NButton @click="createVisible = false">取消</NButton><NButton type="primary" :loading="busy" @click="submit">提交</NButton></NSpace>
    </NModal>
    <NModal v-model:show="detailVisible" preset="card" title="任务详情" style="width: 920px">
      <NDataTable bordered striped :single-line="false" size="small" :scroll-x="900" :columns="detailColumns" :data="Object.keys(detailRow).length ? [detailRow] : []" />
      <NDataTable v-if="detailItems.length" class="mt-12px" bordered striped :single-line="false" size="small" :scroll-x="900" :columns="accountColumns" :data="detailItems" />
      <NSpace class="mt-12px"><NButton @click="copyUids">复制账号和密码</NButton><NButton @click="exportCsv">导出 CSV</NButton></NSpace>
    </NModal>
  </div>
</template>
