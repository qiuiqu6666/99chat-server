<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { listTasks, retryTask } from "@/api/lifePayments";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const actionLoading = ref("");
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  task_no: "",
  order_no: "",
  service_type: "",
  status: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listTasks({
      page: query.page,
      page_size: query.page_size,
      task_no: query.task_no || undefined,
      order_no: query.order_no || undefined,
      service_type: query.service_type || undefined,
      status: query.status || undefined
    });
    const picked = pickList(raw);
    rows.value = picked.items;
    total.value = picked.total;
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function doRetry(row: Record<string, unknown>) {
  const taskNo = str(row, "task_no", "taskNo");
  if (!taskNo) return;
  let reason = "";
  try {
    const { value } = await ElMessageBox.prompt("请填写重试原因（可选）", "任务重试", {
      inputPlaceholder: "原因",
      confirmButtonText: "重试",
      cancelButtonText: "取消"
    });
    reason = String(value || "").trim();
  } catch {
    return;
  }
  actionLoading.value = taskNo;
  try {
    await retryTask(taskNo, reason ? { reason } : undefined);
    ElMessage.success("已提交重试");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = "";
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="缴费任务" />
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.task_no" clearable placeholder="任务号" style="width: 160px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.order_no" clearable placeholder="订单号" style="width: 160px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.service_type" clearable placeholder="服务类型" style="width: 140px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.status" clearable placeholder="状态" style="width: 140px" @keyup.enter="query.page=1; load()" />
        <el-button type="primary" @click="query.page=1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="任务号" min-width="150">
          <template #default="{ row }">{{ str(row, "task_no", "taskNo") || "—" }}</template>
        </el-table-column>
        <el-table-column label="订单号" min-width="140">
          <template #default="{ row }">{{ str(row, "order_no", "orderNo") || "—" }}</template>
        </el-table-column>
        <el-table-column label="服务" width="110">
          <template #default="{ row }">{{ str(row, "service_type", "serviceType") || "—" }}</template>
        </el-table-column>
        <el-table-column label="动作" width="100">
          <template #default="{ row }">{{ str(row, "task_action", "taskAction") || "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ row.status || "—" }}</template>
        </el-table-column>
        <el-table-column label="尝试" width="90">
          <template #default="{ row }">
            {{ (row.attempt_count ?? row.attemptCount ?? 0) }}/{{ (row.max_attempts ?? row.maxAttempts ?? 0) }}
          </template>
        </el-table-column>
        <el-table-column label="锁定人" width="120">
          <template #default="{ row }">{{ str(row, "locked_by", "lockedBy") || "—" }}</template>
        </el-table-column>
        <el-table-column label="错误" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "last_error_message", "lastErrorMessage") || "—" }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.created_at || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              :loading="actionLoading === str(row, 'task_no', 'taskNo')"
              @click="doRetry(row)"
            >重试</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px; display: flex; justify-content: flex-end">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.page_size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>
  </div>
</template>
