<script setup lang="ts">
import { ref, computed, watch, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import { deviceDetection } from "@pureadmin/utils";
import userAvatar from "@/assets/user.jpg";
import {
  getAdminUserDetail,
  mapAdminUserItemToDetailView,
  adminApiErrMessage,
  postUserGamePrivileged,
  formatLoginHistoryDeviceType,
  normalizeImUserUid,
  isImUserUid,
  type AdminUserListItem,
  type AdminUserWalletResponse
} from "@/api/im-user";
import {
  buildWalletBalanceSnapshot,
  formatWalletCurrencyAmount,
  getWalletCurrencyMeta,
  resolveWalletAddressForCurrency,
  WALLET_CURRENCY_OPTIONS
} from "./utils/walletCurrencyPolicy";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import { displayDeviceModelCell, deviceTypeToPlatform } from "@/utils/deviceModelDisplay";
import { message } from "@/utils/message";
import { hasPerms } from "@/utils/auth";
import { toggleImUserLoginDisabledWithConfirm } from "./utils/toggleLoginDisabled";

defineOptions({ name: "ImAccountUserDetail" });

const route = useRoute();
const router = useRouter();

const loading = ref(true);
const detail = ref<Record<string, unknown> | null>(null);
const detailWallet = ref<AdminUserWalletResponse | null>(null);
const loginToggleBusy = ref(false);
const gamePrivilegeBusy = ref(false);

const statusMap: Record<
  number,
  { label: string; type: "success" | "danger" | "warning" | "info" }
> = {
  1: { label: "正常", type: "success" },
  0: { label: "禁用", type: "danger" }
};

const userId = computed(() => normalizeImUserUid(route.params.id));
const canUserWrite = computed(() => hasPerms("user.write"));

const walletCurrencyRows = computed(() => {
  const embedded = detailWallet.value?.currencies ?? [];
  if (embedded.length) {
    return embedded.map(row => {
      const code = row.currency;
      const meta = getWalletCurrencyMeta(code);
      return {
        code,
        label: meta.label,
        availableText: formatWalletCurrencyAmount(code, row.balance_available),
        frozenText: formatWalletCurrencyAmount(code, row.balance_frozen),
        address: row.wallet_address?.trim() || "—",
        addressLabel: row.wallet_address_label?.trim() || ""
      };
    });
  }
  const d = detail.value;
  if (!d) return [];
  const raw = (d._raw ?? d) as AdminUserListItem;
  const snap = buildWalletBalanceSnapshot(raw);
  const walletMeta = detailWallet.value;
  const addressCtx: Record<string, unknown> = {
    ...raw,
    deposit_address: walletMeta?.deposit_address ?? raw.deposit_address ?? null,
    trx_address: walletMeta?.trx_address ?? raw.trx_address ?? null
  };
  return WALLET_CURRENCY_OPTIONS.map(meta => {
    const row = snap[meta.code];
    const code = meta.code;
    return {
      code,
      label: meta.label,
      availableText: formatWalletCurrencyAmount(code, row?.available ?? 0),
      frozenText: formatWalletCurrencyAmount(code, row?.frozen ?? 0),
      address: resolveWalletAddressForCurrency(code, addressCtx),
      addressLabel: code === "USDT" ? "TRON 充值地址" : ""
    };
  });
});

const quickLinks = computed(() => {
  const uid = userId.value;
  if (!isImUserUid(uid)) return [];
  const q = encodeURIComponent(uid);
  return [
    { label: "好友关系", path: `/im-admin/relations/index?user_uid=${q}` },
    { label: "设备终端", path: `/im-admin/devices/index?user_uid=${q}` },
    { label: "登录记录", path: `/im-admin/devices/login-records?user_uid=${q}` },
    { label: "消息审计", path: `/im-admin/messages/query?user_a=${q}` },
    { label: "查看通讯录", path: `/im-admin/privacy/contacts?user_uid=${q}` },
    { label: "查看相册", path: `/im-admin/privacy/album?user_uid=${q}` },
    { label: "隐私数据总览", path: "/im-admin/privacy/index" }
  ];
});

function redirectLegacyTabQuery() {
  const tab = String(route.query.tab ?? "").trim();
  const privacyTab = String(route.query.privacyTab ?? "").trim();
  const uid = userId.value;
  if (!isImUserUid(uid)) return;

  if (tab === "contacts" || privacyTab === "contacts") {
    router.replace({ path: "/im-admin/privacy/contacts", query: { user_uid: uid } });
    return;
  }
  if (tab === "album" || privacyTab === "album") {
    router.replace({ path: "/im-admin/privacy/album", query: { user_uid: uid } });
    return;
  }
  if (tab === "privacy" || tab === "files" || privacyTab === "files") {
    router.replace({ name: "ImAccountUserDetail", params: { id: uid } });
    return;
  }
  if (tab === "social") {
    router.replace({ path: "/im-admin/relations/index", query: { user_uid: uid } });
    return;
  }
  if (tab === "devices") {
    router.replace({ path: "/im-admin/devices/index", query: { user_uid: uid } });
  }
}

function accountStatusMeta(d: Record<string, unknown>) {
  const raw = Number(d.userStatusRaw);
  if (Number.isFinite(raw) && raw === -2) return { label: "注销", type: "warning" as const };
  const st = Number(d.status);
  return statusMap[st] ?? { label: "未知", type: "info" as const };
}

function fmtDateTime(v: unknown) {
  if (v == null || v === "" || v === 0 || v === "0") return "—";
  return formatAdminUnixTime(v, true);
}

function fmtNicknameModified(d: Record<string, unknown>) {
  const s = fmtDateTime(d.nicknameLastModified);
  if (s !== "—") return s;
  return fmtDateTime(d.nicknameLastModified2);
}

function go(path: string) {
  router.push(path);
}

function goBack() {
  router.push({ name: "ImUsersList" });
}

async function toggleLoginDisableDetail() {
  const d = detail.value;
  if (!d || !isImUserUid(userId.value)) return;
  loginToggleBusy.value = true;
  try {
    const ok = await toggleImUserLoginDisabledWithConfirm({
      user_uid: normalizeImUserUid(d.id ?? d.uid ?? userId.value),
      nickname: d.nickname,
      userStatusRaw: d.userStatusRaw
    });
    if (ok) await fetchDetail();
  } finally {
    loginToggleBusy.value = false;
  }
}

async function saveGamePrivilege(gamePrivileged: boolean) {
  const d = detail.value;
  if (!d || !isImUserUid(userId.value)) return;
  if (Boolean(d.gamePrivileged) === gamePrivileged) return;
  const uid = normalizeImUserUid(d.id ?? d.uid ?? userId.value);
  gamePrivilegeBusy.value = true;
  try {
    await postUserGamePrivileged({ user_uid: uid, game_privileged: gamePrivileged });
    message(gamePrivileged ? "已开启游戏特权" : "已关闭游戏特权", { type: "success" });
    await fetchDetail();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "游戏特权设置失败"), { type: "warning" });
  } finally {
    gamePrivilegeBusy.value = false;
  }
}

