<script setup lang="ts">
import dayjs from "dayjs";
import { onMounted, reactive, ref } from "vue";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import { getLifePaymentWorkers, type LifePaymentWorkerItem } from "@/api/im-life-payments";

defineOptions({ name: "ImLifePaymentWorkers" });

const loading = ref(false);
const dataList = ref<LifePaymentWorkerItem[]>([]);
const query = reactive({ status: "" });

function formatTime(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

function statusType(status?: string | null) {
  const s = String(status || "");
  if (s === "online") return "success";
  if (s === "busy") return "warning";
  if (s === "disabled") return "danger";
  return "info";
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getLifePaymentWorkers({
      status: query.status || undefined
    });
    dataList.value = res.items;
  } catch (err: unknown) {
    dataList.value = [];
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 wallet.read）", { type: "warning" });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "Worker 列表加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

onMounted(() => loadData());
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" class="w-36!">
            <el-option label="online" value="online" />
            <el-option label="busy" value="busy" />
            <el-option label="offline" value="offline" />
            <el-option label="disabled" value="disabled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="loadData">查询</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="dataList" border stripe v-loading="loading" row-key="worker_id">
        <el-table-column prop="worker_id" label="Worker ID" min-width="160" show-overflow-tooltip />
        <el-table-column prop="device_id" label="设备 ID" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.device_id || "—" }}</template>
        </el-table-column>
        <el-table-column prop="device_name" label="设备名" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.device_name || "—" }}</template>
        </el-table-column>
        <el-table-column label="支持业务" min-width="200">
          <template #default="{ row }">
            <el-tag
              v-for="t in row.support_service_types || []"
              :key="t"
              size="small"
              class="mr-1"
              effect="plain"
            >
              {{ t }}
            </el-tag>
            <span v-if="!(row.support_service_types || []).length">—</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain">{{ row.status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="app_version" label="版本" width="100">
          <template #default="{ row }">{{ row.app_version || "—" }}</template>
        </el-table-column>
        <el-table-column label="最近心跳" min-width="168">
          <template #default="{ row }">{{ formatTime(row.last_heartbeat_at) }}</template>
        </el-table-column>
        <el-table-column label="上线时间" min-width="168">
          <template #default="{ row }">{{ formatTime(row.last_online_at) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
