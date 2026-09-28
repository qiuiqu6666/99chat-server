<script setup lang="ts">
import { computed, h } from "vue";
import { NButton } from "naive-ui";
import { useRoute } from "vue-router";
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { banDevice, kickDevice, listDevices, unbanDevice } from "@/service/api/devices";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { buildAdminColumns } from '@/utils/adminTable';
defineOptions({ name: "risk_devices" });
const route = useRoute();
const canWrite = computed(() => hasPerm("user.write"));
const { loading, rows, total, query, load } = usePagedList(listDevices, () => ({ user_uid: route.query.user_uid || undefined }));
const columns = computed(() =>
  buildAdminColumns(
    rows.value,
    canWrite.value
      ? [
          {
            title: "操作",
            key: "op",
            width: 200,
            render: row =>
              h("div", { class: "flex gap-8px" }, [
                h(NButton, { size: "tiny", type: "error", onClick: () => act(() => banDevice(row.id as string | number)) }, { default: () => "封禁" }),
                h(NButton, { size: "tiny", onClick: () => act(() => unbanDevice(row.id as string | number)) }, { default: () => "解封" }),
                h(NButton, { size: "tiny", onClick: () => act(() => kickDevice(row.id as string | number)) }, { default: () => "踢下线" })
              ])
          }
        ]
      : []
  )
);
async function act(fn: () => Promise<unknown>) {
  if (!canWrite.value) return;
  try { await fn(); window.$message?.success("已执行"); load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="设备终端" subtitle="查询、封禁、解封、踢下线" />
    <NSpace class="mb-12px"><NInput v-model:value="query.keyword" clearable @keyup.enter="query.page=1; load()" /><NButton type="primary" @click="query.page=1; load()">查询</NButton><NButton @click="load">刷新</NButton></NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1400" :columns="columns" :data="rows" :loading="loading" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
  </div>
</template>
