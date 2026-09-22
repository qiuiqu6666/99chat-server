<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { listAdminLoginLogs } from "@/api/adminLogs";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 50,
  username: "",
  ip: "",
  success: "" as "" | "true" | "false" | "1" | "0",
  login_at_from: null as Date | null,
  login_at_to: null as Date | null
});

async function load() {
  loading.value = true;
  try {
    const raw = await listAdminLoginLogs({
      page: query.page,
      page_size: query.page_size,
      username: query.username || undefined,
      ip: query.ip || undefined,
      success: query.success || undefined,
      login_at_from: query.login_at_from ? query.login_at_from.toISOString() : undefined,
      login_at_to: query.login_at_to ? query.login_at_to.toISOString() : undefined
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

function successText(row: Record<string, unknown>) {
  const v = row.success;
  if (v === 1 || v === true || v === "1" || v === "true") return "成功";
  if (v === 0 || v === false || v === "0" || v === "false") return "失败";
  return String(v ?? "—");
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="管理员登录" subtitle="需 admin.manage" />
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.username" clearable placeholder="账号" style="width: 160px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.ip" clearable placeholder="IP" style="width: 150px" />
        <el-select v-model="query.success" clearable placeholder="结果" style="width: 110px">
          <el-option label="成功" value="1" />
          <el-option label="失败" value="0" />
        </el-select>
        <el-date-picker v-model="query.login_at_from" type="datetime" placeholder="开始时间" />
        <el-date-picker v-model="query.login_at_to" type="datetime" placeholder="结束时间" />
        <el-button type="primary" @click="query.page=1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="ID" width="80">
          <template #default="{ row }">{{ row.id }}</template>
        </el-table-column>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.login_at ?? row.loginAt) }}</template>
        </el-table-column>
        <el-table-column label="尝试账号" min-width="120">
          <template #default="{ row }">{{ str(row, "username_attempted", "usernameAttempted") || row.username || "—" }}</template>
        </el-table-column>
        <el-table-column label="管理员" min-width="120">
          <template #default="{ row }">{{ str(row, "admin_username", "adminUsername") || "—" }}</template>
        </el-table-column>
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="successText(row) === '成功' ? 'success' : 'danger'" size="small">{{ successText(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="IP" min-width="130">
          <template #default="{ row }">{{ row.ip || "—" }}</template>
        </el-table-column>
        <el-table-column label="地区" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "geo_address", "geoAddress") || "—" }}</template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "fail_reason", "failReason") || "—" }}</template>
        </el-table-column>
        <el-table-column label="UA" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "user_agent", "userAgent") || "—" }}</template>
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
