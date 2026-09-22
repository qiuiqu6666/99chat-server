<script setup lang="ts">
import { computed, markRaw, onMounted, ref } from "vue";
import dayjs from "dayjs";
import ReCol from "@/components/ReCol";
import { useDark, randomGradient } from "./utils";
import WelcomeTable from "./components/table/index.vue";
import { ReNormalCountTo } from "@/components/ReCountTo";
import { useRenderFlicker } from "@/components/ReFlicker";
import { ChartLine } from "./components/charts";
import {
  kpiCardVisualTemplates,
  KPI_STAT_KEYS,
  KPI_MONEY_KEYS,
  defaultKpiLabels,
  latestNewsData
} from "./data";
import {
  getDashboardOverview,
  getDashboardDailyMetrics,
  type DashboardOverview,
  type DashboardStats,
  type DashboardDailyMetricRow
} from "@/api/dashboard";
import { message } from "@/utils/message";

defineOptions({
  name: "Welcome"
});

const days = ["周日", "周一", "周二", "周三", "周四", "周五", "周六"];

const overviewLoading = ref(false);
const overview = ref<DashboardOverview | null>(null);

const dailyRows = ref<
  Array<{
    id: number;
    date: string;
    newRegistrations: number;
    avgOnline: number | null;
  }>
>([]);
const dailyLoading = ref(false);
/** 已通过 `GET /api/v1/dashboard/daily-metrics` 拉得多日明细 */
const dailyFromApi = ref(false);

function emptyStats(): DashboardStats {
  return {
    user_total: 0,
    registered_today: 0,
    login_today: 0,
    group_total: 0
  };
}

function formatWalletStr(s?: string | null) {
  if (s == null || s === "") return "—";
  const n = Number(s);
  if (!Number.isFinite(n))
    return `¥${String(s)}`;
  return `¥${n.toLocaleString("zh-CN", {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2
  })}`;
}

type KpiCardTemplate = (typeof kpiCardVisualTemplates)[number];

/** KPI 单行：money / 计数 / online 可选缺失 */
type KpiVm = KpiCardTemplate & {
  key: (typeof KPI_STAT_KEYS)[number];
  name: string;
  /** count | money | onlineOptional */
  mode: "count" | "money" | "onlineOptional";
  value: number;
  walletStr?: string;
  optionalMissing?: boolean;
  percent?: string;
  data?: number[];
};

const chartData = computed<KpiVm[]>(() => {
  const stats = overview.value?.stats ?? emptyStats();
  const lblMerged = overview.value?.labels
    ? { ...defaultKpiLabels, ...(overview.value.labels as Record<string, string>) }
    : defaultKpiLabels;

  return KPI_STAT_KEYS.map((key, index) => {
    const tmpl = kpiCardVisualTemplates[index];
    const name =
      lblMerged[key as keyof typeof defaultKpiLabels] ?? defaultKpiLabels[key];

    if (KPI_MONEY_KEYS.has(key)) {
      const raw =
        stats[key as "wallet_balance_total" | "wallet_frozen_total"] ?? "";
      return {
        ...tmpl,
        key,
        name,
        mode: "money",
        value: 0,
        walletStr: formatWalletStr(String(raw ?? "")),
        percent: "",
        data: []
      };
    }

    if (key === "online_users") {
      const has =
        !!overview.value &&
        typeof stats === "object" &&
        stats != null &&
        "online_users" in stats &&
        stats.online_users != null;
      return {
        ...tmpl,
        key,
        name,
        mode: "onlineOptional",
        value: has ? Number(stats.online_users) || 0 : 0,
        optionalMissing: !has,
        percent: "",
        data: []
      };
    }

    const n = Number(stats[key as keyof DashboardStats]);
    return {
      ...tmpl,
      key,
      name,
      mode: "count",
      value: Number.isFinite(n) ? n : 0,
      percent: "",
      data: []
    };
  });
});

