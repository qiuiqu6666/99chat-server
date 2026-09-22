<script setup lang="tsx">
import { onMounted, reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import { deviceDetection } from "@pureadmin/utils";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  collectWalletTreasuryAllApi,
  collectWalletTreasuryOneApi,
  getWalletTreasuryAddressesApi,
  getWalletTreasurySummaryApi,
  tronscanAddressUrl,
  type TreasurySummary,
  type TreasuryWalletItem
} from "@/api/wallet-treasury";
import type { PaginationProps } from "@pureadmin/table";

defineOptions({ name: "ImWalletTreasury" });

const tableRef = ref();
const loading = ref(true);
const summaryLoading = ref(true);
const collectAllLoading = ref(false);
const collectOneLoading = ref("");
const summary = ref<TreasurySummary>({});
const dataList = ref<TreasuryWalletItem[]>([]);
const refreshChain = ref(false);

const form = reactive({ keyword: "" });
const pagination = reactive<PaginationProps>({
  total: 0,
  pageSize: 20,
  currentPage: 1,
  background: true
});

const columns: TableColumnList = [
  { label: "IM 号", prop: "user_uid", width: 128 },
  { label: "昵称", prop: "nickname", minWidth: 100 },
  {
    label: "充值地址",
    prop: "tron_address",
    minWidth: 180,
    showOverflowTooltip: true,
    cellRenderer: ({ row }) => {
      const addr = String(row.tron_address ?? "").trim();
      if (!addr) return "—";
      const url = tronscanAddressUrl(addr);
      if (!url) return addr;
      return (
        <a
          href={url}
          target="_blank"
          rel="noopener noreferrer"
          class="text-[var(--el-color-primary)] hover:underline"
        >
          {addr}
        </a>
      );
    }
  },
  { label: "链上 USDT", prop: "chain_usdt", width: 120 },
  { label: "链上 TRX", prop: "chain_trx", width: 120 },
  {
    label: "同步时间",
    prop: "chain_balance_at",
    width: 168,
    cellRenderer: ({ row }) => {
      const raw = String(row.chain_balance_at ?? "").trim();
      if (!raw) return "—";
      const d = new Date(raw);
      if (Number.isNaN(d.getTime())) return raw;
      return d.toLocaleString();
    }
  },
  { label: "平台 USDT", prop: "platform_usdt", width: 120 },
  { label: "平台 TRX", prop: "platform_trx", width: 120 },
  {
    label: "操作",
    prop: "actions",
    fixed: "right",
    width: 100,
    cellRenderer: ({ row }) => {
      const uid = String(row.user_uid ?? "");
      const busy = collectOneLoading.value === uid;
      return (
        <el-button
          link
          type="primary"
          loading={busy}
          disabled={busy || !summary.value.collect_ready}
          onClick={() => handleCollectOne(row)}
        >
          归集
        </el-button>
      );
    }
  }
];

async function loadSummary() {
  summaryLoading.value = true;
  try {
    summary.value = await getWalletTreasurySummaryApi();
  } catch (err: unknown) {
    summary.value = {};
    message(adminApiErrMessage(err, "汇总加载失败"), { type: "warning" });
  } finally {
    summaryLoading.value = false;
  }
}

