<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { listLoginLogs } from "@/api/users";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const route = useRoute();
const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  user_uid: String(route.query.user_uid || ""),
  keyword: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listLoginLogs({
      page: query.page,
      page_size: query.page_size,
      user_uid: query.user_uid || undefined,
      keyword: query.keyword || undefined
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

watch(
  () => route.query.user_uid,
  v => {
    query.user_uid = String(v || "");
    query.page = 1;
    load();
  }
);
onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="登录记录" />
    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="query.user_uid"
          clearable
          placeholder="用户 UID"
          style="width: 180px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="关键词"
          style="width: 180px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <UserUidLink :uid="str(row, 'user_uid', 'userUid')" />
          </template>
        </el-table-column>
        <el-table-column label="昵称" min-width="110">
          <template #default="{ row }">{{ str(row, "user_nickname", "userNickname") || "—" }}</template>
        </el-table-column>
        <el-table-column label="IP" min-width="130">
          <template #default="{ row }">{{ str(row, "login_ip", "loginIp", "ip") || "—" }}</template>
        </el-table-column>
        <el-table-column label="设备型号" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "device_model", "deviceModel") || "—" }}</template>
        </el-table-column>
        <el-table-column label="设备信息" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "device_info", "deviceInfo") || "—" }}</template>
        </el-table-column>
        <el-table-column label="客户端版本" width="110">
          <template #default="{ row }">{{ str(row, "client_version", "clientVersion") || "—" }}</template>
        </el-table-column>
        <el-table-column label="硬件 ID" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "hardware_id", "hardwareId") || "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ row.status || "—" }}</template>
        </el-table-column>
        <el-table-column label="当前" width="70">
          <template #default="{ row }">{{ (row.is_current ?? row.isCurrent) ? "是" : "否" }}</template>
        </el-table-column>
        <el-table-column label="登录时间" min-width="160">
          <template #default="{ row }">
            {{ formatTime(row.login_time ?? row.loginTime ?? row.login_time2 ?? row.loginTime2 ?? row.created_at) }}
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
