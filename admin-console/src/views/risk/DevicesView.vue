<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { banDevice, kickDevice, listDevices, unbanDevice } from "@/api/devices";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const route = useRoute();
const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const actionId = ref("");
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: "",
  user_uid: String(route.query.user_uid || ""),
  status: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listDevices({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      user_uid: query.user_uid || undefined,
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

function deviceId(row: Record<string, unknown>) {
  return str(row, "id", "device_id", "deviceId");
}

function isBanned(row: Record<string, unknown>) {
  return Boolean(row.is_banned ?? row.isBanned) || str(row, "status") === "banned";
}

async function withRemark(title: string) {
  try {
    const { value } = await ElMessageBox.prompt("可选备注", title, {
      inputPlaceholder: "备注",
      confirmButtonText: "确认",
      cancelButtonText: "取消"
    });
    return String(value || "").trim();
  } catch {
    return null;
  }
}

async function onBan(row: Record<string, unknown>) {
  const id = deviceId(row);
  if (!id || !canWrite.value) return;
  const remark = await withRemark("封禁设备");
  if (remark === null) return;
  actionId.value = id;
  try {
    await banDevice(id, { remark: remark || undefined });
    ElMessage.success("已封禁");
    await load();
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    actionId.value = "";
  }
}

async function onUnban(row: Record<string, unknown>) {
  const id = deviceId(row);
  if (!id || !canWrite.value) return;
  try {
    await ElMessageBox.confirm(`确认解封设备 ${id}？`, "解封");
  } catch {
    return;
  }
  actionId.value = id;
  try {
    await unbanDevice(id);
    ElMessage.success("已解封");
    await load();
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    actionId.value = "";
  }
}

async function onKick(row: Record<string, unknown>) {
  const id = deviceId(row);
  if (!id || !canWrite.value) return;
  try {
    await ElMessageBox.confirm(`确认踢下线设备 ${id}？`, "踢下线", { type: "warning" });
  } catch {
    return;
  }
  actionId.value = id;
  try {
    await kickDevice(id);
    ElMessage.success("已踢下线");
    await load();
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    actionId.value = "";
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
    <PageHeader title="设备终端" subtitle="查询、封禁、解封、踢下线" />
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
          placeholder="设备 ID / 型号 / IP"
          style="width: 220px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-select v-model="query.status" clearable placeholder="状态" style="width: 120px">
          <el-option label="正常" value="active" />
          <el-option label="封禁" value="banned" />
        </el-select>
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="ID" width="90">
          <template #default="{ row }">{{ deviceId(row) }}</template>
        </el-table-column>
        <el-table-column label="用户" min-width="140">
          <template #default="{ row }">
            <UserUidLink :uid="str(row, 'user_uid', 'userUid')" />
          </template>
        </el-table-column>
        <el-table-column label="昵称" min-width="100">
          <template #default="{ row }">{{ row.nickname || "—" }}</template>
        </el-table-column>
        <el-table-column label="型号" min-width="140">
          <template #default="{ row }">{{ str(row, "device_model", "deviceModel") || "—" }}</template>
        </el-table-column>
        <el-table-column label="系统" width="100">
          <template #default="{ row }">{{ str(row, "system_type", "systemType") || "—" }}</template>
        </el-table-column>
        <el-table-column label="版本" width="100">
          <template #default="{ row }">{{ str(row, "app_version", "appVersion") || "—" }}</template>
        </el-table-column>
        <el-table-column label="最近 IP" min-width="120">
          <template #default="{ row }">{{ str(row, "last_login_ip", "lastLoginIp") || "—" }}</template>
        </el-table-column>
        <el-table-column label="在线" width="70">
          <template #default="{ row }">
            {{ row.is_online ?? row.isOnline ? "是" : "否" }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag size="small" :type="isBanned(row) ? 'danger' : 'success'" effect="plain">
              {{ isBanned(row) ? "封禁" : str(row, "status") || "正常" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近活跃" min-width="160">
          <template #default="{ row }">
            {{ formatTime(row.last_seen_at || row.lastSeenAt || row.last_login_time || row.lastLoginTime) }}
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="warning"
              :loading="actionId === deviceId(row)"
              @click="onKick(row)"
            >
              踢下线
            </el-button>
            <el-button
              v-if="!isBanned(row)"
              link
              type="danger"
              :loading="actionId === deviceId(row)"
              @click="onBan(row)"
            >
              封禁
            </el-button>
            <el-button
              v-else
              link
              type="success"
              :loading="actionId === deviceId(row)"
              @click="onUnban(row)"
            >
              解封
            </el-button>
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
