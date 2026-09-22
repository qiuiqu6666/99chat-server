<script setup lang="ts">
import dayjs from "dayjs";
import { onMounted, reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  createClientVersion,
  deleteClientVersion,
  getClientVersions,
  updateClientVersion,
  type ClientVersionItem
} from "@/api/im-client-version";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

defineOptions({ name: "ImSysopsClientVersion" });

const loading = ref(false);
const submitLoading = ref(false);
const rows = ref<ClientVersionItem[]>([]);
const dialogOpen = ref(false);
const editing = ref<ClientVersionItem | null>(null);

const query = reactive({ platform: "", keyword: "", enabled: "" });
const page = reactive({ current: 1, size: 20, total: 0 });
const form = reactive({
  platform: "android",
  version: "",
  version_code: undefined as number | undefined,
  min_version: "",
  min_version_code: undefined as number | undefined,
  update_type: "optional",
  download_url: "",
  changelog: "",
  enabled: true,
  gray_percent: 100
});

function fmt(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

function resetForm() {
  editing.value = null;
  form.platform = "android";
  form.version = "";
  form.version_code = undefined;
  form.min_version = "";
  form.min_version_code = undefined;
  form.update_type = "optional";
  form.download_url = "";
  form.changelog = "";
  form.enabled = true;
  form.gray_percent = 100;
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getClientVersions({
      page: page.current,
      page_size: page.size,
      platform: query.platform || undefined,
      keyword: query.keyword.trim() || undefined,
      enabled: query.enabled || undefined
    });
    rows.value = res.items;
    page.total = res.total;
  } catch (err: unknown) {
    rows.value = [];
    page.total = 0;
    message(adminApiErrMessage(err, "客户端版本加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  resetForm();
  dialogOpen.value = true;
}

function openEdit(row: ClientVersionItem) {
  editing.value = row;
  form.platform = row.platform || "android";
  form.version = row.version || "";
  form.version_code = row.version_code ?? undefined;
  form.min_version = row.min_version || "";
  form.min_version_code = row.min_version_code ?? undefined;
  form.update_type = row.update_type || "optional";
  form.download_url = row.download_url || "";
  form.changelog = row.changelog || "";
  form.enabled = row.enabled !== false;
  form.gray_percent = row.gray_percent ?? 100;
  dialogOpen.value = true;
}

async function submit() {
  if (!form.platform || !form.version) {
    message("平台和版本号必填", { type: "warning" });
    return;
  }
  submitLoading.value = true;
  try {
    const payload = { ...form };
    if (editing.value) await updateClientVersion(editing.value.id, payload);
    else await createClientVersion(payload);
    message(editing.value ? "版本已更新" : "版本已新增", { type: "success" });
    dialogOpen.value = false;
    await loadData();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, editing.value ? "更新失败" : "新增失败"), { type: "warning" });
  } finally {
    submitLoading.value = false;
  }
}

async function remove(row: ClientVersionItem) {
  await ElMessageBox.confirm(`确认删除版本 ${row.platform} ${row.version}？`, "操作确认", { type: "warning" });
  try {
    await deleteClientVersion(row.id);
    message("已删除", { type: "success" });
    await loadData();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "删除失败"), { type: "warning" });
  }
}

function resetQuery() {
  query.platform = "";
  query.keyword = "";
  query.enabled = "";
  page.current = 1;
  loadData();
}

useAdminRealtimeInvalidate([ADMIN_REALTIME_EVENTS.VERSION_UPDATED], () => loadData(), { debounceMs: 180 });
onMounted(loadData);
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="平台">
          <el-select v-model="query.platform" clearable placeholder="全部" class="w-36!">
            <el-option label="Android" value="android" />
            <el-option label="iOS" value="ios" />
            <el-option label="Web" value="web" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" clearable class="w-52!" placeholder="版本号 / 说明" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.enabled" clearable placeholder="全部" class="w-32!">
            <el-option label="启用" value="1" />
            <el-option label="禁用" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="page.current = 1; loadData();">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>客户端版本</span>
          <div>
            <el-button :loading="loading" @click="loadData">刷新</el-button>
            <el-button type="primary" @click="openCreate">新增版本</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading" row-key="id">
        <el-table-column prop="platform" label="平台" width="100" />
        <el-table-column prop="version" label="版本号" width="120" />
        <el-table-column prop="version_code" label="Version Code" width="130" />
        <el-table-column prop="min_version" label="最低版本" width="120" />
        <el-table-column prop="min_version_code" label="最低 Code" width="120" />
        <el-table-column prop="update_type" label="更新类型" width="120" />
        <el-table-column prop="gray_percent" label="灰度比例" width="100">
          <template #default="{ row }">{{ row.gray_percent ?? 100 }}%</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled === false ? 'info' : 'success'" effect="plain">
              {{ row.enabled === false ? '禁用' : '启用' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="download_url" label="下载地址" min-width="220" show-overflow-tooltip />
        <el-table-column prop="changelog" label="更新说明" min-width="220" show-overflow-tooltip />
        <el-table-column label="发布时间" min-width="170">
          <template #default="{ row }">{{ fmt(row.published_at || row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="130" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
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

    <el-dialog v-model="dialogOpen" :title="editing ? '编辑版本' : '新增版本'" width="680px">
      <el-form :model="form" label-width="110px">
        <el-form-item label="平台" required>
          <el-select v-model="form.platform" class="w-full">
            <el-option label="Android" value="android" />
            <el-option label="iOS" value="ios" />
            <el-option label="Web" value="web" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本号" required><el-input v-model="form.version" /></el-form-item>
        <el-form-item label="Version Code"><el-input-number v-model="form.version_code" :min="0" class="w-full!" /></el-form-item>
        <el-form-item label="最低版本"><el-input v-model="form.min_version" /></el-form-item>
        <el-form-item label="最低 Code"><el-input-number v-model="form.min_version_code" :min="0" class="w-full!" /></el-form-item>
        <el-form-item label="更新类型">
          <el-select v-model="form.update_type" class="w-full">
            <el-option label="不更新" value="none" />
            <el-option label="可选更新" value="optional" />
            <el-option label="强制更新" value="force" />
            <el-option label="灰度更新" value="gray" />
          </el-select>
        </el-form-item>
        <el-form-item label="灰度比例"><el-input-number v-model="form.gray_percent" :min="0" :max="100" class="w-full!" /></el-form-item>
        <el-form-item label="下载地址"><el-input v-model="form.download_url" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="更新说明"><el-input v-model="form.changelog" type="textarea" :rows="4" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
