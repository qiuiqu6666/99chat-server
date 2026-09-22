<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessageBox } from "element-plus";
import { deviceDetection } from "@pureadmin/utils";
import {
  displayDeviceModelCell
} from "@/utils/deviceModelDisplay";
import {
  banAdminDevice,
  getAdminDeviceSameUsers,
  getAdminDevices,
  kickAdminDevice,
  unbanAdminDevice,
  type AdminDeviceItem,
  isAdminDeviceActuallyOnline
} from "@/api/im-device";
import { adminApiErrMessage } from "@/api/im-user";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import { message } from "@/utils/message";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";
import { useIpRegionDisplay } from "@/composables/useIpRegionDisplay";

/** 设备管理：查询与轻量操作。客户端 deviceId / pushToken / heartbeat 上报接口保留，不在后台重复造客户端接口。 */
defineOptions({ name: "ImDeviceIndex" });

const router = useRouter();
const route = useRoute();
const loading = ref(false);
const sameUsersLoading = ref(false);
const sameUsersOpen = ref(false);
const currentDevice = ref<AdminDeviceItem | null>(null);
const sameUsers = ref<Record<string, unknown>[]>([]);

const form = reactive({
  user_uid: "",
  device_id: "",
  device_fingerprint: "",
  keyword: "",
  system_type: "",
  app_version: "",
  login_ip: "",
  status: ""
});

const page = reactive({ current: 1, size: 20, total: 0 });
const rows = ref<AdminDeviceItem[]>([]);
const { lookupFromRows, regionText } = useIpRegionDisplay();

const tableHeight = computed(() => (deviceDetection() ? 480 : 620));

function fmtTime(v: unknown) {
  return formatAdminUnixTime(v, true);
}

function deviceStatus(row: AdminDeviceItem) {
  if (row.is_banned) return { label: "已封禁", type: "danger" as const };
  if (isAdminDeviceActuallyOnline(row)) return { label: "在线", type: "success" as const };
  const raw = row.status == null || row.status === "" ? "正常" : String(row.status);
  return { label: raw, type: "info" as const };
}

function safeText(v: unknown) {
  if (v == null || v === "") return "—";
  return String(v);
}

async function fetchList() {
  loading.value = true;
  try {
    const res = await getAdminDevices({
      page: page.current,
      page_size: page.size,
      user_uid: form.user_uid.trim() || undefined,
      device_id: form.device_id.trim() || undefined,
      device_fingerprint: form.device_fingerprint.trim() || undefined,
      keyword: form.keyword.trim() || undefined,
      system_type: form.system_type.trim() || undefined,
      app_version: form.app_version.trim() || undefined,
      login_ip: form.login_ip.trim() || undefined,
      status: form.status || undefined,
      sort: "last_login_time_desc"
    });
    rows.value = res.items;
    page.total = res.total;
    page.size = res.page_size || page.size;
    void lookupFromRows(res.items.map(item => item.last_login_ip));
  } catch (err: unknown) {
    rows.value = [];
    page.total = 0;
    message(adminApiErrMessage(err, "设备列表加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  form.user_uid = "";
  form.device_id = "";
  form.device_fingerprint = "";
  form.keyword = "";
  form.system_type = "";
  form.app_version = "";
  form.login_ip = "";
  form.status = "";
  page.current = 1;
  void fetchList();
}

function openUser(uid: unknown) {
  const v = String(uid ?? "").trim();
  if (!v) return;
  router.push(`/im-admin/users/detail/${encodeURIComponent(v)}`);
}

async function openSameUsers(row: AdminDeviceItem) {
  currentDevice.value = row;
  sameUsersOpen.value = true;
  sameUsers.value = [];
  sameUsersLoading.value = true;
  try {
    const id = row.id || row.device_id || "";
    const res = await getAdminDeviceSameUsers(id, { page: 1, page_size: 50 });
    sameUsers.value = Array.isArray(res.items) ? res.items : [];
    void lookupFromRows(
      sameUsers.value.map(row =>
        String(row.latest_login_ip ?? row.last_login_ip ?? "")
      )
    );
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "同设备账号加载失败"), { type: "warning" });
  } finally {
    sameUsersLoading.value = false;
  }
}

async function runDeviceAction(row: AdminDeviceItem, action: "ban" | "unban" | "kick") {
  const actionText = action === "ban" ? "封禁设备" : action === "unban" ? "解除封禁" : "踢出设备登录";
  const id = row.id || row.device_id || "";
  if (!id) {
    message("设备 ID 为空，无法操作", { type: "warning" });
    return;
  }
  await ElMessageBox.confirm(`确认${actionText}？`, "操作确认", {
    type: action === "ban" ? "warning" : "info",
    confirmButtonText: "确认",
    cancelButtonText: "取消"
  });
  try {
    if (action === "ban") await banAdminDevice(id);
    else if (action === "unban") await unbanAdminDevice(id);
    else await kickAdminDevice(id);
    message(`${actionText}成功`, { type: "success" });
    await fetchList();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, `${actionText}失败`), { type: "warning" });
  }
}

useAdminRealtimeInvalidate(
  [
    ADMIN_REALTIME_EVENTS.DEVICE_UPDATED,
    ADMIN_REALTIME_EVENTS.DEVICE_ONLINE_CHANGED,
    ADMIN_REALTIME_EVENTS.DEVICE_BANNED,
    ADMIN_REALTIME_EVENTS.DEVICE_UNBANNED,
    ADMIN_REALTIME_EVENTS.DEVICE_KICKED,
    ADMIN_REALTIME_EVENTS.USER_STATUS_CHANGED
  ],
  () => fetchList(),
  { debounceMs: 120 }
);

