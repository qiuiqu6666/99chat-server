<script setup lang="ts">
import { computed, h, onMounted, ref } from 'vue';
import { NButton } from 'naive-ui';
import PageHeader from '@/components/business/page-header.vue';
import { createSplash, deleteSplash, listSplash, updateSplash } from '@/service/api/splash';
import { unwrap } from '@/service/http';
import { hasPerm } from '@/utils/perms';
import { errMessage } from '@/utils/format';
import { buildAdminColumns, listOf } from '@/utils/adminTable';

defineOptions({ name: 'ops_splash' });
const canWrite = computed(() => hasPerm('user.write'));
const rows = ref<Record<string, unknown>[]>([]);
const createVisible = ref(false);

async function load() {
  rows.value = listOf(unwrap(await listSplash()));
}
onMounted(load);
async function onUpload(file: File) {
  try {
    await createSplash(file, {});
    createVisible.value = false;
    window.$message?.success('已上传');
    load();
  } catch (e) {
    window.$message?.error(errMessage(e));
  }
  return false;
}
async function toggle(row: Record<string, unknown>) {
  try {
    await updateSplash(row.id as string | number, { enabled: !row.enabled });
    load();
  } catch (e) {
    window.$message?.error(errMessage(e));
  }
}
async function remove(row: Record<string, unknown>) {
  try {
    await deleteSplash(row.id as string | number);
    load();
  } catch (e) {
    window.$message?.error(errMessage(e));
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
            width: 140,
            render: row =>
              h('div', { class: 'flex gap-8px' }, [
                h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => toggle(row) }, { default: () => (row.enabled ? '停用' : '启用') }),
                h(NButton, { text: true, type: 'error', size: 'small', onClick: () => remove(row) }, { default: () => '删除' })
              ])
          }
        ]
      : []
  )
);
</script>
<template>
  <div class="p-16px">
    <PageHeader title="启动图" />
    <NButton v-if="canWrite" type="primary" class="mb-12px" @click="createVisible = true">上传</NButton>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1200" :columns="columns" :data="rows" />
    <NModal v-model:show="createVisible" preset="card" title="上传启动图" style="width:480px">
      <NUpload :show-file-list="false" @before-upload="({file}) => onUpload(file.file as File)"><NButton>选择文件</NButton></NUpload>
    </NModal>
  </div>
</template>
