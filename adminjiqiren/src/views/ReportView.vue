<template>
  <div class="page">
    <header class="top">
      <div class="top-text">
        <h1>机器人玩家报表</h1>
        <p>只读查询 jiqiren 全库当前快照、按日明细、上下分流水与代理团队</p>
      </div>
      <el-button class="logout-btn" @click="onLogout">退出</el-button>
    </header>

    <section class="panel filters">
      <el-form
        class="filter-form"
        :label-position="isMobile ? 'top' : 'right'"
        :inline="!isMobile"
        @submit.prevent="loadAll"
      >
        <el-form-item label="机器码">
          <el-select v-model="machineCode" clearable placeholder="全部" class="filter-control">
            <el-option label="全部" value="" />
            <el-option v-for="m in machines" :key="m" :label="m" :value="m" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input
            v-model="keyword"
            clearable
            placeholder="用户ID / 编号 / 昵称"
            class="filter-control"
          />
        </el-form-item>
        <el-form-item label="日期">
          <el-date-picker
            v-model="dateRange"
            class="filter-control date-picker"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始"
            end-placeholder="结束"
            :disabled-date="disabledDate"
            :unlink-panels="isMobile"
          />
        </el-form-item>
        <el-form-item class="filter-actions">
          <el-button type="primary" :loading="loading" native-type="submit" class="query-btn">
            查询
          </el-button>
        </el-form-item>
      </el-form>
    </section>

    <section class="panel">
      <h2>当前汇总</h2>
      <div class="metrics">
        <div class="metric"><span>总用户数</span><strong>{{ currentSummary.userCount }}</strong></div>
        <div class="metric"><span>总上分</span><strong>{{ fmt(currentSummary.totalUp) }}</strong></div>
        <div class="metric"><span>总下分</span><strong>{{ fmt(currentSummary.totalDown) }}</strong></div>
        <div class="metric"><span>总流水</span><strong>{{ fmt(currentSummary.totalFlow) }}</strong></div>
        <div class="metric"><span>输赢</span><strong>{{ fmt(currentSummary.totalProfitLoss) }}</strong></div>
        <div class="metric"><span>总余额</span><strong>{{ fmt(currentSummary.totalBalance || "0") }}</strong></div>
        <div class="metric"><span>总反水</span><strong>{{ fmt(currentSummary.totalRebate) }}</strong></div>
      </div>
    </section>

    <section class="panel">
      <h2>区间汇总</h2>
      <div class="metrics">
        <div class="metric"><span>区间用户数</span><strong>{{ dailySummary.userCount }}</strong></div>
        <div class="metric"><span>明细行数</span><strong>{{ dailySummary.rowCount || 0 }}</strong></div>
        <div class="metric"><span>总上分</span><strong>{{ fmt(dailySummary.totalUp) }}</strong></div>
        <div class="metric"><span>总下分</span><strong>{{ fmt(dailySummary.totalDown) }}</strong></div>
        <div class="metric"><span>总流水</span><strong>{{ fmt(dailySummary.totalFlow) }}</strong></div>
        <div class="metric"><span>输赢</span><strong>{{ fmt(dailySummary.totalProfitLoss) }}</strong></div>
      </div>
    </section>

    <section class="panel table-panel">
      <el-tabs v-model="tab">
        <el-tab-pane label="当前明细" name="current">
          <div class="table-wrap">
            <el-table
              :data="pagedCurrent"
              stripe
              border
              :height="tableHeight"
              empty-text="暂无数据"
              style="width: 100%"
            >
              <el-table-column prop="machineCode" label="机器码" min-width="120" />
              <el-table-column prop="playerNo" label="用户编号" min-width="90" />
              <el-table-column prop="userId" label="用户ID" min-width="110" />
              <el-table-column label="昵称" min-width="100">
                <template #default="{ row }">{{ nick(row) }}</template>
              </el-table-column>
              <el-table-column prop="totalUp" label="总上分" min-width="90" />
              <el-table-column prop="totalDown" label="总下分" min-width="90" />
              <el-table-column prop="totalFlow" label="总流水" min-width="90" />
              <el-table-column prop="totalProfitLoss" label="输赢" min-width="90" />
              <el-table-column prop="balance" label="余额" min-width="90" />
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="currentPage"
              :page-size="pageSize"
              :layout="pagerLayout"
              :total="currentItems.length"
              :small="isMobile"
              background
            />
          </div>
        </el-tab-pane>
        <el-tab-pane label="按日明细" name="daily">
          <div class="table-wrap">
            <el-table
              :data="pagedDaily"
              stripe
              border
              :height="tableHeight"
              empty-text="暂无数据"
              style="width: 100%"
            >
              <el-table-column prop="businessDate" label="日期" min-width="100" />
              <el-table-column prop="machineCode" label="机器码" min-width="120" />
              <el-table-column prop="playerNo" label="用户编号" min-width="90" />
              <el-table-column prop="userId" label="用户ID" min-width="110" />
              <el-table-column label="昵称" min-width="100">
                <template #default="{ row }">{{ nick(row) }}</template>
              </el-table-column>
              <el-table-column prop="totalUp" label="总上分" min-width="90" />
              <el-table-column prop="totalDown" label="总下分" min-width="90" />
              <el-table-column prop="totalFlow" label="总流水" min-width="90" />
              <el-table-column prop="totalProfitLoss" label="输赢" min-width="90" />
              <el-table-column prop="balance" label="余额" min-width="90" />
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="dailyPage"
              :page-size="pageSize"
              :layout="pagerLayout"
              :total="dailyItems.length"
              :small="isMobile"
              background
            />
          </div>
        </el-tab-pane>
        <el-tab-pane label="上下分流水" name="updown">
          <div class="metrics">
            <div class="metric"><span>笔数</span><strong>{{ updownSummary.rowCount }}</strong></div>
            <div class="metric"><span>区间上分合计</span><strong>{{ fmt(updownSummary.totalUp) }}</strong></div>
            <div class="metric"><span>区间下分合计</span><strong>{{ fmt(updownSummary.totalDown) }}</strong></div>
          </div>
          <div class="table-wrap">
            <el-table
              :data="pagedUpdown"
              stripe
              border
              :height="tableHeight"
              empty-text="暂无数据"
              style="width: 100%"
            >
              <el-table-column label="时间" min-width="160">
                <template #default="{ row }">{{ fmtTs(row.approvedAt) }}</template>
              </el-table-column>
              <el-table-column prop="machineCode" label="机器码" min-width="120" />
              <el-table-column prop="playerNo" label="用户编号" min-width="90" />
              <el-table-column prop="userId" label="用户ID" min-width="110" />
              <el-table-column label="昵称" min-width="100">
                <template #default="{ row }">{{ row.nickname || "-" }}</template>
              </el-table-column>
              <el-table-column label="方向" min-width="80">
                <template #default="{ row }">{{ dirLabel(row.direction) }}</template>
              </el-table-column>
              <el-table-column prop="amount" label="金额" min-width="90" />
              <el-table-column prop="balanceAfter" label="变动后余额" min-width="110" />
              <el-table-column prop="recordId" label="流水ID" min-width="140" />
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="updownPage"
              :page-size="pageSize"
              :layout="pagerLayout"
              :total="updownItems.length"
              :small="isMobile"
              background
            />
          </div>
        </el-tab-pane>
        <el-tab-pane label="代理团队" name="agents">
          <div class="metrics">
            <div class="metric"><span>代理人数</span><strong>{{ agentSummary.agentCount }}</strong></div>
            <div class="metric"><span>一级代理人数</span><strong>{{ agentSummary.level1AgentCount }}</strong></div>
          </div>
          <div class="table-wrap">
            <el-table
              :data="pagedAgents"
              stripe
              border
              :height="tableHeight"
              empty-text="暂无数据"
              style="width: 100%"
            >
              <el-table-column prop="machineCode" label="机器码" min-width="120" />
              <el-table-column prop="playerNo" label="用户编号" min-width="90" />
              <el-table-column prop="userId" label="用户ID" min-width="110" />
              <el-table-column label="昵称" min-width="100">
                <template #default="{ row }">{{ nick(row) }}</template>
              </el-table-column>
              <el-table-column prop="levelNo" label="层级" min-width="70" />
              <el-table-column label="反水比例" min-width="160">
                <template #default="{ row }">{{ fmtRebate(row.rebateRate) }}</template>
              </el-table-column>
              <el-table-column prop="directChildCount" label="直属人数" min-width="90" />
              <el-table-column prop="descendantCount" label="全部下级" min-width="90" />
              <el-table-column prop="balance" label="余额" min-width="90" />
              <el-table-column prop="totalRebate" label="累计反水" min-width="90" />
              <el-table-column label="操作" min-width="100" fixed="right">
                <template #default="{ row }">
                  <el-button type="primary" link @click="openAgentDetail(row)">查看明细</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
          <div class="pager">
            <el-pagination
              v-model:current-page="agentPage"
              :page-size="pageSize"
              :layout="pagerLayout"
              :total="agentItems.length"
              :small="isMobile"
              background
            />
          </div>
        </el-tab-pane>
      </el-tabs>
    </section>

    <el-dialog
      v-model="detailVisible"
      :title="detailDialogTitle"
      :width="isMobile ? '96%' : '90%'"
      destroy-on-close
    >
      <div class="detail-toolbar">
        <el-radio-group v-model="detailScope" :disabled="detailLoading" @change="onDetailScopeChange">
          <el-radio-button value="all">全部下级</el-radio-button>
          <el-radio-button value="direct">仅直属</el-radio-button>
        </el-radio-group>
        <span class="detail-total">共 {{ detailTotal }} 人</span>
      </div>
      <div class="table-wrap">
        <el-table
          v-loading="detailLoading"
          :data="pagedDetail"
          stripe
          border
          :height="tableHeight"
          empty-text="暂无下级"
          style="width: 100%"
        >
          <el-table-column prop="playerNo" label="用户编号" min-width="90" />
          <el-table-column prop="userId" label="用户ID" min-width="110" />
          <el-table-column label="昵称" min-width="100">
            <template #default="{ row }">{{ nick(row) }}</template>
          </el-table-column>
          <el-table-column prop="levelNo" label="层级" min-width="70" />
          <el-table-column label="反水比例" min-width="160">
            <template #default="{ row }">{{ fmtRebate(row.rebateRate) }}</template>
          </el-table-column>
          <el-table-column label="直属上级" min-width="110">
            <template #default="{ row }">
              {{ row.directParentNo || row.directParentUserId || "-" }}
            </template>
          </el-table-column>
          <el-table-column prop="balance" label="余额" min-width="90" />
          <el-table-column prop="totalUp" label="总上分" min-width="90" />
          <el-table-column prop="totalDown" label="总下分" min-width="90" />
          <el-table-column prop="totalFlow" label="总流水" min-width="90" />
          <el-table-column prop="totalProfitLoss" label="输赢" min-width="90" />
          <el-table-column prop="totalRebate" label="累计反水" min-width="90" />
        </el-table>
      </div>
      <div class="pager">
        <el-pagination
          v-model:current-page="detailPage"
          :page-size="pageSize"
          :layout="pagerLayout"
          :total="detailItems.length"
          :small="isMobile"
          background
        />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import {
  fetchAgentDescendants,
  fetchAgents,
  fetchCurrent,
  fetchDaily,
  fetchMachines,
  fetchUpdown,
  logout,
  type AgentDescendantItem,
  type AgentTeamItem,
  type AgentTeamSummary,
  type CurrentItem,
  type DailyItem,
  type MoneySummary,
  type UpdownItem,
  type UpdownSummary
} from "@/api/report";
import { clearToken } from "@/router";

