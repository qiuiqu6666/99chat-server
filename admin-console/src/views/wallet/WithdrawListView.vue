<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import {
  approveWithdraw,
  listWithdraws,
  manualCompleteWithdraw,
  rejectWithdraw,
  withdrawTotpConfirm,
  withdrawTotpReset,
  withdrawTotpSetup,
  withdrawTotpStatus
} from "@/api/finance";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const route = useRoute();
const router = useRouter();
const canWrite = computed(() => hasPerm("user.write"));

const loading = ref(false);
const actionLoading = ref("");
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: "",
  user_uid: String(route.query.user_uid || ""),
  status: ""
});

const totpConfigured = ref(false);
const withdrawMode = ref("");
const isAutoMode = computed(() => withdrawMode.value.trim().toLowerCase() === "auto");
const totpLoading = ref(false);
const paidDialogVisible = ref(false);
const paidRow = ref<Record<string, unknown> | null>(null);
const paidForm = reactive({ tx_id: "", totp_code: "" });
const setupVisible = ref(false);
const setupLoading = ref(false);
const confirmLoading = ref(false);
const otpauthUri = ref("");
const setupSecret = ref("");
const confirmCode = ref("");

const statusTagType: Record<string, "success" | "warning" | "danger" | "info"> = {
  待审核: "warning",
  处理中: "warning",
  成功: "success",
  已通过: "success",
  失败: "danger",
  已拒绝: "danger"
};

function pickItems(raw: unknown) {
  const obj = (raw || {}) as Record<string, unknown>;
  const data =
    obj.data && typeof obj.data === "object" && !Array.isArray(obj.data)
      ? (obj.data as Record<string, unknown>)
      : obj;
  const items = (data.items || data.list || data.rows || []) as Record<string, unknown>[];
  return {
    items: Array.isArray(items) ? items : [],
    total: Number(data.total ?? items.length ?? 0)
  };
}

function bizNo(row: Record<string, unknown>) {
  return String(row.biz_no || row.bizNo || row.order_no || row.orderNo || row.id || "");
}

function userUid(row: Record<string, unknown>) {
  return String(row.user_uid || row.userUid || "");
}

function amountText(row: Record<string, unknown>) {
  const n = Number(row.amount);
  if (!Number.isFinite(n)) return String(row.amount ?? "—");
  return n.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 6 });
}

function statusOf(row: Record<string, unknown>) {
  return String(row.status || "");
}

function isPending(row: Record<string, unknown>) {
  return statusOf(row) === "待审核";
}

async function loadTotpStatus() {
  totpLoading.value = true;
  try {
    const res = await withdrawTotpStatus();
    totpConfigured.value = res.configured;
    withdrawMode.value = res.withdraw_mode || "";
  } catch {
    totpConfigured.value = false;
  } finally {
    totpLoading.value = false;
  }
}

