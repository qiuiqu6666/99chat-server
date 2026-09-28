<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import PageHeader from '@/components/business/page-header.vue';
import { usePagedList } from '@/hooks/business/use-paged-list';
import { listLedger } from '@/service/api/finance';
import { buildAdminColumns } from '@/utils/adminTable';

defineOptions({ name: 'wallet_ledger' });

const route = useRoute();
const { loading, rows, total, query, load } = usePagedList(listLedger, () => ({
  user_uid: route.query.user_uid || undefined,
  user_a: route.query.user_a || undefined
}));
const columns = computed(() => buildAdminColumns(rows.value));
function search() {
  query.page = 1;
  load();
}
</script>

<template>
  <div class="p-16px">
    <PageHeader title="账变记录"  />
    <NSpace class="mb-12px">
      <NInput v-model:value="query.keyword" clearable placeholder="关键词" @keyup.enter="search" />
      <NButton type="primary" @click="search">查询</NButton>
      <NButton @click="load">刷新</NButton>
    </NSpace>
    <NDataTable bordered striped :single-line="false" size="small" remote :columns="columns" :data="rows" :loading="loading" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" :scroll-x="960" />
  </div>
</template>
