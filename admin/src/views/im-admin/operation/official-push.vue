<script setup lang="ts">
import dayjs from "dayjs";
import { computed, onMounted, reactive, ref } from "vue";
import type { FormRules } from "element-plus";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  cancelOfficialPushTask,
  createOfficialPushTask,
  getOfficialPushAccounts,
  getOfficialPushTasks,
  retryOfficialPushTask,
  type OfficialPushAccount,
  type OfficialPushScope,
  type OfficialPushTask
} from "@/api/im-official-push";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

/** 公众号推送管理：后台只提交任务；真正推客户端必须由后端事务成功后广播 client.*。 */
defineOptions({ name: "ImOperationOfficialPush" });

type ScopeMode = OfficialPushScope;

const formRef = ref();
const loading = ref(false);
const submitLoading = ref(false);
const accountsLoading = ref(false);
const accounts = ref<OfficialPushAccount[]>([]);
const tasks = ref<OfficialPushTask[]>([]);

const query = reactive({
  keyword: "",
  status: ""
});

const pagination = reactive({
  total: 0,
  pageSize: 10,
  currentPage: 1,
  background: true
});

const form = reactive({
  title: "",
  content: "",
  accountId: "",
  scope: "all" as ScopeMode,
  uidsText: "",
  scheduledAt: ""
});

const uidParseResult = computed(() => {
  const lines = String(form.uidsText ?? "")
    .split(/\r?\n/g)
    .map(v => v.trim())
    .filter(Boolean);
  const invalid: string[] = [];
  const uids: string[] = [];
  for (const raw of lines) {
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(raw)) invalid.push(raw);
    else uids.push(raw);
  }
  return { uids: Array.from(new Set(uids)), invalid };
});

const formRules = computed<FormRules>(() => ({
  title: [
    { required: true, message: "请输入推送标题", trigger: "blur" },
    { min: 1, max: 128, message: "标题长度 1-128 字符", trigger: "blur" }
  ],
  content: [
    { required: true, message: "请输入推送内容", trigger: "blur" },
    { min: 1, max: 4000, message: "内容长度 1-4000 字符", trigger: "blur" }
  ],
  scope: [{ required: true, message: "请选择发送范围", trigger: "change" }],
  uidsText: [
    {
      validator: (_rule, value, callback) => {
        if (form.scope !== "uids") return callback();
        const v = value == null ? "" : String(value).trim();
        if (!v) return callback(new Error("请输入用户 UID，每行一个"));
        if (uidParseResult.value.invalid.length > 0) {
          return callback(
            new Error(`存在无效 UID：${uidParseResult.value.invalid.slice(0, 5).join(", ")}`)
          );
        }
        if (uidParseResult.value.uids.length <= 0) return callback(new Error("UID 解析为空"));
        callback();
      },
      trigger: ["blur", "change"]
    }
  ]
}));

function statusTagType(status?: string | null) {
  const s = String(status || "").toLowerCase();
  if (["success", "sent", "done", "published"].includes(s)) return "success";
  if (["failed", "fail", "error"].includes(s)) return "danger";
  if (["sending", "running", "pending"].includes(s)) return "warning";
  if (["cancelled", "canceled"].includes(s)) return "info";
  return "info";
}

function formatTime(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

async function loadAccounts() {
  accountsLoading.value = true;
  try {
    accounts.value = await getOfficialPushAccounts();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "公众号账号加载失败"), { type: "warning" });
    accounts.value = [];
  } finally {
    accountsLoading.value = false;
  }
}

async function loadTasks() {
  loading.value = true;
  try {
    const res = await getOfficialPushTasks({
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      keyword: query.keyword.trim() || undefined,
      status: query.status || undefined
    });
    tasks.value = res.items;
    pagination.total = res.total;
  } catch (err: unknown) {
    tasks.value = [];
    pagination.total = 0;
    message(adminApiErrMessage(err, "公众号推送任务加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

function resetCreateForm() {
  form.title = "";
  form.content = "";
  form.accountId = "";
  form.scope = "all";
  form.uidsText = "";
  form.scheduledAt = "";
  formRef.value?.clearValidate?.();
}

async function submitTask() {
  const ok = await formRef.value?.validate?.().catch(() => false);
  if (!ok) return;
  submitLoading.value = true;
  try {
    await createOfficialPushTask({
      title: form.title.trim(),
      content: form.content.trim(),
      account_id: form.accountId || undefined,
      scope: form.scope,
      user_uids: form.scope === "uids" ? uidParseResult.value.uids : undefined,
      scheduled_at: form.scheduledAt || undefined
    });
    message("公众号推送任务已提交", { type: "success" });
    resetCreateForm();
    await loadTasks();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "公众号推送提交失败"), { type: "warning" });
  } finally {
    submitLoading.value = false;
  }
}

async function retryTask(row: OfficialPushTask) {
  try {
    await retryOfficialPushTask(row.id);
    message("已提交重试", { type: "success" });
    await loadTasks();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "重试失败"), { type: "warning" });
  }
}

