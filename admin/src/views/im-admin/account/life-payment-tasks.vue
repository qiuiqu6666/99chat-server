<script setup lang="ts">
import dayjs from "dayjs";
import { onMounted, reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  getLifePaymentTasks,
  retryLifePaymentTask,
  type LifePaymentTaskItem
} from "@/api/im-life-payments";

defineOptions({ name: "ImLifePaymentTasks" });

const loading = ref(false);
const dataList = ref<LifePaymentTaskItem[]>([]);
const detailVisible = ref(false);
const current = ref<LifePaymentTaskItem | null>(null);

const query = reactive({
  task_no: "",
  order_no: "",
  service_type: "",
  status: ""
});

const pagination = reactive({
  total: 0,
  pageSize: 20,
  currentPage: 1
});

const serviceLabel: Record<string, string> = {
  mobile: "手机充值",
  water: "水费",
  electric: "电费",
  gas: "燃气费"
};

function formatTime(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

function statusType(status?: string | null) {
  const s = String(status || "");
  if (s === "success") return "success";
  if (s === "failed" || s === "cancelled") return "danger";
  if (s === "need_manual") return "warning";
  if (s === "running") return "primary";
  return "info";
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getLifePaymentTasks({
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      task_no: query.task_no.trim() || undefined,
      order_no: query.order_no.trim() || undefined,
      service_type: query.service_type || undefined,
      status: query.status || undefined
    });
    dataList.value = res.items;
    pagination.total = res.total;
  } catch (err: unknown) {
    dataList.value = [];
    pagination.total = 0;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 wallet.read）", { type: "warning" });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "任务列表加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function resetQuery() {
  query.task_no = "";
  query.order_no = "";
  query.service_type = "";
  query.status = "";
  pagination.currentPage = 1;
  loadData();
}

function onSizeChange(v: number) {
  pagination.pageSize = v;
  pagination.currentPage = 1;
  loadData();
}

function onCurrentChange(v: number) {
  pagination.currentPage = v;
  loadData();
}

function openDetail(row: LifePaymentTaskItem) {
  current.value = row;
  detailVisible.value = true;
}

function canRetry(row: LifePaymentTaskItem) {
  const s = String(row.status || "");
  return ["failed", "need_manual", "ready"].includes(s);
}

async function retryTask(row: LifePaymentTaskItem) {
  try {
    const { value } = await ElMessageBox.prompt("请输入重试原因", "重试任务", {
      confirmButtonText: "确认重试",
      cancelButtonText: "取消",
      inputPlaceholder: "页面异常，重新执行",
      inputValue: "页面异常，重新执行"
    });
    await retryLifePaymentTask(row.task_no, value);
    message("任务已重新排队", { type: "success" });
    await loadData();
  } catch (err: unknown) {
    if (err === "cancel" || err === "close") return;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 user.write）", { type: "warning" });
      return;
    }
    message(adminApiErrMessage(err, "重试失败"), { type: "warning" });
  }
}

onMounted(() => loadData());
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="任务号">
          <el-input v-model="query.task_no" clearable class="w-48!" placeholder="task_no" />
        </el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="query.order_no" clearable class="w-48!" placeholder="order_no" />
        </el-form-item>
        <el-form-item label="业务">
          <el-select v-model="query.service_type" clearable placeholder="全部" class="w-36!">
            <el-option label="手机充值" value="mobile" />
            <el-option label="水费" value="water" />
            <el-option label="电费" value="electric" />
            <el-option label="燃气费" value="gas" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" class="w-36!">
            <el-option label="ready" value="ready" />
            <el-option label="running" value="running" />
            <el-option label="success" value="success" />
            <el-option label="failed" value="failed" />
            <el-option label="need_manual" value="need_manual" />
            <el-option label="cancelled" value="cancelled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="pagination.currentPage = 1; loadData();">
            查询
          </el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="dataList" border stripe v-loading="loading" row-key="task_no">
        <el-table-column prop="task_no" label="任务号" min-width="150" show-overflow-tooltip />
        <el-table-column prop="order_no" label="订单号" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.order_no || "—" }}</template>
        </el-table-column>
        <el-table-column label="业务" width="100">
          <template #default="{ row }">
            {{ serviceLabel[row.service_type || ""] || row.service_type || "—" }}
          </template>
        </el-table-column>
        <el-table-column prop="task_action" label="动作" width="90" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="重试" width="90">
          <template #default="{ row }">
            {{ row.attempt_count ?? 0 }}/{{ row.max_attempts ?? 3 }}
          </template>
        </el-table-column>
        <el-table-column prop="locked_by" label="锁定 Worker" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.locked_by || "—" }}</template>
        </el-table-column>
        <el-table-column prop="last_error_code" label="错误码" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.last_error_code || "—" }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="168">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">详情</el-button>
            <el-button
              v-if="canRetry(row)"
              link
              type="warning"
              size="small"
              @click="retryTask(row)"
            >
              重试
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-3 flex justify-end">
        <el-pagination
          v-model:current-page="pagination.currentPage"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="onSizeChange"
          @current-change="onCurrentChange"
        />
      </div>
    </el-card>

    <el-drawer v-model="detailVisible" title="任务详情" size="560px">
      <template v-if="current">
        <el-descriptions :column="1" border>
          <el-descriptions-item label="任务号">{{ current.task_no }}</el-descriptions-item>
          <el-descriptions-item label="订单号">{{ current.order_no || "—" }}</el-descriptions-item>
          <el-descriptions-item label="查询号">{{ current.query_no || "—" }}</el-descriptions-item>
          <el-descriptions-item label="动作">{{ current.task_action }}</el-descriptions-item>
          <el-descriptions-item label="状态">{{ current.status }}</el-descriptions-item>
          <el-descriptions-item label="支付状态">{{ current.payment_status }}</el-descriptions-item>
          <el-descriptions-item label="错误码">{{ current.last_error_code || "—" }}</el-descriptions-item>
          <el-descriptions-item label="错误信息">{{ current.last_error_message || "—" }}</el-descriptions-item>
          <el-descriptions-item label="心跳">{{ formatTime(current.heartbeat_at) }}</el-descriptions-item>
        </el-descriptions>
        <div class="mt-4 text-sm font-medium">payload_json</div>
        <pre class="mt-2 overflow-auto rounded bg-gray-50 p-3 text-xs">{{
          JSON.stringify(current.payload_json || {}, null, 2)
        }}</pre>
      </template>
    </el-drawer>
  </div>
</template>
