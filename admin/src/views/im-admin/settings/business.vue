<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRoute } from "vue-router";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  getInfrastructureConfig,
  getPlatformConfig,
  getPushBusinessConfig,
  isUsdtCurrency,
  limitDisplayToRaw,
  mapWalletLimitRows,
  microToUsdt,
  updateInfrastructureKey,
  updatePlatformConfig,
  updatePushBusinessConfig,
  usdtToMicro,
  type InfrastructureItem,
  type PlatformConfig,
  type PushBusinessConfig,
  type WalletLimitFormRow
} from "@/api/system-config";

defineOptions({ name: "ImSystemConfigIndex" });

const activeTab = ref("daily");
const route = useRoute();
const loading = ref({ platform: false, business: false, infra: false });
const saving = ref({ platform: false, business: false, infra: false });
const forbidden = ref(false);

const platformForm = reactive({
  website: "",
  email: "",
  customer_service_url: "",
  feedback_prefix: "",
  max_feedback_screenshots: 5,
  max_feedback_content_length: 2000
});

const businessForm = reactive({
  push_enabled: false,
  skip_when_online: true,
  voip_push_enabled: true,
  jpush_enabled: false,
  jpush_app_key: "",
  jpush_master_secret: "",
  jpush_base_url: "",
  im_callback_enabled: true,
  chat_push_enabled: true,
  callback_token: "",
  allowed_sdk_app_ids: "",
  chat_push_skip_when_online: false,
  skip_sender_ids: "",
  max_group_members_per_push: 500,
  dedup_ttl_hours: 48,
  group_create_limit_enabled: true,
  group_join_limit_max: 3000,
  group_join_limit_max_community: 1000,
  group_create_limit_max_community: 3,
  group_create_limit_enforce: true,
  group_create_limit_log_only: false,
  group_create_limit_use_im_count_fallback: true,
  pay_pin_max_failures: 5,
  pay_pin_lock_minutes: 30,
  red_packet_expire_hours: 24,
  min_deposit_usdt: 1,
  deposit_confirmations: 19,
  deposit_mode: "address-poll",
  deposit_mnemonic_configured: false,
  hot_wallet_configured: false,
  trongrid_api_key: "",
  new_jpush_app_key: "",
  new_jpush_master_secret: "",
  new_callback_token: "",
  new_trongrid_api_key: ""
});

const walletLimits = ref<WalletLimitFormRow[]>([]);

const infraItems = ref<InfrastructureItem[]>([]);
const infraDialog = reactive({
  visible: false,
  key: "",
  label: "",
  secret: false,
  value: ""
});

function handleForbidden(err: unknown) {
  const ax = err as { response?: { status?: number; data?: { error?: string } } };
  if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
    forbidden.value = true;
    message("无 system.config 或 admin.manage 权限，请重新登录", { type: "warning" });
    return true;
  }
  return false;
}

function applyPlatform(data: PlatformConfig) {
  platformForm.website = data.website || "";
  platformForm.email = data.email || "";
  platformForm.customer_service_url = data.customer_service_url || "";
  platformForm.feedback_prefix = data.feedback_prefix || "";
  platformForm.max_feedback_screenshots = data.max_feedback_screenshots || 5;
  platformForm.max_feedback_content_length = data.max_feedback_content_length || 2000;
}

