<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import {
  createClientVersion,
  deleteClientVersion,
  listClientVersions,
  updateClientVersion
} from "@/api/clientVersions";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const saving = ref(false);
const dialogVisible = ref(false);
const editingId = ref<string | number | null>(null);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: "",
  platform: "",
  enabled: "" as "" | "true" | "false"
});

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
  gray_percent: 100,
  published_at: null as Date | null
});

function resetForm() {
  Object.assign(form, {
    platform: "android",
    version: "",
    version_code: undefined,
    min_version: "",
    min_version_code: undefined,
    update_type: "optional",
    download_url: "",
    changelog: "",
    enabled: true,
    gray_percent: 100,
    published_at: null
  });
}

async function load() {
  loading.value = true;
  try {
    const raw = await listClientVersions({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      platform: query.platform || undefined,
      enabled: query.enabled || undefined
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

function openCreate() {
  if (!canWrite.value) {
    ElMessage.warning("无修改权限（需要 user.write）");
    return;
  }
  editingId.value = null;
  resetForm();
  dialogVisible.value = true;
}

async function openEdit(row: Record<string, unknown>) {
  if (!canWrite.value) {
    ElMessage.warning("无修改权限（需要 user.write）");
    return;
  }
  const id = row.id;
  if (id == null || id === "") {
    ElMessage.warning("缺少版本 ID，无法编辑");
    return;
  }
  editingId.value = id as string | number;
  form.platform = String(row.platform || "android").toLowerCase();
  form.version = String(row.version || "");
  form.version_code = Number(row.version_code ?? row.versionCode ?? 0) || undefined;
  form.min_version = str(row, "min_version", "minVersion");
  form.min_version_code = Number(row.min_version_code ?? row.minVersionCode ?? 0) || undefined;
  form.update_type = String(row.update_type ?? row.updateType ?? "optional").toLowerCase();
  form.download_url = str(row, "download_url", "downloadUrl");
  form.changelog = String(row.changelog || "");
  form.enabled = Boolean(row.enabled);
  form.gray_percent = Number(row.gray_percent ?? row.grayPercent ?? 100);
  {
    const raw = str(row, "published_at", "publishedAt");
    form.published_at = raw ? new Date(raw) : null;
  }
  dialogVisible.value = true;
}

async function submit() {
  if (!canWrite.value) {
    ElMessage.warning("无修改权限（需要 user.write）");
    return;
  }
  if (!form.platform || !form.version) {
    ElMessage.warning("请填写平台和版本号");
    return;
  }
  saving.value = true;
  const payload = {
    platform: form.platform,
    version: form.version,
    version_code: form.version_code,
    min_version: form.min_version || undefined,
    min_version_code: form.min_version_code,
    update_type: form.update_type,
    download_url: form.download_url || undefined,
    changelog: form.changelog || undefined,
    enabled: form.enabled,
    gray_percent: form.gray_percent,
    published_at: form.published_at ? form.published_at.toISOString() : undefined
  };
  try {
    if (editingId.value == null) await createClientVersion(payload);
    else await updateClientVersion(editingId.value, payload);
    ElMessage.success("已保存");
    dialogVisible.value = false;
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

async function remove(row: Record<string, unknown>) {
  const id = row.id;
  if (id == null) return;
  try {
    await ElMessageBox.confirm(`确认删除版本 ${row.platform}/${row.version}？`, "删除确认", { type: "warning" });
  } catch {
    return;
  }
  try {
    await deleteClientVersion(id as string | number);
    ElMessage.success("已删除");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="版本发布" subtitle="新建与修改客户端版本">
      <el-button v-if="canWrite" type="primary" @click="openCreate">新建版本</el-button>
    </PageHeader>
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.keyword" clearable placeholder="关键词" style="width: 180px" @keyup.enter="query.page=1; load()" />
        <el-select v-model="query.platform" clearable placeholder="平台" style="width: 120px">
          <el-option label="Android" value="android" />
          <el-option label="iOS" value="ios" />
          <el-option label="Web" value="web" />
        </el-select>
        <el-select v-model="query.enabled" clearable placeholder="启用" style="width: 110px">
          <el-option label="启用" value="true" />
          <el-option label="停用" value="false" />
        </el-select>
        <el-button type="primary" @click="query.page=1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="平台" width="90">
          <template #default="{ row }">{{ row.platform || "—" }}</template>
        </el-table-column>
        <el-table-column label="版本" width="110">
          <template #default="{ row }">{{ row.version || "—" }}</template>
        </el-table-column>
        <el-table-column label="版本号" width="90">
          <template #default="{ row }">{{ row.version_code ?? row.versionCode ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="最低版本" width="110">
          <template #default="{ row }">{{ str(row, "min_version", "minVersion") || "—" }}</template>
        </el-table-column>
        <el-table-column label="更新类型" width="100">
          <template #default="{ row }">{{ str(row, "update_type", "updateType") || "—" }}</template>
        </el-table-column>
        <el-table-column label="灰度%" width="80">
          <template #default="{ row }">{{ row.gray_percent ?? row.grayPercent ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? "是" : "否" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="下载" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "download_url", "downloadUrl") || "—" }}</template>
        </el-table-column>
        <el-table-column label="发布时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.published_at || row.publishedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="140" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link :disabled="!canWrite" @click="openEdit(row)">编辑</el-button>
            <el-button type="danger" link :disabled="!canWrite" @click="remove(row)">删除</el-button>
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

    <el-dialog v-model="dialogVisible" :title="editingId == null ? '新建版本' : '编辑版本'" width="600px">
      <el-form label-width="110px">
        <el-form-item label="平台" required>
          <el-select v-model="form.platform" style="width: 100%">
            <el-option label="Android" value="android" />
            <el-option label="iOS" value="ios" />
            <el-option label="Web" value="web" />
          </el-select>
        </el-form-item>
        <el-form-item label="版本" required><el-input v-model="form.version" placeholder="如 1.2.3" /></el-form-item>
        <el-form-item label="版本号"><el-input-number v-model="form.version_code" :controls="false" style="width: 100%" /></el-form-item>
        <el-form-item label="最低版本"><el-input v-model="form.min_version" /></el-form-item>
        <el-form-item label="最低版本号"><el-input-number v-model="form.min_version_code" :controls="false" style="width: 100%" /></el-form-item>
        <el-form-item label="更新类型">
          <el-select v-model="form.update_type" style="width: 100%">
            <el-option label="none" value="none" />
            <el-option label="optional" value="optional" />
            <el-option label="force" value="force" />
            <el-option label="gray" value="gray" />
          </el-select>
        </el-form-item>
        <el-form-item label="灰度%"><el-input-number v-model="form.gray_percent" :min="0" :max="100" :controls="false" style="width: 100%" /></el-form-item>
        <el-form-item label="下载地址"><el-input v-model="form.download_url" /></el-form-item>
        <el-form-item label="更新说明"><el-input v-model="form.changelog" type="textarea" :rows="3" /></el-form-item>
        <el-form-item label="发布时间">
          <el-date-picker v-model="form.published_at" type="datetime" style="width: 100%" />
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
