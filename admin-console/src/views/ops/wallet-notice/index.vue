<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import PageHeader from "@/components/business/page-header.vue";
import { sendWalletNotice } from "@/service/api/walletNotice";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
defineOptions({ name: "ops_wallet-notice" });
const canWrite = computed(() => hasPerm("user.write"));
const sending = ref(false);
const loading = computed(() => sending.value);
const rows = reactive([{ title: "", body: "" }]);
function addRow() { rows.push({ title: "", body: "" }); }
function removeRow(i: number) { rows.splice(i, 1); }
async function send() {
  if (!canWrite.value) return;
  sending.value = true;
  try { await sendWalletNotice({ items: rows }); window.$message?.success("已发送"); }
  catch (e) { window.$message?.error(errMessage(e)); }
  finally { sending.value = false; }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="支付助手通知" subtitle="对接 /api/v1/im/platform-wallet-notice" />
    <div v-for="(row, i) in rows" :key="i" class="mb-8px flex gap-8px">
      <NInput v-model:value="row.title" placeholder="标题" />
      <NInput v-model:value="row.body" placeholder="内容" />
      <NButton text type="error" @click="removeRow(i)">删</NButton>
    </div>
    <NButton text type="primary" @click="addRow">添加一行</NButton>
    <div class="mt-12px"><NButton type="primary" :loading="loading" :disabled="!canWrite" @click="send">发送</NButton></div>
  </div>
</template>