function normalizeDailyRows(
  raw: DashboardDailyMetricRow[]
): Array<{
  id: number;
  date: string;
  newRegistrations: number;
  avgOnline: number | null;
}> {
  return raw
    .map((r, i) => {
      const ds = String(r.date ?? r.stat_date ?? "").slice(0, 10);
      const reg = Number(r.registered_count ?? r.new_registrations ?? 0);
      /** 后端可能下发 `login_distinct_users`（当日登录去重用户数） */
      const avgRaw =
        r.login_distinct_users ??
        r.avg_online_users ??
        r.login_users ??
        r.login_users_count ??
        null;

      let avgOnline: number | null = null;
      if (avgRaw != null && Number.isFinite(Number(avgRaw))) {
        avgOnline = Number(avgRaw);
      }

      return {
        id: i + 1,
        date: ds,
        newRegistrations: Number.isFinite(reg) ? Math.max(0, Math.floor(reg)) : 0,
        avgOnline
      };
    })
    .filter(r => r.date !== "");
}

function buildTodayOnlyRows(stats: DashboardStats | null | undefined) {
  if (!stats) return [];
  const d = dayjs().format("YYYY-MM-DD");
  return [
    {
      id: 1,
      date: d,
      newRegistrations: stats.registered_today ?? 0,
      avgOnline:
        typeof stats.login_today === "number" && Number.isFinite(stats.login_today)
          ? stats.login_today
          : null
    }
  ];
}

async function fetchDailySeries() {
  dailyLoading.value = true;
  try {
    const res = await getDashboardDailyMetrics({ days: 30 });
    const pack = [...(res.items ?? []), ...(res.rows ?? [])];
    const norm = normalizeDailyRows(pack).sort((a, b) =>
      a.date < b.date ? 1 : a.date > b.date ? -1 : 0
    );
    if (!norm.length) throw new Error("empty daily");
    dailyRows.value = norm.map((row, ix) => ({ ...row, id: ix + 1 }));
    dailyFromApi.value = true;
  } catch {
    dailyFromApi.value = false;
    dailyRows.value = buildTodayOnlyRows(overview.value?.stats ?? null);
  } finally {
    dailyLoading.value = false;
  }
}

const timelineForRender = computed(() => {
  if (dailyRows.value.length === 0) {
    return latestNewsData.map(item => ({
      dateLabel: `${item.date as string} ${days[dayjs(item.date as string).day()]}`,
      newRegistrations: item.newRegistrations,
      avgOnline: item.avgOnline,
      demo: true
    }));
  }
  return dailyRows.value.slice(0, 14).map(row => ({
    dateLabel: dayjs(row.date).isValid()
      ? `${row.date} ${days[dayjs(row.date).day()]}`
      : row.date,
    newRegistrations: row.newRegistrations,
    avgOnline: row.avgOnline,
    demo: false
  }));
});

onMounted(async () => {
  overviewLoading.value = true;
  try {
    overview.value = await getDashboardOverview();
  } catch (err: unknown) {
    overview.value = null;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    const status = ax?.response?.status;
    const errCode = ax?.response?.data?.error;
    if (status === 403 || errCode === "forbidden") {
      message("暂无仪表盘查看权限（需要 dashboard.view）", { type: "warning" });
    } else if (status !== 401) {
      message("仪表盘汇总指标加载失败，请稍后重试", { type: "warning" });
    }
  } finally {
    overviewLoading.value = false;
  }

  await fetchDailySeries();
});

function fmtNumOrDash(v: number | string | null | undefined) {
  if (v == null || v === "" || Number.isNaN(Number(v))) return "—";
  return Number(v).toLocaleString("zh-CN");
}

const { isDark } = useDark();
</script>

