<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { usePagedList } from "@/composables/usePagedList";
import { createGenerationTask, getGenerationTask, listGenerationTasks } from "@/api/users";
import { str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = () => hasPerm("user.write");
const { loading, rows, total, query, load } = usePagedList(params =>
  listGenerationTasks({
    ...params,
    task_no: params.keyword || undefined,
    keyword: undefined
  })
);

const createVisible = ref(false);
const detailVisible = ref(false);
const busy = ref(false);
const form = reactive({ password: "", count: 10, sex: "" });
const task = ref<Record<string, unknown>>({});
const accounts = ref<Record<string, unknown>[]>([]);

const successUids = computed(() =>
  accounts.value
    .filter(a => {
      const st = String(a.status || "").toLowerCase();
      const uid = str(a, "user_uid", "userUid");
      return !!uid && (st === "success" || st === "succeeded" || st === "ok" || !st);
    })
    .map(a => str(a, "user_uid", "userUid"))
);

async function submit() {
  if (!form.password || form.count < 1) {
    ElMessage.warning("请填写密码与数量");
    return;
  }
  busy.value = true;
  try {
    await createGenerationTask({
      password: form.password,
      count: form.count,
      sex: form.sex || undefined
    });
    ElMessage.success("任务已创建");
    createVisible.value = false;
    form.password = "";
    form.count = 10;
    load();
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    busy.value = false;
  }
}

async function openDetail(row: Record<string, unknown>) {
  const taskNo = String(row.task_no || row.taskNo || "");
  if (!taskNo) return;
  try {
    const raw = (await getGenerationTask(taskNo)) as Record<string, unknown>;
    task.value = (raw.task as Record<string, unknown>) || raw;
    accounts.value = Array.isArray(raw.accounts)
      ? (raw.accounts as Record<string, unknown>[])
      : [];
    detailVisible.value = true;
  } catch (e) {
    ElMessage.warning(errMessage(e));
  }
}

async function copyUids() {
  const text = successUids.value.join("\n");
  if (!text) {
    ElMessage.info("暂无可复制 UID");
    return;
  }
  try {
    await navigator.clipboard.writeText(text);
    ElMessage.success(`已复制 ${successUids.value.length} 个 UID`);
  } catch {
    ElMessage.warning("复制失败，请手动选择");
  }
}

function exportCsv() {
  if (!accounts.value.length) {
    ElMessage.info("无账号明细");
    return;
  }
  const header = ["index", "status", "user_uid", "nickname", "trx_address", "deposit_address", "error_message"];
  const lines = [header.join(",")];
  for (const a of accounts.value) {
    const cols = [
      a.index ?? "",
      a.status ?? "",
      str(a, "user_uid", "userUid"),
      JSON.stringify(String(a.nickname ?? "")),
      str(a, "trx_address", "trxAddress"),
      str(a, "deposit_address", "depositAddress"),
      JSON.stringify(String(str(a, "error_message", "errorMessage")))
    ];
    lines.push(cols.join(","));
  }
  const blob = new Blob([lines.join("\n")], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const a = document.createElement("a");
  a.href = url;
  a.download = `generation-${str(task.value, "task_no", "taskNo") || "task"}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}
</script>

<template>
  <div class="page">
    <PageHeader title="账号生成任务">
      <el-button v-if="canWrite()" type="primary" @click="createVisible = true">新建任务</el-button>
    </PageHeader>
    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="任务号"
          style="width: 220px"
          @keyup.enter="load"
        />
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
        <el-button @click="load()">刷新</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="任务号" min-width="180">
          <template #default="{ row }">{{ row.task_no || row.taskNo }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="120" />
        <el-table-column label="请求数" width="90">
          <template #default="{ row }">{{ row.requested_count ?? row.requestedCount ?? row.total_count ?? row.totalCount }}</template>
        </el-table-column>
        <el-table-column label="已处理" width="90">
          <template #default="{ row }">{{ row.processed_count ?? row.processedCount ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="成功" width="90">
          <template #default="{ row }">{{ row.success_count ?? row.successCount }}</template>
        </el-table-column>
        <el-table-column label="失败" width="90">
          <template #default="{ row }">{{ row.fail_count ?? row.failCount }}</template>
        </el-table-column>
        <el-table-column label="创建人" width="120">
          <template #default="{ row }">{{ str(row, "created_by", "createdBy") || "—" }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.created_at || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openDetail(row)">详情</el-button>
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

    <el-dialog v-model="createVisible" title="新建生成任务" width="440px">
      <el-form label-width="90px">
        <el-form-item label="统一密码">
          <el-input v-model="form.password" type="password" show-password />
        </el-form-item>
        <el-form-item label="数量">
          <el-input-number v-model="form.count" :min="1" :max="100" />
        </el-form-item>
        <el-form-item label="性别">
          <el-select v-model="form.sex" clearable placeholder="可选" style="width: 100%">
            <el-option label="男" value="1" />
            <el-option label="女" value="2" />
          </el-select>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="busy" @click="submit">提交</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="detailVisible" title="任务详情" width="860px">
      <el-descriptions :column="3" border size="small" style="margin-bottom: 12px">
        <el-descriptions-item label="任务号">{{ str(task, "task_no", "taskNo") || "—" }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ task.status || "—" }}</el-descriptions-item>
        <el-descriptions-item label="创建人">{{ str(task, "created_by", "createdBy") || "—" }}</el-descriptions-item>
        <el-descriptions-item label="成功/失败">
          {{ (task.success_count ?? task.successCount ?? 0) + " / " + (task.fail_count ?? task.failCount ?? 0) }}
        </el-descriptions-item>
        <el-descriptions-item label="最近错误" :span="2">{{ str(task, "last_error", "lastError") || "—" }}</el-descriptions-item>
      </el-descriptions>
      <div class="toolbar" style="margin-bottom: 8px">
        <el-button @click="copyUids">复制成功 UID</el-button>
        <el-button @click="exportCsv">导出 CSV</el-button>
      </div>
      <el-table :data="accounts" stripe border max-height="420">
        <el-table-column label="#" width="60">
          <template #default="{ row }">{{ row.index ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ row.status || "—" }}</template>
        </el-table-column>
        <el-table-column label="UID" min-width="140">
          <template #default="{ row }"><UserUidLink :uid="str(row, 'user_uid', 'userUid')" /></template>
        </el-table-column>
        <el-table-column label="昵称" min-width="120">
          <template #default="{ row }">{{ row.nickname || "—" }}</template>
        </el-table-column>
        <el-table-column label="充值地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "deposit_address", "depositAddress") || str(row, "trx_address", "trxAddress") || "—" }}</template>
        </el-table-column>
        <el-table-column label="错误" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "error_message", "errorMessage") || "—" }}</template>
        </el-table-column>
      </el-table>
    </el-dialog>
  </div>
</template>
