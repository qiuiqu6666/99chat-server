<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import {
  getInfrastructure,
  getPlatformConfig,
  getPushBusiness,
  putInfrastructure,
  putPlatformConfig,
  putPushBusiness
} from "@/api/systemConfig";
import { pickList, str } from "@/utils/financeRows";
import { errMessage } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canEdit = computed(() => hasPerm("system.config") || hasPerm("admin.manage"));
const tab = ref("platform");
const loading = ref(false);
const saving = ref(false);

const platform = reactive({
  website: "",
  email: "",
  customer_service_url: "",
  feedback_prefix: "",
  max_feedback_screenshots: 0,
  max_feedback_content_length: 0
});

const push = reactive({
  push_enabled: false,
  skip_when_online: false,
  voip_push_enabled: false,
  jpush_enabled: false,
  jpush_app_key: "",
  jpush_master_secret: "",
  jpush_base_url: "",
  im_callback_enabled: false,
  chat_push_enabled: false,
  callback_token: "",
  allowed_sdk_app_ids: "",
  chat_push_skip_when_online: false,
  skip_sender_ids: "",
  max_group_members_per_push: 0,
  dedup_ttl_hours: 0,
  pay_pin_max_failures: 0,
  pay_pin_lock_minutes: 0,
  red_packet_expire_hours: 0,
  min_deposit_usdt_micro: 0,
  deposit_confirmations: 0,
  deposit_mode: "",
  trongrid_api_key: "",
  deposit_mnemonic_configured: false,
  hot_wallet_configured: false,
  group_create_limit_enabled: false,
  group_join_limit_max: 3000,
  group_join_limit_max_community: 1000,
  group_create_limit_max_community: 0,
  group_create_limit_enforce: false,
  group_create_limit_log_only: false,
  group_create_limit_use_im_count_fallback: false
});

const infraRows = ref<Record<string, unknown>[]>([]);
const infraEdit = reactive({ key: "", label: "", value: "", visible: false });

function assignFrom(raw: Record<string, unknown>, target: Record<string, unknown>) {
  for (const k of Object.keys(target)) {
    const camel = k.replace(/_([a-z])/g, (_, c: string) => c.toUpperCase());
    if (raw[k] !== undefined) target[k] = raw[k] as never;
    else if (raw[camel] !== undefined) target[k] = raw[camel] as never;
  }
}

async function loadPlatform() {
  const raw = (await getPlatformConfig()) as Record<string, unknown>;
  assignFrom(raw, platform as unknown as Record<string, unknown>);
}

async function loadPush() {
  const raw = (await getPushBusiness()) as Record<string, unknown>;
  assignFrom(raw, push as unknown as Record<string, unknown>);
}

async function loadInfra() {
  const raw = await getInfrastructure();
  infraRows.value = pickList(raw).items.length
    ? pickList(raw).items
    : (((raw as Record<string, unknown>).items as Record<string, unknown>[]) || []);
}

