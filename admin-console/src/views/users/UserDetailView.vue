<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import {
  adjustBalance,
  getUserDetail,
  getUserWallet,
  loginUnfreeze,
  resetFundPassword,
  resetLoginPassword,
  setGamePrivileged,
  setLoginDisabled,
  setSkipDeviceSms,
  updateNickname
} from "@/api/users";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const props = withDefaults(
  defineProps<{
    userUid?: string;
    embedded?: boolean;
  }>(),
  { embedded: false }
);

const emit = defineEmits<{
  updated: [];
}>();

const route = useRoute();
const router = useRouter();
const uid = computed(() => String(props.userUid || route.params.userUid || ""));
const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const detail = ref<Record<string, unknown>>({});
const wallet = ref<Record<string, unknown>>({});

const nicknameForm = reactive({ nickname: "" });
const loginPwd = reactive({ password: "" });
const fundPwd = reactive({ password: "" });
const adjust = reactive({
  currency: "USDT",
  direction: "add",
  amount: "",
  remark: ""
});

const links = computed(() => {
  const q = encodeURIComponent(uid.value);
  return [
    { label: "好友关系", path: `/risk/relations?user_uid=${q}` },
    { label: "设备终端", path: `/risk/devices?user_uid=${q}` },
    { label: "登录记录", path: `/users/login-records?user_uid=${q}` },
    { label: "消息审计", path: `/risk/messages?user_a=${q}` },
    { label: "聊天投诉", path: `/risk/complaints?user_uid=${q}` },
    { label: "账变记录", path: `/wallet/ledger?user_uid=${q}` },
    { label: "提现审核", path: `/wallet/withdraws?user_uid=${q}` },
    { label: "通讯录", path: `/privacy/contacts?user_uid=${q}` },
    { label: "相册", path: `/privacy/album?user_uid=${q}` }
  ];
});

const statusDisabled = computed(() => {
  const s = detail.value.user_status ?? detail.value.status;
  return Number(s) === 0;
});

const gamePrivileged = computed({
  get: () => Boolean(detail.value.game_privileged ?? detail.value.gamePrivileged),
  set: () => undefined
});

const skipDeviceSms = computed({
  get: () => Boolean(detail.value.skip_device_sms ?? detail.value.skipDeviceSms),
  set: () => undefined
});

const walletRows = computed(() => {
  const currencies = wallet.value.currencies;
  if (Array.isArray(currencies)) return currencies as Record<string, unknown>[];
  const balances = (wallet.value.wallet_balances ||
    wallet.value.walletBalances ||
    {}) as Record<string, unknown>;
  return Object.keys(balances).map(code => ({
    currency: code,
    balance_available: balances[code],
    balance_frozen:
      ((wallet.value.wallet_frozen_by_currency ||
        wallet.value.walletFrozenByCurrency ||
        {}) as Record<string, unknown>)[code] ?? "—"
  }));
});

function pickUser(raw: Record<string, unknown>) {
  const user = (raw.user || raw.profile || raw) as Record<string, unknown>;
  return user;
}

