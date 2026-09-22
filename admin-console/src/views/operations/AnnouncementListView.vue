<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { listAnnouncements } from "@/api/announcements";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 10,
  keyword: "",
  content_type: "",
  scope_type: "",
  status: "",
  im_push_status: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listAnnouncements({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      content_type: query.content_type || undefined,
      scope_type: query.scope_type || undefined,
      status: query.status || undefined,
      im_push_status: query.im_push_status || undefined
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

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="公告记录" />
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.keyword" clearable placeholder="关键词" style="width: 180px" @keyup.enter="query.page=1; load()" />
        <el-select v-model="query.content_type" clearable placeholder="类型" style="width: 120px">
          <el-option label="文本" value="text" />
          <el-option label="图片" value="image" />
          <el-option label="视频" value="video" />
        </el-select>
        <el-select v-model="query.scope_type" clearable placeholder="范围" style="width: 120px">
          <el-option label="全员" value="global" />
          <el-option label="个人" value="personal" />
        </el-select>
        <el-select v-model="query.status" clearable placeholder="状态" style="width: 120px">
          <el-option label="已发布" value="published" />
          <el-option label="定时" value="scheduled" />
          <el-option label="草稿" value="draft" />
        </el-select>
        <el-button type="primary" @click="query.page=1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="ID" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ row.id || "—" }}</template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">{{ str(row, "content_type_label", "contentTypeLabel") || str(row, "content_type", "contentType") || "—" }}</template>
        </el-table-column>
        <el-table-column label="摘要" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "content_summary", "contentSummary") || "—" }}</template>
        </el-table-column>
        <el-table-column label="范围" width="90">
          <template #default="{ row }">{{ str(row, "scope_label", "scopeLabel") || "—" }}</template>
        </el-table-column>
        <el-table-column label="目标用户" min-width="130">
          <template #default="{ row }">
            <UserUidLink :uid="str(row, 'target_user_id', 'targetUserId')" />
          </template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ str(row, "status_label", "statusLabel") || row.status || "—" }}</template>
        </el-table-column>
        <el-table-column label="IM 推送" width="100">
          <template #default="{ row }">{{ str(row, "im_push_status_label", "imPushStatusLabel") || "—" }}</template>
        </el-table-column>
        <el-table-column label="创建人" width="110">
          <template #default="{ row }">{{ str(row, "created_by", "createdBy") || "—" }}</template>
        </el-table-column>
        <el-table-column label="发布时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.publish_at || row.publishAt) }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.created_at || row.createdAt) }}</template>
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
  </div>
</template>