async function load() {
  loading.value = true;
  try {
    const raw = await listWithdraws({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      user_uid: query.user_uid || undefined,
      status: query.status || undefined
    });
    const picked = pickItems(raw);
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

async function promptTotp(actionLabel: string) {
  try {
    const { value } = await ElMessageBox.prompt(
      `请输入 Google Authenticator 中的 6 位验证码以${actionLabel}：`,
      "谷歌验证",
      {
        type: "warning",
        confirmButtonText: "确认",
        cancelButtonText: "取消",
        inputPlaceholder: "6 位数字",
        inputPattern: /^\d{6}$/,
        inputErrorMessage: "请输入 6 位数字验证码"
      }
    );
    return String(value || "").trim();
  } catch {
    return "";
  }
}

async function ensureTotpReady() {
  if (totpConfigured.value) return true;
  ElMessage.warning("请先完成提现审核谷歌验证绑定");
  await openSetup();
  return totpConfigured.value;
}

async function openSetup() {
  setupVisible.value = true;
  setupLoading.value = true;
  otpauthUri.value = "";
  setupSecret.value = "";
  confirmCode.value = "";
  try {
    const res = await withdrawTotpSetup();
    otpauthUri.value = res.otpauth_uri;
    setupSecret.value = res.secret;
    if (!otpauthUri.value && !setupSecret.value) {
      ElMessage.warning("生成验证密钥失败");
      setupVisible.value = false;
    }
  } catch (e) {
    setupVisible.value = false;
    ElMessage.warning(errMessage(e, "初始化谷歌验证失败"));
  } finally {
    setupLoading.value = false;
  }
}

async function confirmSetup() {
  if (!/^\d{6}$/.test(confirmCode.value.trim())) {
    ElMessage.warning("请输入 6 位谷歌验证码");
    return;
  }
  confirmLoading.value = true;
  try {
    await withdrawTotpConfirm({ totp_code: confirmCode.value.trim() });
    totpConfigured.value = true;
    setupVisible.value = false;
    ElMessage.success("谷歌验证已启用");
  } catch (e) {
    ElMessage.warning(errMessage(e, "验证失败"));
  } finally {
    confirmLoading.value = false;
  }
}

async function rebindTotp() {
  try {
    await ElMessageBox.confirm(
      "重新绑定将使旧验证码失效，是否继续？",
      "重新绑定谷歌验证",
      { type: "warning" }
    );
  } catch {
    return;
  }
  const code = await promptTotp("验证旧验证码");
  if (!code) return;
  try {
    await withdrawTotpReset({ totp_code: code });
    totpConfigured.value = false;
    ElMessage.success("已重置，请重新绑定");
    await openSetup();
  } catch (e) {
    ElMessage.warning(errMessage(e, "重置失败"));
  }
}

function normalizeTxId(raw: string) {
  let tx = raw.trim();
  if (tx.startsWith("0x") || tx.startsWith("0X")) tx = tx.slice(2);
  return tx;
}

function openPaidDialog(row: Record<string, unknown>) {
  paidRow.value = row;
  paidForm.tx_id = "";
  paidForm.totp_code = "";
  paidDialogVisible.value = true;
}

async function submitPaidManually() {
  const row = paidRow.value;
  const orderNo = row ? bizNo(row) : "";
  if (!orderNo || !canWrite.value) return;
  if (!(await ensureTotpReady())) return;
  const tx = normalizeTxId(paidForm.tx_id);
  if (!/^[0-9a-fA-F]{64}$/.test(tx)) {
    ElMessage.warning("请填写 64 位链上交易哈希");
    return;
  }
  if (!/^\d{6}$/.test(paidForm.totp_code.trim())) {
    ElMessage.warning("请输入 6 位谷歌验证码");
    return;
  }
  actionLoading.value = orderNo;
  try {
    await manualCompleteWithdraw(orderNo, {
      tx_id: tx,
      totp_code: paidForm.totp_code.trim()
    });
    paidDialogVisible.value = false;
    ElMessage.success("已确认打款，订单已结单");
    await load();
  } catch (e) {
    ElMessage.warning(errMessage(e, "确认打款失败"));
  } finally {
    actionLoading.value = "";
  }
}

async function handleApprove(row: Record<string, unknown>) {
  const orderNo = bizNo(row);
  if (!orderNo || !canWrite.value) return;
  if (!(await ensureTotpReady())) return;
  try {
    await ElMessageBox.confirm(
      `确认通过 UID ${userUid(row) || "—"} 的提现？金额 ${amountText(row)}`,
      "提现审核",
      { type: "warning", confirmButtonText: "下一步" }
    );
  } catch {
    return;
  }
  const totpCode = await promptTotp("通过审核");
  if (!totpCode) return;
  actionLoading.value = orderNo;
  try {
    await approveWithdraw(orderNo, { totp_code: totpCode });
    ElMessage.success("已通过，正在链上打款");
    await load();
  } catch (e) {
    ElMessage.warning(errMessage(e, "审核通过失败"));
  } finally {
    actionLoading.value = "";
  }
}

async function handleReject(row: Record<string, unknown>) {
  const orderNo = bizNo(row);
  if (!orderNo || !canWrite.value) return;
  if (!(await ensureTotpReady())) return;
  let remark = "";
  try {
    const { value } = await ElMessageBox.prompt(
      `拒绝 UID ${userUid(row) || "—"} 的提现（${amountText(row)}），可填原因：`,
      "拒绝提现",
      {
        type: "warning",
        confirmButtonText: "下一步",
        inputPlaceholder: "可选，如：地址异常"
      }
    );
    remark = String(value || "").trim();
  } catch {
    return;
  }
  const totpCode = await promptTotp("拒绝审核");
  if (!totpCode) return;
  actionLoading.value = orderNo;
  try {
    await rejectWithdraw(orderNo, { remark, totp_code: totpCode });
    ElMessage.success("已拒绝并退回余额");
    await load();
  } catch (e) {
    ElMessage.warning(errMessage(e, "拒绝失败"));
  } finally {
    actionLoading.value = "";
  }
}

function search() {
  query.page = 1;
  load();
}

const qrUrl = computed(() => {
  if (!otpauthUri.value) return "";
  return `https://api.qrserver.com/v1/create-qr-code/?size=180x180&data=${encodeURIComponent(otpauthUri.value)}`;
});

watch(
  () => route.query.user_uid,
  v => {
    query.user_uid = String(v || "");
    search();
  }
);

onMounted(async () => {
  await loadTotpStatus();
  await load();
});
</script>

<template>
  <div class="page">
    <PageHeader
      title="提现审核"
      :subtitle="
        isAutoMode
          ? '自动打款模式 · 通过将从热钱包广播；通过/拒绝需谷歌验证码'
          : '手动下款模式 · 链上打款后点「确认已打款」填 Tx 结单，勿再点自动打款'
      "
    >
      <el-space>
        <el-tag v-loading="totpLoading" :type="totpConfigured ? 'success' : 'danger'" effect="plain">
          {{ totpConfigured ? "TOTP 已绑定" : "TOTP 未绑定" }}
        </el-tag>
        <el-button v-if="canWrite && !totpConfigured" type="warning" @click="openSetup">
          绑定谷歌验证
        </el-button>
        <el-button v-if="canWrite && totpConfigured" @click="rebindTotp">重新绑定</el-button>
      </el-space>
    </PageHeader>

    <el-alert
      v-if="canWrite && !totpConfigured"
      type="warning"
      show-icon
      :closable="false"
      title="尚未绑定提现审核谷歌验证。通过/拒绝前需先完成绑定。"
      style="margin-bottom: 12px"
    />

    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="query.user_uid"
          clearable
          placeholder="用户 UID"
          style="width: 180px"
          @keyup.enter="search"
        />
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="单号 / 地址 / 关键词"
          style="width: 200px"
          @keyup.enter="search"
        />
        <el-select v-model="query.status" clearable placeholder="全部状态" style="width: 130px">
          <el-option label="全部" value="" />
          <el-option label="待审核" value="待审核" />
          <el-option label="处理中" value="处理中" />
          <el-option label="已通过" value="已通过" />
          <el-option label="成功" value="成功" />
          <el-option label="失败" value="失败" />
          <el-option label="已拒绝" value="已拒绝" />
        </el-select>
        <el-button type="primary" @click="search">查询</el-button>
        <el-button @click="load">刷新</el-button>
      </div>

      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="业务单号" min-width="140">
          <template #default="{ row }">{{ bizNo(row) || "—" }}</template>
        </el-table-column>
        <el-table-column label="用户" min-width="140">
          <template #default="{ row }">
            <UserUidLink :uid="userUid(row)" />
          </template>
        </el-table-column>
        <el-table-column label="昵称" min-width="100">
          <template #default="{ row }">{{ row.nickname || "—" }}</template>
        </el-table-column>
        <el-table-column label="渠道/链" width="100">
          <template #default="{ row }">{{ row.channel || "—" }}</template>
        </el-table-column>
        <el-table-column label="地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ row.address || "—" }}</template>
        </el-table-column>
        <el-table-column label="金额" width="120">
          <template #default="{ row }">{{ amountText(row) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag size="small" :type="statusTagType[statusOf(row)] || 'info'" effect="plain">
              {{ statusOf(row) || "—" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="链上流水" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.external_order_no || row.externalOrderNo || "—" }}
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">
            {{ formatTime(row.create_time || row.createTime) }}
          </template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="220" fixed="right">
          <template #default="{ row }">
            <template v-if="isPending(row)">
              <el-button
                link
                type="success"
                :loading="actionLoading === bizNo(row)"
                @click="openPaidDialog(row)"
              >
                确认已打款
              </el-button>
              <el-button
                v-if="isAutoMode"
                link
                type="warning"
                :loading="actionLoading === bizNo(row)"
                @click="handleApprove(row)"
              >
                自动打款
              </el-button>
              <el-button
                link
                type="danger"
                :loading="actionLoading === bizNo(row)"
                @click="handleReject(row)"
              >
                拒绝
              </el-button>
            </template>
            <el-button
              v-else
              link
              type="primary"
              @click="router.push(`/users/${encodeURIComponent(userUid(row))}`)"
            >
              用户
            </el-button>
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

    <el-dialog v-model="setupVisible" title="绑定提现审核谷歌验证" width="480px">
      <div v-loading="setupLoading">
        <p class="muted">使用 Google Authenticator 扫描二维码，或手动输入密钥。</p>
        <div v-if="qrUrl" style="text-align: center; margin: 12px 0">
          <img :src="qrUrl" alt="TOTP QR" width="180" height="180" />
        </div>
        <el-form label-width="80px">
          <el-form-item label="密钥">
            <el-input :model-value="setupSecret" readonly />
          </el-form-item>
          <el-form-item label="验证码">
            <el-input v-model="confirmCode" maxlength="6" placeholder="扫码后输入 6 位码" />
          </el-form-item>
        </el-form>
      </div>
      <template #footer>
        <el-button @click="setupVisible = false">取消</el-button>
        <el-button type="primary" :loading="confirmLoading" @click="confirmSetup">确认启用</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="paidDialogVisible" title="确认已打款" width="480px">
      <p class="muted">
        用于手动链上下款后结单，不会再从热钱包打一笔。请粘贴已成功的 TRC20 交易哈希。
      </p>
      <el-form label-width="88px">
        <el-form-item label="用户">
          {{ paidRow ? userUid(paidRow) : "—" }}
        </el-form-item>
        <el-form-item label="金额">
          {{ paidRow ? amountText(paidRow) : "—" }}
        </el-form-item>
        <el-form-item label="Tx 哈希" required>
          <el-input
            v-model="paidForm.tx_id"
            placeholder="64 位交易哈希，可带 0x"
            @keyup.enter="submitPaidManually"
          />
        </el-form-item>
        <el-form-item label="谷歌验证" required>
          <el-input
            v-model="paidForm.totp_code"
            maxlength="6"
            placeholder="6 位数字"
            @keyup.enter="submitPaidManually"
          />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="paidDialogVisible = false">取消</el-button>
        <el-button
          type="primary"
          :loading="!!paidRow && actionLoading === bizNo(paidRow)"
          @click="submitPaidManually"
        >
          确认结单
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>
