<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { listFeedback, updateFeedbackStatus } from "@/api/feedback";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: "",
  status: "pending"
});

async function load() {
  loading.value = true;
  try {
    const raw = await listFeedback({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      status: query.status || undefined
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

async function setStatus(row: Record<string, unknown>, status: string) {
  if (!hasPerm("user.write")) return;
  let reply = "";
  try {
    if (status === "processed") {
      const { value } = await ElMessageBox.prompt("可选回复", "处理反馈", {
        inputPlaceholder: "回复（可选）"
      });
      reply = String(value || "").trim();
    } else {
      await ElMessageBox.confirm(`标记为 ${status}？`, "确认");
    }
  } catch {
    return;
  }
  try {
    await updateFeedbackStatus(String(row.id), { status, reply: reply || undefined });
    ElMessage.success("已更新");
    load();
  } catch (e) {
    ElMessage.warning(errMessage(e));
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="用户反馈" />
    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="关键词"
          style="width: 220px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-select v-model="query.status" clearable placeholder="状态" style="width: 130px">
          <el-option label="待处理" value="pending" />
          <el-option label="已处理" value="processed" />
          <el-option label="已关闭" value="closed" />
        </el-select>
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column prop="id" label="ID" width="80" />
        <el-table-column label="用户" min-width="140">
          <template #default="{ row }">
            <UserUidLink :uid="str(row, 'user_uid', 'userUid')" />
          </template>
        </el-table-column>
        <el-table-column label="内容" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.content || "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            {{ str(row, "status_label", "statusLabel", "status") || "—" }}
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">
            {{ formatTime(row.created_at || row.createdAt) }}
          </template>
        </el-table-column>
        <el-table-column v-if="hasPerm('user.write')" label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="setStatus(row, 'processed')">已处理</el-button>
            <el-button link @click="setStatus(row, 'closed')">关闭</el-button>
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
  </div>
</template>