async function fetchDetail() {
  if (!isImUserUid(userId.value)) {
    loading.value = false;
    detail.value = null;
    detailWallet.value = null;
    return;
  }
  loading.value = true;
  detailWallet.value = null;
  try {
    const res = await getAdminUserDetail(userId.value);
    const avatarBase = import.meta.env.VITE_USER_AVATAR_BASE_URL ?? "";
    detail.value = mapAdminUserItemToDetailView(
      res.profile as AdminUserListItem,
      String(avatarBase)
    );
    detailWallet.value = res.wallet ?? null;
  } catch (err: unknown) {
    detail.value = null;
    detailWallet.value = null;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    const status = ax.response?.status;
    const errCode = ax.response?.data?.error;
    if (status === 403 || errCode === "forbidden") {
      message("无用户查看权限（需要 user.read）", { type: "warning" });
    } else if (status === 404 || errCode === "not_found" || errCode === "user_not_found") {
      message("未找到该用户", { type: "warning" });
    } else if (status !== 401) {
      message("用户信息加载失败", { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

watch(
  () => route.params.id,
  () => {
    redirectLegacyTabQuery();
    fetchDetail();
  }
);

watch(
  () => route.query,
  () => redirectLegacyTabQuery(),
  { deep: true }
);

onMounted(() => {
  redirectLegacyTabQuery();
  fetchDetail();
});
</script>

<template>
  <div
    class="detail-page p-4"
    :class="[deviceDetection() ? '' : 'max-w-312 mx-auto']"
    v-loading="loading"
  >
    <el-page-header class="mb-4!" @back="goBack">
      <template #content>
        <span class="text-lg font-medium">
          用户详情
          <template v-if="detail">· {{ detail.nickname }}（{{ detail.uid }}）</template>
        </span>
      </template>
      <template v-if="detail" #extra>
        <el-tag :type="accountStatusMeta(detail).type" size="small" effect="plain" class="mr-2">
          {{ accountStatusMeta(detail).label }}
        </el-tag>
        <el-tag :type="detail.online ? 'success' : 'info'" size="small" effect="plain">
          {{ detail.online ? "在线" : "离线" }}
        </el-tag>
      </template>
    </el-page-header>

    <el-empty v-if="!loading && !detail" description="未找到该用户或参数无效">
      <el-button type="primary" @click="goBack">返回用户列表</el-button>
    </el-empty>

    <template v-else-if="detail">
      <el-card shadow="never" class="mb-4! border border-[var(--el-border-color-light)]">
        <template #header><span class="font-medium">基本信息</span></template>
        <div class="mb-6 flex flex-wrap items-start gap-6">
          <el-image
            :src="(detail.avatar as string) || userAvatar"
            fit="cover"
            class="size-28 shrink-0 overflow-hidden rounded-lg border border-[var(--el-border-color-lighter)]"
            preview-teleported
            :preview-src-list="[(detail.avatar as string) || userAvatar]"
          />
          <div class="min-w-[min(100%,540px)] flex-1">
            <el-descriptions :column="2" border size="small">
              <el-descriptions-item label="IM 号">{{ detail.uid }}</el-descriptions-item>
              <el-descriptions-item label="好友 / 有效群">
                {{ detail.friendCount ?? "—" }} / {{ detail.groupCount ?? "—" }}
              </el-descriptions-item>
              <el-descriptions-item label="昵称">{{ detail.nickname || "—" }}</el-descriptions-item>
              <el-descriptions-item label="手机号">{{ detail.phone || "—" }}</el-descriptions-item>
              <el-descriptions-item label="注册 IP">{{ detail.registerIp || "—" }}</el-descriptions-item>
              <el-descriptions-item label="注册时间">{{ fmtDateTime(detail.registerTime) }}</el-descriptions-item>
              <el-descriptions-item label="昵称修改时间" :span="2">
                {{ fmtNicknameModified(detail) }}
              </el-descriptions-item>
            </el-descriptions>
          </div>
        </div>
      </el-card>

      <el-card shadow="never" class="mb-4! border border-[var(--el-border-color-light)]">
        <template #header><span class="font-medium">账号与钱包</span></template>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="账号状态">
            <el-tag :type="accountStatusMeta(detail).type" size="small" effect="plain">
              {{ accountStatusMeta(detail).label }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="在线状态">
            <el-tag :type="detail.online ? 'success' : 'info'" size="small" effect="plain">
              {{ detail.online ? "在线" : "离线" }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item v-if="canUserWrite" label="登录管控" :span="2">
            <el-button
              v-if="Number(detail.userStatusRaw) === 1"
              type="danger"
              plain
              size="small"
              :loading="loginToggleBusy"
              @click="toggleLoginDisableDetail"
            >
              禁用账号登录
            </el-button>
            <el-button
              v-else
              type="success"
              plain
              size="small"
              :loading="loginToggleBusy"
              @click="toggleLoginDisableDetail"
            >
              允许账号登录
            </el-button>
          </el-descriptions-item>
          <el-descriptions-item v-if="canUserWrite" label="游戏特权" :span="2">
            <el-switch
              :model-value="Boolean(detail.gamePrivileged)"
              :loading="gamePrivilegeBusy"
              @change="saveGamePrivilege"
            />
          </el-descriptions-item>
        </el-descriptions>
        <div class="mt-4">
          <div class="mb-2 text-sm font-medium">币种余额与钱包地址</div>
          <el-table :data="walletCurrencyRows" border stripe size="small">
            <el-table-column prop="label" label="币种" width="128" />
            <el-table-column label="可用余额" min-width="140">
              <template #default="{ row }">{{ row.availableText }}</template>
            </el-table-column>
            <el-table-column label="冻结" min-width="120">
              <template #default="{ row }">{{ row.frozenText }}</template>
            </el-table-column>
            <el-table-column label="钱包地址" min-width="260" show-overflow-tooltip>
              <template #default="{ row }">{{ row.address }}</template>
            </el-table-column>
          </el-table>
        </div>
      </el-card>

      <el-card shadow="never" class="mb-4! border border-[var(--el-border-color-light)]">
        <template #header><span class="font-medium">最近登录与设备摘要</span></template>
        <el-descriptions :column="2" border size="small">
          <el-descriptions-item label="终端类型">{{ detail.loginDevices || "—" }}</el-descriptions-item>
          <el-descriptions-item label="设备机型">
            {{ detail.deviceModel || "—" }}
          </el-descriptions-item>
          <el-descriptions-item label="最近登录时间">
            {{ fmtDateTime(detail.lastLoginTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="最近登录 IP">{{ detail.loginIp || "—" }}</el-descriptions-item>
        </el-descriptions>
      </el-card>

      <el-card shadow="never" class="border border-[var(--el-border-color-light)]">
        <template #header><span class="font-medium">快捷跳转</span></template>
        <p class="mt-0 mb-3 text-sm text-gray-500 dark:text-gray-400">
          社交、设备、消息、通讯录与相册请在独立页面查看，避免与本页重复。
        </p>
        <div class="flex flex-wrap gap-2">
          <el-button v-for="link in quickLinks" :key="link.path" @click="go(link.path)">
            {{ link.label }}
          </el-button>
        </div>
      </el-card>
    </template>
  </div>
</template>
