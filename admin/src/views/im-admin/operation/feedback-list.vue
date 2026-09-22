<script setup lang="ts">
import dayjs from "dayjs";
import { onMounted, reactive, ref } from "vue";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  getFeedbackList,
  updateFeedbackStatus,
  type ImFeedbackItem
} from "@/api/im-feedback";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

defineOptions({ name: "ImFeedbackList" });

const loading = ref(false);
const dataList = ref<ImFeedbackItem[]>([]);
const previewImages = ref<string[]>([]);
const previewVisible = ref(false);

const query = reactive({
  keyword: "",
  user_uid: "",
  status: ""
});

const pagination = reactive({
  total: 0,
  pageSize: 10,
  currentPage: 1,
  background: true
});

const statusLabelMap: Record<string, string> = {
  pending: "待处理",
  processed: "已处理",
  closed: "已关闭"
};

function formatTime(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

function displayStatus(row: ImFeedbackItem) {
  return row.status_label || statusLabelMap[String(row.status || "").toLowerCase()] || row.status || "待处理";
}

function statusType(status?: string | null) {
  const s = String(status || "").toLowerCase();
  if (["done", "resolved", "processed"].includes(s)) return "success";
  if (["closed"].includes(s)) return "info";
  if (["pending", "new", "open"].includes(s)) return "warning";
  if (["ignored", "rejected"].includes(s)) return "info";
  return "info";
}

function isPending(row: ImFeedbackItem) {
  const s = String(row.status || "pending").toLowerCase();
  return !s || s === "pending" || s === "new" || s === "open";
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getFeedbackList({
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      keyword: query.keyword.trim() || undefined,
      user_uid: query.user_uid.trim() || undefined,
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
      message(adminApiErrMessage(err, "问题反馈加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function resetQuery() {
  query.keyword = "";
  query.user_uid = "";
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

function openImages(row: ImFeedbackItem) {
  previewImages.value = row.images || [];
  previewVisible.value = previewImages.value.length > 0;
}

async function changeStatus(row: ImFeedbackItem, status: string, successText: string) {
  try {
    await updateFeedbackStatus(row.id, { status });
    message(successText, { type: "success" });
    await loadData();
  } catch (err: unknown) {
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 user.write）", { type: "warning" });
      return;
    }
    message(adminApiErrMessage(err, "操作失败"), { type: "warning" });
  }
}

function markDone(row: ImFeedbackItem) {
  return changeStatus(row, "processed", "已标记处理");
}

function markClosed(row: ImFeedbackItem) {
  return changeStatus(row, "closed", "已关闭反馈");
}

useAdminRealtimeInvalidate(
  [ADMIN_REALTIME_EVENTS.FEEDBACK_CREATED, ADMIN_REALTIME_EVENTS.FEEDBACK_UPDATED],
  () => loadData(),
  { debounceMs: 200 }
);

onMounted(() => loadData());
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="UID">
          <el-input v-model="query.user_uid" clearable class="w-45!" placeholder="用户 UID" />
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="query.keyword" clearable class="w-56!" placeholder="内容 / 回复" />
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.status" clearable placeholder="全部" class="w-40!">
            <el-option label="待处理" value="pending" />
            <el-option label="已处理" value="processed" />
            <el-option label="已关闭" value="closed" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="pagination.currentPage = 1; loadData();">查询</el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="dataList" border stripe v-loading="loading" row-key="id">
        <el-table-column prop="id" label="反馈 ID" min-width="90" show-overflow-tooltip />
        <el-table-column prop="user_uid" label="UID" min-width="110" show-overflow-tooltip />
        <el-table-column prop="nickname" label="昵称" min-width="120" show-overflow-tooltip />
        <el-table-column prop="category" label="类型" width="88">
          <template #default="{ row }">{{ row.category || '—' }}</template>
        </el-table-column>
        <el-table-column prop="content" label="反馈内容" min-width="240" show-overflow-tooltip />
        <el-table-column prop="contact" label="联系方式" min-width="130" show-overflow-tooltip>
          <template #default="{ row }">{{ row.contact || row.phone || '—' }}</template>
        </el-table-column>
        <el-table-column label="附件" width="88">
          <template #default="{ row }">
            <el-button v-if="row.images?.length" link type="primary" size="small" @click="openImages(row)">
              {{ row.images.length }} 张
            </el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="client_version" label="客户端版本" min-width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ row.client_version || '—' }}</template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="96">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" effect="plain">{{ displayStatus(row) }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提交时间" min-width="168">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="isPending(row)"
              link
              type="primary"
              size="small"
              @click="markDone(row)"
            >
              标记处理
            </el-button>
            <el-button
              v-if="row.status !== 'closed'"
              link
              type="info"
              size="small"
              @click="markClosed(row)"
            >
              关闭
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

    <el-image-viewer
      v-if="previewVisible"
      :url-list="previewImages"
      teleported
      @close="previewVisible = false"
    />
  </div>
</template>
