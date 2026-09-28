<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import PageHeader from '@/components/business/page-header.vue';
import { opsApiMetrics, opsApiMetricsRequests, opsApiMetricsReset } from '@/service/api/adminOps';
import { unwrap } from '@/service/http';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { buildAdminColumns, kvRows, listOf } from '@/utils/adminTable';

defineOptions({ name: 'ops_api-monitor' });
const canManage = computed(() => hasPerm('admin.manage'));
const view = ref<'summary' | 'detail'>('summary');
const summary = ref<Record<string, unknown>[]>([]);
const requests = ref<Record<string, unknown>[]>([]);
const overview = ref<Record<string, unknown>>({});
const loading = ref(false);
const pathFilter = ref('');
const requestMode = ref<'all' | 'errors' | 'slow'>('all');
const summaryColumns = computed(() => buildAdminColumns(summary.value));
const requestColumns = computed(() => buildAdminColumns(requests.value));
const overviewRows = computed(() => kvRows(overview.value));
const overviewColumns = [
  { title: '项目', key: 'label', width: 160 },
  { title: '内容', key: 'value' }
];

async function load() {
  if (!canManage.value) return;
  loading.value = true;
  try {
    if (view.value === 'summary') {
      const data = unwrap(await opsApiMetrics());
      overview.value = Object.fromEntries(Object.entries(data).filter(([, value]) => !Array.isArray(value) && (typeof value !== 'object' || value == null)));
      summary.value = listOf(data);
    } else {
      const data = unwrap(
        await opsApiMetricsRequests({
          path: pathFilter.value || undefined,
          status_gte: requestMode.value === 'errors' ? 400 : undefined,
          slow_only: requestMode.value === 'slow'
        })
      );
      requests.value = listOf(data);
    }
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    loading.value = false;
  }
}
onMounted(load);
function openDetail(row: Record<string, unknown>) {
  pathFilter.value = String(row.path || '');
  requestMode.value = 'all';
  view.value = 'detail';
  load();
}
function show(mode: 'all' | 'errors' | 'slow') {
  requestMode.value = mode;
  if (mode !== 'all') pathFilter.value = '';
  view.value = 'detail';
  load();
}
async function resetMetrics() {
  if (!canManage.value) return;
  await opsApiMetricsReset();
  window.$message?.success('已清空');
  load();
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="接口监控" subtitle="点击一行查看该接口的请求明细" />
    <div v-if="!canManage">当前账号没有管理权限</div>
    <template v-else>
      <NSpace class="mb-12px">
        <NButton v-if="view==='detail'" @click="view='summary'; load()">返回汇总</NButton>
        <NButton type="primary" :loading="loading" @click="load">刷新</NButton>
        <NButton type="error" ghost @click="resetMetrics">清空</NButton>
        <NButton @click="show('errors')">仅报错</NButton>
        <NButton @click="show('slow')">仅慢请求</NButton>
      </NSpace>
      <NDataTable v-if="view==='summary'" class="mb-12px" bordered striped :single-line="false" size="small" :columns="overviewColumns" :data="overviewRows" />
      <NDataTable v-if="view==='summary'" bordered striped :single-line="false" size="small" :scroll-x="1100" :loading="loading" :data="summary" :columns="summaryColumns" :row-props="(row: Record<string, unknown>) => ({ style: 'cursor:pointer', onClick: () => openDetail(row) })" />
      <NDataTable v-else bordered striped :single-line="false" size="small" :scroll-x="1200" :loading="loading" :data="requests" :columns="requestColumns" />
    </template>
  </div>
</template>
