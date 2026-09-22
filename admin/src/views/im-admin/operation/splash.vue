<script setup lang="ts">
import dayjs from "dayjs";
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import type { UploadRequestOptions } from "element-plus";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  createSplash,
  deleteSplash,
  getSplashList,
  updateSplash,
  type SplashItem
} from "@/api/im-splash";

defineOptions({ name: "ImOperationSplash" });

const loading = ref(false);
const submitLoading = ref(false);
const rows = ref<SplashItem[]>([]);
const dialogOpen = ref(false);
const editing = ref<SplashItem | null>(null);
const uploadFile = ref<File | null>(null);
const previewUrl = ref("");

const form = reactive({
  enabled: true,
  fit: "cover",
  platforms: [] as string[],
  channels: "",
  min_app_version: "",
  start_at: "" as string,
  end_at: "" as string
});

const isCreate = computed(() => !editing.value);

function fmt(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

function fmtBytes(n?: number | null) {
  if (n == null || !Number.isFinite(n)) return "—";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / (1024 * 1024)).toFixed(2)} MB`;
}

function fmtSize(row: SplashItem) {
  if (row.width == null || row.height == null) return "—";
  return `${row.width}×${row.height}`;
}

function toIsoOrEmpty(local: string) {
  if (!local) return "";
  const d = dayjs(local);
  return d.isValid() ? d.toISOString() : "";
}

function fromIsoToLocal(iso?: string | null) {
  if (!iso) return "";
  const d = dayjs(iso);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : "";
}

function resetForm() {
  editing.value = null;
  uploadFile.value = null;
  if (previewUrl.value.startsWith("blob:")) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = "";
  form.enabled = true;
  form.fit = "cover";
  form.platforms = [];
  form.channels = "";
  form.min_app_version = "";
  form.start_at = "";
  form.end_at = "";
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getSplashList();
    rows.value = res.items;
  } catch (err: unknown) {
    rows.value = [];
    message(adminApiErrMessage(err, "启动图加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

function openCreate() {
  resetForm();
  dialogOpen.value = true;
}

function openEdit(row: SplashItem) {
  editing.value = row;
  uploadFile.value = null;
  if (previewUrl.value.startsWith("blob:")) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = row.image_url || "";
  form.enabled = row.enabled !== false;
  form.fit = row.fit || "cover";
  form.platforms = row.platforms
    ? row.platforms.split(",").map(s => s.trim()).filter(Boolean)
    : [];
  form.channels = row.channels || "";
  form.min_app_version = row.min_app_version || "";
  form.start_at = fromIsoToLocal(row.start_at);
  form.end_at = fromIsoToLocal(row.end_at);
  dialogOpen.value = true;
}

function beforeUpload(file: File) {
  const okType = ["image/jpeg", "image/png", "image/webp"].includes(file.type);
  if (!okType) {
    message("仅支持 PNG / JPG / WebP", { type: "warning" });
    return false;
  }
  if (file.size > 8 * 1024 * 1024) {
    message("原图请不超过 8MB", { type: "warning" });
    return false;
  }
  return true;
}

async function onPickFile(options: UploadRequestOptions) {
  const file = options.file as File;
  if (!beforeUpload(file)) {
    return Promise.reject(new Error("invalid"));
  }
  uploadFile.value = file;
  if (previewUrl.value.startsWith("blob:")) URL.revokeObjectURL(previewUrl.value);
  previewUrl.value = URL.createObjectURL(file);
  return Promise.resolve({});
}

async function submit() {
  if (isCreate.value && !uploadFile.value) {
    message("请先选择启动图", { type: "warning" });
    return;
  }
  submitLoading.value = true;
  try {
    const platforms = form.platforms.length ? form.platforms.join(",") : "";
    const channels = form.channels.trim();
    if (isCreate.value && uploadFile.value) {
      await createSplash({
        file: uploadFile.value,
        enabled: form.enabled,
        fit: form.fit,
        start_at: toIsoOrEmpty(form.start_at) || undefined,
        end_at: toIsoOrEmpty(form.end_at) || undefined,
        min_app_version: form.min_app_version.trim() || undefined,
        platforms: platforms || undefined,
        channels: channels || undefined
      });
      message("启动图已发布", { type: "success" });
    } else if (editing.value) {
      const clearStart = !form.start_at && !!editing.value.start_at;
      const clearEnd = !form.end_at && !!editing.value.end_at;
      await updateSplash(editing.value.id, {
        enabled: form.enabled,
        fit: form.fit,
        start_at: toIsoOrEmpty(form.start_at) || null,
        end_at: toIsoOrEmpty(form.end_at) || null,
        clear_start_at: clearStart || undefined,
        clear_end_at: clearEnd || undefined,
        min_app_version: form.min_app_version.trim(),
        platforms,
        channels
      });
      message("启动图已更新", { type: "success" });
    }
    dialogOpen.value = false;
    await loadData();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, isCreate.value ? "发布失败" : "更新失败"), {
      type: "warning"
    });
  } finally {
    submitLoading.value = false;
  }
}

async function toggleEnabled(row: SplashItem) {
  const next = !row.enabled;
  try {
    await updateSplash(row.id, { enabled: next });
    message(next ? "已启用" : "已关闭（客户端回退默认图）", { type: "success" });
    await loadData();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "状态更新失败"), { type: "warning" });
  }
}

async function remove(row: SplashItem) {
  await ElMessageBox.confirm(
    `确认删除启动图 ${row.version}？删除后 CDN 对象也会清理。`,
    "操作确认",
    { type: "warning" }
  );
  try {
    await deleteSplash(row.id);
    message("已删除", { type: "success" });
    await loadData();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "删除失败"), { type: "warning" });
  }
}

onMounted(loadData);
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <div class="text-sm text-gray-500 leading-6">
        App 冷启动后台拉取
        <code class="mx-1">GET /api/v1/platform/splash</code>
        ；关闭或过期后客户端回退包内默认图。建议竖屏 ≤1080×1920，服务端会转 WebP 并控制在 1MB 内。
      </div>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>启动图配置</span>
          <div>
            <el-button :loading="loading" @click="loadData">刷新</el-button>
            <el-button type="primary" @click="openCreate">上传启动图</el-button>
          </div>
        </div>
      </template>

      <el-table :data="rows" border stripe v-loading="loading" row-key="id">
        <el-table-column label="预览" width="88">
          <template #default="{ row }">
            <el-image
              v-if="row.image_url"
              :src="row.image_url"
              fit="cover"
              class="h-14 w-10 rounded"
              :preview-src-list="[row.image_url]"
              preview-teleported
            />
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="130" />
        <el-table-column label="尺寸" width="120">
          <template #default="{ row }">{{ fmtSize(row) }}</template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ fmtBytes(row.bytes) }}</template>
        </el-table-column>
        <el-table-column prop="fit" label="适配" width="90" />
        <el-table-column label="平台" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.platforms || "全部" }}</template>
        </el-table-column>
        <el-table-column label="渠道" width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.channels || "全部" }}</template>
        </el-table-column>
        <el-table-column label="最低版本" width="110">
          <template #default="{ row }">{{ row.min_app_version || "—" }}</template>
        </el-table-column>
        <el-table-column label="排期" min-width="200">
          <template #default="{ row }">
            <div class="text-xs leading-5">
              <div>起：{{ fmt(row.start_at) }}</div>
              <div>止：{{ fmt(row.end_at) }}</div>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">
              {{ row.enabled ? "启用" : "关闭" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="170">
          <template #default="{ row }">{{ fmt(row.updated_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openEdit(row)">编辑</el-button>
            <el-button link size="small" @click="toggleEnabled(row)">
              {{ row.enabled ? "关闭" : "启用" }}
            </el-button>
            <el-button link type="danger" size="small" @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog
      v-model="dialogOpen"
      :title="isCreate ? '上传启动图' : `编辑 ${editing?.version || ''}`"
      width="640px"
      destroy-on-close
      @closed="resetForm"
    >
      <el-form :model="form" label-width="110px">
        <el-form-item v-if="isCreate" label="图片" required>
          <div class="flex flex-col gap-2">
            <el-upload
              :show-file-list="false"
              accept="image/png,image/jpeg,image/webp"
              :http-request="onPickFile"
            >
              <el-button>选择 PNG / JPG / WebP</el-button>
            </el-upload>
            <el-image
              v-if="previewUrl"
              :src="previewUrl"
              fit="contain"
              class="h-48 w-28 rounded border border-gray-200 bg-gray-50"
            />
            <div v-if="uploadFile" class="text-xs text-gray-500">
              {{ uploadFile.name }} · {{ fmtBytes(uploadFile.size) }}
            </div>
          </div>
        </el-form-item>
        <el-form-item v-else label="预览">
          <el-image
            v-if="previewUrl"
            :src="previewUrl"
            fit="contain"
            class="h-48 w-28 rounded border border-gray-200 bg-gray-50"
            :preview-src-list="[previewUrl]"
            preview-teleported
          />
          <span v-else>—</span>
        </el-form-item>

        <el-form-item label="启用">
          <el-switch v-model="form.enabled" />
          <span class="ml-3 text-xs text-gray-400">关闭后客户端回退默认图</span>
        </el-form-item>
        <el-form-item label="适配方式">
          <el-select v-model="form.fit" class="w-full">
            <el-option label="cover（铺满裁剪）" value="cover" />
            <el-option label="contain（完整显示）" value="contain" />
            <el-option label="fill（拉伸）" value="fill" />
          </el-select>
        </el-form-item>
        <el-form-item label="平台">
          <el-select
            v-model="form.platforms"
            multiple
            clearable
            collapse-tags
            placeholder="空=全部平台"
            class="w-full"
          >
            <el-option label="iOS" value="ios" />
            <el-option label="Android" value="android" />
          </el-select>
        </el-form-item>
        <el-form-item label="渠道">
          <el-input
            v-model="form.channels"
            clearable
            placeholder="空=全部；多个用逗号，如 official,huawei"
          />
        </el-form-item>
        <el-form-item label="最低 App 版本">
          <el-input v-model="form.min_app_version" clearable placeholder="如 1.2.0，空=不限" />
        </el-form-item>
        <el-form-item label="开始时间">
          <el-date-picker
            v-model="form.start_at"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="可选"
            class="w-full!"
          />
        </el-form-item>
        <el-form-item label="结束时间">
          <el-date-picker
            v-model="form.end_at"
            type="datetime"
            value-format="YYYY-MM-DD HH:mm:ss"
            placeholder="可选"
            class="w-full!"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogOpen = false">取消</el-button>
        <el-button type="primary" :loading="submitLoading" @click="submit">
          {{ isCreate ? "上传并发布" : "保存" }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