async function load() {
  loading.value = true;
  try {
    const [d, w] = await Promise.all([
      getUserDetail(uid.value),
      getUserWallet(uid.value).catch(() => ({} as Record<string, unknown>))
    ]);
    detail.value = pickUser(d);
    wallet.value = w;
    nicknameForm.nickname = String(detail.value.nickname || "");
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function runWrite(label: string, fn: () => Promise<unknown>, reload = true) {
  try {
    await fn();
    ElMessage.success(label);
    if (reload) await load();
    emit("updated");
  } catch (e) {
    ElMessage.warning(errMessage(e));
  }
}

async function onToggleDisabled() {
  if (!canWrite.value) return;
  const disabled = !statusDisabled.value;
  try {
    await ElMessageBox.confirm(
      disabled ? "确认禁用该用户并清理会话？" : "确认解禁该用户？",
      "确认",
      { type: "warning" }
    );
    await runWrite(disabled ? "已禁用" : "已解禁", () =>
      setLoginDisabled({
        user_uid: uid.value,
        disabled,
        clear_http_token: disabled
      })
    );
  } catch (e) {
    if (e !== "cancel") ElMessage.warning(errMessage(e));
  }
}

async function onGamePrivileged(val: boolean) {
  await runWrite("游戏特权已更新", () =>
    setGamePrivileged({ user_uid: uid.value, game_privileged: val })
  );
}

async function onSkipDeviceSms(val: boolean) {
  await runWrite("设备短信策略已更新", () =>
    setSkipDeviceSms({ user_uid: uid.value, enabled: val })
  );
}

async function onSaveNickname() {
  if (!nicknameForm.nickname.trim()) {
    ElMessage.warning("昵称不能为空");
    return;
  }
  await runWrite("昵称已更新", () =>
    updateNickname({ user_uid: uid.value, nickname: nicknameForm.nickname.trim() })
  );
}

async function onResetLoginPwd() {
  if (loginPwd.password.length < 6) {
    ElMessage.warning("登录密码至少 6 位");
    return;
  }
  try {
    await ElMessageBox.confirm("确认重置登录密码？", "危险操作", { type: "warning" });
    await runWrite("登录密码已重置", async () => {
      await resetLoginPassword({
        user_uid: uid.value,
        new_password: loginPwd.password
      });
      loginPwd.password = "";
    });
  } catch (e) {
    if (e !== "cancel") ElMessage.warning(errMessage(e));
  }
}

async function onResetFundPwd() {
  if (!/^\d{6}$/.test(fundPwd.password)) {
    ElMessage.warning("资金密码须为 6 位数字");
    return;
  }
  try {
    await ElMessageBox.confirm("确认重置资金密码？", "危险操作", { type: "warning" });
    await runWrite("资金密码已重置", async () => {
      await resetFundPassword({
        user_uid: uid.value,
        new_fund_password: fundPwd.password
      });
      fundPwd.password = "";
    });
  } catch (e) {
    if (e !== "cancel") ElMessage.warning(errMessage(e));
  }
}

async function onAdjust() {
  if (!adjust.amount) {
    ElMessage.warning("请填写金额");
    return;
  }
  try {
    await ElMessageBox.confirm(
      `确认对 ${uid.value} ${adjust.direction === "add" ? "增加" : "扣减"} ${adjust.amount} ${adjust.currency}？`,
      "调账确认",
      { type: "warning" }
    );
    await runWrite("调账成功", async () => {
      await adjustBalance({
        user_uid: uid.value,
        currency: adjust.currency,
        direction: adjust.direction,
        amount: adjust.amount,
        remark: adjust.remark || undefined
      });
      adjust.amount = "";
      adjust.remark = "";
    });
  } catch (e) {
    if (e !== "cancel") ElMessage.warning(errMessage(e));
  }
}

async function onUnfreeze() {
  try {
    await ElMessageBox.confirm("确认解除该用户登录试错冻结？", "确认");
    await runWrite("已解冻登录限制", () => loginUnfreeze({ user_uid: uid.value }));
  } catch (e) {
    if (e !== "cancel") ElMessage.warning(errMessage(e));
  }
}

watch(
  uid,
  v => {
    if (v) load();
  },
  { immediate: true }
);
</script>

<template>
  <div class="page" :class="{ embedded }" v-loading="loading">
    <PageHeader v-if="!embedded" :title="`用户详情 · ${uid}`">
      <el-button @click="router.push('/users')">返回列表</el-button>
    </PageHeader>

    <div class="page-card mb">
      <el-descriptions :column="2" border>
        <el-descriptions-item label="UID">
          {{ detail.user_uid || detail.userUid || uid }}
        </el-descriptions-item>
        <el-descriptions-item label="昵称">{{ detail.nickname || "—" }}</el-descriptions-item>
        <el-descriptions-item label="手机">
          {{ detail.phone_num || detail.phoneNum || "—" }}
        </el-descriptions-item>
        <el-descriptions-item label="状态">
          <el-tag :type="statusDisabled ? 'danger' : 'success'" size="small">
            {{ statusDisabled ? "禁用" : "正常" }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="注册时间">
          {{ formatTime(detail.register_time || detail.registerTime) }}
        </el-descriptions-item>
        <el-descriptions-item label="最近登录">
          {{ formatTime(detail.latest_login_time || detail.latestLoginTime) }}
        </el-descriptions-item>
      </el-descriptions>
    </div>

    <div v-if="canWrite" class="page-card mb">
      <h3 class="sec">账号操作</h3>
      <el-space wrap>
        <el-button :type="statusDisabled ? 'success' : 'danger'" @click="onToggleDisabled">
          {{ statusDisabled ? "解禁登录" : "禁用登录" }}
        </el-button>
        <el-button @click="onUnfreeze">解除登录冻结</el-button>
        <span class="switch-item">
          游戏特权
          <el-switch
            :model-value="gamePrivileged"
            @change="(v: string | number | boolean) => onGamePrivileged(Boolean(v))"
          />
        </span>
        <span class="switch-item">
          免设备短信
          <el-switch
            :model-value="skipDeviceSms"
            @change="(v: string | number | boolean) => onSkipDeviceSms(Boolean(v))"
          />
        </span>
      </el-space>

      <el-divider />

      <el-form label-width="100px" style="max-width: 520px">
        <el-form-item label="修改昵称">
          <el-input v-model="nicknameForm.nickname" style="max-width: 280px" />
          <el-button style="margin-left: 8px" type="primary" @click="onSaveNickname">保存</el-button>
        </el-form-item>
        <el-form-item label="登录密码">
          <el-input
            v-model="loginPwd.password"
            type="password"
            show-password
            placeholder="新密码 ≥6 位"
            style="max-width: 280px"
          />
          <el-button style="margin-left: 8px" type="warning" @click="onResetLoginPwd">重置</el-button>
        </el-form-item>
        <el-form-item label="资金密码">
          <el-input
            v-model="fundPwd.password"
            type="password"
            show-password
            placeholder="6 位数字"
            style="max-width: 280px"
          />
          <el-button style="margin-left: 8px" type="warning" @click="onResetFundPwd">重置</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div v-if="canWrite" class="page-card mb">
      <h3 class="sec">钱包调账</h3>
      <el-form inline>
        <el-form-item label="币种">
          <el-select v-model="adjust.currency" style="width: 120px">
            <el-option label="USDT" value="USDT" />
            <el-option label="CNY" value="CNY" />
            <el-option label="TRX" value="TRX" />
          </el-select>
        </el-form-item>
        <el-form-item label="方向">
          <el-select v-model="adjust.direction" style="width: 120px">
            <el-option label="增加" value="add" />
            <el-option label="扣减" value="subtract" />
          </el-select>
        </el-form-item>
        <el-form-item label="金额">
          <el-input v-model="adjust.amount" placeholder="金额" style="width: 140px" />
        </el-form-item>
        <el-form-item label="备注">
          <el-input v-model="adjust.remark" placeholder="可选" style="width: 180px" />
        </el-form-item>
        <el-form-item>
          <el-button type="danger" @click="onAdjust">提交调账</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card mb">
      <h3 class="sec">钱包余额</h3>
      <el-table :data="walletRows" stripe border empty-text="暂无余额数据">
        <el-table-column prop="currency" label="币种" width="100" />
        <el-table-column label="可用" min-width="120">
          <template #default="{ row }">
            {{ row.balance_available ?? row.available ?? row.balance ?? "—" }}
          </template>
        </el-table-column>
        <el-table-column label="冻结" min-width="120">
          <template #default="{ row }">
            {{ row.balance_frozen ?? row.frozen ?? "—" }}
          </template>
        </el-table-column>
      </el-table>
    </div>

    <div class="page-card">
      <h3 class="sec">快捷入口</h3>
      <el-space wrap>
        <el-button v-for="l in links" :key="l.path" @click="router.push(l.path)">
          {{ l.label }}
        </el-button>
      </el-space>
    </div>
  </div>
</template>

<style scoped>
.mb {
  margin-bottom: 12px;
}
.sec {
  margin: 0 0 12px;
  font-size: 15px;
}
.switch-item {
  display: inline-flex;
  align-items: center;
  gap: 8px;
  margin-left: 8px;
  color: #475569;
  font-size: 14px;
}
.embedded {
  padding: 0 4px 12px;
}
</style>