const router = useRouter();
const loading = ref(false);
const machines = ref<string[]>([]);
const machineCode = ref("");
const keyword = ref("");
const tab = ref("current");
const pageSize = 50;
const currentPage = ref(1);
const dailyPage = ref(1);
const updownPage = ref(1);
const agentPage = ref(1);
const detailVisible = ref(false);
const detailLoading = ref(false);
const detailScope = ref<"all" | "direct">("all");
const detailAgent = ref<{
  machineCode: string;
  userId: string;
  playerNo: string;
  nickname: string;
} | null>(null);
const detailItems = ref<AgentDescendantItem[]>([]);
const detailTotal = ref(0);
const detailPage = ref(1);
const viewportWidth = ref(typeof window !== "undefined" ? window.innerWidth : 1200);

const isMobile = computed(() => viewportWidth.value < 768);
const tableHeight = computed(() => (isMobile.value ? 360 : 480));
const pagerLayout = computed(() =>
  isMobile.value ? "total, prev, next" : "total, prev, pager, next"
);

function onResize() {
  viewportWidth.value = window.innerWidth;
}

function defaultRange(): [string, string] {
  const end = new Date();
  const start = new Date(end.getFullYear(), end.getMonth(), 1);
  const maxStart = new Date(end);
  maxStart.setDate(maxStart.getDate() - 92);
  const realStart = start < maxStart ? maxStart : start;
  return [fmtDate(realStart), fmtDate(end)];
}

