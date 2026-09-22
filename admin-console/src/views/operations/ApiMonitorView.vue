<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { opsApiMetrics, opsApiMetricsRequests, opsApiMetricsReset } from "@/api/adminOps";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

type PathRow = Record<string, unknown>;
type SampleRow = Record<string, unknown>;
type ViewMode = "summary" | "detail";

const canManage = computed(() => hasPerm("admin.manage"));
const view = ref<ViewMode>("summary");
const loading = ref(false);
const loadingRequests = ref(false);
const autoRefresh = ref(true);
const summary = reactive({
  started_at: 0,
  collected_at: 0,
  slow_threshold_ms: 800,
  total_requests: 0,
  total_errors: 0,
  path_count: 0,
  buffer_size: 0,
  buffer_capacity: 10000
});
const paths = ref<PathRow[]>([]);
const items = ref<SampleRow[]>([]);
const recentSlow = ref<SampleRow[]>([]);
const recentErrors = ref<SampleRow[]>([]);
const loadingSide = ref(false);
const total = ref(0);
const timeRange = ref<[Date, Date] | null>(null);
const detailLabel = ref("");

/** summary 页本地过滤（方法精确、路径子串） */
const summaryFilter = reactive({
  method: "",
  path: ""
});

const query = reactive({
  page: 1,
  page_size: 50,
  method: "",
  path: "",
  status: undefined as number | undefined,
  status_gte: undefined as number | undefined,
  ip: "",
  device: "",
  keyword: "",
  slow_only: false,
  slow_ms: 800
});

let timer: ReturnType<typeof setInterval> | null = null;

function num(v: unknown) {
  const n = Number(v);
  return Number.isFinite(n) ? n : 0;
}

function statusType(status: unknown): "success" | "warning" | "danger" | "info" {
  const s = num(status);
  if (s >= 500) return "danger";
  if (s >= 400) return "warning";
  if (s >= 200 && s < 300) return "success";
  return "info";
}

function unwrapData(raw: unknown): Record<string, unknown> {
  if (!raw || typeof raw !== "object") return {};
  const obj = raw as Record<string, unknown>;
  if (obj.data && typeof obj.data === "object" && !Array.isArray(obj.data)) {
    return obj.data as Record<string, unknown>;
  }
  return obj;
}

const filteredPaths = computed(() => {
  const method = summaryFilter.method.trim().toUpperCase();
  const pathQ = summaryFilter.path.trim().toLowerCase();
  return paths.value.filter(row => {
    if (method && String(row.method || "").toUpperCase() !== method) return false;
    if (pathQ && !String(row.path || "").toLowerCase().includes(pathQ)) return false;
    return true;
  });
});

function buildRequestParams() {
  const params: Record<string, unknown> = {
    page: query.page,
    page_size: query.page_size,
    slow_ms: query.slow_ms,
    slow_only: query.slow_only
  };
  if (query.method) params.method = query.method;
  if (query.path.trim()) params.path = query.path.trim();
  if (query.status != null && Number.isFinite(query.status)) params.status = query.status;
  else if (query.status_gte != null) params.status_gte = query.status_gte;
  if (query.ip.trim()) params.ip = query.ip.trim();
  if (query.device.trim()) params.device = query.device.trim();
  if (query.keyword.trim()) params.keyword = query.keyword.trim();
  if (timeRange.value && timeRange.value.length === 2) {
    params.from_ms = timeRange.value[0].getTime();
    params.to_ms = timeRange.value[1].getTime();
  }
  return params;
}

