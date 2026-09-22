<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { issueWorkerToken, listWorkers } from "@/api/lifePayments";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const actionLoading = ref("");
const rows = ref<Record<string, unknown>[]>([]);
const query = reactive({ status: "" });
const tokenDialog = reactive({
  visible: false,
  workerId: "",
  token: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listWorkers({
      status: query.status || undefined
    });
    rows.value = pickList(raw).items;
  } catch (e) {
    rows.value = [];
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function doIssue(row: Record<string, unknown>) {
  const workerId = str(row, "worker_id", "workerId");
  if (!workerId) return;
  try {
    await ElMessageBox.confirm(`确认为工人 ${workerId} 签发新 Token？旧 Token 可能失效。`, "签发 Token", {
      type: "warning"
    });
  } catch {
    return;
  }
  actionLoading.value = workerId;
  try {
    const res = (await issueWorkerToken(workerId, {
      device_id: str(row, "device_id", "deviceId") || undefined,
      device_name: str(row, "device_name", "deviceName") || undefined
    })) as Record<string, unknown>;
    tokenDialog.workerId = workerId;
    tokenDialog.token = String(res.worker_token ?? res.workerToken ?? "");
    tokenDialog.visible = true;
    ElMessage.success("Token 已签发");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = "";
  }
}

async function copyToken() {
  try {
    await navigator.clipboard.writeText(tokenDialog.token);
    ElMessage.success("已复制");
  } catch {
    ElMessage.warning("复制失败，请手动选择");
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="缴费工人" />
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.status" clearable placeholder="状态" style="width: 160px" @keyup.enter="load" />
        <el-button type="primary" @click="load">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="工人 ID" min-width="150">
          <template #default="{ row }">{{ str(row, "worker_id", "workerId") || "—" }}</template>
        </el-table-column>
        <el-table-column label="设备" min-width="140">
          <template #default="{ row }">{{ str(row, "device_name", "deviceName") || str(row, "device_id", "deviceId") || "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ row.status || "—" }}</template>
        </el-table-column>
        <el-table-column label="版本" width="100">
          <template #default="{ row }">{{ str(row, "app_version", "appVersion") || "—" }}</template>
        </el-table-column>
        <el-table-column label="支持类型" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ Array.isArray(row.support_service_types || row.supportServiceTypes)
              ? (row.support_service_types || row.supportServiceTypes).join(", ")
              : "—" }}
          </template>
        </el-table-column>
        <el-table-column label="Token" width="90">
          <template #default="{ row }">{{ (row.has_token ?? row.hasToken) ? "有" : "无" }}</template>
        </el-table-column>
        <el-table-column label="最近在线" min-width="160">
          <template #default="{ row }">{{ formatTime(row.last_online_at || row.lastOnlineAt) }}</template>
        </el-table-column>
        <el-table-column label="心跳" min-width="160">
          <template #default="{ row }">{{ formatTime(row.last_heartbeat_at || row.lastHeartbeatAt) }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="110" fixed="right">
          <template #default="{ row }">
            <el-button
              type="primary"
              link
              :loading="actionLoading === str(row, 'worker_id', 'workerId')"
              @click="doIssue(row)"
            >签发 Token</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="tokenDialog.visible" title="工人 Token" width="520px">
      <p style="margin-bottom: 8px">工人：{{ tokenDialog.workerId }}</p>
      <el-input :model-value="tokenDialog.token" type="textarea" :rows="3" readonly />
      <template #footer>
        <el-button @click="tokenDialog.visible = false">关闭</el-button>
        <el-button type="primary" @click="copyToken">复制</el-button>
      </template>
    </el-dialog>
  </div>
</template>