function fmtDate(d: Date): string {
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  return `${y}-${m}-${day}`;
}

const dateRange = ref<[string, string]>(defaultRange());

const emptySummary = (): MoneySummary => ({
  userCount: 0,
  totalUp: "0.0000",
  totalDown: "0.0000",
  totalFlow: "0.0000",
  totalProfitLoss: "0.0000",
  totalBalance: "0.0000",
  totalRebate: "0.0000",
  rowCount: 0
});

const currentSummary = ref<MoneySummary>(emptySummary());
const dailySummary = ref<MoneySummary>(emptySummary());
const currentItems = ref<CurrentItem[]>([]);
const dailyItems = ref<DailyItem[]>([]);
const emptyUpdownSummary = (): UpdownSummary => ({
  rowCount: 0,
  totalUp: "0.0000",
  totalDown: "0.0000"
});
const updownSummary = ref<UpdownSummary>(emptyUpdownSummary());
const updownItems = ref<UpdownItem[]>([]);
const emptyAgentSummary = (): AgentTeamSummary => ({
  agentCount: 0,
  level1AgentCount: 0
});
const agentSummary = ref<AgentTeamSummary>(emptyAgentSummary());
const agentItems = ref<AgentTeamItem[]>([]);

const pagedCurrent = computed(() => {
  const start = (currentPage.value - 1) * pageSize;
  return currentItems.value.slice(start, start + pageSize);
});

