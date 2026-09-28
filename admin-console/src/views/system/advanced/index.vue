<script setup lang="ts">
import { computed, ref } from 'vue';
import PageHeader from '@/components/business/page-header.vue';
import { opsFriendSyncStatus, opsGroupProjectionStatus, opsPushTest, opsSettingsReload } from '@/service/api/adminOps';
import { unwrap } from '@/service/http';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { kvRows } from '@/utils/adminTable';

defineOptions({ name: 'system_advanced' });
const canManage = computed(() => hasPerm('admin.manage'));
const loading = ref('');
const output = ref<Record<string, unknown>>({});
const pushUid = ref('');
const rows = computed(() => kvRows(output.value));
const columns = [
  { title: '项目', key: 'label', width: 180 },
  { title: '内容', key: 'value' }
];
async function run(label: string, key: string, fn: () => Promise<unknown>) {
  if (!canManage.value) return;
  loading.value = key;
  try {
    output.value = unwrap(await fn());
    window.$message?.success(label);
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    loading.value = '';
  }
}
async function doReload() {
  if (!canManage.value) return;
  loading.value = 'reload';
  try {
    await opsSettingsReload();
    window.$message?.success('已重载');
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    loading.value = '';
  }
}
async function doPushTest() {
  if (!canManage.value) return;
  loading.value = 'push';
  try {
    await opsPushTest({ user_uid: pushUid.value });
    window.$message?.success('已发送测试推送');
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    loading.value = '';
  }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="高级运维" subtitle="低频危险操作，需 admin.manage" />
    <NSpace class="mb-12px">
      <NButton :loading="loading==='group'" :disabled="!canManage" @click="run('群投影状态', 'group', opsGroupProjectionStatus)">群投影状态</NButton>
      <NButton :loading="loading==='friend'" :disabled="!canManage" @click="run('好友同步状态', 'friend', opsFriendSyncStatus)">好友同步状态</NButton>
      <NButton type="error" :loading="loading==='reload'" :disabled="!canManage" @click="doReload">重载配置</NButton>
    </NSpace>
    <NSpace class="mb-12px">
      <NInput v-model:value="pushUid" placeholder="用户 IM 号" />
      <NButton type="primary" :loading="loading==='push'" :disabled="!canManage" @click="doPushTest">推送测试</NButton>
    </NSpace>
    <NDataTable v-if="rows.length" bordered striped :single-line="false" size="small" :columns="columns" :data="rows" />
  </div>
</template>
