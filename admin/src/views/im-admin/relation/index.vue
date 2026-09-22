<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { deviceDetection } from "@pureadmin/utils";
import {
  getRelationsSameDevice,
  getRelationsSameIp,
  getUserFriends,
  getUserGroups
} from "@/api/im-relation";
import { adminApiErrMessage } from "@/api/im-user";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import { message } from "@/utils/message";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

defineOptions({ name: "ImRelationIndex" });

type TabName = "friends" | "groups" | "sameIp" | "sameDevice";

const router = useRouter();
const route = useRoute();
const activeTab = ref<TabName>("friends");
const loading = ref(false);
const listHint = ref("");
const keyword = ref("");
const form = reactive({
  user_uid: "",
  ip: "",
  device_id: ""
});
const page = reactive({ current: 1, size: 20, total: 0 });
const rows = ref<Record<string, unknown>[]>([]);

const tableHeight = computed(() => (deviceDetection() ? 430 : 560));

const tabGuide = computed(() => {
  switch (activeTab.value) {
    case "friends":
      return "输入用户 IM 号，查询其好友列表。";
    case "groups":
      return "输入用户 IM 号，查询其加入的群组。";
    case "sameIp":
      return "输入用户 IM 号，查找曾使用相同 IP 登录的其他账号。";
    case "sameDevice":
      return "输入用户 IM 号或设备 ID，查找同设备关联账号。";
    default:
      return "";
  }
});

function fmtTime(v: unknown) {
  return formatAdminUnixTime(v, true);
}

function safeText(v: unknown) {
  if (v == null || v === "") return "—";
  return String(v);
}

function fmtUserStatus(v: unknown) {
  const n = Number(v);
  if (n === 1) return "正常";
  if (n === 0) return "禁用";
  return safeText(v);
}

function mainUid(row: Record<string, unknown>) {
  return row.user_uid ?? row.userUid ?? row.uid ?? row.owner_uid ?? row.ownerUid;
}

function friendUid(row: Record<string, unknown>) {
  return row.friend_uid ?? row.friendUid ?? row.target_uid ?? row.targetUid;
}

function groupId(row: Record<string, unknown>) {
  return row.group_id ?? row.g_id ?? row.groupId;
}

function openUser(uid: unknown) {
  const v = String(uid ?? "").trim();
  if (!v) return;
  router.push(`/im-admin/users/detail/${encodeURIComponent(v)}`);
}

function openGroup(gid: unknown) {
  const v = String(gid ?? "").trim();
  if (!v) return;
  router.push(`/im-admin/groups/detail/${encodeURIComponent(v)}`);
}

function resetRows() {
  rows.value = [];
  page.total = 0;
  listHint.value = "";
}

function validateBeforeQuery() {
  if (activeTab.value === "sameIp" && !form.user_uid.trim()) {
    message("同 IP 查询请输入锚点 UID", { type: "warning" });
    return false;
  }
  if (activeTab.value === "sameDevice") {
    if (!form.user_uid.trim() && !form.device_id.trim()) {
      message("同设备查询请输入用户 UID 或设备 ID（至少一项）", { type: "warning" });
      return false;
    }
  }
  if (!["sameIp", "sameDevice"].includes(activeTab.value) && !form.user_uid.trim()) {
    message("请输入用户 UID", { type: "warning" });
    return false;
  }
  return true;
}

