<script setup lang="ts">
import dayjs from "dayjs";
import { onMounted, reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  getLifePaymentProviders,
  importLifePaymentProviders,
  setLifePaymentProviderEnabled,
  type LifePaymentProviderItem
} from "@/api/im-life-payments";

defineOptions({ name: "ImLifePaymentProviders" });

const loading = ref(false);
const importing = ref(false);
const dataList = ref<LifePaymentProviderItem[]>([]);
const importVisible = ref(false);
const importJson = ref(`[
  {
    "city_name": "北京",
    "city_code": "110100",
    "provider_name": "北京自来水集团"
  }
]`);
const importForm = reactive({
  service_type: "water",
  source: "alipay_capture"
});

const query = reactive({
  service_type: "",
  city_name: "",
  city_code: "",
  keyword: "",
  enabled: "" as "" | "true" | "false"
});

const pagination = reactive({
  total: 0,
  pageSize: 20,
  currentPage: 1
});

const serviceLabel: Record<string, string> = {
  water: "水费",
  electric: "电费",
  gas: "燃气费"
};

function formatTime(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getLifePaymentProviders({
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      service_type: query.service_type || undefined,
      city_name: query.city_name.trim() || undefined,
      city_code: query.city_code.trim() || undefined,
      keyword: query.keyword.trim() || undefined,
      enabled: query.enabled === "" ? undefined : query.enabled === "true"
    });
    dataList.value = res.items;
    pagination.total = res.total;
  } catch (err: unknown) {
    dataList.value = [];
    pagination.total = 0;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 wallet.read）", { type: "warning" });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "缴费单位加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function resetQuery() {
  query.service_type = "";
  query.city_name = "";
  query.city_code = "";
  query.keyword = "";
  query.enabled = "";
  pagination.currentPage = 1;
  loadData();
}

function onSizeChange(v: number) {
  pagination.pageSize = v;
  pagination.currentPage = 1;
  loadData();
}

function onCurrentChange(v: number) {
  pagination.currentPage = v;
  loadData();
}

async function toggleEnabled(row: LifePaymentProviderItem) {
  const next = !row.enabled;
  try {
    await ElMessageBox.confirm(
      `确认${next ? "启用" : "禁用"}缴费单位「${row.provider_name}」？`,
      "提示",
      { type: "warning" }
    );
    await setLifePaymentProviderEnabled(row.provider_code, next);
    message(next ? "已启用" : "已禁用", { type: "success" });
    await loadData();
  } catch (err: unknown) {
    if (err === "cancel" || err === "close") return;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 user.write）", { type: "warning" });
      return;
    }
    message(adminApiErrMessage(err, "操作失败"), { type: "warning" });
  }
}

async function submitImport() {
  let items: Array<{
    city_name: string;
    city_code?: string;
    provider_name: string;
    provider_code?: string;
    province_name?: string;
  }>;
  try {
    const parsed = JSON.parse(importJson.value);
    items = Array.isArray(parsed) ? parsed : parsed.items;
    if (!Array.isArray(items) || !items.length) {
      message("请提供非空 items 数组", { type: "warning" });
      return;
    }
  } catch {
    message("JSON 格式错误", { type: "warning" });
    return;
  }
  importing.value = true;
  try {
    const res = await importLifePaymentProviders({
      service_type: importForm.service_type,
      source: importForm.source || "alipay_capture",
      items
    });
    const data = (res || {}) as Record<string, unknown>;
    message(
      `导入完成：新增 ${data.inserted ?? 0}，更新 ${data.updated ?? 0}，跳过 ${data.skipped ?? 0}`,
      { type: "success" }
    );
    importVisible.value = false;
    await loadData();
  } catch (err: unknown) {
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 user.write）", { type: "warning" });
    } else {
      message(adminApiErrMessage(err, "导入失败"), { type: "warning" });
    }
  } finally {
    importing.value = false;
  }
}

onMounted(() => loadData());
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="业务">
          <el-select v-model="query.service_type" clearable placeholder="全部" class="w-36!">
            <el-option label="水费" value="water" />
            <el-option label="电费" value="electric" />
            <el-option label="燃气费" value="gas" />
          </el-select>
        </el-form-item>
        <el-form-item label="城市">
          <el-input v-model="query.city_name" clearable class="w-36!" placeholder="城市名" />
        </el-form-item>
        <el-form-item label="城市码">
          <el-input v-model="query.city_code" clearable class="w-36!" placeholder="city_code" />
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" clearable class="w-44!" placeholder="单位名/编码" />
        </el-form-item>
        <el-form-item label="启用">
          <el-select v-model="query.enabled" clearable placeholder="全部" class="w-28!">
            <el-option label="启用" value="true" />
            <el-option label="禁用" value="false" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="pagination.currentPage = 1; loadData();">
            查询
          </el-button>
          <el-button @click="resetQuery">重置</el-button>
          <el-button type="success" @click="importVisible = true">导入</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="dataList" border stripe v-loading="loading" row-key="provider_code">
        <el-table-column prop="provider_code" label="编码" min-width="160" show-overflow-tooltip />
        <el-table-column label="业务" width="90">
          <template #default="{ row }">
            {{ serviceLabel[row.service_type || ""] || row.service_type || "—" }}
          </template>
        </el-table-column>
        <el-table-column prop="city_name" label="城市" width="100" show-overflow-tooltip />
        <el-table-column prop="city_code" label="城市码" width="100" show-overflow-tooltip>
          <template #default="{ row }">{{ row.city_code || "—" }}</template>
        </el-table-column>
        <el-table-column prop="provider_name" label="缴费单位" min-width="180" show-overflow-tooltip />
        <el-table-column prop="source" label="来源" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.source || "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">
              {{ row.enabled ? "启用" : "禁用" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="最近采集" min-width="168">
          <template #default="{ row }">{{ formatTime(row.last_captured_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link :type="row.enabled ? 'warning' : 'success'" size="small" @click="toggleEnabled(row)">
              {{ row.enabled ? "禁用" : "启用" }}
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-3 flex justify-end">
        <el-pagination
          v-model:current-page="pagination.currentPage"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="onSizeChange"
          @current-change="onCurrentChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="importVisible" title="导入缴费单位" width="640px">
      <el-form label-width="90px">
        <el-form-item label="业务类型">
          <el-select v-model="importForm.service_type" class="w-48!">
            <el-option label="水费" value="water" />
            <el-option label="电费" value="electric" />
            <el-option label="燃气费" value="gas" />
          </el-select>
        </el-form-item>
        <el-form-item label="来源">
          <el-input v-model="importForm.source" class="w-64!" />
        </el-form-item>
        <el-form-item label="JSON">
          <el-input v-model="importJson" type="textarea" :rows="12" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="importVisible = false">取消</el-button>
        <el-button type="primary" :loading="importing" @click="submitImport">导入</el-button>
      </template>
    </el-dialog>
  </div>
</template>
