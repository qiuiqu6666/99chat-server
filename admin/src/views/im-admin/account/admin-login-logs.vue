<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  getAdminLoginLogsApi,
  type AdminLoginLogItem
} from "@/api/im-admin-logs";
import { displayIpRegion, resolveIpRegions } from "@/utils/ipRegion";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";

defineOptions({ name: "ImAdminLoginLogsPage" });

const loading = ref(false);
const forbidden = ref(false);
const rows = ref<AdminLoginLogItem[]>([]);
const regionByIp = ref<Record<string, string>>({});

const query = reactive({
  adminUserId: "",
  username: "",
  success: "",
  ip: "",
  loginAtFrom: "",
  loginAtTo: ""
});

const page = reactive({ current: 1, size: 20, total: 0 });

function isSuccess(row: AdminLoginLogItem) {
  return row.success === 1 || row.success === true || row.success === "1";
}

function failReasonText(reason?: string | null) {
  if (!reason) return "—";
  const map: Record<string, string> = {
    invalid_credentials: "用户名或密码错误"
  };
  return map[reason] || reason;
}

async function loadData() {
  loading.value = true;
  forbidden.value = false;
  try {
    const res = await getAdminLoginLogsApi({
      page: page.current,
      page_size: page.size,
      sort: "login_at_desc",
      admin_user_id: query.adminUserId.trim() || undefined,
      username: query.username.trim() || undefined,
      success: query.success || undefined,
      ip: query.ip.trim() || undefined,
      login_at_from: query.loginAtFrom || undefined,
      login_at_to: query.loginAtTo || undefined
    });
    rows.value = res.items;
    page.total = res.total;
    regionByIp.value = await resolveIpRegions(
      rows.value.map(r => r.ip as string | null | undefined)
    );
  } catch (err: unknown) {
    rows.value = [];
    page.total = 0;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      forbidden.value = true;
      message("无权限查看登录流水，请使用含 admin.manage 的账号重新登录", {
        type: "warning"
      });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "管理员登录流水加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function resetQuery() {
  query.adminUserId = "";
  query.username = "";
  query.success = "";
  query.ip = "";
  query.loginAtFrom = "";
  query.loginAtTo = "";
  page.current = 1;
  loadData();
}

onMounted(loadData);
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-alert
      v-if="forbidden"
      type="warning"
      :closable="false"
      title="当前登录态无 admin.manage 权限；退出后重新登录 admin 账号即可查看。"
    />

    <el-card shadow="never">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="管理员 ID">
          <el-input
            v-model="query.adminUserId"
            clearable
            class="w-36!"
            placeholder="管理员 ID"
          />
        </el-form-item>
        <el-form-item label="尝试用户名">
          <el-input v-model="query.username" clearable class="w-40!" placeholder="模糊匹配" />
        </el-form-item>
        <el-form-item label="结果">
          <el-select v-model="query.success" clearable placeholder="全部" class="w-32!">
            <el-option label="成功" value="1" />
            <el-option label="失败" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="IP">
          <el-input v-model="query.ip" clearable class="w-40!" placeholder="精确匹配" />
        </el-form-item>
        <el-form-item label="起始日期">
          <el-date-picker
            v-model="query.loginAtFrom"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="开始"
            class="w-40!"
          />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker
            v-model="query.loginAtTo"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="结束"
            class="w-40!"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="page.current = 1; loadData();">
            查询
          </el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>管理员登录流水</span>
          <el-button :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading" row-key="id">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="管理员" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ row.admin_username || row.username_attempted || "—" }}</div>
            <div v-if="row.admin_user_id" class="text-xs text-gray-400">ID: {{ row.admin_user_id }}</div>
          </template>
        </el-table-column>
        <el-table-column prop="username_attempted" label="尝试用户名" width="130" show-overflow-tooltip />
        <el-table-column label="结果" width="90">
          <template #default="{ row }">
            <el-tag :type="isSuccess(row) ? 'success' : 'danger'" effect="plain">
              {{ isSuccess(row) ? "成功" : "失败" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140" show-overflow-tooltip />
        <el-table-column label="地区" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">
            {{ displayIpRegion(row.ip, regionByIp, row.geo_address) }}
          </template>
        </el-table-column>
        <el-table-column prop="user_agent" label="User-Agent" min-width="180" show-overflow-tooltip />
        <el-table-column label="失败原因" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ isSuccess(row) ? "—" : failReasonText(row.fail_reason) }}
          </template>
        </el-table-column>
        <el-table-column label="登录时间" min-width="170">
          <template #default="{ row }">
            {{ formatAdminUnixTime(row.login_at, true) }}
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-3 flex justify-end">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :total="page.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="loadData"
          @current-change="loadData"
        />
      </div>
    </el-card>
  </div>
</template>
