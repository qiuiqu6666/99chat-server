<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import PageHeader from '@/components/business/page-header.vue';
import { friendBackfillStatus, startFriendBackfill } from '@/service/api/friendBackfill';
import { unwrap } from '@/service/http';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { kvRows } from '@/utils/adminTable';

defineOptions({ name: 'ops_friend-backfill' });
const canWrite = computed(() => hasPerm('user.write'));
const status = ref<Record<string, unknown>>({});
const starting = ref(false);
const rows = computed(() => kvRows(status.value));
const columns = [
  { title: '项目', key: 'label', width: 180 },
  { title: '内容', key: 'value' }
];
async function load() {
  status.value = unwrap(await friendBackfillStatus());
}
onMounted(load);
async function start() {
  if (!canWrite.value) return;
  starting.value = true;
  try {
    await startFriendBackfill();
    window.$message?.success('已开始');
    await load();
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    starting.value = false;
  }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="系统好友补全" />
    <NSpace class="mb-12px">
      <NButton @click="load">刷新状态</NButton>
      <NButton type="primary" :loading="starting" :disabled="!canWrite" @click="start">开始补全</NButton>
    </NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :columns="columns" :data="rows" />
  </div>
</template>
