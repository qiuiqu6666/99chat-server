<script setup lang="ts">
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { listProviders, setProviderEnabled } from "@/service/api/lifePayments";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { computed } from 'vue';
import { buildAdminColumns } from '@/utils/adminTable';
defineOptions({ name: "wallet_life-payments_providers" });
const columns = computed(() => buildAdminColumns(rows.value));
const canWrite = hasPerm("user.write");
const { loading, rows, load } = usePagedList(listProviders);
async function toggle(row: Record<string, unknown>, enabled: boolean) {
  try { await setProviderEnabled(String(row.code), { enabled }); window.$message?.success("已更新"); load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="缴费供应商" />
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1200" :loading="loading" :data="rows" :columns="columns" />
    <div v-for="row in rows" v-if="canWrite" :key="String(row.code)" class="mt-8px flex gap-8px">
      <span>{{ row.code }}</span>
      <NButton size="small" @click="toggle(row, true)">启用</NButton>
      <NButton size="small" @click="toggle(row, false)">停用</NButton>
    </div>
  </div>
</template>
