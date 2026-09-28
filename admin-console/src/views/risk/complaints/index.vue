<script setup lang="ts">
import { computed, h } from "vue";
import { NButton } from "naive-ui";
import { useRoute } from "vue-router";
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { listComplaints, updateComplaintStatus } from "@/service/api/complaints";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { buildAdminColumns } from "@/utils/adminTable";
defineOptions({ name: "risk_complaints" });
const route = useRoute();
const { loading, rows, total, query, load } = usePagedList(listComplaints, () => ({ user_uid: route.query.user_uid || undefined }));
async function setStatus(row: Record<string, unknown>, status: string) {
  if (!hasPerm("user.write")) return;
  try { await updateComplaintStatus(String(row.id), { status }); window.$message?.success("已更新"); load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
}
const columns = computed(() =>
  buildAdminColumns(rows.value, [
    {
      title: "处理",
      key: "op",
      width: 140,
      render: row =>
        h("div", { class: "flex gap-8px" }, [
          h(NButton, { text: true, type: "primary", size: "small", disabled: !hasPerm("user.write"), onClick: () => setStatus(row, "processed") }, { default: () => "已处理" }),
          h(NButton, { text: true, size: "small", disabled: !hasPerm("user.write"), onClick: () => setStatus(row, "closed") }, { default: () => "关闭" })
        ])
    }
  ])
);
</script>
<template>
  <div class="p-16px">
    <PageHeader title="聊天投诉" />
    <NSpace class="mb-12px"><NButton type="primary" @click="query.page=1; load()">查询</NButton><NButton @click="load">刷新</NButton></NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1400" :columns="columns" :data="rows" :loading="loading" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
  </div>
</template>
