<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRoute } from 'vue-router';
import PageHeader from '@/components/business/page-header.vue';
import { usePagedList } from '@/hooks/business/use-paged-list';
import { listC2cMessages, listGroupMessages } from '@/service/api/messages';
import { buildAdminColumns } from '@/utils/adminTable';

defineOptions({ name: 'risk_messages' });
const route = useRoute();
const kind = ref<'c2c' | 'group'>('c2c');
const { loading, rows, total, query, load } = usePagedList(
  params => (kind.value === 'c2c' ? listC2cMessages(params) : listGroupMessages(params)),
  () => ({ user_a: route.query.user_a || undefined, user_uid: route.query.user_uid || undefined })
);
const columns = computed(() => buildAdminColumns(rows.value));
function changeKind(v: 'c2c' | 'group') {
  kind.value = v;
  query.page = 1;
  load();
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="消息审计" />
    <NSpace class="mb-12px">
      <NButton :type="kind === 'c2c' ? 'primary' : 'default'" @click="changeKind('c2c')">单聊</NButton>
      <NButton :type="kind === 'group' ? 'primary' : 'default'" @click="changeKind('group')">群聊</NButton>
      <NButton type="primary" @click="query.page = 1; load()">查询</NButton>
      <NButton :disabled="query.page * query.page_size >= total" @click="query.page += 1; load()">下一页</NButton>
    </NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :columns="columns" :data="rows" :loading="loading" />
  </div>
</template>
