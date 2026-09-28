<script setup lang="ts">
import { computed } from 'vue';
import { useRoute } from 'vue-router';
import PageHeader from '@/components/business/page-header.vue';
import { usePagedList } from '@/hooks/business/use-paged-list';
import { privacyStatsUsers } from '@/service/api/privacy';
import { buildAdminColumns } from '@/utils/adminTable';

defineOptions({ name: 'privacy_stats' });

const route = useRoute();
const { loading, rows, total, query, load } = usePagedList(privacyStatsUsers, () => ({
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
    <PageHeader title="隐私统计"  />
    <NSpace class="mb-12px">
      <NInput v-model:value="query.keyword" clearable placeholder="关键词" @keyup.enter="search" />
      <NButton type="primary" @click="search">查询</NButton>
      <NButton @click="load">刷新</NButton>
    </NSpace>
    <NDataTable bordered striped :single-line="false" size="small" remote :columns="columns" :data="rows" :loading="loading" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" :scroll-x="960" />
  </div>
</template>
