<script setup lang="ts">
import { ref, watch, reactive } from "vue";
import dayjs from "dayjs";
import { getGroupsMembers } from "@/api/im-group";
import { adminApiErrMessage } from "@/api/im-user";
import { formatGroupMemberRole } from "@/utils/imGroupRole";
import { message } from "@/utils/message";

const props = defineProps<{
  ctx: { gId: string; groupNo: string; name: string };
}>();

const loading = ref(false);
const members = ref<any[]>([]);
const pagination = reactive({
  total: 0,
  pageSize: 50,
  currentPage: 1
});

async function fetchMembers(p = 1) {
  loading.value = true;
  try {
    pagination.currentPage = p;
    const res = await getGroupsMembers({
      g_id: props.ctx.gId,
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      sort: "join_time_desc"
    });
    const items = (res.items ?? []).map((r: Record<string, unknown>) => ({
      uid: r.user_uid ?? r.member_user_uid,
      userNickname: r.user_nickname ?? "",
      nicknameIngroup: r.nickname_ingroup ?? "",
      role: r.role,
      joinTime: r.join_time,
      userRecordId: r.user_record_id,
      _raw: r
    }));
    members.value = items;
    pagination.total = res.total ?? items.length;
    if (typeof res.page_size === "number" && res.page_size > 0) {
      pagination.pageSize = res.page_size;
    }
  } catch (err: unknown) {
    members.value = [];
    pagination.total = 0;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 404 || ax?.response?.data?.error === "group_not_found") {
      message("群不存在", { type: "warning" });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "成员列表加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

watch(
  () => props.ctx.gId,
  () => fetchMembers(1),
  { immediate: true }
);
</script>

<template>
  <div class="max-h-[65vh] overflow-y-auto px-1">
    <el-text size="small" type="info" class="mb-3! block">
      {{ ctx.name }} · {{ ctx.groupNo }} · 共 {{ pagination.total }} 人
    </el-text>
    <el-table v-loading="loading" :data="members" border size="small" stripe class="mb-3">
      <el-table-column prop="uid" label="IM 号" min-width="120" />
      <el-table-column prop="userNickname" label="全局昵称" min-width="110" />
      <el-table-column prop="nicknameIngroup" label="群内昵称" min-width="110" />
      <el-table-column label="角色" width="100">
        <template #default="{ row }">{{ formatGroupMemberRole(row.role) }}</template>
      </el-table-column>
      <el-table-column label="入群时间" min-width="168">
        <template #default="{ row }">
          {{
            row.joinTime
              ? dayjs(row.joinTime as string | number).format("YYYY-MM-DD HH:mm:ss")
              : "—"
          }}
        </template>
      </el-table-column>
      <el-table-column prop="userRecordId" label="记录 ID" width="100" show-overflow-tooltip />
    </el-table>
    <div class="flex justify-end">
      <el-pagination
        v-model:current-page="pagination.currentPage"
        v-model:page-size="pagination.pageSize"
        layout="total, prev, pager, next"
        small
        background
        :total="pagination.total"
        @current-change="fetchMembers"
      />
    </div>
  </div>
</template>
