<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { listProviders, setProviderEnabled } from "@/api/lifePayments";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const actionLoading = ref("");
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: "",
  service_type: "",
  city_name: "",
  enabled: "" as "" | "true" | "false"
});

async function load() {
  loading.value = true;
  try {
    const raw = await listProviders({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      service_type: query.service_type || undefined,
      city_name: query.city_name || undefined,
      enabled: query.enabled === "" ? undefined : query.enabled === "true"
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

async function toggle(row: Record<string, unknown>, enabled: boolean) {
  const code = str(row, "provider_code", "providerCode");
  if (!code) return;
  try {
    await ElMessageBox.confirm(
      `确认${enabled ? "启用" : "停用"}供应商 ${code}？`,
      "供应商状态",
      { type: "warning" }
    );
  } catch {
    return;
  }
  actionLoading.value = code;
  try {
    await setProviderEnabled(code, { enabled });
    ElMessage.success(enabled ? "已启用" : "已停用");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = "";
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="缴费供应商" />
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.keyword" clearable placeholder="关键词" style="width: 160px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.service_type" clearable placeholder="服务类型" style="width: 140px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.city_name" clearable placeholder="城市" style="width: 140px" @keyup.enter="query.page=1; load()" />
        <el-select v-model="query.enabled" clearable placeholder="启用状态" style="width: 120px">
          <el-option label="启用" value="true" />
          <el-option label="停用" value="false" />
        </el-select>
        <el-button type="primary" @click="query.page=1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="编码" min-width="140">
          <template #default="{ row }">{{ str(row, "provider_code", "providerCode") || "—" }}</template>
        </el-table-column>
        <el-table-column label="名称" min-width="140">
          <template #default="{ row }">{{ str(row, "provider_name", "providerName") || "—" }}</template>
        </el-table-column>
        <el-table-column label="服务" width="110">
          <template #default="{ row }">{{ str(row, "service_type", "serviceType") || "—" }}</template>
        </el-table-column>
        <el-table-column label="省份" width="100">
          <template #default="{ row }">{{ str(row, "province_name", "provinceName") || "—" }}</template>
        </el-table-column>
        <el-table-column label="城市" width="100">
          <template #default="{ row }">{{ str(row, "city_name", "cityName") || "—" }}</template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">{{ row.source || "—" }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? "是" : "否" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.updated_at || row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="!row.enabled"
              type="primary"
              link
              :loading="actionLoading === str(row, 'provider_code', 'providerCode')"
              @click="toggle(row, true)"
            >启用</el-button>
            <el-button
              v-else
              type="danger"
              link
              :loading="actionLoading === str(row, 'provider_code', 'providerCode')"
              @click="toggle(row, false)"
            >停用</el-button>
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