async function fetchList() {
  if (!validateBeforeQuery()) return;
  loading.value = true;
  listHint.value = "";
  try {
    const base = {
      page: page.current,
      page_size: page.size,
      keyword: keyword.value.trim() || undefined
    };
    let res;
    if (activeTab.value === "friends") {
      res = await getUserFriends({ user_uid: form.user_uid.trim(), ...base });
    } else if (activeTab.value === "groups") {
      res = await getUserGroups({ user_uid: form.user_uid.trim(), ...base });
    } else if (activeTab.value === "sameIp") {
      res = await getRelationsSameIp({
        user_uid: form.user_uid.trim(),
        ip: form.ip.trim() || undefined,
        page: page.current,
        page_size: page.size
      });
      if (res.hint === "no_login_ip_in_history") {
        listHint.value = "该用户暂无成功登录 IP 记录";
      }
    } else {
      res = await getRelationsSameDevice({
        user_uid: form.user_uid.trim() || undefined,
        device_id: form.device_id.trim() || undefined,
        page: page.current,
        page_size: page.size
      });
      if (res.hint === "no_device_in_history") {
        listHint.value = "该用户暂无设备登录记录";
      } else if (res.hint === "no_users_on_device") {
        listHint.value = "该设备 ID 暂无关联账号";
      }
    }
    rows.value = res.items;
    page.total = res.total;
    page.size = res.page_size || page.size;
    if (res.truncated && !listHint.value) {
      listHint.value = "结果可能未完全加载（truncated=true）";
    }
  } catch (err: unknown) {
    resetRows();
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 404) {
      message("用户不存在", { type: "warning" });
    } else if (ax?.response?.status === 422) {
      message(adminApiErrMessage(err, "参数错误"), { type: "warning" });
    } else {
      message(adminApiErrMessage(err, "关系查询失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function onTabChange() {
  page.current = 1;
  resetRows();
}

function resetForm() {
  form.user_uid = "";
  form.ip = "";
  form.device_id = "";
  keyword.value = "";
  page.current = 1;
  resetRows();
}

useAdminRealtimeInvalidate(
  [
    ADMIN_REALTIME_EVENTS.RELATION_UPDATED,
    ADMIN_REALTIME_EVENTS.FRIEND_UPDATED,
    ADMIN_REALTIME_EVENTS.GROUP_MEMBER_UPDATED,
    ADMIN_REALTIME_EVENTS.DEVICE_ONLINE_CHANGED,
    ADMIN_REALTIME_EVENTS.USER_STATUS_CHANGED
  ],
  () => {
    if (
      rows.value.length > 0 ||
      form.user_uid.trim() ||
      form.ip.trim() ||
      form.device_id.trim()
    ) {
      return fetchList();
    }
  },
  { debounceMs: 600 }
);

onMounted(() => {
  const uid = String(route.query.user_uid ?? "").trim();
  if (uid) {
    form.user_uid = uid;
    void fetchList();
  }
});
</script>

<template>
  <div class="p-4">
    <el-alert
      class="mb-4"
      :closable="false"
      type="info"
      :title="tabGuide"
    />

    <el-card shadow="never" class="mb-4">
      <el-tabs v-model="activeTab" @tab-change="onTabChange">
        <el-tab-pane label="好友列表" name="friends" />
        <el-tab-pane label="加入群组" name="groups" />
        <el-tab-pane label="同 IP 账号" name="sameIp" />
        <el-tab-pane label="同设备账号" name="sameDevice" />
      </el-tabs>

      <el-form :inline="true" :model="form" label-width="88px">
        <el-form-item label="用户 IM 号">
          <el-input
            v-model="form.user_uid"
            class="w-44!"
            clearable
            :placeholder="
              activeTab === 'sameDevice'
                ? '可选，查该用户关联设备上的其他账号'
                : '必填，用户 IM 号'
            "
          />
        </el-form-item>
        <el-form-item v-if="activeTab === 'sameIp'" label="过滤 IP">
          <el-input
            v-model="form.ip"
            class="w-42!"
            clearable
            placeholder="可选，精确匹配 shared_ip"
          />
        </el-form-item>
        <el-form-item v-if="activeTab === 'sameDevice'" label="设备 ID">
          <el-input
            v-model="form.device_id"
            class="w-48!"
            clearable
            placeholder="可选，仅填此项查该设备全部账号"
          />
        </el-form-item>
        <el-form-item
          v-if="['friends', 'groups'].includes(activeTab)"
          label="关键字"
        >
          <el-input
            v-model="keyword"
            class="w-48!"
            clearable
            placeholder="昵称 / UID / 群名"
          />
        </el-form-item>
        <el-form-item>
          <el-button
            type="primary"
            :loading="loading"
            @click="
              page.current = 1;
              fetchList();
            "
          >
            查询
          </el-button>
          <el-button @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="flex items-center justify-between gap-3">
          <div class="flex flex-col gap-1">
            <span>关系结果</span>
            <span v-if="listHint" class="text-xs text-[var(--el-text-color-secondary)]">
              {{ listHint }}
            </span>
          </div>
          <el-button :loading="loading" @click="fetchList">刷新</el-button>
        </div>
      </template>

      <el-table
        v-loading="loading"
        :data="rows"
        stripe
        border
        :height="tableHeight"
        size="small"
      >
        <template #empty>
          <el-empty description="请输入条件后点击查询" :image-size="80" />
        </template>
        <template v-if="activeTab === 'friends'">
          <el-table-column label="用户 UID" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <el-button link type="primary" @click="openUser(mainUid(row))">
                {{ safeText(mainUid(row)) }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="好友 UID" min-width="110" show-overflow-tooltip>
            <template #default="{ row }">
              <el-button link type="primary" @click="openUser(friendUid(row))">
                {{ safeText(friendUid(row)) }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="好友昵称" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">
              {{ safeText(row.friend_nickname ?? row.nickname) }}
            </template>
          </el-table-column>
          <el-table-column label="添加时间" min-width="170">
            <template #default="{ row }">{{ fmtTime(row.add_time) }}</template>
          </el-table-column>
        </template>

        <template v-else-if="activeTab === 'groups'">
          <el-table-column label="群 ID" min-width="180" show-overflow-tooltip>
            <template #default="{ row }">
              <el-button link type="primary" @click="openGroup(groupId(row))">
                {{ safeText(groupId(row)) }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="群名称" min-width="160" show-overflow-tooltip>
            <template #default="{ row }">{{ safeText(row.group_name) }}</template>
          </el-table-column>
          <el-table-column label="成员数" width="90">
            <template #default="{ row }">{{ safeText(row.member_count) }}</template>
          </el-table-column>
          <el-table-column label="加入时间" min-width="170">
            <template #default="{ row }">{{ fmtTime(row.join_time) }}</template>
          </el-table-column>
          <el-table-column label="身份" width="110">
            <template #default="{ row }">{{ safeText(row.group_type ?? row.role) }}</template>
          </el-table-column>
        </template>

        <template v-else>
          <el-table-column label="UID" min-width="120">
            <template #default="{ row }">
              <el-button link type="primary" @click="openUser(mainUid(row))">
                {{ safeText(mainUid(row)) }}
              </el-button>
            </template>
          </el-table-column>
          <el-table-column label="昵称" min-width="130" show-overflow-tooltip>
            <template #default="{ row }">{{ safeText(row.nickname) }}</template>
          </el-table-column>
          <el-table-column
            v-if="activeTab === 'sameIp'"
            label="共同 IP"
            min-width="146"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ safeText(row.shared_ip ?? row.ip) }}
            </template>
          </el-table-column>
          <el-table-column
            v-if="activeTab === 'sameDevice'"
            label="共同设备"
            min-width="180"
            show-overflow-tooltip
          >
            <template #default="{ row }">
              {{ safeText(row.shared_device_id ?? row.device_id) }}
            </template>
          </el-table-column>
          <el-table-column label="手机号" min-width="120" show-overflow-tooltip>
            <template #default="{ row }">{{ safeText(row.phone_num) }}</template>
          </el-table-column>
          <el-table-column label="账号状态" width="100">
            <template #default="{ row }">{{ fmtUserStatus(row.user_status) }}</template>
          </el-table-column>
        </template>
      </el-table>

      <div class="mt-3 flex justify-end">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          background
          layout="total, sizes, prev, pager, next"
          :total="page.total"
          :page-sizes="[10, 20, 50, 100]"
          @size-change="fetchList"
          @current-change="fetchList"
        />
      </div>
    </el-card>
  </div>
</template>