function applyBusiness(data: PushBusinessConfig) {
  businessForm.push_enabled = data.push_enabled;
  businessForm.skip_when_online = data.skip_when_online;
  businessForm.voip_push_enabled = data.voip_push_enabled;
  businessForm.jpush_enabled = data.jpush_enabled;
  businessForm.jpush_app_key = data.jpush_app_key || "";
  businessForm.jpush_master_secret = data.jpush_master_secret || "";
  businessForm.jpush_base_url = data.jpush_base_url || "";
  businessForm.im_callback_enabled = data.im_callback_enabled;
  businessForm.chat_push_enabled = data.chat_push_enabled;
  businessForm.callback_token = data.callback_token || "";
  businessForm.allowed_sdk_app_ids = data.allowed_sdk_app_ids || "";
  businessForm.chat_push_skip_when_online = data.chat_push_skip_when_online;
  businessForm.skip_sender_ids = data.skip_sender_ids || "";
  businessForm.max_group_members_per_push = data.max_group_members_per_push || 500;
  businessForm.dedup_ttl_hours = data.dedup_ttl_hours || 48;
  businessForm.group_create_limit_enabled = data.group_create_limit_enabled;
  businessForm.group_join_limit_max = data.group_join_limit_max ?? 3000;
  businessForm.group_join_limit_max_community = data.group_join_limit_max_community ?? 1000;
  businessForm.group_create_limit_max_community = data.group_create_limit_max_community ?? 3;
  businessForm.group_create_limit_enforce = data.group_create_limit_enforce;
  businessForm.group_create_limit_log_only = data.group_create_limit_log_only;
  businessForm.group_create_limit_use_im_count_fallback =
    data.group_create_limit_use_im_count_fallback ?? true;
  businessForm.pay_pin_max_failures = data.pay_pin_max_failures || 5;
  businessForm.pay_pin_lock_minutes = data.pay_pin_lock_minutes || 30;
  businessForm.red_packet_expire_hours = data.red_packet_expire_hours || 24;
  businessForm.min_deposit_usdt = microToUsdt(data.min_deposit_usdt_micro || 1_000_000);
  businessForm.deposit_confirmations = data.deposit_confirmations || 19;
  businessForm.deposit_mode = data.deposit_mode || "address-poll";
  businessForm.deposit_mnemonic_configured = data.deposit_mnemonic_configured;
  businessForm.hot_wallet_configured = data.hot_wallet_configured;
  businessForm.trongrid_api_key = data.trongrid_api_key || "";
  businessForm.new_jpush_app_key = "";
  businessForm.new_jpush_master_secret = "";
  businessForm.new_callback_token = "";
  businessForm.new_trongrid_api_key = "";
  walletLimits.value = mapWalletLimitRows(data.wallet_limits);
}

async function loadPlatform() {
  loading.value.platform = true;
  try {
    applyPlatform(await getPlatformConfig());
  } catch (err) {
    if (!handleForbidden(err) && (err as { response?: { status?: number } })?.response?.status !== 401) {
      message(adminApiErrMessage(err, "平台配置加载失败"), { type: "warning" });
    }
  } finally {
    loading.value.platform = false;
  }
}

async function loadBusiness() {
  loading.value.business = true;
  try {
    applyBusiness(await getPushBusinessConfig());
  } catch (err) {
    if (!handleForbidden(err) && (err as { response?: { status?: number } })?.response?.status !== 401) {
      message(adminApiErrMessage(err, "业务开关加载失败"), { type: "warning" });
    }
  } finally {
    loading.value.business = false;
  }
}

async function loadInfra() {
  loading.value.infra = true;
  try {
    const res = await getInfrastructureConfig();
    infraItems.value = res.items || [];
  } catch (err) {
    infraItems.value = [];
    if (!handleForbidden(err) && (err as { response?: { status?: number } })?.response?.status !== 401) {
      message(adminApiErrMessage(err, "第三方配置加载失败"), { type: "warning" });
    }
  } finally {
    loading.value.infra = false;
  }
}

async function savePlatform() {
  saving.value.platform = true;
  try {
    applyPlatform(
      await updatePlatformConfig({
        website: platformForm.website.trim(),
        email: platformForm.email.trim(),
        customer_service_url: platformForm.customer_service_url.trim(),
        feedback_prefix: platformForm.feedback_prefix.trim(),
        max_feedback_screenshots: platformForm.max_feedback_screenshots,
        max_feedback_content_length: platformForm.max_feedback_content_length
      })
    );
    message("平台配置已保存", { type: "success" });
  } catch (err) {
    if (!handleForbidden(err)) {
      message(adminApiErrMessage(err, "保存失败"), { type: "warning" });
    }
  } finally {
    saving.value.platform = false;
  }
}