async function cancelTask(row: OfficialPushTask) {
  try {
    await cancelOfficialPushTask(row.id);
    message("已取消任务", { type: "success" });
    await loadTasks();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "取消失败"), { type: "warning" });
  }
}

function onSizeChange(v: number) {
  pagination.pageSize = v;
  pagination.currentPage = 1;
  loadTasks();
}

function onCurrentChange(v: number) {
  pagination.currentPage = v;
  loadTasks();
}

useAdminRealtimeInvalidate(
  [ADMIN_REALTIME_EVENTS.OFFICIAL_PUSH_UPDATED, ADMIN_REALTIME_EVENTS.ANNOUNCEMENT_PUBLISHED],
  () => loadTasks(),
  { debounceMs: 200 }
);

onMounted(() => {
  loadAccounts();
  loadTasks();
});
</script>

<template>
  <div class="p-4 space-y-4">
    <el-alert
      type="warning"
      :closable="false"
      title="后端尚未提供公众号推送 API（/api/v1/official-push），以下界面仅供预览，暂不可用。"
    />
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <template #header>
        <div class="font-medium">新建公众号推送</div>
      </template>
      <el-form ref="formRef" :model="form" :rules="formRules" label-width="104px">
        <el-form-item label="公众号账号">
          <el-select
            v-model="form.accountId"
            clearable
            filterable
            :loading="accountsLoading"
            placeholder="默认官方账号"
            class="w-80!"
          >
            <el-option v-for="it in accounts" :key="it.id" :label="it.name" :value="it.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="推送标题" prop="title">
          <el-input v-model="form.title" maxlength="128" show-word-limit placeholder="最多 128 字符" />
        </el-form-item>
        <el-form-item label="推送内容" prop="content">
          <el-input
            v-model="form.content"
            type="textarea"
            maxlength="4000"
            show-word-limit
            :autosize="{ minRows: 5, maxRows: 10 }"
            placeholder="发送给客户端公众号会话 / 官方消息入口的正文"
          />
        </el-form-item>
        <el-form-item label="发送范围" prop="scope">
          <el-radio-group v-model="form.scope">
            <el-radio-button value="all">全部用户</el-radio-button>
            <el-radio-button value="uids">指定 UID</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scope === 'uids'" label="用户 UID" prop="uidsText">
          <el-input
            v-model="form.uidsText"
            type="textarea"
            :autosize="{ minRows: 5, maxRows: 10 }"
            placeholder="每行一个 UID"
          />
        </el-form-item>
        <el-form-item label="定时发送">
          <el-date-picker
            v-model="form.scheduledAt"
            type="datetime"
            clearable
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="不填则立即发送"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="submitLoading" @click="submitTask">提交推送</el-button>
          <el-button :disabled="submitLoading" @click="resetCreateForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="flex items-center justify-between gap-3">
          <span class="font-medium">公众号推送任务</span>
          <el-button :loading="loading" @click="loadTasks">刷新</el-button>
        </div>
      </template>

      <el-form :inline="true" :model="query" class="mb-2">
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" clearable class="w-56!" placeholder="标题 / 内容" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" class="w-40!">
            <el-option label="待发送" value="pending" />
            <el-option label="发送中" value="sending" />
            <el-option label="成功" value="success" />
            <el-option label="失败" value="failed" />
            <el-option label="已取消" value="cancelled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="pagination.currentPage = 1; loadTasks();">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="tasks" border stripe v-loading="loading" row-key="id">
        <el-table-column prop="id" label="任务 ID" min-width="110" show-overflow-tooltip />
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column prop="account_name" label="公众号" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.account_name || row.account_id || '默认官方账号' }}</template>
        </el-table-column>
        <el-table-column prop="scope" label="范围" width="90" />
        <el-table-column label="发送统计" min-width="150">
          <template #default="{ row }">
            {{ row.success_count ?? 0 }} / {{ row.target_count ?? 0 }}
            <span class="text-red-500" v-if="Number(row.fail_count || 0) > 0">失败 {{ row.fail_count }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" effect="plain">{{ row.status || '—' }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="168">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="发送时间" min-width="168">
          <template #default="{ row }">{{ formatTime(row.sent_at || row.scheduled_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="retryTask(row)">重试</el-button>
            <el-button link type="danger" size="small" @click="cancelTask(row)">取消</el-button>
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
  </div>
  </div>
</template>
