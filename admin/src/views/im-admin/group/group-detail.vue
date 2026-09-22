<script setup lang="ts">
import { ref, computed, watch, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import dayjs from "dayjs";
import { deviceDetection } from "@pureadmin/utils";
import { getGroupDetail, postGroupGameEnabled } from "@/api/im-group";
import { getAdminAuthMe } from "@/api/admin-auth";
import { adminApiErrMessage } from "@/api/im-user";
import { hasPerms, userKey } from "@/utils/auth";
import { storageLocal } from "@pureadmin/utils";
import { useUserStoreHook } from "@/store/modules/user";
import { message } from "@/utils/message";
import GroupMembersPanel from "./components/GroupMembersPanel.vue";
import GroupMessagesPanel from "./components/GroupMessagesPanel.vue";

defineOptions({
  name: "ImGroupDetail"
});

const GROUP_ID_RE = /^[A-Za-z0-9@#_-]{1,128}$/;

const GSTATUS_META: Record<
  number,
  { label: string; type: "success" | "danger" | "warning" | "info" }
> = {
  [-1]: { label: "已删除", type: "info" },
  0: { label: "正常(0)", type: "success" },
  1: { label: "封禁等(1)", type: "danger" },
  2: { label: "全员禁言(2)", type: "warning" }
};

const route = useRoute();
const router = useRouter();

const gId = computed(() => String(route.params.gId ?? "").trim());

const loading = ref(true);
const group = ref<Record<string, unknown> | null>(null);
const gameEnabled = ref(false);
const gameSaving = ref(false);
const permissionsReady = ref(false);
const canGroupWrite = computed(() => hasPerms("group.write"));

const panelCtx = computed(() => ({
  gId: gId.value,
  groupNo: gId.value,
  name: String(group.value?.g_name ?? "群聊")
}));

const invalidParam = computed(
  () => gId.value.length > 0 && !GROUP_ID_RE.test(gId.value)
);

function fmtCell(v: unknown): string {
  if (v == null || v === "") return "—";
  if (typeof v === "number" && Number.isFinite(v))
    return Math.abs(v) > 1e12
      ? dayjs(v).format("YYYY-MM-DD HH:mm:ss")
      : String(v);
  if (typeof v === "string") {
    const n = Number(v);
    if (/^-?\d+$/.test(v.trim()) && Number.isFinite(n) && Math.abs(n) > 946684800000) {
      const d = dayjs(n);
      return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
    }
    return v;
  }
  return JSON.stringify(v);
}

function gStatusMeta(raw: unknown) {
  const n = Number(raw);
  return (
    GSTATUS_META[n as keyof typeof GSTATUS_META] ?? {
      label: `状态(${raw})`,
      type: "info" as const
    }
  );
}

async function loadDetail() {
  if (!gId.value || !GROUP_ID_RE.test(gId.value)) {
    loading.value = false;
    group.value = null;
    return;
  }
  loading.value = true;
  group.value = null;
  try {
    const res = await getGroupDetail(gId.value);
    const g = res.group as Record<string, unknown> | undefined;
    group.value = g && typeof g === "object" ? g : null;
    gameEnabled.value = Boolean(group.value?.game_enabled);
    if (!group.value) {
      message("群资料为空", { type: "warning" });
    }
  } catch (err: unknown) {
    group.value = null;
    const ax = err as {
      response?: { status?: number; data?: { error?: string } };
    };
    const st = ax.response?.status;
    const code = ax.response?.data?.error;
    if (st === 403 || code === "forbidden") {
      message("无权限查看群组（需要 group.read）", { type: "warning" });
    } else if (st === 404 || code === "group_not_found") {
      message("群不存在或已删除", { type: "warning" });
    } else if (st !== 401) {
      message(adminApiErrMessage(err, "群详情加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function goBack() {
  router.push({ name: "ImGroupsList" });
}

async function refreshPermissions() {
  try {
    const { user } = await getAdminAuthMe();
    const perms = Array.isArray(user.permissions) ? user.permissions : [];
    useUserStoreHook().SET_PERMS(perms);
    const cached = storageLocal().getItem<Record<string, unknown>>(userKey);
    if (cached && typeof cached === "object") {
      storageLocal().setItem(userKey, { ...cached, permissions: perms });
    }
  } catch {
    // 保持登录态缓存的权限；无权限时开关仍禁用
  } finally {
    permissionsReady.value = true;
  }
}

async function onGameEnabledChange(next: boolean) {
  if (!canGroupWrite) {
    message("无权限（需要 group.write）", { type: "warning" });
    gameEnabled.value = !next;
    return;
  }
  if (!gId.value) return;
  const prev = !next;
  gameSaving.value = true;
  try {
    const res = await postGroupGameEnabled({
      g_id: gId.value,
      game_enabled: next
    });
    gameEnabled.value = Boolean(res.game_enabled ?? next);
    if (group.value) {
      group.value = { ...group.value, game_enabled: gameEnabled.value };
    }
    message(next ? "已开启群游戏" : "已关闭群游戏", { type: "success" });
  } catch (err: unknown) {
    gameEnabled.value = prev;
    const ax = err as {
      response?: { status?: number; data?: { error?: string } };
    };
    const st = ax.response?.status;
    const code = ax.response?.data?.error;
    if (st === 403 || code === "forbidden") {
      message("无权限（需要 group.write）", { type: "warning" });
    } else if (st === 404 || code === "group_not_found") {
      message("群不存在或已删除", { type: "warning" });
    } else if (st !== 401) {
      message(adminApiErrMessage(err, "群游戏开关保存失败"), { type: "warning" });
    }
  } finally {
    gameSaving.value = false;
  }
}

watch(gId, () => {
  void loadDetail();
});

onMounted(() => {
  void refreshPermissions();
  void loadDetail();
});

const extraFields = computed(() => {
  const g = group.value;
  if (!g) return [] as { key: string; value: string }[];
  const skip = new Set([
    "g_id",
    "g_name",
    "g_status",
    "g_notice",
    "g_owner_user_uid",
    "owner_nickname",
    "create_user_uid",
    "create_user_nickname",
    "g_member_count",
    "max_member_count",
    "group_mode",
    "last_seq",
    "create_time",
    "game_enabled"
  ]);
  return Object.keys(g)
    .filter(k => !skip.has(k))
    .sort()
    .map(k => ({
      key: k,
      value: fmtCell(g[k])
    }));
});
</script>

<template>
  <div
    class="group-detail-page p-4"
    :class="[deviceDetection() ? '' : 'max-w-320 mx-auto']"
    v-loading="loading"
  >
    <el-page-header class="mb-4!" @back="goBack">
      <template #content>
        <span class="text-lg font-medium">
          群聊详情
          <el-text v-if="group" type="primary" tag="span" class="ml-1">
            {{ group.g_name }}
          </el-text>
          <el-text v-if="gId" type="info" tag="span" size="small" class="ml-2">
            {{ gId }}
          </el-text>
        </span>
      </template>
    </el-page-header>

    <el-alert
      v-if="invalidParam"
      type="warning"
      :closable="false"
      class="mb-4!"
      title="路由参数 g_id 须为 1～32 位字母或数字"
    />

    <el-empty
      v-if="!loading && !invalidParam && !group"
      description="未加载到群资料"
    >
      <el-button type="primary" @click="goBack">返回群组列表</el-button>
    </el-empty>

    <template v-else-if="group">
      <el-tabs type="border-card" class="rounded-md overflow-hidden">
        <el-tab-pane label="群资料" lazy>
          <div class="py-3">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="群 ID (g_id)">
                {{ fmtCell(group.g_id) }}
              </el-descriptions-item>
              <el-descriptions-item label="群状态 (g_status)">
                <el-tag
                  :type="gStatusMeta(group.g_status).type"
                  size="small"
                  effect="plain"
                >
                  {{ gStatusMeta(group.g_status).label }}
                </el-tag>
              </el-descriptions-item>
              <el-descriptions-item label="群名称" :span="2">
                {{ fmtCell(group.g_name) }}
              </el-descriptions-item>
              <el-descriptions-item label="群公告 (g_notice)" :span="2">
                {{ fmtCell(group.g_notice) }}
              </el-descriptions-item>
              <el-descriptions-item label="群主 UID">
                {{ fmtCell(group.g_owner_user_uid) }}
              </el-descriptions-item>
              <el-descriptions-item label="群主昵称 (owner_nickname)">
                {{ fmtCell(group.owner_nickname) }}
              </el-descriptions-item>
              <el-descriptions-item label="创建人 UID">
                {{ fmtCell(group.create_user_uid) }}
              </el-descriptions-item>
              <el-descriptions-item label="创建人昵称">
                {{ fmtCell(group.create_user_nickname) }}
              </el-descriptions-item>
              <el-descriptions-item label="成员数 / 上限">
                {{ fmtCell(group.g_member_count) }} /
                {{ fmtCell(group.max_member_count) }}
              </el-descriptions-item>
              <el-descriptions-item label="group_mode / last_seq">
                {{ fmtCell(group.group_mode) }} · {{ fmtCell(group.last_seq) }}
              </el-descriptions-item>
              <el-descriptions-item label="创建时间 (create_time)" :span="2">
                {{ fmtCell(group.create_time) }}
              </el-descriptions-item>
              <el-descriptions-item label="群游戏" :span="2">
                <el-alert
                  v-if="permissionsReady && !canGroupWrite"
                  type="warning"
                  :closable="false"
                  show-icon
                  class="mb-2!"
                  title="当前账号无 group.write 权限，无法修改群游戏开关"
                  description="请退出后重新登录以同步最新权限，或联系超级管理员在 admin_account 中开通。"
                />
                <el-switch
                  v-model="gameEnabled"
                  :disabled="!permissionsReady || !canGroupWrite || gameSaving"
                  :loading="gameSaving"
                  active-text="已开启"
                  inactive-text="已关闭"
                  @change="onGameEnabledChange"
                />
                <el-text
                  v-if="!permissionsReady"
                  type="info"
                  size="small"
                  class="ml-2"
                >
                  正在校验权限…
                </el-text>
                <el-text
                  v-else-if="canGroupWrite"
                  type="info"
                  size="small"
                  class="ml-2"
                >
                  开启后客户端群聊将展示群游戏入口
                </el-text>
              </el-descriptions-item>
            </el-descriptions>

            <el-collapse v-if="extraFields.length" class="mt-4 border-none!">
              <el-collapse-item title="其它 group_base 字段" name="extra">
                <el-descriptions :column="2" border size="small">
                  <el-descriptions-item
                    v-for="row in extraFields"
                    :key="row.key"
                    :label="row.key"
                  >
                    {{ row.value }}
                  </el-descriptions-item>
                </el-descriptions>
              </el-collapse-item>
            </el-collapse>
          </div>
        </el-tab-pane>

        <el-tab-pane label="成员" lazy>
          <div class="py-2">
            <GroupMembersPanel :ctx="panelCtx" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="群消息" lazy>
          <div class="py-2">
            <GroupMessagesPanel :ctx="panelCtx" />
          </div>
        </el-tab-pane>
      </el-tabs>
    </template>
  </div>
</template>