const pagedDaily = computed(() => {
  const start = (dailyPage.value - 1) * pageSize;
  return dailyItems.value.slice(start, start + pageSize);
});

const pagedUpdown = computed(() => {
  const start = (updownPage.value - 1) * pageSize;
  return updownItems.value.slice(start, start + pageSize);
});

const pagedAgents = computed(() => {
  const start = (agentPage.value - 1) * pageSize;
  return agentItems.value.slice(start, start + pageSize);
});

const pagedDetail = computed(() => {
  const start = (detailPage.value - 1) * pageSize;
  return detailItems.value.slice(start, start + pageSize);
});

const detailDialogTitle = computed(() => {
  const agent = detailAgent.value;
  if (!agent) return "团队明细";
  const name = agent.nickname || agent.playerNo || "-";
  return `团队明细 - ${name} (${agent.userId})`;
});

function disabledDate(date: Date) {
  return date.getTime() > Date.now();
}

function fmt(v: string | number | undefined) {
  const n = Number(v ?? 0);
  if (Number.isNaN(n)) return "0";
  return n.toLocaleString("zh-CN", { maximumFractionDigits: 4 });
}

function nick(row: { nickname?: string; displayName?: string }) {
  return row.nickname || row.displayName || "-";
}

function fmtTs(sec: number | undefined) {
  const n = Number(sec ?? 0);
  if (!n) return "-";
  const d = new Date(n * 1000);
  if (Number.isNaN(d.getTime())) return "-";
  const y = d.getFullYear();
  const m = String(d.getMonth() + 1).padStart(2, "0");
  const day = String(d.getDate()).padStart(2, "0");
  const hh = String(d.getHours()).padStart(2, "0");
  const mm = String(d.getMinutes()).padStart(2, "0");
  const ss = String(d.getSeconds()).padStart(2, "0");
  return `${y}-${m}-${day} ${hh}:${mm}:${ss}`;
}

function dirLabel(direction: string | undefined) {
  if (direction === "UP") return "上分";
  if (direction === "DOWN") return "下分";
  return direction || "-";
}

function fmtRebate(raw: string | number | undefined) {
  const n = Number(raw ?? NaN);
  if (Number.isNaN(n)) return "-";
  return `${n.toFixed(4)}（${(n / 100).toFixed(4)}%）`;
}

async function openAgentDetail(row: AgentTeamItem) {
  detailAgent.value = {
    machineCode: row.machineCode,
    userId: row.userId,
    playerNo: row.playerNo,
    nickname: row.nickname || row.displayName || ""
  };
  detailScope.value = "all";
  detailPage.value = 1;
  detailVisible.value = true;
  await loadAgentDetail();
}

async function onDetailScopeChange() {
  detailPage.value = 1;
  await loadAgentDetail();
}

async function loadAgentDetail() {
  const agent = detailAgent.value;
  if (!agent) return;
  detailLoading.value = true;
  try {
    const data = await fetchAgentDescendants({
      machineCode: agent.machineCode,
      agentUserId: agent.userId,
      scope: detailScope.value
    });
    detailItems.value = data.items;
    detailTotal.value = data.total;
  } catch (e) {
    detailItems.value = [];
    detailTotal.value = 0;
    ElMessage.error(e instanceof Error ? e.message : "加载团队明细失败");
  } finally {
    detailLoading.value = false;
  }
}

