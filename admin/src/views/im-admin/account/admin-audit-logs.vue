<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  getAdminAuditLogsApi,
  type AdminAuditLogItem
} from "@/api/im-admin-logs";
import { displayIpRegion, resolveIpRegions } from "@/utils/ipRegion";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import {
  formatAuditAction,
  formatAuditDetailLines,
  formatAuditDetailSummary,
  formatResourceTarget
} from "./utils/auditDisplay";
import type { AuditDetailLine } from "./utils/auditDisplay";

defineOptions({ name: "ImAdminAuditLogsPage" });

const loading = ref(false);
const forbidden = ref(false);
const rows = ref<AdminAuditLogItem[]>([]);
const regionByIp = ref<Record<string, string>>({});
const detailOpen = ref(false);
const detailRow = ref<AdminAuditLogItem | null>(null);

const query = reactive({
  adminUserId: "",
  action: "",
  resourceType: "",
  resourceId: "",
  createdFrom: "",
  createdTo: ""
});

const page = reactive({ current: 1, size: 20, total: 0 });

const detailLines = ref<AuditDetailLine[]>([]);

const actionOptions = [
  { value: "client_version.create", label: "新增客户端版本" },
  { value: "client_version.update", label: "更新客户端版本" },
  { value: "client_version.delete", label: "删除客户端版本" },
  { value: "announcement.send", label: "发送公告" },
  { value: "announcement.schedule", label: "定时发送公告" },
  { value: "wallet.withdraw.approve", label: "通过提现" },
  { value: "wallet.withdraw.reject", label: "拒绝提现" },
  { value: "currency.update", label: "更新币种" },
  { value: "user.wallet.balance_adjust", label: "调整余额" },
  { value: "user.create", label: "创建用户" }
];

function openDetail(row: AdminAuditLogItem) {
  detailRow.value = row;
  detailLines.value = formatAuditDetailLines(row.action, row.detail, row);
  detailOpen.value = true;
}

async function loadData() {
  loading.value = true;
  forbidden.value = false;
  try {
    const res = await getAdminAuditLogsApi({
      page: page.current,
      page_size: page.size,
      sort: "created_at_desc",
      admin_user_id: query.adminUserId.trim() || undefined,
      action: query.action.trim() || undefined,
      resource_type: query.resourceType.trim() || undefined,
      resource_id: query.resourceId.trim() || undefined,
      created_from: query.createdFrom || undefined,
      created_to: query.createdTo || undefined
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
      message("无权限查看操作审计，请使用含 admin.manage 的账号重新登录", {
        type: "warning"
      });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "操作审计加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function resetQuery() {
  query.adminUserId = "";
  query.action = "";
  query.resourceType = "";
  query.resourceId = "";
  query.createdFrom = "";
  query.createdTo = "";
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
        <el-form-item label="操作者 ID">
          <el-input
            v-model="query.adminUserId"
            clearable
            class="w-36!"
            placeholder="管理员 ID"
          />
        </el-form-item>
        <el-form-item label="操作类型">
          <el-select
            v-model="query.action"
            clearable
            filterable
            allow-create
            default-first-option
            class="w-52!"
            placeholder="选择或输入"
          >
            <el-option
              v-for="opt in actionOptions"
              :key="opt.value"
              :label="opt.label"
              :value="opt.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="资源类型">
          <el-input v-model="query.resourceType" clearable class="w-36!" placeholder="如 user" />
        </el-form-item>
        <el-form-item label="资源标识">
          <el-input v-model="query.resourceId" clearable class="w-40!" placeholder="用户 ID 等" />
        </el-form-item>
        <el-form-item label="起始日期">
          <el-date-picker
            v-model="query.createdFrom"
            type="date"
            value-format="YYYY-MM-DD"
            placeholder="开始"
            class="w-40!"
          />
        </el-form-item>
        <el-form-item label="结束日期">
          <el-date-picker
            v-model="query.createdTo"
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
          <span>操作审计</span>
          <el-button :loading="loading" @click="loadData">刷新</el-button>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading" row-key="id">
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="操作者" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ row.admin_username || "—" }}</div>
            <div v-if="row.admin_user_id" class="text-xs text-gray-400">ID: {{ row.admin_user_id }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <div>{{ formatAuditAction(row.action) }}</div>
            <div v-if="row.action" class="text-xs text-gray-400">{{ row.action }}</div>
          </template>
        </el-table-column>
        <el-table-column label="操作对象" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            {{ formatResourceTarget(row) }}
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openDetail(row)">
              {{ formatAuditDetailSummary(row.action, row.detail, row.resource_id) }}
            </el-button>
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" width="140" show-overflow-tooltip />
        <el-table-column label="地区" min-width="100" show-overflow-tooltip>
          <template #default="{ row }">
            {{ displayIpRegion(row.ip, regionByIp, row.geo_address) }}
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="170">
          <template #default="{ row }">
            {{ formatAdminUnixTime(row.created_at, true) }}
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

    <el-dialog v-model="detailOpen" title="操作详情" width="560px">
      <el-descriptions v-if="detailRow" :column="1" border size="small">
        <el-descriptions-item label="操作者">
          {{ detailRow.admin_username || "—" }}
          <span v-if="detailRow.admin_user_id">（ID: {{ detailRow.admin_user_id }}）</span>
        </el-descriptions-item>
        <el-descriptions-item label="IP">{{ detailRow.ip || "—" }}</el-descriptions-item>
        <el-descriptions-item label="时间">
          {{ formatAdminUnixTime(detailRow.created_at, true) }}
        </el-descriptions-item>
        <el-descriptions-item
          v-for="line in detailLines"
          :key="line.label"
          :label="line.label"
        >
          {{ line.value }}
        </el-descriptions-item>
      </el-descriptions>
      <template #footer>
        <el-button @click="detailOpen = false">关闭</el-button>
      </template>
    </el-dialog>
  </div>
</template>
