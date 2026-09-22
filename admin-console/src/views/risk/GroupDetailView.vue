<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { getGroupDetail, listGroupMembers, setGroupGameEnabled, setGroupGameid } from "@/api/groups";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const route = useRoute();
const router = useRouter();
const canWrite = computed(() => hasPerm("group.write"));
const loading = ref(false);
const memberLoading = ref(false);
const actionLoading = ref(false);
const gameidDraft = ref("");
const detail = ref<Record<string, unknown>>({});
const group = ref<Record<string, unknown>>({});
const members = ref<Record<string, unknown>[]>([]);
const memberTotal = ref(0);
const query = reactive({ page: 1, page_size: 50 });

const gId = computed(() => String(route.params.gId || ""));

async function loadDetail() {
  loading.value = true;
  try {
    const raw = (await getGroupDetail({ g_id: gId.value })) as Record<string, unknown>;
    detail.value = raw;
    group.value = (raw.group as Record<string, unknown>) || {};
    gameidDraft.value = String(group.value.gameid ?? group.value.gameId ?? "");
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function loadMembers() {
  memberLoading.value = true;
  try {
    const raw = await listGroupMembers({
      g_id: gId.value,
      page: query.page,
      page_size: query.page_size
    });
    const picked = pickList(raw);
    members.value = picked.items;
    memberTotal.value = picked.total;
  } catch (e) {
    members.value = [];
    memberTotal.value = 0;
    ElMessage.warning(errMessage(e));
  } finally {
    memberLoading.value = false;
  }
}

async function toggleGame(enabled: boolean) {
  try {
    await ElMessageBox.confirm(`确认${enabled ? "开启" : "关闭"}该群游戏权限？`, "群游戏", {
      type: "warning"
    });
  } catch {
    return;
  }
  actionLoading.value = true;
  try {
    await setGroupGameEnabled({ g_id: gId.value, game_enabled: enabled });
    ElMessage.success("已更新");
    await loadDetail();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = false;
  }
}

async function saveGameid() {
  if (!canWrite.value) {
    ElMessage.warning("无权限（需要 group.write）");
    return;
  }
  try {
    await ElMessageBox.confirm("确认保存该群游戏 ID？", "游戏 ID", {
      type: "warning"
    });
  } catch {
    return;
  }
  actionLoading.value = true;
  try {
    await setGroupGameid({ g_id: gId.value, gameid: gameidDraft.value });
    ElMessage.success("已更新");
    await loadDetail();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = false;
  }
}

async function clearGameid() {
  if (!canWrite.value) {
    ElMessage.warning("无权限（需要 group.write）");
    return;
  }
  try {
    await ElMessageBox.confirm("确认清空该群游戏 ID？业务库与 IM 将同时清空。", "游戏 ID", {
      type: "warning"
    });
  } catch {
    return;
  }
  actionLoading.value = true;
  try {
    await setGroupGameid({ g_id: gId.value, gameid: "" });
    ElMessage.success("已清空");
    await loadDetail();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = false;
  }
}

onMounted(async () => {
  await loadDetail();
  await loadMembers();
});
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader :title="`群详情 ${gId}`">
      <el-button @click="router.push('/risk/groups')">返回</el-button>
      <el-button
        link
        type="primary"
        @click="router.push({ path: '/risk/messages', query: { g_id: gId } })"
      >消息审计</el-button>
    </PageHeader>

    <div class="page-card" style="margin-bottom: 12px">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="群 ID">{{ detail.g_id || detail.gId || gId }}</el-descriptions-item>
        <el-descriptions-item label="来源">{{ detail.source || "—" }}</el-descriptions-item>
        <el-descriptions-item label="群名">{{ group.g_name || group.gName || group.name || "—" }}</el-descriptions-item>
        <el-descriptions-item label="群主">
          <UserUidLink :uid="str(group, 'g_owner_user_uid', 'gOwnerUserUid', 'owner_uid')" />
        </el-descriptions-item>
        <el-descriptions-item label="成员数">
          {{ group.g_member_count ?? group.gMemberCount ?? group.member_count ?? "—" }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">{{ group.g_status ?? group.gStatus ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="游戏权限" :span="2">
          {{ group.game_enabled ?? group.gameEnabled ?? "—" }}
          <template v-if="canWrite">
            <el-button
              style="margin-left: 8px"
              size="small"
              :loading="actionLoading"
              @click="toggleGame(true)"
            >开启</el-button>
            <el-button size="small" :loading="actionLoading" @click="toggleGame(false)">关闭</el-button>
          </template>
        </el-descriptions-item>
        <el-descriptions-item label="游戏 ID" :span="3">
          <span>{{ group.gameid || group.gameId || "—" }}</span>
          <template v-if="canWrite">
            <el-input
              v-model="gameidDraft"
              style="width: 240px; margin-left: 8px"
              size="small"
              maxlength="128"
              placeholder="空 = 未绑定"
            />
            <el-button
              style="margin-left: 8px"
              size="small"
              :loading="actionLoading"
              @click="saveGameid"
            >保存</el-button>
            <el-button size="small" :loading="actionLoading" @click="clearGameid">清空</el-button>
          </template>
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="page-card">
      <h3 style="margin: 0 0 12px; font-size: 15px">成员</h3>
      <el-table :data="members" v-loading="memberLoading" stripe border>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }"><UserUidLink :uid="str(row, 'user_uid', 'userUid')" /></template>
        </el-table-column>
        <el-table-column label="昵称" min-width="120">
          <template #default="{ row }">{{ str(row, "user_nickname", "userNickname") || "—" }}</template>
        </el-table-column>
        <el-table-column label="群内昵称" min-width="120">
          <template #default="{ row }">{{ str(row, "nickname_ingrouproup", "nicknameIngroup") || "—" }}</template>
        </el-table-column>
        <el-table-column label="角色" width="100">
          <template #default="{ row }">{{ row.role || "—" }}</template>
        </el-table-column>
        <el-table-column label="邀请人" min-width="120">
          <template #default="{ row }">{{ str(row, "be_invite_user_id", "beInviteUserId") || "—" }}</template>
        </el-table-column>
        <el-table-column label="加入时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.join_time ?? row.joinTime) }}</template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px; display: flex; justify-content: flex-end">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.page_size"
          :total="memberTotal"
          layout="total, prev, pager, next"
          @current-change="loadMembers"
        />
      </div>
    </div>
  </div>
</template>
