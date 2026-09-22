<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { listC2cMessages, listGroupMessages } from "@/api/messages";
import { str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const route = useRoute();
const mode = ref<"c2c" | "group">("c2c");
const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const hasMore = ref(false);
const query = reactive({
  page: 1,
  page_size: 30,
  keyword: "",
  user_a: String(route.query.user_a || ""),
  user_b: String(route.query.user_b || ""),
  g_id: String(route.query.g_id || route.query.group_id || ""),
  last_msg_key: "",
  req_msg_seq: undefined as number | undefined
});

async function load(resetCursor = true) {
  if (mode.value === "c2c") {
    if (!query.user_a.trim() || !query.user_b.trim()) {
      rows.value = [];
      ElMessage.info("单聊需填写双方 UID");
      return;
    }
  } else if (!query.g_id.trim()) {
    rows.value = [];
    ElMessage.info("群聊需填写群 ID");
    return;
  }
  if (resetCursor) {
    query.last_msg_key = "";
    query.req_msg_seq = undefined;
    query.page = 1;
  }
  loading.value = true;
  try {
    if (mode.value === "c2c") {
      const raw = (await listC2cMessages({
        user_a: query.user_a,
        user_b: query.user_b,
        keyword: query.keyword || undefined,
        page: query.page,
        page_size: query.page_size,
        last_msg_key: query.last_msg_key || undefined
      })) as Record<string, unknown>;
      rows.value = (raw.items as Record<string, unknown>[]) || [];
      hasMore.value = Boolean(raw.has_more ?? raw.hasMore);
      query.last_msg_key = String(raw.last_msg_key ?? raw.lastMsgKey ?? "");
    } else {
      const raw = (await listGroupMessages({
        g_id: query.g_id,
        keyword: query.keyword || undefined,
        page: query.page,
        page_size: query.page_size,
        req_msg_seq: query.req_msg_seq
      })) as Record<string, unknown>;
      rows.value = (raw.items as Record<string, unknown>[]) || [];
      hasMore.value = Boolean(raw.has_more ?? raw.hasMore);
      const next = raw.next_req_msg_seq ?? raw.nextReqMsgSeq;
      query.req_msg_seq = next == null ? undefined : Number(next);
    }
  } catch (e) {
    rows.value = [];
    hasMore.value = false;
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

watch(mode, () => load(true));
onMounted(() => load(true));
</script>

<template>
  <div class="page">
    <PageHeader title="消息审计" />
    <div class="page-card">
      <div class="toolbar">
        <el-radio-group v-model="mode">
          <el-radio-button value="c2c">单聊</el-radio-button>
          <el-radio-button value="group">群聊</el-radio-button>
        </el-radio-group>
        <template v-if="mode === 'c2c'">
          <el-input v-model="query.user_a" clearable placeholder="用户 A" style="width: 160px" />
          <el-input v-model="query.user_b" clearable placeholder="用户 B" style="width: 160px" />
        </template>
        <el-input v-else v-model="query.g_id" clearable placeholder="群 ID (g_id)" style="width: 200px" />
        <el-input v-model="query.keyword" clearable placeholder="关键词" style="width: 160px" @keyup.enter="load(true)" />
        <el-button type="primary" @click="load(true)">查询</el-button>
        <el-button :disabled="!hasMore" @click="query.page += 1; load(false)">下一页</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="发送方" min-width="130">
          <template #default="{ row }">
            <UserUidLink :uid="str(row, 'from_account', 'fromAccount')" />
          </template>
        </el-table-column>
        <el-table-column label="接收方/群" min-width="130">
          <template #default="{ row }">{{ str(row, "to_account", "toAccount") || "—" }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ str(row, "msg_type", "msgType") || "—" }}</template>
        </el-table-column>
        <el-table-column label="内容预览" min-width="260" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "text_preview", "textPreview") || "—" }}</template>
        </el-table-column>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.msg_time ?? row.msgTime) }}</template>
        </el-table-column>
        <el-table-column label="MsgKey" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "msg_key", "msgKey") || "—" }}</template>
        </el-table-column>
      </el-table>
    </div>
  </div>
</template>
