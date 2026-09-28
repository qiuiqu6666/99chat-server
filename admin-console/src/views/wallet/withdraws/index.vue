<script setup lang="ts">
import { computed, h, onMounted, ref } from "vue";
import { NButton } from "naive-ui";
import { useRoute } from "vue-router";
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { approveWithdraw, listWithdraws, manualCompleteWithdraw, rejectWithdraw, withdrawTotpConfirm, withdrawTotpReset, withdrawTotpSetup, withdrawTotpStatus } from "@/service/api/finance";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { buildAdminColumns } from '@/utils/adminTable';
defineOptions({ name: "wallet_withdraws" });
const route = useRoute();
const canWrite = computed(() => hasPerm("user.write"));
const totpConfigured = ref(false);
const totpLoading = ref(false);
const setup = ref<Record<string, unknown>>({});
const code = ref("");
const tx = ref("");
const isAutoMode = ref(true);
const { loading, rows, total, query, load } = usePagedList(p => listWithdraws({ ...p, user_uid: route.query.user_uid || undefined }));
async function loadTotp() {
  totpLoading.value = true;
  try {
    const data = await withdrawTotpStatus();
    totpConfigured.value = data.configured;
    isAutoMode.value = data.withdraw_mode !== "manual";
  } catch { /* status optional */ }
  finally { totpLoading.value = false; }
}
onMounted(loadTotp);
async function openSetup() {
  const data = await withdrawTotpSetup();
  setup.value = { secret: data.secret, otpauth_url: data.otpauth_uri };
}
async function confirmTotp() {
  await withdrawTotpConfirm({ totp_code: code.value });
  window.$message?.success("已绑定");
  code.value = "";
  await loadTotp();
}
async function rebind() {
  await withdrawTotpReset({ totp_code: code.value });
  await openSetup();
}
function bizNo(row: Record<string, unknown>) {
  return String(row.biz_no || row.order_no || row.id || "");
}
const columns = computed(() =>
  buildAdminColumns(rows.value, [
    {
      title: "审核",
      key: "review",
      width: 240,
      render: row =>
        h("div", { class: "flex gap-8px" }, [
          h(NButton, { size: "tiny", type: "primary", disabled: !canWrite.value, onClick: () => act(() => approveWithdraw(bizNo(row), { totp_code: code.value })) }, { default: () => "通过" }),
          h(NButton, { size: "tiny", disabled: !canWrite.value, onClick: () => act(() => rejectWithdraw(bizNo(row), { totp_code: code.value })) }, { default: () => "拒绝" }),
          h(NButton, { size: "tiny", type: "warning", disabled: !canWrite.value, onClick: () => act(() => manualCompleteWithdraw(bizNo(row), { tx_id: tx.value, totp_code: code.value })) }, { default: () => "确认已打款" })
        ])
    }
  ])
);
async function act(fn: () => Promise<unknown>) {
  if (!canWrite.value) return;
  try { await fn(); window.$message?.success("已提交"); load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="提现审核" :subtitle="isAutoMode ? '自动打款模式 · 通过将从热钱包广播；通过/拒绝需谷歌验证码' : '手动下款模式 · 链上打款后点「确认已打款」填 Tx 结单，勿再点自动打款'">
      <NTag :type="totpConfigured ? 'success' : 'error'">{{ totpConfigured ? "TOTP 已绑定" : "TOTP 未绑定" }}</NTag>
      <NButton v-if="canWrite && !totpConfigured" type="warning" @click="openSetup">绑定谷歌验证</NButton>
      <NButton v-if="canWrite && totpConfigured" @click="rebind">重新绑定</NButton>
    </PageHeader>
    <NAlert v-if="canWrite && !totpConfigured" type="warning" class="mb-12px">尚未绑定谷歌验证码</NAlert>
    <NInput v-if="setup.otpauth_url || setup.secret" v-model:value="code" placeholder="验证码" class="mb-8px" />
    <div v-if="setup.secret" class="mb-8px">密钥：{{ setup.secret }}</div>
    <NButton v-if="setup.secret" class="mb-12px" type="primary" @click="confirmTotp">确认绑定</NButton>
    <NInput v-model:value="code" placeholder="操作验证码" class="mb-8px" />
    <NInput v-model:value="tx" placeholder="Tx Hash" class="mb-8px" />
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1400" :loading="loading" :data="rows" :columns="columns" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" />
  </div>
</template>
