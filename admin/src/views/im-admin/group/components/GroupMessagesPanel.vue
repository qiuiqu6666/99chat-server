<script setup lang="ts">
import { reactive, ref, watch } from "vue";
import { getGroupMessagesApi } from "@/api/im-group";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import {
  describeMsgTypeForAuditRow,
  formatImMessageForAudit
} from "@/utils/imMessageDisplay";

const props = defineProps<{
  ctx: { gId: string; groupNo: string; name: string };
}>();

const loading = ref(false);
const keyword = ref("");
const tableData = ref<any[]>([]);
const meta = reactive({ gName: "" });

const pagination = reactive({
  total: 0,
  pageSize: 15,
  currentPage: 1
});

async function fetchMessages(page = 1) {
  loading.value = true;
  try {
    pagination.currentPage = page;
    const res = (await getGroupMessagesApi({
      g_id: props.ctx.gId,
      keyword: keyword.value.trim() || undefined,
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      sort: undefined
    })) as Record<string, unknown>;

    meta.gName = typeof res.g_name === "string" ? res.g_name : "";

    const rawItems = Array.isArray(res.items) ? res.items : [];
    tableData.value = rawItems.map((r: Record<string, unknown>) => {
      const rid = r.collect_id ?? r.id ?? r.seq;
      const src = r.src_uid ?? r.sender_uid ?? r.from_uid;
      const t = r.msg_time2 ?? r.sentAt ?? r.created_at ?? r.create_time;
      return {
        rid,
        sender: src != null ? String(src) : "—",
        typeDisplay: describeMsgTypeForAuditRow(r),
        contentDisplay: formatImMessageForAudit(r),
        ts: t,
        _raw: r
      };
    });
    pagination.total = typeof res.total === "number" ? res.total : tableData.value.length;
    if (typeof res.page_size === "number" && res.page_size > 0) {
      pagination.pageSize = res.page_size;
    }
  } catch (err: unknown) {
    tableData.value = [];
    pagination.total = 0;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (
      ax?.response?.status === 404 ||
      ax?.response?.data?.error === "group_not_found"
    ) {
      message("群不存在", { type: "warning" });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "群消息加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.ctx.gId,
  () => {
    pagination.currentPage = 1;
    keyword.value = "";
    fetchMessages(1);
  },
  { immediate: true }
);

function fmtTs(t: unknown) {
  return formatAdminUnixTime(t, true);
}
</script>

<template>
  <div class="max-h-[65vh] overflow-y-auto px-1">
    <el-text size="small" type="info" class="mb-2! block leading-relaxed">
      {{ ctx.name }} · {{ ctx.groupNo }}
      <template v-if="meta.gName"> · {{ meta.gName }}</template>
    </el-text>
    <div class="mb-3 flex flex-wrap gap-2">
      <el-input
        v-model="keyword"
        clearable
        placeholder="消息内容关键字（≤64 字节）"
        class="max-w-65!"
        @keyup.enter="
          pagination.currentPage = 1;
          fetchMessages(1);
        "
      />
      <el-button
        type="primary"
        @click="
          pagination.currentPage = 1;
          fetchMessages(1);
        "
      >
        查询
      </el-button>
    </div>
    <el-table v-loading="loading" :data="tableData" border size="small" stripe>
      <el-table-column prop="rid" label="序号/ID" min-width="100" show-overflow-tooltip />
      <el-table-column prop="sender" label="发送方 UID" min-width="118" />
      <el-table-column prop="typeDisplay" label="类型" width="108" show-overflow-tooltip />
      <el-table-column prop="contentDisplay" label="内容(摘要)" min-width="220" show-overflow-tooltip />
      <el-table-column label="时间" min-width="166">
        <template #default="{ row }">
          {{ fmtTs(row.ts) }}
        </template>
      </el-table-column>
    </el-table>
    <div class="mt-3 flex justify-end">
      <el-pagination
        v-model:current-page="pagination.currentPage"
        v-model:page-size="pagination.pageSize"
        :total="pagination.total"
        :page-sizes="[10, 15, 30, 50]"
        layout="total, sizes, prev, pager, next"
        background
        small
        @current-change="fetchMessages"
        @size-change="
          () => {
            pagination.currentPage = 1;
            fetchMessages(1);
          }
        "
      />
    </div>
  </div>
</template>
