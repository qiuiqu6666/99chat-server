<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { deviceDetection } from "@pureadmin/utils";
import {
  deleteStorageFile,
  getStorageFiles,
  type StorageFileItem
} from "@/api/im-storage";
import { adminApiErrMessage } from "@/api/im-user";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import { message } from "@/utils/message";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

defineOptions({ name: "PrivacyStoragePanel" });

const props = withDefaults(
  defineProps<{
    userUid?: string;
    embedded?: boolean;
  }>(),
  {
    userUid: "",
    embedded: false
  }
);

const loading = ref(false);
const apiUnsupported = ref(false);
const unsupportedHint = ref("");
const rows = ref<StorageFileItem[]>([]);
const page = reactive({ current: 1, size: 20, total: 0 });
const form = reactive({
  keyword: "",
  file_type: "",
  storage_status: ""
});

const fixedUserUid = computed(() => String(props.userUid ?? "").trim());
const tableHeight = computed(() => (deviceDetection() ? 420 : props.embedded ? 520 : 620));

function safeText(v: unknown) {
  if (v == null || v === "") return "—";
  return String(v);
}

function fmtTime(v: unknown) {
  return formatAdminUnixTime(v, true);
}

function fmtSize(v: unknown) {
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) return "—";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  if (n < 1024 * 1024 * 1024) return `${(n / 1024 / 1024).toFixed(2)} MB`;
  return `${(n / 1024 / 1024 / 1024).toFixed(2)} GB`;
}

function previewUrl(row: StorageFileItem) {
  return row.preview_url || row.url || row.download_url || "";
}

function thumbUrl(row: StorageFileItem) {
  return row.thumb_url || previewUrl(row);
}

function isImage(row: StorageFileItem) {
  const t = `${row.file_type ?? ""} ${row.file_name ?? ""}`.toLowerCase();
  return t.includes("image") || /\.(png|jpe?g|webp|gif|bmp)$/i.test(t);
}

function isVideo(row: StorageFileItem) {
  const t = `${row.file_type ?? ""} ${row.file_name ?? ""}`.toLowerCase();
  return t.includes("video") || /\.(mp4|mov|m4v|webm|avi)$/i.test(t);
}

async function fetchList() {
  loading.value = true;
  try {
    const res = await getStorageFiles({
      page: page.current,
      page_size: page.size,
      user_uid: fixedUserUid.value || undefined,
      keyword: form.keyword.trim() || undefined,
      file_type: form.file_type || undefined,
      storage_status: form.storage_status || undefined,
      sort: "created_at_desc"
    });
    rows.value = res.items;
    page.total = res.total;
    page.size = res.page_size || page.size;
    apiUnsupported.value = !!res.unsupported;
    unsupportedHint.value = res.compat_message || "文件管理功能尚未接入。";
  } catch (err: unknown) {
    rows.value = [];
    page.total = 0;
    apiUnsupported.value = false;
    unsupportedHint.value = "";
    message(adminApiErrMessage(err, "文件列表加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  form.keyword = "";
  form.file_type = "";
  form.storage_status = "";
  page.current = 1;
  void fetchList();
}

async function deleteFile(row: StorageFileItem) {
  const id = row.file_id || row.id;
  if (!id) {
    message("文件 ID 为空，无法删除", { type: "warning" });
    return;
  }
  await ElMessageBox.confirm("确认软删除该文件？", "操作确认", {
    type: "warning",
    confirmButtonText: "确认",
    cancelButtonText: "取消"
  });
  try {
    await deleteStorageFile(id);
    message("文件已标记删除", { type: "success" });
    await fetchList();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "删除文件失败"), { type: "warning" });
  }
}

useAdminRealtimeInvalidate(
  [ADMIN_REALTIME_EVENTS.FILE_UPDATED, ADMIN_REALTIME_EVENTS.MESSAGE_UPDATED],
  () => fetchList(),
  { debounceMs: 500, enabled: () => !!fixedUserUid.value || rows.value.length > 0 }
);

watch(
  () => props.userUid,
  () => {
    page.current = 1;
    void fetchList();
  }
);

onMounted(fetchList);
</script>

<template>
  <div :class="embedded ? '' : 'p-4'">
    <el-alert
      v-if="apiUnsupported"
      type="warning"
      :closable="false"
      show-icon
      class="mb-4"
      title="文件管理未接入"
      :description="unsupportedHint"
    />

    <el-card shadow="never" class="mb-4">
      <el-form :inline="true" :model="form" label-width="82px" :disabled="apiUnsupported">
        <el-form-item label="类型">
          <el-select v-model="form.file_type" class="w-40!" clearable placeholder="全部">
            <el-option label="聊天图片" value="chat_image" />
            <el-option label="聊天语音" value="chat_voice" />
            <el-option label="聊天视频" value="chat_video" />
            <el-option label="聊天文件" value="chat_file" />
            <el-option label="用户头像" value="avatar" />
            <el-option label="群头像" value="group_avatar" />
            <el-option label="公告图片" value="announcement" />
            <el-option label="系统资源" value="system" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="form.storage_status" class="w-34!" clearable placeholder="全部">
            <el-option label="正常" value="normal" />
            <el-option label="已删除" value="deleted" />
            <el-option label="异常" value="error" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="form.keyword" class="w-48!" clearable placeholder="文件名 / hash / ID" />
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
          <span>{{ embedded ? "上传文件" : "文件列表" }}</span>
          <el-button :loading="loading" @click="fetchList">刷新</el-button>
        </div>
      </template>

      <el-table
        v-if="!apiUnsupported"
        v-loading="loading"
        :data="rows"
        stripe
        border
        :height="tableHeight"
        size="small"
      >
        <el-table-column label="预览" width="96" fixed="left">
          <template #default="{ row }">
            <el-image
              v-if="isImage(row) && thumbUrl(row)"
              :src="thumbUrl(row)"
              :preview-src-list="[previewUrl(row) || thumbUrl(row)]"
              fit="cover"
              class="h-14 w-20 rounded"
              preview-teleported
            />
            <video
              v-else-if="isVideo(row) && previewUrl(row)"
              :src="previewUrl(row)"
              class="h-14 w-20 rounded object-cover"
              controls
              muted
            />
            <el-tag v-else size="small">{{ safeText(row.file_type) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="文件 ID" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.file_id) }}</template>
        </el-table-column>
        <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.file_name) }}</template>
        </el-table-column>
        <el-table-column label="类型" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.file_type) }}</template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ fmtSize(row.file_size) }}</template>
        </el-table-column>
        <el-table-column label="Hash" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.file_hash) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ safeText(row.storage_status) }}</template>
        </el-table-column>
        <el-table-column label="上传时间" min-width="170">
          <template #default="{ row }">{{ fmtTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="160">
          <template #default="{ row }">
            <el-button v-if="previewUrl(row)" link type="primary" tag="a" :href="previewUrl(row)" target="_blank">
              打开
            </el-button>
            <el-button link type="danger" @click="deleteFile(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-empty v-else description="后端尚未提供文件列表接口，接入后将在此展示用户上传文件。" />

      <div v-if="!apiUnsupported" class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :page-sizes="[20, 50, 100, 200]"
          layout="total, sizes, prev, pager, next, jumper"
          :total="page.total"
          @size-change="() => { page.current = 1; fetchList(); }"
          @current-change="fetchList"
        />
      </div>
    </el-card>
  </div>
</template>