async function saveBusiness() {
  saving.value.business = true;
  try {
    const payload: Record<string, unknown> = {
      push_enabled: businessForm.push_enabled,
      skip_when_online: businessForm.skip_when_online,
      voip_push_enabled: businessForm.voip_push_enabled,
      jpush_enabled: businessForm.jpush_enabled,
      jpush_base_url: businessForm.jpush_base_url.trim(),
      im_callback_enabled: businessForm.im_callback_enabled,
      chat_push_enabled: businessForm.chat_push_enabled,
      allowed_sdk_app_ids: businessForm.allowed_sdk_app_ids.trim(),
      chat_push_skip_when_online: businessForm.chat_push_skip_when_online,
      skip_sender_ids: businessForm.skip_sender_ids.trim(),
      max_group_members_per_push: businessForm.max_group_members_per_push,
      dedup_ttl_hours: businessForm.dedup_ttl_hours,
      group_create_limit_enabled: businessForm.group_create_limit_enabled,
      group_join_limit_max: businessForm.group_join_limit_max,
      group_join_limit_max_community: businessForm.group_join_limit_max_community,
      group_create_limit_max_community: businessForm.group_create_limit_max_community,
      group_create_limit_enforce: businessForm.group_create_limit_enforce,
      group_create_limit_log_only: businessForm.group_create_limit_log_only,
      group_create_limit_use_im_count_fallback:
        businessForm.group_create_limit_use_im_count_fallback,
      pay_pin_max_failures: businessForm.pay_pin_max_failures,
      pay_pin_lock_minutes: businessForm.pay_pin_lock_minutes,
      red_packet_expire_hours: businessForm.red_packet_expire_hours,
      min_deposit_usdt_micro: usdtToMicro(businessForm.min_deposit_usdt),
      deposit_confirmations: businessForm.deposit_confirmations,
      deposit_mode: businessForm.deposit_mode
    };
    if (businessForm.new_jpush_app_key.trim()) {
      payload.jpush_app_key = businessForm.new_jpush_app_key.trim();
    }
    if (businessForm.new_jpush_master_secret.trim()) {
      payload.jpush_master_secret = businessForm.new_jpush_master_secret.trim();
    }
    if (businessForm.new_callback_token.trim()) {
      payload.callback_token = businessForm.new_callback_token.trim();
    }
    if (businessForm.new_trongrid_api_key.trim()) {
      payload.trongrid_api_key = businessForm.new_trongrid_api_key.trim();
    }
    payload.wallet_limits = walletLimits.value.map(row => ({
      id: row.id,
      per_tx_max: limitDisplayToRaw(row.currency, row.per_tx_display),
      daily_max: limitDisplayToRaw(row.currency, row.daily_display),
      enabled: row.enabled
    }));
    applyBusiness(await updatePushBusinessConfig(payload));
    message("业务开关已保存", { type: "success" });
  } catch (err) {
    if (!handleForbidden(err)) {
      message(adminApiErrMessage(err, "保存失败"), { type: "warning" });
    }
  } finally {
    saving.value.business = false;
  }
}

function openInfraEdit(row: InfrastructureItem) {
  infraDialog.key = row.key;
  infraDialog.label = row.label;
  infraDialog.secret = row.secret;
  infraDialog.value = "";
  infraDialog.visible = true;
}

async function saveInfra() {
  if (!infraDialog.value.trim()) {
    message("请输入新值", { type: "warning" });
    return;
  }
  saving.value.infra = true;
  try {
    const updated = await updateInfrastructureKey(infraDialog.key, infraDialog.value.trim());
    const idx = infraItems.value.findIndex(i => i.key === updated.key);
    if (idx >= 0) {
      infraItems.value[idx] = updated;
    }
    infraDialog.visible = false;
    message(`${infraDialog.label} 已更新`, { type: "success" });
    if (infraDialog.key === "JWT_SECRET") {
      message("JWT 密钥已变更，所有用户需重新登录", { type: "warning", duration: 5000 });
    }
  } catch (err) {
    if (!handleForbidden(err)) {
      message(adminApiErrMessage(err, "保存失败"), { type: "warning" });
    }
  } finally {
    saving.value.infra = false;
  }
}

