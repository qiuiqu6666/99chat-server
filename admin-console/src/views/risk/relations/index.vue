<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import PageHeader from '@/components/business/page-header.vue';
import { usePagedList } from '@/hooks/business/use-paged-list';
import { listFriends, listRelationGroups, listSameDevice, listSameIp } from '@/service/api/relations';
import { buildAdminColumns } from '@/utils/adminTable';

defineOptions({ name: 'risk_relations' });
const route = useRoute();
const tab = ref('friends');
const fetchers = { friends: listFriends, groups: listRelationGroups, ip: listSameIp, device: listSameDevice };
const { loading, rows, total, query, load } = usePagedList(
  params => fetchers[tab.value as keyof typeof fetchers](params),
  () => ({ user_uid: route.query.user_uid || undefined })
);
const columns = computed(() => buildAdminColumns(rows.value));
function changeTab(v: string) {
  tab.value = v;
  query.page = 1;
  load();
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="好友关系" subtitle="按用户查好友 / 群 / 同 IP / 同设备" />
    <NSpace class="mb-12px">
      <NInput v-model:value="query.keyword" clearable placeholder="用户 UID" @keyup.enter="query.page = 1; load()" />
      <NButton type="primary" @click="query.page = 1; load()">查询</NButton>
    </NSpace>
    <NTabs type="line" :value="tab" @update:value="changeTab">
      <NTab name="friends" tab="好友" />
      <NTab name="groups" tab="群" />
      <NTab name="ip" tab="同 IP" />
      <NTab name="device" tab="同设备" />
    </NTabs>
    <NDataTable bordered striped :single-line="false" size="small" class="mt-12px" :columns="columns" :data="rows" :loading="loading" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
  </div>
</template>
