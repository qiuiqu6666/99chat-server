<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { ElMessageBox } from "element-plus";
import { deviceDetection } from "@pureadmin/utils";
import {
  deleteUserAlbumFile,
  getUserPhoneAlbumList,
  type UserPhoneAlbumFile
} from "@/api/im-phone-album";
import { adminApiErrMessage } from "@/api/im-user";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import { message } from "@/utils/message";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

defineOptions({ name: "PrivacyAlbumPanel" });

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
const rows = ref<UserPhoneAlbumFile[]>([]);
const page = reactive({ current: 1, size: 24, total: 0 });
const form = reactive({ file_type: "", keyword: "" });

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

function mediaUrl(row: UserPhoneAlbumFile) {
  return row.previewUrl || row.oss_url || row.url || row.local_url || "";
}

function thumbUrl(row: UserPhoneAlbumFile) {
  return row.thumbUrl || row.oss_thumb_url || mediaUrl(row);
}

function isVideo(row: UserPhoneAlbumFile) {
  const t = String(row.file_type ?? row.file_name ?? "").toLowerCase();
  return t.includes("video") || /\.(mp4|mov|m4v|webm|avi)$/i.test(t);
}

function fileId(row: UserPhoneAlbumFile) {
  return String(row.album_id ?? row.id ?? "").trim();
}

async function fetchList() {
  loading.value = true;
  try {
    const res = await getUserPhoneAlbumList({
      page: page.current,
      page_size: page.size,
      user_uid: fixedUserUid.value || undefined,
      file_type: form.file_type || undefined,
      keyword: form.keyword.trim() || undefined,
      sort: "upload_time_desc"
    });
    rows.value = res.files;
    page.total = res.total;
    page.size = res.page_size || page.size;
  } catch (err: unknown) {
    rows.value = [];
    page.total = 0;
    message(adminApiErrMessage(err, "相册列表加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  form.file_type = "";
  form.keyword = "";
  page.current = 1;
  void fetchList();
}

async function deleteAlbum(row: UserPhoneAlbumFile) {
  const id = fileId(row);
  if (!id) {
    message("相册文件 ID 为空，无法删除", { type: "warning" });
    return;
  }
  await ElMessageBox.confirm("确认标记删除该相册文件？", "操作确认", {
    type: "warning",
    confirmButtonText: "确认",
    cancelButtonText: "取消"
  });
  try {
    await deleteUserAlbumFile(id);
    message("已标记删除", { type: "success" });
    await fetchList();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "删除相册文件失败"), { type: "warning" });
  }
}

useAdminRealtimeInvalidate(
  [ADMIN_REALTIME_EVENTS.ALBUM_SYNCED, ADMIN_REALTIME_EVENTS.FILE_UPDATED],
  () => fetchList(),
  {
    debounceMs: 500,
    enabled: () => !!fixedUserUid.value || rows.value.length > 0
  }
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
      v-if="!embedded"
      class="mb-4"
      :closable="false"
      type="info"
      title="相册信息：查询用户同步的手机相册，支持按类型、关键字筛选。"
    />

    <el-card shadow="never" class="mb-4">
      <el-form :inline="true" :model="form" label-width="82px">
        <el-form-item label="类型">
          <el-select v-model="form.file_type" class="w-34!" clearable placeholder="全部">
            <el-option label="图片" value="image" />
            <el-option label="视频" value="video" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="form.keyword" class="w-48!" clearable placeholder="文件名 / hash" />
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
          <span>{{ embedded ? "手机相册" : "相册列表" }}</span>
          <el-button :loading="loading" @click="fetchList">刷新</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="rows" stripe border :height="tableHeight" size="small">
        <el-table-column label="预览" width="96" fixed="left">
          <template #default="{ row }">
            <template v-if="isVideo(row)">
              <video
                v-if="mediaUrl(row)"
                :src="mediaUrl(row)"
                preload="none"
                class="h-14 w-20 rounded object-cover"
                controls
                muted
              />
              <el-tag v-else size="small">视频</el-tag>
            </template>
            <el-image
              v-else-if="thumbUrl(row)"
              :src="thumbUrl(row)"
              lazy
              :preview-src-list="[mediaUrl(row) || thumbUrl(row)]"
              fit="cover"
              class="h-14 w-20 rounded"
              preview-teleported
            />
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="文件名" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.file_name) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ safeText(row.file_type ?? (isVideo(row) ? 'video' : 'image')) }}</template>
        </el-table-column>
        <el-table-column label="大小" width="110">
          <template #default="{ row }">{{ fmtSize(row.file_size) }}</template>
        </el-table-column>
        <el-table-column label="拍摄时间" min-width="170">
          <template #default="{ row }">{{ fmtTime(row.shoot_time ?? row.last_modified) }}</template>
        </el-table-column>
        <el-table-column label="上传时间" min-width="170">
          <template #default="{ row }">{{ fmtTime(row.upload_time ?? row.last_modified) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="110">
          <template #default="{ row }">{{ safeText(row.storage_status) }}</template>
        </el-table-column>
        <el-table-column label="操作" fixed="right" width="150">
          <template #default="{ row }">
            <el-button v-if="mediaUrl(row)" link type="primary" tag="a" :href="mediaUrl(row)" target="_blank">
              打开
            </el-button>
            <el-button link type="danger" @click="deleteAlbum(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :page-sizes="[24, 48, 96, 200]"
          layout="total, sizes, prev, pager, next, jumper"
          :total="page.total"
          @size-change="() => { page.current = 1; fetchList(); }"
          @current-change="fetchList"
        />
      </div>
    </el-card>
  </div>
</template>
