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
} from "@/views/im-admin/account/utils/walletCurrencyPolicy";
import { IM_ADMIN_NAV_SECTIONS } from "./navSections";

defineOptions({ name: "ImAnalyticsOverview" });

const router = useRouter();
const loading = ref(false);
const loadError = ref("");
const stats = ref<Partial<DashboardStats>>({});
const siteWalletFunds = ref<SiteWalletFundItem[]>([]);
const treasury = ref<TreasurySummary | null>(null);

type StatCard = {
  label: string;
  value: string | number;
  link?: string;
  highlight?: boolean;
};

const statGroups = computed(() => {
  const s = stats.value;
  const groups: { title: string; items: StatCard[] }[] = [
    {
      title: "用户与活跃",
      items: [
        { label: "用户总数", value: s.user_total ?? 0, link: "/im-admin/users/list" },
        { label: "今日新增", value: s.registered_today ?? 0, link: "/im-admin/users/list" },
        {
          label: "今日活跃",
          value: s.active_today ?? s.login_today ?? 0,
          link: "/im-admin/devices/login-records"
        },
        { label: "当前在线", value: s.online_users ?? 0 }
      ]
    },
    {
      title: "群组与消息",
      items: [
        { label: "群组总数", value: s.group_total ?? 0, link: "/im-admin/groups/list" },
        { label: "今日新群", value: s.group_created_today ?? 0, link: "/im-admin/groups/list" },
        { label: "今日消息", value: s.message_today ?? 0, link: "/im-admin/messages/query" },
        { label: "单聊 / 群聊", value: `${s.c2c_message_today ?? 0} / ${s.group_message_today ?? 0}` }
      ]
    },
    {
      title: "资金流水（今日）",
      items: [
        { label: "充值金额", value: s.recharge_amount_today ?? "0.00", link: "/im-admin/recharges/list" },
        { label: "提现金额", value: s.withdraw_amount_today ?? "0.00", link: "/im-admin/withdraws/list" },
        {
          label: "待审核提现",
          value: s.pending_withdraw_count ?? 0,
          link: "/im-admin/withdraws/list",
          highlight: Number(s.pending_withdraw_count ?? 0) > 0
        },
        {
          label: "红包",
          value: s.red_packet_amount_today ?? "0.00",
          link: "/im-admin/red-packets/list"
        },
        {
          label: "转账",
          value: s.transfer_amount_today ?? "0.00",
          link: "/im-admin/transfers/list"
        }
      ]
    }
  ];
  return groups;
});

const walletFundRows = computed(() =>
  siteWalletFunds.value.map(row => ({
    ...row,
    currencyLabel: row.label || formatWalletCurrencyLabel(row.currency),
    availableText: formatWalletCurrencyAmount(row.currency, row.available),
    frozenText: formatWalletCurrencyAmount(row.currency, row.frozen),
    totalText: formatWalletCurrencyAmount(row.currency, row.total)
  }))
);

const navSections = IM_ADMIN_NAV_SECTIONS;

function openLink(path?: string) {
  if (path) router.push(path);
}

async function loadOverview() {
  loading.value = true;
  loadError.value = "";
  try {
    const [overview, treasuryRes] = await Promise.all([
      getDashboardOverview(),
      getWalletTreasurySummaryApi().catch(() => null)
    ]);
    stats.value = overview.stats || {};
    siteWalletFunds.value = overview.site_wallet_funds || [];
    treasury.value = treasuryRes;
  } catch (error: unknown) {
    const ax = error as {
      response?: { status?: number; data?: { error?: string; message?: string } };
    };
    const status = ax?.response?.status;
    const errCode = ax?.response?.data?.error;
    if (status === 403 || errCode === "forbidden") {
      loadError.value = "暂无看板权限，请联系管理员开通 dashboard.view。";
    } else if (status === 401 || errCode === "unauthorized") {
      loadError.value = "登录已失效，请重新登录。";
    } else {
      loadError.value = "看板数据加载失败，请稍后重试或联系运维。";
    }
  } finally {
    loading.value = false;
  }
}

onMounted(loadOverview);
</script>

<template>
  <div class="p-4 space-y-4">
    <div class="flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 class="text-lg font-medium m-0">工作台</h2>
        <p class="mt-1 mb-0 text-sm text-gray-500 dark:text-gray-400">
          关键指标一览；下方「功能导航」可直达全部 {{ navSections.reduce((n, s) => n + s.items.length, 0) }} 个功能页
        </p>
      </div>
      <el-button type="primary" plain :loading="loading" @click="loadOverview">刷新</el-button>
    </div>

    <el-alert v-if="loadError" :closable="false" type="warning" :title="loadError" />

    <el-card shadow="never" v-loading="loading">
      <template #header>
        <span class="text-base font-medium">全站钱包余额</span>
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

    <el-card v-if="treasury" shadow="never" v-loading="loading">
      <template #header>
        <div class="flex flex-wrap items-center justify-between gap-2">
          <span class="font-medium">链上热钱包</span>
          <el-button link type="primary" @click="openLink('/im-admin/wallet/treasury')">
            进入链上钱包 →
          </el-button>
        </div>
      </template>
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="平台 USDT">{{ treasury.platform_usdt_total ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="链上 USDT">{{ treasury.chain_usdt_total ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="托管地址数">{{ treasury.wallet_count ?? "—" }}</el-descriptions-item>
      </el-descriptions>
    </el-card>

    <div v-for="group in statGroups" :key="group.title" v-loading="loading">
      <div class="mb-3 text-sm font-medium text-gray-600 dark:text-gray-300">{{ group.title }}</div>
      <el-row :gutter="16">
        <el-col
          v-for="item in group.items"
          :key="item.label"
          :xs="24"
          :sm="12"
          :md="8"
          :lg="6"
          :xl="4"
        >
          <el-card
            shadow="hover"
            class="mb-4"
            :class="[
              item.link ? 'cursor-pointer hover:opacity-90' : '',
              item.highlight ? 'border-[var(--el-color-warning)]' : ''
            ]"
            @click="openLink(item.link)"
          >
            <div class="text-sm text-gray-500 dark:text-gray-400">{{ item.label }}</div>
            <div
              class="mt-2 text-2xl font-semibold"
              :class="item.highlight ? 'text-[var(--el-color-warning)]' : ''"
            >
              {{ item.value }}
            </div>
            <div v-if="item.link" class="mt-1 text-xs text-[var(--el-color-primary)]">查看 →</div>
          </el-card>
        </el-col>
      </el-row>
    </div>

    <el-card shadow="never">
      <template #header>
        <span class="font-medium">功能导航</span>
      </template>
      <div v-for="section in navSections" :key="section.title" class="mb-5 last:mb-0">
        <div class="mb-2 text-sm font-medium text-gray-600 dark:text-gray-300">{{ section.title }}</div>
        <div class="flex flex-wrap gap-2">
          <el-button
            v-for="item in section.items"
            :key="item.path"
            @click="openLink(item.path)"
          >
            {{ item.label }}
          </el-button>
        </div>
      </div>
    </el-card>
  </div>
</template>