async function onSearch() {
  loading.value = true;
  try {
    const res = await getWalletTreasuryAddressesApi({
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      keyword: form.keyword.trim() || undefined,
      refresh_chain: refreshChain.value
    });
    dataList.value = (res.items ?? []).map(r => ({
      ...r,
      chain_usdt: r.chain_loaded ? (r.chain_usdt ?? "0.00") : "—",
      chain_trx: r.chain_loaded ? (r.chain_trx ?? "0.000000") : "—"
    }));
    pagination.total = Number(res.total ?? 0);
    if (typeof res.page_size === "number" && res.page_size > 0) {
      pagination.pageSize = res.page_size;
    }
    if (refreshChain.value) {
      await loadSummary();
    }
  } catch (err: unknown) {
    dataList.value = [];
    pagination.total = 0;
    message(adminApiErrMessage(err, "钱包列表加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
    tableRef.value?.setAdaptive?.();
  }
}

async function refreshAll() {
  await loadSummary();
  await onSearch();
}

async function handleCollectOne(row: TreasuryWalletItem) {
  const uid = String(row.user_uid ?? "").trim();
  if (!uid) return;
  try {
    await ElMessageBox.confirm(
      `确认归集 UID ${uid} 链上 USDT/TRX 至热钱包？`,
      "单钱包归集",
      { type: "warning", confirmButtonText: "归集", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  collectOneLoading.value = uid;
  try {
    const res = await collectWalletTreasuryOneApi(uid);
    message(
      `归集成功：USDT ${res.usdt_swept ?? "0"}，TRX ${res.trx_swept ?? "0"}`,
      { type: "success" }
    );
    await refreshAll();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "归集失败"), { type: "warning" });
  } finally {
    collectOneLoading.value = "";
  }
}

async function handleCollectAll() {
  try {
    await ElMessageBox.confirm(
      "将全部用户充值地址中的链上 USDT/TRX 归集至热钱包（每批最多 100 个地址，有余额才执行）。是否继续？",
      "全部归集",
      { type: "warning", confirmButtonText: "全部归集", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  collectAllLoading.value = true;
  try {
    const res = await collectWalletTreasuryAllApi();
    message(
      `全部归集完成：成功 ${res.success ?? 0}，失败 ${res.failed ?? 0}，跳过 ${res.skipped ?? 0}${
        res.has_more ? "（仍有更多地址，请再次执行）" : ""
      }`,
      { type: res.failed ? "warning" : "success" }
    );
    await refreshAll();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "全部归集失败"), { type: "warning" });
  } finally {
    collectAllLoading.value = false;
  }
}

function resetForm() {
  form.keyword = "";
  pagination.currentPage = 1;
  onSearch();
}

function handleSizeChange(val: number) {
  pagination.pageSize = val;
  pagination.currentPage = 1;
  onSearch();
}

function handleCurrentChange(val: number) {
  pagination.currentPage = val;
  onSearch();
}

onMounted(async () => {
  await loadSummary();
  await onSearch();
});
</script>

<template>
  <div :class="[deviceDetection() ? 'flex flex-col gap-3' : '']">
    <el-card v-loading="summaryLoading" shadow="never" class="mb-3">
      <div class="flex flex-wrap gap-6 text-sm">
        <div>
          <div class="text-gray-500">链上 USDT 总额</div>
          <div class="text-lg font-medium">{{ summary.chain_usdt_total ?? "—" }}</div>
        </div>
        <div>
          <div class="text-gray-500">链上 TRX 总额</div>
          <div class="text-lg font-medium">{{ summary.chain_trx_total ?? "—" }}</div>
        </div>
        <div>
          <div class="text-gray-500">平台 USDT 总额</div>
          <div class="text-lg font-medium">{{ summary.platform_usdt_total ?? "—" }}</div>
        </div>
        <div>
          <div class="text-gray-500">平台 TRX 总额</div>
          <div class="text-lg font-medium">{{ summary.platform_trx_total ?? "—" }}</div>
        </div>
        <div>
          <div class="text-gray-500">热钱包 USDT / TRX</div>
          <div class="text-lg font-medium">
            {{ summary.hot_wallet_usdt ?? "—" }} / {{ summary.hot_wallet_trx ?? "—" }}
          </div>
          <div v-if="summary.hot_wallet_address" class="text-xs text-gray-400 mt-0.5 break-all">
            {{ summary.hot_wallet_address }}
          </div>
        </div>
        <div>
          <div class="text-gray-500">充值地址数</div>
          <div class="text-lg font-medium">{{ summary.wallet_count ?? 0 }}</div>
        </div>
      </div>
      <el-alert
        v-if="summary.collect_ready === false"
        class="mt-3"
        type="warning"
        show-icon
        :closable="false"
        title="归集未就绪：请先在系统配置中设置收款助记词与热钱包私钥"
      />
    </el-card>

    <el-form
      :inline="true"
      :model="form"
      class="search-form bg-bg_color w-full pl-8 pt-3 overflow-auto"
    >
      <el-form-item label="IM号/地址" prop="keyword">
        <el-input v-model="form.keyword" placeholder="IM号 / 昵称 / TRON 地址" clearable class="w-55!" />
      </el-form-item>
      <el-form-item label="链上余额">
        <el-switch v-model="refreshChain" active-text="实时刷新" inactive-text="本地缓存" />
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          :icon="useRenderIcon('ri/search-line')"
          :loading="loading"
          @click="
            pagination.currentPage = 1;
            onSearch();
          "
        >
          查询
        </el-button>
        <el-button :icon="useRenderIcon(Refresh)" @click="resetForm">重置</el-button>
      </el-form-item>
    </el-form>

    <PureTableBar title="链上充值钱包" :columns="columns" @refresh="refreshAll">
      <template #buttons>
        <el-button
          type="warning"
          :loading="collectAllLoading"
          :disabled="!summary.collect_ready"
          @click="handleCollectAll"
        >
          全部归集
        </el-button>
      </template>
      <template v-slot="{ size, dynamicColumns }">
        <pure-table
          ref="tableRef"
          row-key="user_uid"
          adaptive
          :adaptive-config="{ offsetBottom: 108 }"
          align-whole="center"
          table-layout="auto"
          :loading="loading"
          :size="size"
          :data="dataList"
          :columns="dynamicColumns"
          :pagination="{ ...pagination, size }"
          :header-cell-style="{
            background: 'var(--el-fill-color-light)',
            color: 'var(--el-text-color-primary)'
          }"
          @page-size-change="handleSizeChange"
          @page-current-change="handleCurrentChange"
        />
      </template>
    </PureTableBar>
  </div>
</template>
