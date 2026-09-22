<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { createSplash, deleteSplash, listSplash, updateSplash } from "@/api/splash";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const saving = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const createVisible = ref(false);
const editVisible = ref(false);

const createForm = reactive({
  file: null as File | null,
  enabled: true,
  fit: "cover",
  start_at: null as Date | null,
  end_at: null as Date | null,
  min_app_version: "",
  platforms: "",
  channels: ""
});

const editForm = reactive({
  id: "" as string,
  enabled: true,
  fit: "cover",
  start_at: null as Date | null,
  end_at: null as Date | null,
  clear_start_at: false,
  clear_end_at: false,
  min_app_version: "",
  platforms: "",
  channels: ""
});

async function load() {
  loading.value = true;
  try {
    rows.value = pickList(await listSplash()).items;
  } catch (e) {
    rows.value = [];
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

function onPickFile(file: File) {
  createForm.file = file;
  return false;
}

async function submitCreate() {
  if (!createForm.file) {
    ElMessage.warning("请先选择图片");
    return;
  }
  saving.value = true;
  try {
    await createSplash(createForm.file, {
      enabled: createForm.enabled,
      fit: createForm.fit || undefined,
      start_at: createForm.start_at ? createForm.start_at.toISOString() : undefined,
      end_at: createForm.end_at ? createForm.end_at.toISOString() : undefined,
      min_app_version: createForm.min_app_version || undefined,
      platforms: createForm.platforms || undefined,
      channels: createForm.channels || undefined
    });
    ElMessage.success("已上传");
    createVisible.value = false;
    createForm.file = null;
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

function openEdit(row: Record<string, unknown>) {
  editForm.id = String(row.id || "");
  editForm.enabled = Boolean(row.enabled);
  editForm.fit = String(row.fit || "cover");
  {
    const s = str(row, "start_at", "startAt");
    const e = str(row, "end_at", "endAt");
    editForm.start_at = s ? new Date(s) : null;
    editForm.end_at = e ? new Date(e) : null;
  }
  editForm.clear_start_at = false;
  editForm.clear_end_at = false;
  editForm.min_app_version = str(row, "min_app_version", "minAppVersion");
  editForm.platforms = String(row.platforms || "");
  editForm.channels = String(row.channels || "");
  editVisible.value = true;
}

async function submitEdit() {
  saving.value = true;
  try {
    await updateSplash(editForm.id, {
      enabled: editForm.enabled,
      fit: editForm.fit || undefined,
      start_at: editForm.clear_start_at
        ? undefined
        : editForm.start_at
          ? editForm.start_at.toISOString()
          : undefined,
      end_at: editForm.clear_end_at
        ? undefined
        : editForm.end_at
          ? editForm.end_at.toISOString()
          : undefined,
      clear_start_at: editForm.clear_start_at || undefined,
      clear_end_at: editForm.clear_end_at || undefined,
      min_app_version: editForm.min_app_version || undefined,
      platforms: editForm.platforms || undefined,
      channels: editForm.channels || undefined
    });
    ElMessage.success("已保存");
    editVisible.value = false;
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
    await ElMessageBox.confirm(`确认删除启动图 #${id}？`, "删除确认", { type: "warning" });
  } catch {
    return;
  }
  try {
    await deleteSplash(id as string | number);
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
    <PageHeader title="启动图" />
    <div class="page-card">
      <div class="toolbar">
        <el-button @click="load">刷新</el-button>
        <el-button v-if="canWrite" type="primary" @click="createVisible = true">上传</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="预览" width="100">
          <template #default="{ row }">
            <img
              v-if="str(row, 'image_url', 'imageUrl')"
              :src="str(row, 'image_url', 'imageUrl')"
              style="width: 56px; height: 56px; object-fit: cover; border-radius: 6px"
            />
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="版本" width="100">
          <template #default="{ row }">{{ row.version || "—" }}</template>
        </el-table-column>
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? "是" : "否" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="适配" width="90">
          <template #default="{ row }">{{ row.fit || "—" }}</template>
        </el-table-column>
        <el-table-column label="尺寸" width="110">
          <template #default="{ row }">{{ (row.width || "—") + "×" + (row.height || "—") }}</template>
        </el-table-column>
        <el-table-column label="平台" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.platforms || "—" }}</template>
        </el-table-column>
        <el-table-column label="渠道" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.channels || "—" }}</template>
        </el-table-column>
        <el-table-column label="生效" min-width="180">
          <template #default="{ row }">
            {{ formatTime(row.start_at || row.startAt) }} ~ {{ formatTime(row.end_at || row.endAt) }}
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.updated_at || row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
            <el-button type="danger" link @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="createVisible" title="上传启动图" width="560px">
      <el-form label-width="110px">
        <el-form-item label="图片" required>
          <el-upload :show-file-list="!!createForm.file" :limit="1" accept="image/*" :before-upload="onPickFile">
            <el-button>选择文件</el-button>
            <template #tip>
              <div class="el-upload__tip">{{ createForm.file?.name || "未选择" }}</div>
            </template>
          </el-upload>
        </el-form-item>
        <el-form-item label="启用"><el-switch v-model="createForm.enabled" /></el-form-item>
        <el-form-item label="适配"><el-input v-model="createForm.fit" placeholder="cover / contain" /></el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="createForm.start_at" type="datetime" style="width:100%" />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="createForm.end_at" type="datetime" style="width:100%" />
        </el-form-item>
        <el-form-item label="最低版本"><el-input v-model="createForm.min_app_version" /></el-form-item>
        <el-form-item label="平台"><el-input v-model="createForm.platforms" placeholder="android,ios 等，逗号分隔" /></el-form-item>
        <el-form-item label="渠道"><el-input v-model="createForm.channels" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="createVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitCreate">上传</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="editVisible" title="编辑启动图" width="560px">
      <el-form label-width="110px">
        <el-form-item label="启用"><el-switch v-model="editForm.enabled" /></el-form-item>
        <el-form-item label="适配"><el-input v-model="editForm.fit" /></el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker v-model="editForm.start_at" type="datetime" style="width:100%" :disabled="editForm.clear_start_at" />
          <el-checkbox v-model="editForm.clear_start_at" style="margin-left:8px">清空开始时间</el-checkbox>
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker v-model="editForm.end_at" type="datetime" style="width:100%" :disabled="editForm.clear_end_at" />
          <el-checkbox v-model="editForm.clear_end_at" style="margin-left:8px">清空结束时间</el-checkbox>
        </el-form-item>
        <el-form-item label="最低版本"><el-input v-model="editForm.min_app_version" /></el-form-item>
        <el-form-item label="平台"><el-input v-model="editForm.platforms" /></el-form-item>
        <el-form-item label="渠道"><el-input v-model="editForm.channels" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
