<script setup lang="ts">
import { onMounted, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { ReQrcode } from "@/components/ReQrcode";
import {
  adminWithdrawAuditTotpConfirmApi,
  adminWithdrawAuditTotpResetApi,
  adminWithdrawAuditTotpSetupApi,
  adminWithdrawAuditTotpStatusApi
} from "@/api/im-finance-actions";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";

defineOptions({ name: "WithdrawAuditTotpSetup" });

const emit = defineEmits<{ (e: "configured"): void }>();

const configured = ref(false);
const loading = ref(true);
const setupVisible = ref(false);
const setupLoading = ref(false);
const confirmLoading = ref(false);
const resetLoading = ref(false);
const otpauthUri = ref("");
const setupSecret = ref("");
const confirmCode = ref("");

async function loadStatus() {
  loading.value = true;
  try {
    const res = await adminWithdrawAuditTotpStatusApi();
    configured.value = Boolean(res?.configured);
  } catch {
    configured.value = false;
  } finally {
    loading.value = false;
  }
}

async function openSetup() {
  setupLoading.value = true;
  setupVisible.value = true;
  otpauthUri.value = "";
  setupSecret.value = "";
  confirmCode.value = "";
  try {
    const res = await adminWithdrawAuditTotpSetupApi();
    otpauthUri.value = String(res?.otpauth_uri ?? "").trim();
    setupSecret.value = String(res?.secret ?? "").trim();
    if (!otpauthUri.value) {
      message("生成验证密钥失败", { type: "warning" });
      setupVisible.value = false;
    }
  } catch (err: unknown) {
    setupVisible.value = false;
    message(adminApiErrMessage(err, "初始化谷歌验证失败"), { type: "warning" });
  } finally {
    setupLoading.value = false;
  }
}

async function confirmSetup() {
  const code = confirmCode.value.trim();
  if (!/^\d{6}$/.test(code)) {
    message("请输入 6 位谷歌验证码", { type: "warning" });
    return;
  }
  confirmLoading.value = true;
  try {
    await adminWithdrawAuditTotpConfirmApi({ totp_code: code });
    configured.value = true;
    setupVisible.value = false;
    message("谷歌验证已启用，提现审核时将要求填写验证码", { type: "success" });
    emit("configured");
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "验证失败"), { type: "warning" });
  } finally {
    confirmLoading.value = false;
  }
}

async function rebind() {
  try {
    await ElMessageBox.confirm(
      "重新绑定将使旧验证码失效，需用 Google Authenticator 重新扫码。是否继续？",
      "重新绑定谷歌验证",
      { type: "warning", confirmButtonText: "继续", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  let totpCode = "";
  try {
    const { value } = await ElMessageBox.prompt(
      "请输入当前 Google Authenticator 中的 6 位验证码：",
      "验证旧验证码",
      {
        type: "warning",
        confirmButtonText: "确认",
        cancelButtonText: "取消",
        inputPlaceholder: "6 位数字",
        inputPattern: /^\d{6}$/,
        inputErrorMessage: "请输入 6 位数字验证码"
      }
    );
    totpCode = value?.trim() ?? "";
  } catch {
    return;
  }
  resetLoading.value = true;
  try {
    await adminWithdrawAuditTotpResetApi({ totp_code: totpCode });
    configured.value = false;
    await openSetup();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "重置失败"), { type: "warning" });
  } finally {
    resetLoading.value = false;
  }
}

async function promptSetupIfNeeded() {
  if (loading.value || configured.value) return true;
  try {
    await ElMessageBox.confirm(
      "提现审核尚未设置谷歌验证，需先完成设置才能通过或拒绝提现。",
      "设置谷歌验证",
      { type: "warning", confirmButtonText: "去设置", cancelButtonText: "取消" }
    );
    await openSetup();
  } catch {
    return false;
  }
  return false;
}

function isTotpConfigured() {
  return configured.value;
}

defineExpose({ loadStatus, promptSetupIfNeeded, isTotpConfigured, configured });

onMounted(loadStatus);
</script>

<template>
  <div v-if="!loading" class="mb-3">
    <el-alert v-if="!configured" type="warning" show-icon :closable="false">
      <template #title>提现审核需谷歌验证</template>
      <template #default>
        <div class="flex flex-wrap items-center gap-2 mt-1">
          <span class="text-sm">请使用 Google Authenticator 等应用扫码绑定，每次审核须填写动态验证码。</span>
          <el-button type="primary" size="small" @click="openSetup">设置谷歌验证</el-button>
        </div>
      </template>
    </el-alert>

    <el-alert v-else type="success" show-icon :closable="false">
      <template #title>谷歌验证已绑定</template>
      <template #default>
        <div class="flex flex-wrap items-center gap-2 mt-1">
          <span class="text-sm">提现通过/拒绝时需填写 Google Authenticator 动态验证码。</span>
          <el-button size="small" :loading="resetLoading" @click="rebind">重新绑定</el-button>
        </div>
      </template>
    </el-alert>
  </div>

  <el-dialog
    v-model="setupVisible"
    title="设置提现审核谷歌验证"
    width="420px"
    destroy-on-close
    :close-on-click-modal="false"
  >
    <div v-loading="setupLoading" class="flex flex-col items-center gap-4 py-2">
      <p class="text-sm text-gray-600 text-center">
        使用 Google Authenticator（或同类 TOTP 应用）扫描下方二维码，然后输入 6 位动态码完成绑定。
      </p>
      <p class="text-xs text-amber-600 text-center px-2">
        请勿重复打开本弹窗，否则密钥会被刷新，需重新扫码。
      </p>
      <ReQrcode v-if="otpauthUri" :text="otpauthUri" :width="200" />
      <p v-if="setupSecret" class="text-xs text-gray-500 break-all text-center px-2">
        无法扫码时可手动输入密钥：{{ setupSecret }}
      </p>
      <el-input
        v-model="confirmCode"
        maxlength="6"
        placeholder="输入 6 位验证码"
        class="w-48!"
        inputmode="numeric"
        autocomplete="one-time-code"
        @keyup.enter="confirmSetup"
      />
    </div>
    <template #footer>
      <el-button @click="setupVisible = false">取消</el-button>
      <el-button type="primary" :loading="confirmLoading" @click="confirmSetup">
        确认启用
      </el-button>
    </template>
  </el-dialog>
</template>