async function loadSummary() {
  if (!canManage.value) return;
  loading.value = true;
  try {
    const data = unwrapData(
      await opsApiMetrics({
        path_limit: 100,
        slow_ms: query.slow_ms
      })
    );
    summary.started_at = num(data.started_at);
    summary.collected_at = num(data.collected_at);
    summary.slow_threshold_ms = num(data.slow_threshold_ms) || query.slow_ms;
    summary.total_requests = num(data.total_requests);
    summary.total_errors = num(data.total_errors);
    summary.path_count = num(data.path_count);
    summary.buffer_size = num(data.buffer_size);
    summary.buffer_capacity = num(data.buffer_capacity) || 10000;
    paths.value = Array.isArray(data.paths) ? (data.paths as PathRow[]) : [];
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function loadRequests() {
  if (!canManage.value || view.value !== "detail") return;
  loadingRequests.value = true;
  try {
    const data = unwrapData(await opsApiMetricsRequests(buildRequestParams()));
    items.value = Array.isArray(data.items) ? (data.items as SampleRow[]) : [];
    total.value = num(data.total);
    summary.buffer_size = num(data.buffer_size) || summary.buffer_size;
    summary.buffer_capacity = num(data.buffer_capacity) || summary.buffer_capacity;
    if (data.slow_threshold_ms != null) {
      summary.slow_threshold_ms = num(data.slow_threshold_ms) || summary.slow_threshold_ms;
    }
  } catch (e) {
    items.value = [];
    total.value = 0;
    ElMessage.warning(errMessage(e));
  } finally {
    loadingRequests.value = false;
  }
}

async function loadRecentSlow() {
  if (!canManage.value) return;
  const data = unwrapData(
    await opsApiMetricsRequests({
      page: 1,
      page_size: 50,
      slow_ms: query.slow_ms,
      slow_only: true
    })
  );
  recentSlow.value = Array.isArray(data.items) ? (data.items as SampleRow[]) : [];
  if (data.buffer_size != null) summary.buffer_size = num(data.buffer_size) || summary.buffer_size;
}

async function loadRecentErrors() {
  if (!canManage.value) return;
  const data = unwrapData(
    await opsApiMetricsRequests({
      page: 1,
      page_size: 50,
      slow_ms: query.slow_ms,
      status_gte: 400
    })
  );
  recentErrors.value = Array.isArray(data.items) ? (data.items as SampleRow[]) : [];
  if (data.buffer_size != null) summary.buffer_size = num(data.buffer_size) || summary.buffer_size;
}

async function loadSidePanels() {
  if (!canManage.value || view.value !== "summary") return;
  loadingSide.value = true;
  try {
    await Promise.all([loadRecentSlow(), loadRecentErrors()]);
  } catch (e) {
    recentSlow.value = [];
    recentErrors.value = [];
    ElMessage.warning(errMessage(e));
  } finally {
    loadingSide.value = false;
  }
}

async function load() {
  if (view.value === "detail") {
    await Promise.all([loadSummary(), loadRequests()]);
  } else {
    await Promise.all([loadSummary(), loadSidePanels()]);
  }
}

function search() {
  query.page = 1;
  load();
}

function openDetail(row: PathRow | SampleRow) {
  const method = String(row.method || "").toUpperCase();
  const path = String(row.path || "");
  query.method = method;
  query.path = path;
  query.page = 1;
  query.status = undefined;
  query.status_gte = undefined;
  query.ip = "";
  query.device = "";
  query.keyword = "";
  query.slow_only = false;
  timeRange.value = null;
  detailLabel.value = `${method} ${path}`.trim();
  view.value = "detail";
  loadRequests();
}

function backToSummary() {
  query.method = "";
  query.path = "";
  query.page = 1;
  detailLabel.value = "";
  items.value = [];
  total.value = 0;
  view.value = "summary";
  load();
}

function presetErrorsOnly() {
  query.status = undefined;
  query.status_gte = 400;
  query.slow_only = false;
  search();
}

function presetSlowOnly() {
  query.slow_only = true;
  query.status_gte = undefined;
  search();
}

function clearFilters() {
  query.status = undefined;
  query.status_gte = undefined;
  query.ip = "";
  query.device = "";
  query.keyword = "";
  query.slow_only = false;
  timeRange.value = null;
  // 保留因点击写入的 method/path（仍在该接口详情内）
  search();
}

async function resetMetrics() {
  if (!canManage.value) {
    ElMessage.warning("需要 admin.manage 权限");
    return;
  }
  try {
    await ElMessageBox.confirm("确认清空当前进程内的接口监控数据？", "清空监控", {
      type: "warning"
    });
  } catch {
    return;
  }
  try {
    await opsApiMetricsReset();
    ElMessage.success("已清空");
    await load();
  } catch (e) {
    ElMessage.warning(errMessage(e));
  }
}

function startTimer() {
  stopTimer();
  timer = setInterval(() => {
    if (autoRefresh.value) load();
  }, 8000);
}

function stopTimer() {
  if (timer) {
    clearInterval(timer);
    timer = null;
  }
}

onMounted(() => {
  load();
  startTimer();
});
onUnmounted(stopTimer);
</script>

<template>
  <div class="page">
    <PageHeader
      title="接口监控"
      subtitle="默认接口汇总，点击进入明细；明细仅含进程内窗口（最多 10000）；重启清空；需 admin.manage"
    />

    <div v-if="!canManage" class="page-card">
      <el-alert type="warning" show-icon :closable="false" title="当前账号无 admin.manage 权限，无法查看接口监控" />
    </div>

    <template v-else>
      <div class="page-card mb">
        <div class="toolbar filters">
          <template v-if="view === 'summary'">
            <el-select v-model="summaryFilter.method" clearable placeholder="方法" style="width: 110px">
              <el-option label="全部" value="" />
              <el-option label="GET" value="GET" />
              <el-option label="POST" value="POST" />
              <el-option label="PUT" value="PUT" />
              <el-option label="PATCH" value="PATCH" />
              <el-option label="DELETE" value="DELETE" />
            </el-select>
            <el-input
              v-model="summaryFilter.path"
              clearable
              placeholder="路径"
              style="width: 220px"
            />
          </template>

          <template v-else>
            <el-button @click="backToSummary">← 返回汇总</el-button>
            <el-tag type="info" effect="plain" class="detail-tag">{{ detailLabel || "—" }}</el-tag>
            <el-select v-model="query.method" clearable placeholder="方法" style="width: 110px">
              <el-option label="全部" value="" />
              <el-option label="GET" value="GET" />
              <el-option label="POST" value="POST" />
              <el-option label="PUT" value="PUT" />
              <el-option label="PATCH" value="PATCH" />
              <el-option label="DELETE" value="DELETE" />
            </el-select>
            <el-input v-model="query.path" clearable placeholder="路径" style="width: 180px" @keyup.enter="search" />
            <el-input-number
              v-model="query.status"
              :controls="false"
              :min="100"
              :max="599"
              placeholder="状态码"
              style="width: 100px"
              @change="query.status_gte = undefined"
            />
            <el-input v-model="query.ip" clearable placeholder="IP" style="width: 140px" @keyup.enter="search" />
            <el-input
              v-model="query.device"
              clearable
              placeholder="设备(UA/平台/型号)"
              style="width: 180px"
              @keyup.enter="search"
            />
            <el-input
              v-model="query.keyword"
              clearable
              placeholder="关键词(路径/错误)"
              style="width: 160px"
              @keyup.enter="search"
            />
            <el-switch v-model="query.slow_only" active-text="仅慢请求" />
            <el-date-picker
              v-model="timeRange"
              type="datetimerange"
              range-separator="至"
              start-placeholder="开始"
              end-placeholder="结束"
              style="width: 340px"
            />
            <el-button type="primary" @click="search">查询</el-button>
            <el-button @click="presetErrorsOnly">仅报错</el-button>
            <el-button @click="presetSlowOnly">仅慢请求</el-button>
            <el-button @click="clearFilters">清空筛选</el-button>
          </template>

          <el-input-number v-model="query.slow_ms" :min="100" :max="30000" :step="100" controls-position="right" />
          <span class="muted">慢阈值 (ms)</span>
          <el-switch v-model="autoRefresh" active-text="自动刷新" />
          <el-button type="primary" :loading="loading || loadingRequests || loadingSide" @click="load">刷新</el-button>
          <el-button type="danger" plain @click="resetMetrics">清空</el-button>
        </div>
        <div class="stats">
          <div class="stat">
            <div class="stat-label">总请求</div>
            <div class="stat-value">{{ summary.total_requests.toLocaleString() }}</div>
          </div>
          <div class="stat">
            <div class="stat-label">报错数</div>
            <div class="stat-value danger">{{ summary.total_errors.toLocaleString() }}</div>
          </div>
          <div class="stat">
            <div class="stat-label">路径数</div>
            <div class="stat-value">{{ summary.path_count.toLocaleString() }}</div>
          </div>
          <div class="stat">
            <div class="stat-label">慢阈值</div>
            <div class="stat-value">{{ summary.slow_threshold_ms }} ms</div>
          </div>
          <div class="stat">
            <div class="stat-label">窗口已用</div>
            <div class="stat-value small">
              {{ summary.buffer_size.toLocaleString() }} / {{ summary.buffer_capacity.toLocaleString() }}
            </div>
          </div>
          <div class="stat wide">
            <div class="stat-label">采集窗口</div>
            <div class="stat-value small">
              {{ formatTime(summary.started_at) }} → {{ formatTime(summary.collected_at) }}
            </div>
          </div>
        </div>
      </div>

      <template v-if="view === 'summary'">
        <div class="page-card mb">
          <h3 class="section-title">接口汇总 <span class="muted tip">点击一行查看请求明细</span></h3>
          <el-table
            :data="filteredPaths"
            v-loading="loading"
            stripe
            border
            max-height="420"
            class="clickable-table"
            @row-click="openDetail"
          >
            <el-table-column label="方法" width="90">
              <template #default="{ row }">{{ row.method || "—" }}</template>
            </el-table-column>
            <el-table-column label="路径" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">
                <span class="linkish">{{ row.path || "—" }}</span>
              </template>
            </el-table-column>
            <el-table-column label="次数" width="90" align="right">
              <template #default="{ row }">{{ num(row.count).toLocaleString() }}</template>
            </el-table-column>
            <el-table-column label="报错" width="80" align="right">
              <template #default="{ row }">
                <span :class="{ danger: num(row.error_count) > 0 }">{{ num(row.error_count) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="平均 ms" width="100" align="right">
              <template #default="{ row }">{{ num(row.avg_ms) }}</template>
            </el-table-column>
            <el-table-column label="最大 ms" width="100" align="right">
              <template #default="{ row }">
                <span :class="{ warn: num(row.max_ms) >= summary.slow_threshold_ms }">{{ num(row.max_ms) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最近 ms" width="100" align="right">
              <template #default="{ row }">{{ num(row.last_ms) }}</template>
            </el-table-column>
            <el-table-column label="最近状态" width="100" align="center">
              <template #default="{ row }">
                <el-tag size="small" :type="statusType(row.last_status)" effect="plain">
                  {{ row.last_status ?? "—" }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column label="最近时间" min-width="160">
              <template #default="{ row }">{{ formatTime(row.last_at) }}</template>
            </el-table-column>
            <el-table-column label="操作" width="90" align="center" fixed="right">
              <template #default>
                <span class="linkish">查看</span>
              </template>
            </el-table-column>
          </el-table>
        </div>

        <div class="grid-2">
          <div class="page-card">
            <h3 class="section-title">
              最近慢请求 (≥ {{ summary.slow_threshold_ms }} ms)
              <span class="muted tip">点击进入该接口明细</span>
            </h3>
            <el-table
              :data="recentSlow"
              v-loading="loadingSide"
              stripe
              border
              max-height="360"
              class="clickable-table"
              @row-click="openDetail"
            >
              <el-table-column label="时间" min-width="150">
                <template #default="{ row }">{{ formatTime(row.at) }}</template>
              </el-table-column>
              <el-table-column label="方法" width="80">
                <template #default="{ row }">{{ row.method || "—" }}</template>
              </el-table-column>
              <el-table-column label="路径" min-width="160" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="linkish">{{ row.path || "—" }}</span>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="90" align="right">
                <template #default="{ row }">
                  <span class="warn">{{ num(row.duration_ms) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="IP" width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ row.ip || "—" }}</template>
              </el-table-column>
            </el-table>
          </div>

          <div class="page-card">
            <h3 class="section-title">
              最近报错 (≥ 400)
              <span class="muted tip">点击进入该接口明细</span>
            </h3>
            <el-table
              :data="recentErrors"
              v-loading="loadingSide"
              stripe
              border
              max-height="360"
              class="clickable-table"
              @row-click="openDetail"
            >
              <el-table-column label="时间" min-width="150">
                <template #default="{ row }">{{ formatTime(row.at) }}</template>
              </el-table-column>
              <el-table-column label="方法" width="80">
                <template #default="{ row }">{{ row.method || "—" }}</template>
              </el-table-column>
              <el-table-column label="路径" min-width="140" show-overflow-tooltip>
                <template #default="{ row }">
                  <span class="linkish">{{ row.path || "—" }}</span>
                </template>
              </el-table-column>
              <el-table-column label="状态" width="80" align="center">
                <template #default="{ row }">
                  <el-tag size="small" :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag>
                </template>
              </el-table-column>
              <el-table-column label="耗时" width="80" align="right">
                <template #default="{ row }">{{ num(row.duration_ms) }}</template>
              </el-table-column>
              <el-table-column label="IP" width="110" show-overflow-tooltip>
                <template #default="{ row }">{{ row.ip || "—" }}</template>
              </el-table-column>
              <el-table-column label="错误信息" min-width="120" show-overflow-tooltip>
                <template #default="{ row }">{{ row.error || "—" }}</template>
              </el-table-column>
            </el-table>
          </div>
        </div>
      </template>

      <div v-else class="page-card">
        <h3 class="section-title">
          请求明细
          <span class="muted tip">明细仅含进程内窗口（最多 {{ summary.buffer_capacity.toLocaleString() }}）</span>
        </h3>
        <el-table :data="items" v-loading="loadingRequests" stripe border max-height="520">
          <el-table-column label="时间" min-width="160">
            <template #default="{ row }">{{ formatTime(row.at) }}</template>
          </el-table-column>
          <el-table-column label="方法" width="80">
            <template #default="{ row }">{{ row.method || "—" }}</template>
          </el-table-column>
          <el-table-column label="路径" min-width="200" show-overflow-tooltip>
            <template #default="{ row }">{{ row.path || "—" }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80" align="center">
            <template #default="{ row }">
              <el-tag size="small" :type="statusType(row.status)" effect="plain">{{ row.status ?? "—" }}</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="耗时 ms" width="90" align="right">
            <template #default="{ row }">
              <span :class="{ warn: num(row.duration_ms) >= summary.slow_threshold_ms }">
                {{ num(row.duration_ms) }}
              </span>
            </template>
          </el-table-column>
          <el-table-column label="IP" width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ row.ip || "—" }}</template>
          </el-table-column>
          <el-table-column label="平台" width="90" show-overflow-tooltip>
            <template #default="{ row }">{{ row.platform || "—" }}</template>
          </el-table-column>
          <el-table-column label="型号" width="110" show-overflow-tooltip>
            <template #default="{ row }">{{ row.device_model || "—" }}</template>
          </el-table-column>
          <el-table-column label="UA" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ row.user_agent || "—" }}</template>
          </el-table-column>
          <el-table-column label="错误信息" min-width="140" show-overflow-tooltip>
            <template #default="{ row }">{{ row.error || "—" }}</template>
          </el-table-column>
        </el-table>
        <div class="pager">
          <el-pagination
            v-model:current-page="query.page"
            v-model:page-size="query.page_size"
            :total="total"
            :page-sizes="[50, 100]"
            layout="total, sizes, prev, pager, next"
            @current-change="loadRequests"
            @size-change="
              () => {
                query.page = 1;
                loadRequests();
              }
            "
          />
        </div>
      </div>
    </template>
  </div>
</template>

<style scoped>
.mb {
  margin-bottom: 12px;
}
.section-title {
  margin: 0 0 12px;
  font-size: 15px;
}
.tip {
  margin-left: 8px;
  font-size: 12px;
  font-weight: 400;
}
.filters {
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}
.detail-tag {
  max-width: 420px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.stats {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  margin-top: 12px;
}
.stat {
  min-width: 120px;
  background: #f8fafc;
  border: 1px solid #e2e8f0;
  border-radius: 8px;
  padding: 10px 12px;
}
.stat.wide {
  min-width: 280px;
  flex: 1;
}
.stat-label {
  color: #64748b;
  font-size: 12px;
  margin-bottom: 4px;
}
.stat-value {
  font-size: 20px;
  font-weight: 650;
}
.stat-value.small {
  font-size: 13px;
  font-weight: 500;
}
.danger {
  color: #dc2626;
}
.warn {
  color: #d97706;
  font-weight: 600;
}
.muted {
  color: #64748b;
  font-size: 13px;
}
.linkish {
  color: #2563eb;
}
.clickable-table :deep(.el-table__row) {
  cursor: pointer;
}
.grid-2 {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}
@media (max-width: 1100px) {
  .grid-2 {
    grid-template-columns: 1fr;
  }
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: flex-end;
}
</style>