<template>
  <div v-loading="overviewLoading">
    <el-row :gutter="24" justify="space-around">
      <re-col
        v-for="(item, index) in chartData"
        :key="item.key"
        v-motion
        class="mb-4.5"
        :value="6"
        :md="12"
        :sm="12"
        :xs="24"
        :initial="{
          opacity: 0,
          y: 100
        }"
        :enter="{
          opacity: 1,
          y: 0,
          transition: {
            delay: 80 * (index + 1)
          }
        }"
      >
        <el-card class="line-card" shadow="never">
          <div class="flex justify-between">
            <span class="text-md font-medium">
              {{ item.name }}
            </span>
            <div
              class="size-8 flex-c rounded-md"
              :style="{
                backgroundColor: isDark ? 'transparent' : item.bgColor
              }"
            >
              <IconifyIconOffline
                :icon="item.icon"
                :color="item.color"
                width="18"
                height="18"
              />
            </div>
          </div>
          <div class="flex justify-between items-start mt-3">
            <div :class="item.data.length > 1 ? 'w-1/2' : 'w-full min-w-0'">
              <template v-if="item.mode === 'money'">
                <p class="text-[1.6em] leading-tight font-semibold tabular-nums">
                  {{ item.walletStr ?? "—" }}
                </p>
              </template>
              <template v-else-if="item.mode === 'onlineOptional' && item.optionalMissing">
                <p class="text-[1.6em] leading-tight font-medium text-[var(--el-text-color-secondary)]">
                  —
                </p>
                <p class="mt-1 text-xs text-[var(--el-text-color-secondary)]">
                  服务端未下发 online_users 时不可用
                </p>
              </template>
              <template v-else>
                <ReNormalCountTo
                  :key="`${index}-${item.key}-${item.value}`"
                  :duration="item.duration"
                  :fontSize="'1.6em'"
                  :startVal="0"
                  :endVal="item.value"
                />
              </template>
              <p
                v-if="item.percent"
                class="font-medium text-green-500"
              >
                {{ item.percent }}
              </p>
            </div>
            <ChartLine
              v-if="item.data.length > 1"
              class="w-1/2!"
              :color="item.color"
              :data="item.data"
            />
          </div>
        </el-card>
      </re-col>

      <re-col
        v-motion
        class="mb-4.5"
        :value="18"
        :xs="24"
        :initial="{
          opacity: 0,
          y: 100
        }"
        :enter="{
          opacity: 1,
          y: 0,
          transition: {
            delay: 560
          }
        }"
      >
        <el-card shadow="never">
          <div class="flex flex-wrap items-center gap-2 justify-between">
            <span class="text-md font-medium">
              每日注册与在线<span class="text-secondary text-xs font-normal ml-2">对接：GET daily-metrics · 兜底：今日汇总</span>
            </span>
            <el-text v-if="dailyFromApi" type="success" size="small">已加载多日明细</el-text>
            <el-text
              v-else-if="dailyRows.length > 0"
              type="warning"
              size="small"
              class="max-w-md text-right!"
            >
              未检测到 daily-metrics（或为空），以下为今日一行汇总：新注册=
              registered_today；当日登录（去重）= 概览 login_today。
            </el-text>
          </div>
          <el-scrollbar max-height="504" class="mt-3">
            <WelcomeTable :data-list="dailyRows as any[]" :loading="dailyLoading" />
          </el-scrollbar>
        </el-card>
      </re-col>

      <re-col
        v-motion
        class="mb-4.5"
        :value="6"
        :xs="24"
        :initial="{
          opacity: 0,
          y: 100
        }"
        :enter="{
          opacity: 1,
          y: 0,
          transition: {
            delay: 640
          }
        }"
      >
        <el-card shadow="never">
          <div class="flex justify-between flex-wrap gap-1">
            <span class="text-md font-medium">最新动态</span>
            <el-text type="info" size="small">与上方「每日注册与在线」表同源（无数据时为演示）</el-text>
          </div>
          <el-scrollbar max-height="504" class="mt-3">
            <el-timeline>
              <el-timeline-item
                v-for="(item, index) in timelineForRender"
                :key="index"
                center
                placement="top"
                :icon="
                  markRaw(
                    useRenderFlicker({
                      background: randomGradient({
                        randomizeHue: true
                      })
                    })
                  )
                "
                :timestamp="item.dateLabel"
              >
                <p class="text-text_color_regular text-sm leading-relaxed">
                  新注册
                  {{ typeof item.newRegistrations === 'number' ? item.newRegistrations.toLocaleString('zh-CN') : item.newRegistrations }}
                  人 · 当日登录（去重）
                  {{ fmtNumOrDash(item.avgOnline) }}
                  <template v-if="item.demo"><span class="text-secondary"> （演示）</span></template>
                </p>
              </el-timeline-item>
            </el-timeline>
          </el-scrollbar>
        </el-card>
      </re-col>
    </el-row>
  </div>
</template>

<style lang="scss" scoped>
:deep(.el-card) {
  --el-card-border-color: none;

  .el-scrollbar__bar {
    display: none;
  }

  .el-timeline-item {
    margin: 0 6px;
  }
}

:deep(.el-timeline.is-start) {
  padding-left: 0;
}

.main-content {
  margin: 20px 20px 0 !important;
}
</style>
