<script setup lang="ts">
import { computed, h, onMounted, reactive, ref } from 'vue';
import { NButton } from 'naive-ui';
import PageHeader from '@/components/business/page-header.vue';
import { broadcastOfficial, createOfficialAccount, deleteOfficialAccount, linkOfficialAccount, listOfficialAccounts, updateOfficialAccount } from '@/service/api/officialAccounts';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { buildAdminColumns, listOf } from '@/utils/adminTable';

defineOptions({ name: 'ops_official-accounts' });
const canWrite = computed(() => hasPerm('user.write'));
const rows = ref<Record<string, unknown>[]>([]);
const saving = ref(false);
const createVisible = ref(false);
const linkVisible = ref(false);
const editVisible = ref(false);
const broadcastVisible = ref(false);
const currentId = ref('');
const form = reactive<Record<string, string>>({ name: '', account: '', content: '' });

function accountId(row: Record<string, unknown>) {
  return String(row.official_account_id || row.officialAccountId || row.id || '');
}

async function load() {
  rows.value = listOf(await listOfficialAccounts());
}
onMounted(load);
function reset() {
  form.name = '';
  form.account = '';
  form.content = '';
}
async function run(fn: () => Promise<unknown>) {
  saving.value = true;
  try {
    await fn();
    window.$message?.success('已完成');
    createVisible.value = linkVisible.value = editVisible.value = broadcastVisible.value = false;
    await load();
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

const columns = computed(() =>
  buildAdminColumns(
    rows.value,
    canWrite.value
      ? [
          {
            title: '操作',
            key: 'op',
            width: 180,
            render: row =>
              h('div', { class: 'flex gap-8px' }, [
                h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => { currentId.value = accountId(row); form.name = String(row.name || ''); editVisible.value = true; } }, { default: () => '编辑' }),
                h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => { currentId.value = accountId(row); broadcastVisible.value = true; } }, { default: () => '广播' }),
                h(NButton, { text: true, type: 'error', size: 'small', onClick: () => run(() => deleteOfficialAccount(accountId(row))) }, { default: () => '删除' })
              ])
          }
        ]
      : []
  )
);
</script>
<template>
  <div class="p-16px">
    <PageHeader title="公众号" />
    <NSpace class="mb-12px">
      <NButton @click="load">刷新</NButton>
      <NButton v-if="canWrite" type="primary" @click="createVisible = true; reset()">创建</NButton>
      <NButton v-if="canWrite" @click="linkVisible = true; reset()">绑定已有</NButton>
    </NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1200" :columns="columns" :data="rows" />
    <NModal v-model:show="createVisible" preset="card" title="创建公众号" style="width:560px">
      <NInput v-model:value="form.account" placeholder="标识" class="mb-8px" />
      <NInput v-model:value="form.name" placeholder="名称" class="mb-8px" />
      <NSpace justify="end"><NButton @click="createVisible=false">取消</NButton><NButton type="primary" :loading="saving" @click="run(() => createOfficialAccount({ slug: form.account, name: form.name }))">创建</NButton></NSpace>
    </NModal>
    <NModal v-model:show="linkVisible" preset="card" title="绑定已有公众号" style="width:560px">
      <NInput v-model:value="form.account" placeholder="公众号编号" class="mb-8px" />
      <NInput v-model:value="form.name" placeholder="名称" class="mb-8px" />
      <NSpace justify="end"><NButton @click="linkVisible=false">取消</NButton><NButton type="primary" :loading="saving" @click="run(() => linkOfficialAccount({ official_account_id: form.account, name: form.name }))">绑定</NButton></NSpace>
    </NModal>
    <NModal v-model:show="editVisible" preset="card" title="编辑公众号" style="width:560px">
      <NInput v-model:value="form.name" class="mb-8px" />
      <NSpace justify="end"><NButton @click="editVisible=false">取消</NButton><NButton type="primary" :loading="saving" @click="run(() => updateOfficialAccount(currentId, { name: form.name }))">保存</NButton></NSpace>
    </NModal>
    <NModal v-model:show="broadcastVisible" preset="card" title="广播消息" style="width:520px">
      <NInput v-model:value="form.content" type="textarea" placeholder="广播内容" class="mb-8px" />
      <NSpace justify="end"><NButton @click="broadcastVisible=false">取消</NButton><NButton type="primary" :loading="saving" @click="run(() => broadcastOfficial(currentId, { text: form.content }))">发送</NButton></NSpace>
    </NModal>
  </div>
</template>
