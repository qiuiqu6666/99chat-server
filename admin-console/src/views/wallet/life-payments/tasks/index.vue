<script setup lang="ts">
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { listTasks, retryTask } from "@/service/api/lifePayments";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { computed } from 'vue';
import { buildAdminColumns } from '@/utils/adminTable';
defineOptions({ name: "wallet_life-payments_tasks" });
const columns = computed(() => buildAdminColumns(rows.value));
const canWrite = hasPerm("user.write");
const { loading, rows, total, query, load } = usePagedList(listTasks);
async function retry(row: Record<string, unknown>) {
  try { await retryTask(String(row.task_no || row.id)); window.$message?.success("已重试"); load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="缴费任务" />
    <NButton type="primary" class="mb-12px" @click="query.page=1; load()">查询</NButton>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1200" :loading="loading" :data="rows" :columns="columns" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
    <NButton v-for="row in rows" v-if="canWrite" :key="String(row.task_no)" class="mt-8px" size="small" @click="retry(row)">重试 {{ row.task_no }}</NButton>
  </div>
</template>
