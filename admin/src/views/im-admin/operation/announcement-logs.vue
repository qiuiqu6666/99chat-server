<script setup lang="ts">
import dayjs from "dayjs";
import { onMounted, reactive, ref } from "vue";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  getAnnouncementList,
  type ImAnnouncementLogItem
} from "@/api/im-announcement";

defineOptions({ name: "ImAnnouncementLogs" });

const loading = ref(false);
const dataList = ref<ImAnnouncementLogItem[]>([]);
const previewUrl = ref("");
const previewVisible = ref(false);
const previewIsVideo = ref(false);

const query = reactive({
  keyword: "",
  content_type: "",
  scope_type: "",
  target_user_id: "",
  im_push_status: "",
  status: ""
});

const pagination = reactive({
  total: 0,
  pageSize: 10,
  currentPage: 1,
  background: true
});

function formatTime(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

function pushStatusType(status?: string | null) {
  const s = String(status || "").toLowerCase();
  if (["done"].includes(s)) return "success";
  if (["running"].includes(s)) return "warning";
  if (["scheduled"].includes(s)) return "warning";
  if (["pending"].includes(s)) return "info";
  if (["skipped"].includes(s)) return "info";
  return "info";
}

function publishStatusType(status?: string | null) {
  const s = String(status || "").toLowerCase();
  if (["published"].includes(s)) return "success";
  if (["scheduled"].includes(s)) return "warning";
  if (["revoked"].includes(s)) return "danger";
  if (["draft"].includes(s)) return "info";
  return "info";
}

function contentTypeTagType(type?: string | null) {
  const t = String(type || "").toLowerCase();
  if (t === "image") return "success";
  if (t === "video") return "warning";
  return "";
}

function openPreview(row: ImAnnouncementLogItem) {
  const type = String(row.content_type || "").toLowerCase();
  if (type === "image") {
    previewUrl.value = row.preview_url || row.thumb_url || row.media_url || "";
    previewIsVideo.value = false;
  } else if (type === "video") {
    previewUrl.value = row.media_url || "";
    previewIsVideo.value = true;
  } else {
    return;
  }
  previewVisible.value = !!previewUrl.value;
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getAnnouncementList({
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      keyword: query.keyword.trim() || undefined,
      content_type: query.content_type || undefined,
      scope_type: query.scope_type || undefined,
      target_user_id: query.target_user_id.trim() || undefined,
      im_push_status: query.im_push_status || undefined,
      status: query.status || undefined
    });
    dataList.value = res.items;
    pagination.total = res.total;
  } catch (err: unknown) {
    dataList.value = [];
    pagination.total = 0;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 user.read）", { type: "warning" });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "公告推送记录加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function resetQuery() {
  query.keyword = "";
  query.content_type = "";
  query.scope_type = "";
  query.target_user_id = "";
  query.im_push_status = "";
  query.status = "";
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

onMounted(() => loadData());
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="关键字">
          <el-input
            v-model="query.keyword"
            clearable
            class="w-56!"
            placeholder="公告 ID / 内容 / 操作人"
          />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="query.content_type" clearable placeholder="全部" class="w-32!">
            <el-option label="文本" value="text" />
            <el-option label="图片" value="image" />
            <el-option label="视频" value="video" />
          </el-select>
        </el-form-item>
        <el-form-item label="范围">
          <el-select v-model="query.scope_type" clearable placeholder="全部" class="w-32!">
            <el-option label="全站" value="global" />
            <el-option label="指定用户" value="personal" />
          </el-select>
        </el-form-item>
        <el-form-item label="目标 UID">
          <el-input
            v-model="query.target_user_id"
            clearable
            class="w-40!"
            placeholder="指定用户 UID"
          />
        </el-form-item>
        <el-form-item label="推送状态">
          <el-select v-model="query.im_push_status" clearable placeholder="全部" class="w-36!">
            <el-option label="定时待发" value="scheduled" />
            <el-option label="排队中" value="pending" />
            <el-option label="推送中" value="running" />
            <el-option label="已完成" value="done" />
            <el-option label="未启用全站推送" value="skipped" />
          </el-select>
        </el-form-item>
        <el-form-item label="发布状态">
          <el-select v-model="query.status" clearable placeholder="全部" class="w-32!">
            <el-option label="定时待发" value="scheduled" />
            <el-option label="已发布" value="published" />
            <el-option label="草稿" value="draft" />
            <el-option label="已撤回" value="revoked" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="pagination.currentPage = 1; loadData();">
            查询
          </el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="dataList" border stripe v-loading="loading" row-key="id">
        <el-table-column prop="id" label="公告 ID" min-width="280" show-overflow-tooltip />
        <el-table-column label="类型" width="88">
          <template #default="{ row }">
            <el-tag :type="contentTypeTagType(row.content_type)" effect="plain">
              {{ row.content_type_label || row.content_type || "—" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="260">
          <template #default="{ row }">
            <div class="flex items-center gap-2">
              <el-image
                v-if="row.content_type === 'image' && (row.thumb_url || row.preview_url)"
                :src="row.thumb_url || row.preview_url"
                fit="cover"
                class="h-10 w-10 rounded cursor-pointer shrink-0"
                @click="openPreview(row)"
              />
              <el-button
                v-else-if="row.content_type === 'video'"
                link
                type="primary"
                @click="openPreview(row)"
              >
                查看视频
              </el-button>
              <span class="truncate">{{ row.content_summary || "—" }}</span>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="范围" width="110">
          <template #default="{ row }">
            <div>{{ row.scope_label || "—" }}</div>
            <div v-if="row.target_user_id" class="text-xs text-gray-500">{{ row.target_user_id }}</div>
          </template>
        </el-table-column>
        <el-table-column label="发布状态" width="100">
          <template #default="{ row }">
            <el-tag :type="publishStatusType(row.status)" effect="plain">
              {{ row.status_label || row.status || "—" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="IM 推送" width="130">
          <template #default="{ row }">
            <el-tag :type="pushStatusType(row.im_push_status)" effect="plain">
              {{ row.im_push_status_label || row.im_push_status || "—" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="created_by" label="操作人" min-width="100" show-overflow-tooltip />
        <el-table-column label="计划/发布时间" min-width="170">
          <template #default="{ row }">{{ formatTime(row.publish_at || row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="170">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
      </el-table>

      <div class="flex justify-end mt-4">
        <el-pagination
          v-model:current-page="pagination.currentPage"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          background
          @size-change="onSizeChange"
          @current-change="onCurrentChange"
        />
      </div>
    </el-card>

    <el-dialog v-model="previewVisible" title="预览" width="720px" destroy-on-close>
      <video
        v-if="previewIsVideo && previewUrl"
        :src="previewUrl"
        controls
        class="w-full max-h-120 rounded"
      />
      <el-image v-else-if="previewUrl" :src="previewUrl" fit="contain" class="w-full max-h-120" />
    </el-dialog>
  </div>
</template>