onMounted(() => {
  const uid = String(route.query.user_uid ?? "").trim();
  if (uid) form.user_uid = uid;
  void fetchList();
});
</script>

<template>
  <div class="p-4">
    <el-alert
      class="mb-4"
      :closable="false"
      type="info"
      title="查询用户关联设备，支持封禁、解封、踢出登录及查看同设备账号。"
    />

    <el-card shadow="never" class="mb-4">
      <el-form :inline="true" :model="form" label-width="92px">
        <el-form-item label="IM 号">
          <el-input v-model="form.user_uid" class="w-44!" clearable placeholder="用户 IM 号" />
        </el-form-item>
        <el-form-item label="设备 ID">
          <el-input v-model="form.device_id" class="w-48!" clearable placeholder="deviceId" />
        </el-form-item>
        <el-form-item label="设备指纹">
          <el-input v-model="form.device_fingerprint" class="w-48!" clearable placeholder="fingerprint" />
        </el-form-item>
        <el-form-item label="系统">
          <el-select v-model="form.system_type" class="w-34!" clearable placeholder="全部">
            <el-option label="Android" value="Android" />
            <el-option label="iOS" value="iOS" />
            <el-option label="Web" value="Web" />
          </el-select>
        </el-form-item>
        <el-form-item label="App 版本">
          <el-input v-model="form.app_version" class="w-36!" clearable />
        </el-form-item>
        <el-form-item label="登录 IP">
          <el-input v-model="form.login_ip" class="w-42!" clearable />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.status" class="w-34!" clearable placeholder="全部">
            <el-option label="正常" value="normal" />
            <el-option label="在线" value="online" />
            <el-option label="封禁" value="banned" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="form.keyword" class="w-48!" clearable placeholder="设备名 / 型号" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="page.current = 1; fetchList()">查询</el-button>
          <el-button @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>设备列表</span>
          <el-button :loading="loading" @click="fetchList">刷新</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="rows" stripe border :height="tableHeight" size="small">
        <el-table-column label="UID" min-width="112" fixed="left" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button link type="primary" @click="openUser(row.user_uid)">{{ safeText(row.user_uid) }}</el-button>
          </template>
        </el-table-column>
        <el-table-column prop="nickname" label="昵称" min-width="110" show-overflow-tooltip />
        <el-table-column label="设备 ID" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.device_id ?? row.device_fingerprint) }}</template>
        </el-table-column>
        <el-table-column label="机型" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{
            displayDeviceModelCell(row.system_type, row.device_model)
          }}</template>
        </el-table-column>
        <el-table-column label="系统" width="120">
          <template #default="{ row }">{{ safeText(row.system_type) }} {{ safeText(row.system_version) }}</template>
        </el-table-column>
        <el-table-column label="App" width="96">
          <template #default="{ row }">{{ safeText(row.app_version) }}</template>
        </el-table-column>
        <el-table-column label="最近 IP" width="146" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.last_login_ip) }}</template>
        </el-table-column>
        <el-table-column label="地区" width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ regionText(row.last_login_ip, row.last_login_region) }}
          </template>
        </el-table-column>
        <el-table-column label="最近登录" min-width="170">
          <template #default="{ row }">{{ fmtTime(row.last_login_time) }}</template>
        </el-table-column>
        <el-table-column label="Push Token" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.push_token_masked) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="92">
          <template #default="{ row }">
            <el-tag :type="deviceStatus(row).type">{{ deviceStatus(row).label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openSameUsers(row)">同设备账号</el-button>
            <el-button v-if="!row.is_banned" link type="danger" @click="runDeviceAction(row, 'ban')">封禁</el-button>
            <el-button v-else link type="success" @click="runDeviceAction(row, 'unban')">解封</el-button>
            <el-button link type="warning" @click="runDeviceAction(row, 'kick')">踢出</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-3 flex justify-end">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          background
          layout="total, sizes, prev, pager, next"
          :total="page.total"
          :page-sizes="[10, 20, 50, 100]"
          @size-change="fetchList"
          @current-change="fetchList"
        />
      </div>
    </el-card>

    <el-drawer v-model="sameUsersOpen" size="720px" title="同设备账号">
      <el-alert
        class="mb-3"
        :closable="false"
        type="info"
        :title="`设备：${currentDevice?.device_id || currentDevice?.id || '—'}${currentDevice?.device_model ? ` · ${currentDevice.device_model}` : ''}`"
      />
      <el-table v-loading="sameUsersLoading" :data="sameUsers" stripe border size="small">
        <el-table-column label="UID" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="openUser(row.user_uid ?? row.userUid ?? row.uid)">
              {{ safeText(row.user_uid ?? row.userUid ?? row.uid) }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column label="昵称" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.nickname ?? row.user_nickname) }}</template>
        </el-table-column>
        <el-table-column label="手机号" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.phone_num ?? row.phone) }}</template>
        </el-table-column>
        <el-table-column label="最近 IP" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.latest_login_ip ?? row.last_login_ip) }}</template>
        </el-table-column>
        <el-table-column label="地区" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">
            {{ regionText(row.latest_login_ip ?? row.last_login_ip) }}
          </template>
        </el-table-column>
        <el-table-column label="最近登录" min-width="170">
          <template #default="{ row }">{{ fmtTime(row.latest_login_time ?? row.last_login_time) }}</template>
        </el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>