onMounted(() => {
  const tab = String(route.query.tab ?? "").trim();
  if (tab === "infra" || tab === "secrets") {
    activeTab.value = "secrets";
  } else {
    activeTab.value = "daily";
  }
  loadPlatform();
  loadBusiness();
  loadInfra();
});
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-alert
      v-if="forbidden"
      type="warning"
      :closable="false"
      title="当前账号无 system.config 或 admin.manage 权限；退出后重新登录 admin 账号。"
    />

    <el-tabs v-model="activeTab">
      <el-tab-pane label="日常开关" name="daily">
        <el-card v-loading="loading.platform" shadow="never" class="mb-4">
          <div class="mb-4 text-sm text-gray-500">
            官网与客服信息用于用户端「关于我们 / 帮助与反馈」。
          </div>
          <el-form label-width="148px" class="max-w-3xl">
            <el-form-item label="官网 URL">
              <el-input v-model="platformForm.website" placeholder="https://99chat.com" />
            </el-form-item>
            <el-form-item label="客服邮箱">
              <el-input v-model="platformForm.email" placeholder="support@example.com" />
            </el-form-item>
            <el-form-item label="客服链接">
              <el-input
                v-model="platformForm.customer_service_url"
                placeholder="https://example.com/customer-service"
              />
            </el-form-item>
            <el-form-item label="反馈 OSS 前缀">
              <el-input v-model="platformForm.feedback_prefix" placeholder="feedback/" />
            </el-form-item>
            <el-form-item label="反馈截图上限">
              <el-input-number v-model="platformForm.max_feedback_screenshots" :min="1" :max="20" />
            </el-form-item>
            <el-form-item label="反馈内容字数">
              <el-input-number
                v-model="platformForm.max_feedback_content_length"
                :min="100"
                :max="10000"
                :step="100"
              />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving.platform" @click="savePlatform">
                保存平台配置
              </el-button>
              <el-button @click="loadPlatform">刷新</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-loading="loading.business" shadow="never">
          <el-form label-width="168px" class="max-w-3xl">
            <div class="mb-2 text-base font-medium">推送</div>
            <el-form-item label="推送总开关">
              <el-switch v-model="businessForm.push_enabled" />
            </el-form-item>
            <el-form-item label="在线跳过 Push">
              <el-switch v-model="businessForm.skip_when_online" />
            </el-form-item>
            <el-form-item label="VoIP Push">
              <el-switch v-model="businessForm.voip_push_enabled" />
            </el-form-item>
            <el-form-item label="极光推送">
              <el-switch v-model="businessForm.jpush_enabled" />
            </el-form-item>
            <el-form-item label="极光 Base URL">
              <el-input v-model="businessForm.jpush_base_url" />
            </el-form-item>

            <el-divider />
            <div class="mb-2 text-base font-medium">IM 回调 Push</div>
            <el-form-item label="IM 回调">
              <el-switch v-model="businessForm.im_callback_enabled" />
            </el-form-item>
            <el-form-item label="聊天 Push">
              <el-switch v-model="businessForm.chat_push_enabled" />
            </el-form-item>
            <el-form-item label="在线跳过聊天 Push">
              <el-switch v-model="businessForm.chat_push_skip_when_online" />
            </el-form-item>
            <el-form-item label="群 Push 成员上限">
              <el-input-number v-model="businessForm.max_group_members_per_push" :min="0" :max="5000" />
            </el-form-item>
            <el-form-item label="Push 去重 TTL">
              <el-input-number v-model="businessForm.dedup_ttl_hours" :min="1" :max="168" />
              <span class="ml-2 text-gray-500">小时</span>
            </el-form-item>

            <el-divider />
            <div class="mb-2 text-base font-medium">群加入 / 社群创建限制</div>
            <el-form-item label="限制总开关">
              <el-switch v-model="businessForm.group_create_limit_enabled" />
            </el-form-item>
            <el-form-item label="非社群加入上限">
              <el-input-number v-model="businessForm.group_join_limit_max" :min="0" :max="10000" />
            </el-form-item>
            <el-form-item label="社群加入上限">
              <el-input-number
                v-model="businessForm.group_join_limit_max_community"
                :min="0"
                :max="10000"
              />
            </el-form-item>
            <el-form-item label="社群创建上限">
              <el-input-number
                v-model="businessForm.group_create_limit_max_community"
                :min="0"
                :max="1000"
              />
            </el-form-item>
            <el-form-item label="真正拦截">
              <el-switch v-model="businessForm.group_create_limit_enforce" />
            </el-form-item>
            <el-form-item label="仅日志不拦截">
              <el-switch v-model="businessForm.group_create_limit_log_only" />
            </el-form-item>
            <el-form-item label="社群创建 IM 计数兜底">
              <el-switch v-model="businessForm.group_create_limit_use_im_count_fallback" />
            </el-form-item>

            <el-divider />
            <div class="mb-2 text-base font-medium">钱包业务</div>
            <el-form-item label="支付 PIN 最大失败">
              <el-input-number v-model="businessForm.pay_pin_max_failures" :min="1" :max="20" />
            </el-form-item>
            <el-form-item label="PIN 锁定时长">
              <el-input-number v-model="businessForm.pay_pin_lock_minutes" :min="1" :max="1440" />
              <span class="ml-2 text-gray-500">分钟</span>
            </el-form-item>
            <el-form-item label="红包过期">
              <el-input-number v-model="businessForm.red_packet_expire_hours" :min="1" :max="168" />
              <span class="ml-2 text-gray-500">小时</span>
            </el-form-item>
            <el-form-item label="最低充值 USDT">
              <el-input-number
                v-model="businessForm.min_deposit_usdt"
                :min="0.01"
                :max="100000"
                :step="0.1"
                :precision="2"
              />
            </el-form-item>
            <el-form-item label="充值确认数">
              <el-input-number v-model="businessForm.deposit_confirmations" :min="1" :max="100" />
            </el-form-item>
            <el-form-item label="充值扫描模式">
              <el-select v-model="businessForm.deposit_mode" class="w-48!">
                <el-option label="地址轮询 (address-poll)" value="address-poll" />
                <el-option label="区块扫描 (block-scan)" value="block-scan" />
              </el-select>
            </el-form-item>

            <el-divider />
            <div class="mb-2 text-base font-medium">转账 / 红包限额</div>
            <el-table :data="walletLimits" border class="mb-4 max-w-4xl">
              <el-table-column label="场景" width="90">
                <template #default="{ row }">
                  {{ row.scene_label || (row.scene === "RED_PACKET" ? "红包" : "转账") }}
                </template>
              </el-table-column>
              <el-table-column label="币种" width="90">
                <template #default="{ row }">
                  {{ row.currency_label || row.currency }}
                </template>
              </el-table-column>
              <el-table-column label="单笔上限" min-width="160">
                <template #default="{ row }">
                  <el-input-number
                    v-model="row.per_tx_display"
                    :min="0"
                    :precision="isUsdtCurrency(row.currency) ? 6 : 2"
                    :step="isUsdtCurrency(row.currency) ? 1 : 100"
                    controls-position="right"
                    class="w-full!"
                  />
                </template>
              </el-table-column>
              <el-table-column label="日累计上限" min-width="160">
                <template #default="{ row }">
                  <el-input-number
                    v-model="row.daily_display"
                    :min="0"
                    :precision="isUsdtCurrency(row.currency) ? 6 : 2"
                    :step="isUsdtCurrency(row.currency) ? 1 : 100"
                    controls-position="right"
                    class="w-full!"
                  />
                </template>
              </el-table-column>
              <el-table-column label="启用" width="80">
                <template #default="{ row }">
                  <el-switch v-model="row.enabled" />
                </template>
              </el-table-column>
            </el-table>

            <el-form-item>
              <el-button type="primary" :loading="saving.business" @click="saveBusiness">
                保存业务开关
              </el-button>
              <el-button @click="loadBusiness">刷新</el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-tab-pane>

      <el-tab-pane label="高级 / 密钥" name="secrets">
        <el-alert
          type="warning"
          :closable="false"
          class="mb-4"
          title="密钥与基础设施配置仅限运维/管理员操作，日常运营请使用「日常开关」页。"
        />
        <el-card v-loading="loading.business" shadow="never" class="mb-4">
          <el-form label-width="168px" class="max-w-3xl">
            <el-form-item label="极光 AppKey">
              <el-text class="mr-2">{{ businessForm.jpush_app_key || "未配置" }}</el-text>
              <el-input
                v-model="businessForm.new_jpush_app_key"
                class="max-w-xs!"
                placeholder="留空则不修改"
                show-password
              />
            </el-form-item>
            <el-form-item label="极光 Master Secret">
              <el-text class="mr-2">{{ businessForm.jpush_master_secret || "未配置" }}</el-text>
              <el-input
                v-model="businessForm.new_jpush_master_secret"
                class="max-w-xs!"
                placeholder="留空则不修改"
                show-password
              />
            </el-form-item>
            <el-form-item label="回调 Token">
              <el-text class="mr-2">{{ businessForm.callback_token || "未配置" }}</el-text>
              <el-input
                v-model="businessForm.new_callback_token"
                class="max-w-xs!"
                placeholder="留空则不修改"
                show-password
              />
            </el-form-item>
            <el-form-item label="允许的 SDKAppId">
              <el-input v-model="businessForm.allowed_sdk_app_ids" placeholder="逗号分隔，空=不限制" />
            </el-form-item>
            <el-form-item label="跳过 Push 的发送者">
              <el-input v-model="businessForm.skip_sender_ids" placeholder="如 administrator" />
            </el-form-item>
            <el-form-item label="TronGrid API Key">
              <el-text class="mr-2">{{ businessForm.trongrid_api_key || "未配置" }}</el-text>
              <el-input
                v-model="businessForm.new_trongrid_api_key"
                class="max-w-xs!"
                placeholder="留空则不修改"
                show-password
              />
            </el-form-item>
            <el-form-item label="充值助记词">
              <el-tag :type="businessForm.deposit_mnemonic_configured ? 'success' : 'info'">
                {{ businessForm.deposit_mnemonic_configured ? "已配置" : "未配置" }}
              </el-tag>
            </el-form-item>
            <el-form-item label="热钱包私钥">
              <el-tag :type="businessForm.hot_wallet_configured ? 'success' : 'info'">
                {{ businessForm.hot_wallet_configured ? "已配置" : "未配置" }}
              </el-tag>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving.business" @click="saveBusiness">
                保存密钥配置
              </el-button>
              <el-button @click="loadBusiness">刷新</el-button>
            </el-form-item>
          </el-form>
        </el-card>

        <el-card v-loading="loading.infra" shadow="never">
          <div class="mb-4 text-sm text-gray-500">
            IM / OSS / 短信 / JWT 等基础设施密钥。
          </div>
          <el-table :data="infraItems" border>
            <el-table-column prop="label" label="配置项" min-width="180" />
            <el-table-column prop="key" label="Key" min-width="200" />
            <el-table-column label="状态" width="100">
              <template #default="{ row }">
                <el-tag :type="row.set ? 'success' : 'info'" size="small">
                  {{ row.set ? "已配置" : "未配置" }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="preview" label="当前值（脱敏）" min-width="200" />
            <el-table-column label="操作" width="100" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openInfraEdit(row)">修改</el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="mt-3">
            <el-button @click="loadInfra">刷新</el-button>
          </div>
        </el-card>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="infraDialog.visible" :title="`修改 ${infraDialog.label}`" width="480px">
      <el-alert
        v-if="infraDialog.secret"
        class="mb-3"
        type="warning"
        :closable="false"
        title="此为敏感密钥，请妥善保管；留空不会提交。"
      />
      <el-input
        v-model="infraDialog.value"
        :type="infraDialog.secret ? 'password' : 'text'"
        :show-password="infraDialog.secret"
        placeholder="输入新值"
      />
      <template #footer>
        <el-button @click="infraDialog.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving.infra" @click="saveInfra">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
