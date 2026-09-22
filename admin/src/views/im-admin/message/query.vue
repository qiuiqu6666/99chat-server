<script setup lang="ts">
import { ref, reactive, watch, onMounted } from "vue";
import { useRoute } from "vue-router";
import { deviceDetection } from "@pureadmin/utils";
import { getMessagesC2c, getMessagesGroup, isAdminGroupId } from "@/api/im-messages";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import {
  describeMsgTypeForAuditRow,
  formatImMessageForAudit
} from "@/utils/imMessageDisplay";
import {
  formatC2cSenderLabel,
  pickMsgSrcUid
} from "@/utils/imC2cDisplay";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

defineOptions({
  name: "ImMessageQuery"
});

/** 单聊：`/messages/c2c`；群聊：`/messages/group` */
const route = useRoute();
const queryMode = ref<"c2c" | "group">("c2c");
const userA = ref("");
const userB = ref("");
const groupId = ref("");
const keyword = ref("");
const loading = ref(false);

const pagination = reactive({ page: 1, pageSize: 30 });
const items = ref<Record<string, unknown>[]>([]);
const hasMore = ref(false);
const c2cLastMsgKey = ref<string | null>(null);
const grpNextSeq = ref<number | null>(null);

watch(queryMode, () => {
  pagination.page = 1;
  items.value = [];
  hasMore.value = false;
  c2cLastMsgKey.value = null;
  grpNextSeq.value = null;
});

function rowSrcUid(row: Record<string, unknown>) {
  return row.src_uid ?? row.from_uid ?? row.sender_uid ?? "—";
}

function rowDestUid(row: Record<string, unknown>) {
  return row.dest_uid ?? row.group_id ?? row.g_id ?? "—";
}

function rowSummary(row: Record<string, unknown>) {
  return formatImMessageForAudit(row);
}

function rowTypeLabel(row: Record<string, unknown>) {
  return describeMsgTypeForAuditRow(row);
}

function c2cSideLabel(row: Record<string, unknown>) {
  const a = String(userA.value).trim();
  const b = String(userB.value).trim();
  return formatC2cSenderLabel(pickMsgSrcUid(row), a, b, "ab");
}

