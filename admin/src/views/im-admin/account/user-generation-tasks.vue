<script setup lang="ts">
import dayjs from "dayjs";
import * as XLSX from "xlsx";
import { computed, onMounted, onUnmounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { message } from "@/utils/message";
import {
  adminApiErrMessage,
  getAdminUserGenerationTask,
  getAdminUserGenerationTasks,
  postAdminUserGenerationTask,
  type AdminUserGenerationAccount,
  type AdminUserGenerationTask,
  type AdminUserGenerationStatus
} from "@/api/im-user";
import {
  loginPasswordPolicyError,
  LOGIN_PASSWORD_PLACEHOLDER
} from "./utils/loginPasswordPolicy";

defineOptions({ name: "ImUserGenerationTasks" });

const route = useRoute();
const loading = ref(false);
const creating = ref(false);
const detailLoading = ref(false);
const rows = ref<AdminUserGenerationTask[]>([]);
const current = ref<AdminUserGenerationTask | null>(null);
const accounts = ref<AdminUserGenerationAccount[]>([]);
const detailVisible = ref(false);
const createVisible = ref(false);
const createFormRef = ref();
let pollTimer: ReturnType<typeof setTimeout> | null = null;

const query = reactive({
  task_no: "",
  created_by: "",
  status: "" as AdminUserGenerationStatus | "",
  range: [] as string[]
});
const pagination = reactive({ currentPage: 1, pageSize: 20, total: 0 });
const createForm = reactive({ password: "", count: 10 });

const createRules = {
  password: [
    { required: true, message: "请输入统一登录密码", trigger: "blur" },
    {
      validator: (_rule: unknown, value: string, callback: (error?: Error) => void) => {
        const error = loginPasswordPolicyError(value);
        callback(error ? new Error(error) : undefined);
      },
      trigger: "blur"
    }
  ],
  count: [
    { required: true, message: "请输入生成数量", trigger: "change" },
    {
      validator: (_rule: unknown, value: number, callback: (error?: Error) => void) => {
        callback(
          Number.isInteger(value) && value >= 1 && value <= 100
            ? undefined
            : new Error("数量须为 1～100 的整数")
        );
      },
      trigger: "change"
    }
  ]
};

const hasActiveTask = computed(() =>
  rows.value.some(row => ["pending", "running"].includes(row.status))
);

const statusLabel: Record<string, string> = {
  pending: "排队中",
  running: "生成中",
  success: "成功",
  partial_failed: "部分失败",
  failed: "失败"
};

function statusType(status: string) {
  if (status === "success") return "success";
  if (status === "running") return "primary";
  if (status === "partial_failed") return "warning";
  if (status === "failed") return "danger";
  return "info";
}

function formatTime(value?: string | number | null) {
  if (!value) return "—";
  const normalized = typeof value === "number" && value < 1_000_000_000_000
    ? value * 1000
    : value;
  const parsed = dayjs(normalized);
  return parsed.isValid() ? parsed.format("YYYY-MM-DD HH:mm:ss") : value;
}

function schedulePoll() {
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = null;
  if (hasActiveTask.value) {
    pollTimer = setTimeout(() => loadData(true), 3000);
  }
}

async function loadData(silent = false) {
  if (!silent) loading.value = true;
  try {
    const range = query.range || [];
    const res = await getAdminUserGenerationTasks({
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      task_no: query.task_no.trim() || undefined,
      created_by: query.created_by.trim() || undefined,
      status: query.status || undefined,
      created_from: range[0] ? dayjs(range[0]).startOf("day").toISOString() : undefined,
      created_to: range[1] ? dayjs(range[1]).add(1, "day").startOf("day").toISOString() : undefined
    });
    rows.value = res.items || [];
    pagination.total = Number(res.total || 0);
  } catch (error) {
    if (!silent) message(adminApiErrMessage(error, "任务列表加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
    schedulePoll();
  }
}

function resetQuery() {
  query.task_no = "";
  query.created_by = "";
  query.status = "";
  query.range = [];
  pagination.currentPage = 1;
  loadData();
}

function openCreate() {
  createForm.password = "";
  createForm.count = 10;
  createVisible.value = true;
}

async function submitCreate() {
  const valid = await createFormRef.value?.validate().catch(() => false);
  if (!valid) return;
  creating.value = true;
  try {
    const result = await postAdminUserGenerationTask({
      password: createForm.password,
      count: Number(createForm.count)
    });
    createVisible.value = false;
    message(`任务 ${result.task_no} 已进入队列`, { type: "success" });
    pagination.currentPage = 1;
    await loadData();
  } catch (error) {
    message(adminApiErrMessage(error, "任务创建失败"), { type: "error" });
  } finally {
    creating.value = false;
  }
}

async function openDetail(row: AdminUserGenerationTask) {
  detailVisible.value = true;
  detailLoading.value = true;
  current.value = row;
  accounts.value = [];
  try {
    const detail = await getAdminUserGenerationTask(row.task_no);
    current.value = detail.task;
    accounts.value = detail.accounts || [];
  } catch (error) {
    message(adminApiErrMessage(error, "任务详情加载失败"), { type: "warning" });
  } finally {
    detailLoading.value = false;
  }
}

async function exportTask(row: AdminUserGenerationTask) {
  try {
    const detail = await getAdminUserGenerationTask(row.task_no);
    const exportRows = (detail.accounts || []).map(item => ({
      序号: item.index + 1,
      状态: item.status === "success" ? "成功" : item.status === "failed" ? "失败" : item.status,
      用户UID: item.user_uid || "",
      昵称: item.nickname || "",
      TRX地址: item.trx_address || "",
      充值地址: item.deposit_address || "",
      USDT合约: item.usdt_contract || "",
      最低充值USDT: item.min_deposit_usdt || "",
      错误码: item.error_code || "",
      错误信息: item.error_message || ""
    }));
    const worksheet = XLSX.utils.json_to_sheet(exportRows);
    const workbook = XLSX.utils.book_new();
    XLSX.utils.book_append_sheet(workbook, worksheet, "生成账号");
    XLSX.writeFile(workbook, `账号生成-${row.task_no}.xlsx`);
    message("账号清单已导出（不包含密码）", { type: "success" });
  } catch (error) {
    message(adminApiErrMessage(error, "导出失败"), { type: "error" });
  }
}

function canExport(row: AdminUserGenerationTask) {
  return ["success", "partial_failed", "failed"].includes(row.status);
}

onMounted(() => {
  const taskNo = route.query.task_no;
  if (typeof taskNo === "string") query.task_no = taskNo;
  loadData();
});
onUnmounted(() => {
  if (pollTimer) clearTimeout(pollTimer);
});
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="任务号">
          <el-input v-model="query.task_no" clearable class="w-56!" placeholder="UGT-..." />
        </el-form-item>
        <el-form-item label="创建人">
          <el-input v-model="query.created_by" clearable class="w-40!" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable class="w-36!" placeholder="全部">
            <el-option v-for="(label, value) in statusLabel" :key="value" :label="label" :value="value" />
          </el-select>
        </el-form-item>
        <el-form-item label="创建日期">
          <el-date-picker
            v-model="query.range"
            type="daterange"
            value-format="YYYY-MM-DD"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="pagination.currentPage = 1; loadData()">
            查询
          </el-button>
          <el-button @click="resetQuery">重置</el-button>
          <el-button type="success" @click="openCreate">新建生成任务</el-button>
        </el-form-item>
      </el-form>

      <el-alert
        v-if="hasActiveTask"
        type="info"
        :closable="false"
        show-icon
        class="mb-3"
        title="存在运行中的任务，页面每 3 秒自动刷新"
      />

      <el-table v-loading="loading" :data="rows" border stripe row-key="task_no">
        <el-table-column prop="task_no" label="任务号" min-width="210" />
        <el-table-column label="状态" width="105">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain">
              {{ statusLabel[row.status] || row.status }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="进度" min-width="210">
          <template #default="{ row }">
            <el-progress
              :percentage="row.requested_count ? Math.floor(row.processed_count * 100 / row.requested_count) : 0"
              :status="row.status === 'success' ? 'success' : row.status === 'failed' ? 'exception' : undefined"
            />
            <div class="text-xs text-gray-500">
              {{ row.processed_count }}/{{ row.requested_count }}
              · 成功 {{ row.success_count }} · 失败 {{ row.fail_count }}
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="created_by" label="创建人" min-width="120" />
        <el-table-column label="创建时间" min-width="168">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="完成时间" min-width="168">
          <template #default="{ row }">{{ formatTime(row.finished_at) }}</template>
        </el-table-column>
        <el-table-column prop="last_error" label="最近错误" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.last_error || "—" }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
            <el-button v-if="canExport(row)" link type="success" @click="exportTask(row)">
              导出
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
          @current-change="loadData()"
          @size-change="pagination.currentPage = 1; loadData()"
        />
      </div>
    </el-card>

    <el-dialog v-model="createVisible" title="新建账号生成任务" width="480px" destroy-on-close>
      <el-form ref="createFormRef" :model="createForm" :rules="createRules" label-width="96px">
        <el-form-item label="登录密码" prop="password">
          <el-input
            v-model="createForm.password"
            type="password"
            show-password
            autocomplete="new-password"
            :placeholder="LOGIN_PASSWORD_PLACEHOLDER"
          />
        </el-form-item>
        <el-form-item label="生成数量" prop="count">
          <el-input-number v-model="createForm.count" :min="1" :max="100" class="w-full!" />
        </el-form-item>
        <el-alert
          type="info"
          :closable="false"
          title="提交后可关闭页面，后台会继续生成；导出清单不包含密码。"
        />
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="creating" @click="submitCreate">提交任务</el-button>
      </template>
    </el-dialog>

    <el-drawer v-model="detailVisible" title="账号生成任务详情" size="78%">
      <div v-if="current" class="mb-3 grid grid-cols-2 gap-2 text-sm md:grid-cols-4">
        <div>任务号：{{ current.task_no }}</div>
        <div>状态：{{ statusLabel[current.status] }}</div>
        <div>成功：{{ current.success_count }}</div>
        <div>失败：{{ current.fail_count }}</div>
      </div>
      <el-table v-loading="detailLoading" :data="accounts" border stripe>
        <el-table-column prop="index" label="#" width="60">
          <template #default="{ row }">{{ row.index + 1 }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" />
        <el-table-column prop="user_uid" label="用户 UID" min-width="130">
          <template #default="{ row }">{{ row.user_uid || "—" }}</template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" min-width="150" />
        <el-table-column prop="deposit_address" label="充值地址" min-width="280">
          <template #default="{ row }">{{ row.deposit_address || "—" }}</template>
        </el-table-column>
        <el-table-column prop="error_code" label="错误码" min-width="130">
          <template #default="{ row }">{{ row.error_code || "—" }}</template>
        </el-table-column>
        <el-table-column prop="error_message" label="错误信息" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ row.error_message || "—" }}</template>
        </el-table-column>
      </el-table>
      <div class="mt-3 flex justify-end">
        <el-button v-if="current && canExport(current)" type="success" @click="exportTask(current)">
          导出 Excel
        </el-button>
      </div>
    </el-drawer>
  </div>
</template>
