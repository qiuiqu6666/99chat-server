<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import {
  getDashboardOverview,
  type DashboardStats,
  type SiteWalletFundItem
} from "@/api/dashboard";
import { getWalletTreasurySummaryApi, type TreasurySummary } from "@/api/wallet-treasury";
import {
  formatWalletCurrencyAmount,
  formatWalletCurrencyLabel
} from "./utils/walletCurrencyPolicy";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";

defineOptions({ name: "ImWalletOverview" });

const router = useRouter();
const loading = ref(false);
const stats = ref<Partial<DashboardStats>>({});
const siteWalletFunds = ref<SiteWalletFundItem[]>([]);
const treasury = ref<TreasurySummary | null>(null);

const walletFundRows = computed(() =>
  siteWalletFunds.value.map(row => ({
    ...row,
    currencyLabel: row.label || formatWalletCurrencyLabel(row.currency),
    availableText: formatWalletCurrencyAmount(row.currency, row.available),
    frozenText: formatWalletCurrencyAmount(row.currency, row.frozen),
    totalText: formatWalletCurrencyAmount(row.currency, row.total)
  }))
);

const financeCards = computed(() => [
  {
    label: "今日充值",
    value: stats.value.recharge_amount_today ?? "0.00",
    link: "/im-admin/recharges/list"
  },
  {
    label: "今日提现",
    value: stats.value.withdraw_amount_today ?? "0.00",
    link: "/im-admin/withdraws/list"
  },
  {
    label: "待审核提现",
    value: stats.value.pending_withdraw_count ?? 0,
    link: "/im-admin/withdraws/list",
    highlight: Number(stats.value.pending_withdraw_count ?? 0) > 0
  },
  {
    label: "今日红包",
    value: stats.value.red_packet_amount_today ?? "0.00",
    link: "/im-admin/red-packets/list"
  },
  {
    label: "今日转账",
    value: stats.value.transfer_amount_today ?? "0.00",
    link: "/im-admin/transfers/list"
  }
]);

const quickLinks = [
  { label: "账变记录", path: "/im-admin/wallet/ledger" },
  { label: "充值记录", path: "/im-admin/recharges/list" },
  { label: "提现审核", path: "/im-admin/withdraws/list" },
  { label: "链上钱包", path: "/im-admin/wallet/treasury" },
  { label: "币种管理", path: "/im-admin/wallet/currencies" }
];

function go(path: string) {
  router.push(path);
}

async function load() {
  loading.value = true;
  try {
    const [overview, treasuryRes] = await Promise.all([
      getDashboardOverview(),
      getWalletTreasurySummaryApi().catch(() => null)
    ]);
    stats.value = overview.stats || {};
    siteWalletFunds.value = overview.site_wallet_funds || [];
    treasury.value = treasuryRes;
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "资金概览加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="p-4 space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 class="text-lg font-medium m-0">资金概览</h2>
        <p class="mt-1 text-sm text-gray-500 dark:text-gray-400 mb-0">
          全站用户钱包汇总与今日资金流水快捷入口
        </p>
      </div>
      <el-button type="primary" plain :loading="loading" @click="load">刷新</el-button>
    </div>

    <el-card shadow="never" v-loading="loading">
      <template #header>
        <span class="font-medium">全站钱包余额</span>
      </template>
      <el-table :data="walletFundRows" border stripe empty-text="暂无资金数据">
        <el-table-column prop="currencyLabel" label="币种" width="140" />
        <el-table-column prop="availableText" label="可用余额" min-width="160" />
        <el-table-column prop="frozenText" label="冻结余额" min-width="160" />
        <el-table-column prop="totalText" label="合计" min-width="160">
          <template #default="{ row }">
            <span class="font-semibold">{{ row.totalText }}</span>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-row :gutter="16" v-loading="loading">
      <el-col v-for="item in financeCards" :key="item.label" :xs="24" :sm="12" :md="8" :lg="6">
        <el-card
          shadow="hover"
          class="mb-4 cursor-pointer transition-opacity hover:opacity-90"
          :class="item.highlight ? 'border-[var(--el-color-warning)]' : ''"
          @click="go(item.link)"
        >
          <div class="text-sm text-gray-500 dark:text-gray-400">{{ item.label }}</div>
          <div
            class="mt-2 text-2xl font-semibold"
            :class="item.highlight ? 'text-[var(--el-color-warning)]' : ''"
          >
            {{ item.value }}
          </div>
          <div class="mt-2 text-xs text-[var(--el-color-primary)]">点击查看明细 →</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card v-if="treasury" shadow="never" v-loading="loading">
      <template #header>
        <div class="flex items-center justify-between">
          <span class="font-medium">链上热钱包摘要</span>
          <el-button link type="primary" @click="go('/im-admin/wallet/treasury')">
            进入链上钱包
          </el-button>
        </div>
      </template>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="平台 USDT 合计">
          {{ treasury.platform_usdt_total ?? "—" }}
        </el-descriptions-item>
        <el-descriptions-item label="链上 USDT 合计">
          {{ treasury.chain_usdt_total ?? "—" }}
        </el-descriptions-item>
        <el-descriptions-item label="托管地址数">
          {{ treasury.wallet_count ?? "—" }}
        </el-descriptions-item>
        <el-descriptions-item label="热钱包地址" :span="2">
          {{ treasury.hot_wallet_address ?? "—" }}
        </el-descriptions-item>
        <el-descriptions-item label="可批量归集">
          {{ treasury.collect_ready ? "是" : "否" }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <span class="font-medium">快捷导航</span>
      </template>
      <div class="flex flex-wrap gap-2">
        <el-button
          v-for="link in quickLinks"
          :key="link.path"
          @click="go(link.path)"
        >
          {{ link.label }}
        </el-button>
      </div>
    </el-card>
  </div>
</template>
