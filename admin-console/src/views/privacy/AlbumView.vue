<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { deleteAlbumFile, getAlbumDetail, listAlbums } from "@/api/privacy";
import { str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const route = useRoute();
const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const actionLoading = ref("");
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const summary = reactive({
  total_files: 0,
  photo_count: 0,
  video_count: 0
});
const query = reactive({
  page: 1,
  page_size: 24,
  user_uid: String(route.query.user_uid || ""),
  file_type: "" as "" | "image" | "video",
  keyword: ""
});

const videoVisible = ref(false);
const videoUrl = ref("");
const videoTitle = ref("");

function thumbUrl(row: Record<string, unknown>) {
  return str(row, "oss_thumb_url", "ossThumbUrl", "preview_url", "previewUrl", "oss_url", "ossUrl");
}

function mediaUrl(row: Record<string, unknown>) {
  return str(row, "oss_url", "ossUrl", "preview_url", "previewUrl", "oss_thumb_url", "ossThumbUrl");
}

function isVideo(row: Record<string, unknown>) {
  const media = str(row, "media_type", "mediaType").toUpperCase();
  if (media === "VIDEO") return true;
  const t = str(row, "file_type", "fileType").toLowerCase();
  if (t.includes("video")) return true;
  const name = str(row, "file_name", "fileName").toLowerCase();
  return /\.(mp4|mov|m4v|webm|avi|mkv)$/i.test(name);
}

const imagePreviewList = computed(() =>
  rows.value.filter(r => !isVideo(r) && mediaUrl(r)).map(r => mediaUrl(r))
);

async function loadSummary() {
  if (!query.user_uid.trim()) {
    summary.total_files = 0;
    summary.photo_count = 0;
    summary.video_count = 0;
    return;
  }
  try {
    const raw = (await getAlbumDetail(query.user_uid)) as Record<string, unknown>;
    summary.total_files = Number(raw.total_files ?? raw.totalFiles ?? 0);
    summary.photo_count = Number(raw.photo_count ?? raw.photoCount ?? 0);
    summary.video_count = Number(raw.video_count ?? raw.videoCount ?? 0);
  } catch {
    // 汇总失败不挡列表
  }
}

async function load() {
  if (!query.user_uid.trim()) {
    rows.value = [];
    total.value = 0;
    return;
  }
  loading.value = true;
  try {
    const raw = (await listAlbums({
      page: query.page,
      page_size: query.page_size,
      user_uid: query.user_uid,
      file_type: query.file_type || undefined,
      keyword: query.keyword || undefined,
      sort: "upload_time_desc"
    })) as Record<string, unknown>;
    const files = (raw.files || raw.items || []) as Record<string, unknown>[];
    rows.value = Array.isArray(files) ? files : [];
    total.value = Number(raw.total ?? rows.value.length);
    await loadSummary();
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

function openVideo(row: Record<string, unknown>) {
  const url = mediaUrl(row);
  if (!url) {
    ElMessage.info("无可播放地址");
    return;
  }
  videoUrl.value = url;
  videoTitle.value = str(row, "file_name", "fileName") || "视频预览";
  videoVisible.value = true;
}

function closeVideo() {
  videoVisible.value = false;
  videoUrl.value = "";
}

function fmtSize(v: unknown) {
  const n = Number(v);
  if (!Number.isFinite(n) || n <= 0) return "—";
  if (n < 1024) return `${n} B`;
  if (n < 1024 * 1024) return `${(n / 1024).toFixed(1)} KB`;
  return `${(n / 1024 / 1024).toFixed(2)} MB`;
}

async function remove(row: Record<string, unknown>) {
  const id = String(row.id || "");
  if (!id) return;
  try {
    await ElMessageBox.confirm(`确认删除相册文件 ${row.file_name || row.fileName || id}？`, "删除确认", {
      type: "warning"
    });
  } catch {
    return;
  }
  actionLoading.value = id;
  try {
    await deleteAlbumFile(id);
    ElMessage.success("已删除");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = "";
  }
}

watch(
  () => route.query.user_uid,
  v => {
    query.user_uid = String(v || "");
    query.page = 1;
    load();
  }
);
onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="相册" subtitle="图片与视频混合列表；默认可看全部类型" />
    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="query.user_uid"
          clearable
          placeholder="用户 UID（必填）"
          style="width: 180px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-select v-model="query.file_type" clearable placeholder="全部类型" style="width: 130px">
          <el-option label="图片" value="image" />
          <el-option label="视频" value="video" />
        </el-select>
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="文件名 / hash"
          style="width: 160px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>

      <el-alert
        v-if="query.user_uid"
        :closable="false"
        type="info"
        show-icon
        style="margin-bottom: 12px"
        :title="`合计 ${summary.total_files}（图片 ${summary.photo_count} / 视频 ${summary.video_count}）`"
      />

      <el-table :data="rows" v-loading="loading" stripe border empty-text="请填写用户 UID 后查询">
        <el-table-column label="预览" width="110">
          <template #default="{ row }">
            <template v-if="isVideo(row)">
              <div class="thumb video-thumb" title="点击播放" @click="openVideo(row)">
                <video
                  v-if="mediaUrl(row)"
                  :src="mediaUrl(row)"
                  preload="metadata"
                  muted
                  playsinline
                />
                <img v-else-if="thumbUrl(row)" :src="thumbUrl(row)" alt="" />
                <div v-else class="video-placeholder">视频</div>
                <span class="play-badge">▶</span>
              </div>
            </template>
            <el-image
              v-else-if="mediaUrl(row)"
              :src="thumbUrl(row) || mediaUrl(row)"
              :preview-src-list="imagePreviewList"
              :initial-index="Math.max(0, imagePreviewList.indexOf(mediaUrl(row)))"
              fit="cover"
              preview-teleported
              hide-on-click-modal
              class="thumb-img"
            />
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="用户" min-width="120">
          <template #default="{ row }"><UserUidLink :uid="str(row, 'user_uid', 'userUid')" /></template>
        </el-table-column>
        <el-table-column label="文件名" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">
            <el-button v-if="isVideo(row)" link type="primary" @click="openVideo(row)">
              {{ str(row, "file_name", "fileName") || "查看视频" }}
            </el-button>
            <span v-else>{{ str(row, "file_name", "fileName") || "—" }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="isVideo(row) ? 'warning' : 'success'" size="small">
              {{ isVideo(row) ? "视频" : "图片" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ fmtSize(row.file_size ?? row.fileSize) }}</template>
        </el-table-column>
        <el-table-column label="存储" width="90">
          <template #default="{ row }">{{ str(row, "storage_status", "storageStatus") || "—" }}</template>
        </el-table-column>
        <el-table-column label="上传时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.upload_time ?? row.uploadTime) }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button type="danger" link :loading="actionLoading === String(row.id)" @click="remove(row)">删除</el-button>
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

    <el-dialog
      v-model="videoVisible"
      :title="videoTitle"
      width="720px"
      destroy-on-close
      @closed="closeVideo"
    >
      <video v-if="videoUrl" :src="videoUrl" controls autoplay style="width: 100%; max-height: 70vh; background: #000" />
    </el-dialog>
  </div>
</template>

<style scoped>
.thumb-img {
  width: 72px;
  height: 56px;
  border-radius: 6px;
  cursor: zoom-in;
}
.thumb {
  width: 72px;
  height: 56px;
  border-radius: 6px;
  overflow: hidden;
  position: relative;
  cursor: pointer;
  background: #111;
}
.thumb video,
.thumb img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.video-placeholder {
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 12px;
}
.play-badge {
  position: absolute;
  inset: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
  font-size: 14px;
  background: rgba(0, 0, 0, 0.35);
  pointer-events: none;
}
</style>
