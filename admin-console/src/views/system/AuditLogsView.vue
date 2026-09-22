<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { listAuditLogs } from "@/api/adminLogs";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const detailVisible = ref(false);
const detailText = ref("");
const query = reactive({
  page: 1,
  page_size: 50,
  admin_user_id: "",
  action: "",
  resource_type: "",
  resource_id: "",
  created_from: null as Date | null,
  created_to: null as Date | null
});

async function load() {
  loading.value = true;
  try {
    const raw = await listAuditLogs({
      page: query.page,
      page_size: query.page_size,
      admin_user_id: query.admin_user_id || undefined,
      action: query.action || undefined,
      resource_type: query.resource_type || undefined,
      resource_id: query.resource_id || undefined,
      created_from: query.created_from ? query.created_from.toISOString() : undefined,
      created_to: query.created_to ? query.created_to.toISOString() : undefined
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

function showDetail(row: Record<string, unknown>) {
  const d = row.detail;
  detailText.value =
    typeof d === "string" ? d : JSON.stringify(d ?? {}, null, 2);
  detailVisible.value = true;
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="操作审计" subtitle="需 admin.manage" />
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.action" clearable placeholder="动作 action" style="width: 160px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.admin_user_id" clearable placeholder="管理员 ID" style="width: 140px" />
        <el-input v-model="query.resource_type" clearable placeholder="资源类型" style="width: 140px" />
        <el-input v-model="query.resource_id" clearable placeholder="资源 ID" style="width: 140px" />
        <el-date-picker v-model="query.created_from" type="datetime" placeholder="开始时间" />
        <el-date-picker v-model="query.created_to" type="datetime" placeholder="结束时间" />
        <el-button type="primary" @click="query.page=1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="ID" width="80">
          <template #default="{ row }">{{ row.id }}</template>
        </el-table-column>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.created_at ?? row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="管理员" min-width="120">
          <template #default="{ row }">
            {{ str(row, "admin_username", "adminUsername") || str(row, "admin_user_id", "adminUserId") || "—" }}
          </template>
        </el-table-column>
        <el-table-column label="动作" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.action || "—" }}</template>
        </el-table-column>
        <el-table-column label="资源类型" width="120">
          <template #default="{ row }">{{ str(row, "resource_type", "resourceType") || "—" }}</template>
        </el-table-column>
        <el-table-column label="资源 ID" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "resource_id", "resourceId") || "—" }}</template>
        </el-table-column>
        <el-table-column label="IP" min-width="120">
          <template #default="{ row }">{{ row.ip || "—" }}</template>
        </el-table-column>
        <el-table-column label="地区" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "geo_address", "geoAddress") || "—" }}</template>
        </el-table-column>
        <el-table-column label="详情" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="showDetail(row)">查看</el-button>
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
    <el-dialog v-model="detailVisible" title="审计详情" width="640px">
      <pre style="margin: 0; white-space: pre-wrap">{{ detailText }}</pre>
    </el-dialog>
  </div>
</template>
