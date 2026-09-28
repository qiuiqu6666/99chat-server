<script setup lang="ts">
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { failRefundOrder, listOrders, manualOrder } from "@/service/api/lifePayments";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { computed } from 'vue';
import { buildAdminColumns } from '@/utils/adminTable';
defineOptions({ name: "wallet_life-payments_orders" });
const columns = computed(() => buildAdminColumns(rows.value));
const canWrite = hasPerm("user.write");
const { loading, rows, total, query, load } = usePagedList(listOrders);
async function run(fn: () => Promise<unknown>) {
  try { await fn(); window.$message?.success("已提交"); load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="缴费订单" />
    <NButton type="primary" class="mb-12px" @click="query.page=1; load()">查询</NButton>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1200" :loading="loading" :data="rows" :columns="columns" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
    <div v-for="row in rows" v-if="canWrite" :key="String(row.order_no)" class="mt-8px flex gap-8px">
      <span>{{ row.order_no }}</span>
      <NButton size="small" @click="run(() => manualOrder(String(row.order_no)))">人工处理</NButton>
      <NButton size="small" type="error" @click="run(() => failRefundOrder(String(row.order_no)))">失败退款</NButton>
    </div>
  </div>
</template>