async function loadAll() {
  loading.value = true;
  try {
    await Promise.all([loadPlatform(), loadPush(), loadInfra()]);
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function savePlatform() {
  saving.value = true;
  try {
    await putPlatformConfig({ ...platform });
    ElMessage.success("平台配置已保存");
    await loadPlatform();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

async function savePush() {
  saving.value = true;
  try {
    const {
      deposit_mnemonic_configured: _a,
      hot_wallet_configured: _b,
      ...body
    } = push;
    await putPushBusiness(body);
    ElMessage.success("推送/业务配置已保存");
    await loadPush();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

function openInfra(row: Record<string, unknown>) {
  infraEdit.key = str(row, "key");
  infraEdit.label = str(row, "label") || infraEdit.key;
  infraEdit.value = "";
  infraEdit.visible = true;
}

async function saveInfra() {
  if (!infraEdit.key) return;
  try {
    await ElMessageBox.confirm(`确认更新基础设施项「${infraEdit.label}」？`, "确认", {
      type: "warning"
    });
  } catch {
    return;
  }
  saving.value = true;
  try {
    await putInfrastructure(infraEdit.key, infraEdit.value);
    ElMessage.success("已更新");
    infraEdit.visible = false;
    await loadInfra();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

onMounted(loadAll);
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="平台配置" subtitle="需 system.config 或 admin.manage" />
    <div class="page-card">
      <el-tabs v-model="tab">
        <el-tab-pane label="平台信息" name="platform">
          <el-form label-width="160px" style="max-width: 720px">
            <el-form-item label="官网"><el-input v-model="platform.website" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="邮箱"><el-input v-model="platform.email" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="客服 URL"><el-input v-model="platform.customer_service_url" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="反馈前缀"><el-input v-model="platform.feedback_prefix" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="反馈截图上限">
              <el-input-number v-model="platform.max_feedback_screenshots" :controls="false" :disabled="!canEdit" />
            </el-form-item>
            <el-form-item label="反馈内容长度">
              <el-input-number v-model="platform.max_feedback_content_length" :controls="false" :disabled="!canEdit" />
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" :disabled="!canEdit" @click="savePlatform">保存</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="推送与业务" name="push">
          <el-form label-width="200px" style="max-width: 820px">
            <el-form-item label="推送总开关"><el-switch v-model="push.push_enabled" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="在线跳过推送"><el-switch v-model="push.skip_when_online" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="VoIP 推送"><el-switch v-model="push.voip_push_enabled" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="极光推送"><el-switch v-model="push.jpush_enabled" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="JPush AppKey"><el-input v-model="push.jpush_app_key" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="JPush MasterSecret"><el-input v-model="push.jpush_master_secret" :disabled="!canEdit" show-password /></el-form-item>
            <el-form-item label="JPush BaseURL"><el-input v-model="push.jpush_base_url" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="IM 回调"><el-switch v-model="push.im_callback_enabled" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="聊天推送"><el-switch v-model="push.chat_push_enabled" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="回调 Token"><el-input v-model="push.callback_token" :disabled="!canEdit" show-password /></el-form-item>
            <el-form-item label="允许 SDK AppId"><el-input v-model="push.allowed_sdk_app_ids" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="聊天在线跳过"><el-switch v-model="push.chat_push_skip_when_online" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="跳过发送者"><el-input v-model="push.skip_sender_ids" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="群推送成员上限"><el-input-number v-model="push.max_group_members_per_push" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="去重 TTL(小时)"><el-input-number v-model="push.dedup_ttl_hours" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="支付密码失败次数"><el-input-number v-model="push.pay_pin_max_failures" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="支付密码锁定(分)"><el-input-number v-model="push.pay_pin_lock_minutes" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="红包过期(小时)"><el-input-number v-model="push.red_packet_expire_hours" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="最小充值 USDT micro"><el-input-number v-model="push.min_deposit_usdt_micro" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="充值确认数"><el-input-number v-model="push.deposit_confirmations" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="充值模式"><el-input v-model="push.deposit_mode" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="TronGrid API Key"><el-input v-model="push.trongrid_api_key" :disabled="!canEdit" show-password /></el-form-item>
            <el-form-item label="助记词已配置">{{ push.deposit_mnemonic_configured ? "是" : "否" }}</el-form-item>
            <el-form-item label="热钱包已配置">{{ push.hot_wallet_configured ? "是" : "否" }}</el-form-item>
            <el-divider>群加入 / 社群创建限制</el-divider>
            <el-form-item label="启用限制"><el-switch v-model="push.group_create_limit_enabled" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="非社群加入上限"><el-input-number v-model="push.group_join_limit_max" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="社群加入上限"><el-input-number v-model="push.group_join_limit_max_community" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="社群创建上限"><el-input-number v-model="push.group_create_limit_max_community" :controls="false" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="强制执行"><el-switch v-model="push.group_create_limit_enforce" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="仅日志"><el-switch v-model="push.group_create_limit_log_only" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="社群创建 IM 计数回退"><el-switch v-model="push.group_create_limit_use_im_count_fallback" :disabled="!canEdit" /></el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" :disabled="!canEdit" @click="savePush">保存</el-button>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane label="基础设施密钥" name="infra">
          <el-table :data="infraRows" stripe border>
            <el-table-column label="Key" min-width="160">
              <template #default="{ row }">{{ row.key }}</template>
            </el-table-column>
            <el-table-column label="名称" min-width="160">
              <template #default="{ row }">{{ row.label || "—" }}</template>
            </el-table-column>
            <el-table-column label="已配置" width="90">
              <template #default="{ row }">
                <el-tag :type="row.set ? 'success' : 'info'" size="small">{{ row.set ? "是" : "否" }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column label="预览" min-width="200" show-overflow-tooltip>
              <template #default="{ row }">{{ row.preview || "—" }}</template>
            </el-table-column>
            <el-table-column label="密钥" width="80">
              <template #default="{ row }">{{ row.secret ? "是" : "否" }}</template>
            </el-table-column>
            <el-table-column v-if="canEdit" label="操作" width="90" fixed="right">
              <template #default="{ row }">
                <el-button link type="primary" @click="openInfra(row)">更新</el-button>
              </template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </div>

    <el-dialog v-model="infraEdit.visible" :title="`更新 ${infraEdit.label}`" width="520px">
      <el-input v-model="infraEdit.value" type="textarea" :rows="4" placeholder="新值" />
      <template #footer>
        <el-button @click="infraEdit.visible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveInfra">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
