<script setup lang="ts">
import { computed } from "vue";
import { useRouter } from "vue-router";
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { listGroups } from "@/service/api/groups";
import { buildAdminColumns } from "@/utils/adminTable";
defineOptions({ name: "risk_groups-index" });
const router = useRouter();
const { loading, rows, total, query, load } = usePagedList(listGroups);
const columns = computed(() => buildAdminColumns(rows.value));
function open(row: Record<string, unknown>) {
  const id = String(row.g_id ?? row.gId ?? row.id ?? "");
  if (id) router.push(`/risk/groups/${id}`);
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="群组列表" />
    <NSpace class="mb-12px"><NInput v-model:value="query.keyword" clearable placeholder="关键词" @keyup.enter="query.page=1; load()" /><NButton type="primary" @click="query.page=1; load()">查询</NButton></NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1100" :columns="columns" :data="rows" :loading="loading" :row-props="(row: Record<string, unknown>) => ({ style: 'cursor:pointer', onClick: () => open(row) })" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
  </div>
</template>
