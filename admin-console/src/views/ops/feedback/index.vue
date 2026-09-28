<script setup lang="ts">
import { h } from "vue";
import { NButton } from "naive-ui";
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { listFeedback, updateFeedbackStatus } from "@/service/api/feedback";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { computed } from 'vue';
import { buildAdminColumns } from '@/utils/adminTable';
defineOptions({ name: "ops_feedback" });
const columns = computed(() =>
  buildAdminColumns(rows.value, [
    {
      title: "操作",
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
const { loading, rows, total, query, load } = usePagedList(listFeedback);
async function setStatus(row: Record<string, unknown>, status: string) {
  if (!hasPerm("user.write")) return;
  try { await updateFeedbackStatus(String(row.id), { status }); window.$message?.success("已更新"); load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="用户反馈" />
    <NSpace class="mb-12px"><NButton type="primary" @click="query.page=1; load()">查询</NButton></NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1200" :loading="loading" :data="rows" :columns="columns" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
  </div>
</template>
