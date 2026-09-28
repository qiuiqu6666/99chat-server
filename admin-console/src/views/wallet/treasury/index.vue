<script setup lang="ts">
import { computed, h, onMounted, ref } from "vue";
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { collectAll, collectUser, getTreasurySummary, listTreasuryAddresses } from "@/service/api/walletTreasury";
import { unwrap } from "@/service/http";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { NButton } from 'naive-ui';
import { buildAdminColumns, kvRows } from '@/utils/adminTable';
defineOptions({ name: "wallet_treasury" });
const columns = computed(() =>
  buildAdminColumns(
    rows.value,
    canWrite.value
      ? [
          {
            title: '操作',
            key: 'op',
            width: 120,
            render: row =>
              h(NButton, { size: 'small', onClick: () => run(() => collectUser(String(row.user_uid))) }, { default: () => '归集' })
          }
        ]
      : []
  )
);
const summaryRows = computed(() => kvRows(summary.value));
const summaryColumns = [
  { title: '项目', key: 'label', width: 180 },
  { title: '内容', key: 'value' }
];
const canWrite = computed(() => hasPerm("user.write"));
const summary = ref<Record<string, unknown>>({});
const { loading, rows, total, query, load } = usePagedList(listTreasuryAddresses);
onMounted(async () => { try { summary.value = unwrap(await getTreasurySummary()); } catch { /* empty */ } });
async function run(fn: () => Promise<unknown>) {
  if (!canWrite.value) return;
  try { await fn(); window.$message?.success("已提交归集"); load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="链上钱包" />
    <NDataTable class="mb-12px" bordered striped :single-line="false" size="small" :columns="summaryColumns" :data="summaryRows" />
    <NSpace class="mb-12px">
      <NButton type="primary" @click="query.page=1; load()">查询</NButton>
      <NButton v-if="canWrite" type="warning" @click="run(collectAll)">全部归集</NButton>
    </NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1200" :loading="loading" :data="rows" :columns="columns" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
  </div>
</template>