async function loadAll() {
  if (!dateRange.value || dateRange.value.length !== 2) {
    ElMessage.warning("请选择日期范围");
    return;
  }
  const [startDate, endDate] = dateRange.value;
  loading.value = true;
  try {
    const params = {
      machineCode: machineCode.value || undefined,
      keyword: keyword.value.trim() || undefined
    };
    const [cur, day, updown, agents] = await Promise.all([
      fetchCurrent(params),
      fetchDaily({ startDate, endDate, ...params }),
      fetchUpdown({ startDate, endDate, ...params }),
      fetchAgents(params)
    ]);
    currentSummary.value = cur.summary;
    currentItems.value = cur.items;
    dailySummary.value = day.summary;
    dailyItems.value = day.items;
    updownSummary.value = updown.summary;
    updownItems.value = updown.items;
    agentSummary.value = agents.summary;
    agentItems.value = agents.items;
    currentPage.value = 1;
    dailyPage.value = 1;
    updownPage.value = 1;
    agentPage.value = 1;
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : "查询失败");
  } finally {
    loading.value = false;
  }
}

async function onLogout() {
  try {
    await logout();
  } catch {
    /* ignore */
  }
  clearToken();
  await router.replace("/login");
}

onMounted(async () => {
  window.addEventListener("resize", onResize);
  onResize();
  try {
    machines.value = await fetchMachines();
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : "加载机器码失败");
  }
  await loadAll();
});

onUnmounted(() => {
  window.removeEventListener("resize", onResize);
});
</script>

<style scoped>
.page {
  max-width: 1280px;
  margin: 0 auto;
  padding: 24px 16px calc(48px + env(safe-area-inset-bottom, 0px));
  padding-left: max(16px, env(safe-area-inset-left, 0px));
  padding-right: max(16px, env(safe-area-inset-right, 0px));
}

.top {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  gap: 16px;
  margin-bottom: 16px;
}

.top h1 {
  margin: 0;
  font-size: 28px;
  line-height: 1.25;
}

.top p {
  margin: 6px 0 0;
  color: #64748b;
}

.logout-btn {
  flex-shrink: 0;
}

.panel {
  background: rgba(255, 255, 255, 0.94);
  border: 1px solid #dbe3ee;
  padding: 16px 18px 8px;
  margin-bottom: 14px;
}

.panel h2 {
  margin: 0 0 12px;
  font-size: 16px;
}

.filter-control {
  width: 220px;
}

.date-picker {
  width: 280px;
}

.query-btn {
  min-width: 88px;
}

.metrics {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}

.metric {
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  padding: 10px 12px;
}

.metric span {
  display: block;
  color: #64748b;
  font-size: 12px;
}

.metric strong {
  display: block;
  margin-top: 4px;
  font-size: 18px;
  word-break: break-all;
}

.table-wrap {
  width: 100%;
  overflow-x: auto;
  -webkit-overflow-scrolling: touch;
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding: 12px 0;
  overflow-x: auto;
}

.detail-toolbar {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
  margin-bottom: 12px;
}

.detail-total {
  color: #64748b;
  font-size: 14px;
}

@media (max-width: 767px) {
  .page {
    padding-top: 16px;
    padding-bottom: calc(28px + env(safe-area-inset-bottom, 0px));
  }

  .top {
    flex-wrap: wrap;
    align-items: center;
  }

  .top-text {
    flex: 1 1 auto;
    min-width: 0;
  }

  .top h1 {
    font-size: 20px;
  }

  .top p {
    font-size: 12px;
  }

  .panel {
    padding: 12px 12px 4px;
  }

  .filter-form {
    width: 100%;
  }

  .filter-form :deep(.el-form-item) {
    display: block;
    margin-right: 0;
    width: 100%;
  }

  .filter-form :deep(.el-form-item__content) {
    width: 100%;
  }

  .filter-control,
  .date-picker {
    width: 100% !important;
  }

  .filter-actions :deep(.el-form-item__content) {
    display: block;
  }

  .query-btn {
    width: 100%;
  }

  .metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 8px;
  }

  .metric {
    padding: 8px 10px;
  }

  .metric strong {
    font-size: 15px;
  }

  .pager {
    justify-content: center;
  }

  .table-panel :deep(.el-tabs__header) {
    margin-bottom: 8px;
  }

  .table-panel :deep(.el-tabs__item) {
    padding: 0 12px;
    font-size: 14px;
  }
}
</style>
