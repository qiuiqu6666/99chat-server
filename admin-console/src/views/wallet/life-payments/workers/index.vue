<script setup lang="ts">
import { ref } from "vue";
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { issueWorkerToken, listWorkers } from "@/service/api/lifePayments";
import { unwrap } from "@/service/http";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { computed } from 'vue';
import { buildAdminColumns } from '@/utils/adminTable';
defineOptions({ name: "wallet_life-payments_workers" });
const columns = computed(() => buildAdminColumns(rows.value));
const canWrite = hasPerm("user.write");
const tokenText = ref("");
const show = ref(false);
const { loading, rows, query, load } = usePagedList(listWorkers);
async function issue(row: Record<string, unknown>) {
  try {
    const data = unwrap(await issueWorkerToken(String(row.worker_id || row.id)));
    tokenText.value = String(data.token ?? data.access_token ?? JSON.stringify(data));
    show.value = true;
  } catch (e) { window.$message?.error(errMessage(e)); }
}
async function copyToken() { await navigator.clipboard.writeText(tokenText.value); window.$message?.success("已复制"); }
</script>
<template>
  <div class="p-16px">
    <PageHeader title="缴费工人" />
    <NButton type="primary" class="mb-12px" @click="load">查询</NButton>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1200" :loading="loading" :data="rows" :columns="columns" />
    <NButton v-for="row in rows" v-if="canWrite" :key="String(row.id || row.worker_id)" class="mt-8px" size="small" @click="issue(row)">签发 Token</NButton>
    <NModal v-model:show="show" preset="card" title="工人 Token" style="width: 520px">
      <NInput type="textarea" :value="tokenText" readonly />
      <NSpace class="mt-12px" justify="end"><NButton @click="show=false">关闭</NButton><NButton type="primary" @click="copyToken">复制</NButton></NSpace>
    </NModal>
  </div>
</template>