async function fetchC2c(append = false) {
  const a = String(userA.value).trim();
  const b = String(userB.value).trim();
  if (!a || !b || a === b) {
    message("请输入两个不同的 IM 号", { type: "warning" });
    return;
  }
  loading.value = true;
  try {
    const res = await getMessagesC2c({
      user_a: a,
      user_b: b,
      keyword: keyword.value.trim() || undefined,
      page: pagination.page,
      page_size: pagination.pageSize,
      sort: "collect_id_desc",
      ...(append && c2cLastMsgKey.value
        ? { last_msg_key: c2cLastMsgKey.value }
        : {})
    });
    items.value = append ? [...items.value, ...res.items] : res.items;
    hasMore.value = res.has_more;
    c2cLastMsgKey.value = res.last_msg_key;
    if (typeof res.page_size === "number") pagination.pageSize = res.page_size;
  } catch (err: unknown) {
    if (!append) items.value = [];
    hasMore.value = false;
    message(adminApiErrMessage(err, "单聊查询失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

async function fetchGroup(append = false) {
  const gid = groupId.value.trim();
  if (!isAdminGroupId(gid)) {
    message("群 ID 须为 1～32 位字母、数字或 @ # _ -", { type: "warning" });
    return;
  }
  loading.value = true;
  try {
    const res = await getMessagesGroup({
      g_id: gid,
      keyword: keyword.value.trim() || undefined,
      page: pagination.page,
      page_size: pagination.pageSize,
      ...(append && grpNextSeq.value != null
        ? { req_msg_seq: grpNextSeq.value }
        : {})
    });
    items.value = append ? [...items.value, ...res.items] : res.items;
    hasMore.value = res.has_more;
    grpNextSeq.value = res.next_req_msg_seq;
    if (typeof res.page_size === "number") pagination.pageSize = res.page_size;
  } catch (err: unknown) {
    if (!append) items.value = [];
    hasMore.value = false;
    message(adminApiErrMessage(err, "群聊查询失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

function runQuery() {
  pagination.page = 1;
  c2cLastMsgKey.value = null;
  grpNextSeq.value = null;
  if (queryMode.value === "c2c") void fetchC2c(false);
  else void fetchGroup(false);
}

function loadMore() {
  if (!hasMore.value || loading.value) return;
  if (queryMode.value === "c2c") void fetchC2c(true);
  else void fetchGroup(true);
}

function fmtTs(t: unknown) {
  return formatAdminUnixTime(t, true);
}

useAdminRealtimeInvalidate(
  [
    ADMIN_REALTIME_EVENTS.MESSAGE_CREATED,
    ADMIN_REALTIME_EVENTS.MESSAGE_UPDATED,
    ADMIN_REALTIME_EVENTS.MESSAGE_DELETED,
    ADMIN_REALTIME_EVENTS.MESSAGE_RECALLED
  ],
  () => {
    if (items.value.length > 0) runQuery();
  },
  { debounceMs: 600 }
);

onMounted(() => {
  const a = String(route.query.user_a ?? route.query.user_uid ?? "").trim();
  if (a) userA.value = a;
});
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3" :class="[deviceDetection() ? '' : 'max-w-240 mx-auto']">
    <el-alert
      :closable="false"
      type="info"
      title="按单聊双方 IM 号或群 ID 检索聊天记录，支持关键字过滤消息正文。"
    />

    <el-form :inline="true" label-width="90px" class="items-center flex-wrap">
      <el-form-item label="查询类型">
        <el-radio-group v-model="queryMode">
          <el-radio-button value="c2c">单聊</el-radio-button>
          <el-radio-button value="group">群聊</el-radio-button>
        </el-radio-group>
      </el-form-item>
      <template v-if="queryMode === 'c2c'">
        <el-form-item label="用户 A">
          <el-input v-model="userA" class="w-40!" placeholder="IM 号" />
        </el-form-item>
        <el-form-item label="用户 B">
          <el-input v-model="userB" class="w-40!" placeholder="IM 号" />
        </el-form-item>
      </template>
      <el-form-item v-else label="群 ID (g_id)">
        <el-input
          v-model="groupId"
          class="w-50!"
          placeholder="如 @TGS#_xxx，1～32 位"
          clearable
        />
      </el-form-item>
      <el-form-item label="关键字">
        <el-input v-model="keyword" class="w-50!" placeholder="msg_content ≤64 B" />
      </el-form-item>
      <el-form-item>
        <el-button type="primary" :loading="loading" @click="runQuery">查询</el-button>
      </el-form-item>
      <el-form-item v-if="queryMode === 'c2c'" class="mb-0! w-full">
        <el-text size="small" type="info">
          单聊发送方说明：「A 方」对应左侧填写的 user_a，「B 方」对应 user_b；日期时间为消息发送时间（服务端 msg_time2）。
        </el-text>
      </el-form-item>
    </el-form>

    <el-table v-loading="loading" :data="items" stripe border size="small" max-height="520">
      <template v-if="queryMode === 'c2c'">
        <el-table-column label="发送方" width="148" show-overflow-tooltip>
          <template #default="{ row }">{{ c2cSideLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="108" show-overflow-tooltip>
          <template #default="{ row }">{{ rowTypeLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="内容(摘要)" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ rowSummary(row) }}</template>
        </el-table-column>
        <el-table-column label="日期时间" min-width="178">
          <template #default="{ row }">{{ fmtTs(row.msg_time2 ?? row.create_time ?? row.updated_at) }}</template>
        </el-table-column>
      </template>
      <template v-else>
        <el-table-column label="发送方 UID" width="118">
          <template #default="{ row }">{{ rowSrcUid(row) }}</template>
        </el-table-column>
        <el-table-column label="目标" width="118">
          <template #default="{ row }">{{ rowDestUid(row) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="108" show-overflow-tooltip>
          <template #default="{ row }">{{ rowTypeLabel(row) }}</template>
        </el-table-column>
        <el-table-column label="内容(摘要)" min-width="220" show-overflow-tooltip>
          <template #default="{ row }">{{ rowSummary(row) }}</template>
        </el-table-column>
        <el-table-column label="日期时间" min-width="178">
          <template #default="{ row }">{{ fmtTs(row.msg_time2 ?? row.create_time ?? row.updated_at) }}</template>
        </el-table-column>
      </template>
    </el-table>

    <div class="flex flex-wrap items-center justify-end gap-2">
      <el-text size="small" type="info">已加载 {{ items.length }} 条</el-text>
      <el-button v-if="hasMore" :loading="loading" size="small" @click="loadMore">
        加载更多
      </el-button>
    </div>
  </div>
</template>
