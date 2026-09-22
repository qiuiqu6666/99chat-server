<script setup lang="ts">
import dayjs from "dayjs";
import { computed, onMounted, reactive, ref } from "vue";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  bpsToPercent,
  getExchangeConfig,
  microToUsdt,
  percentToBps,
  updateExchangeConfig,
  usdtToMicro,
  type ExchangeConfigResponse
} from "@/api/im-exchange-config";

defineOptions({ name: "ImExchangeConfig" });

const loading = ref(false);
const saving = ref(false);
const config = ref<ExchangeConfigResponse | null>(null);

const form = reactive({
  enabled: true,
  markup_percent: 0,
  float_percent: 0.5,
  min_withdraw_usdt: 1,
  frankfurter_url: "",
  exchange_rate_cache_seconds: 300
});

const ratePreview = computed(() => config.value?.rate_preview ?? {});
const stats = computed(() => config.value?.stats ?? {});

function formatTime(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

function fmtRate(v?: number | null) {
  if (v == null || !Number.isFinite(v)) return "—";
  return v.toFixed(4);
}

function fmtYuan(v?: number | null) {
  if (v == null || !Number.isFinite(v)) return "—";
  return `¥${v.toLocaleString("zh-CN", { minimumFractionDigits: 2, maximumFractionDigits: 2 })}`;
}

function applyToForm(data: ExchangeConfigResponse) {
  form.enabled = data.enabled !== false;
  form.markup_percent = bpsToPercent(data.markup_bps);
  form.float_percent = bpsToPercent(data.float_bps);
  form.min_withdraw_usdt = microToUsdt(data.min_withdraw_usdt_micro);
  form.frankfurter_url = data.frankfurter_url || "";
  form.exchange_rate_cache_seconds = data.exchange_rate_cache_seconds || 300;
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getExchangeConfig();
    config.value = res;
    applyToForm(res);
  } catch (err: unknown) {
    config.value = null;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 wallet.read）", { type: "warning" });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "闪兑配置加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

async function saveConfig() {
  if (!form.frankfurter_url.trim()) {
    message("请填写汇率 API 地址", { type: "warning" });
    return;
  }
  if (form.min_withdraw_usdt < 1) {
    message("最低提现不能小于 1 USDT", { type: "warning" });
    return;
  }
  saving.value = true;
  try {
    const res = await updateExchangeConfig({
      enabled: form.enabled,
      markup_bps: percentToBps(form.markup_percent),
      float_bps: percentToBps(form.float_percent),
      min_withdraw_usdt_micro: usdtToMicro(form.min_withdraw_usdt),
      frankfurter_url: form.frankfurter_url.trim(),
      exchange_rate_cache_seconds: form.exchange_rate_cache_seconds
    });
    config.value = res;
    applyToForm(res);
    message("闪兑配置已保存", { type: "success" });
  } catch (err: unknown) {
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 user.write）", { type: "warning" });
    } else {
      message(adminApiErrMessage(err, "保存失败"), { type: "warning" });
    }
  } finally {
    saving.value = false;
  }
}

onMounted(() => loadData());
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3" v-loading="loading">
    <el-card shadow="never">
      <div class="mb-4 text-sm text-gray-500">
        配置 USDT ↔ 99币 闪兑汇率加价、买卖价差、最低提现额与汇率数据源；保存后立即对用户端生效。
      </div>

      <el-form label-width="148px" class="max-w-3xl">
        <el-form-item label="闪兑开关">
          <div class="flex items-center gap-3">
            <el-switch v-model="form.enabled" active-text="开放" inactive-text="维护" />
            <span class="text-gray-500 text-sm">关闭后用户闪兑将提示「正在维护」</span>
          </div>
        </el-form-item>
        <el-divider />
        <div class="mb-2 text-base font-medium">汇率参数</div>
        <el-form-item label="基础加价">
          <div class="flex items-center gap-2">
            <el-input-number
              v-model="form.markup_percent"
              :min="0"
              :max="50"
              :step="0.01"
              :precision="2"
            />
            <span class="text-gray-500">%（在 Frankfurter 美元/人民币汇率上加价）</span>
          </div>
        </el-form-item>
        <el-form-item label="买卖价差">
          <div class="flex items-center gap-2">
            <el-input-number
              v-model="form.float_percent"
              :min="0"
              :max="20"
              :step="0.01"
              :precision="2"
            />
            <span class="text-gray-500">%（USDT→99币 减价，99币→USDT 加价）</span>
          </div>
        </el-form-item>

        <el-divider />

        <div class="mb-2 text-base font-medium">提现限制</div>
        <el-form-item label="最低提现 USDT">
          <div class="flex items-center gap-2">
            <el-input-number v-model="form.min_withdraw_usdt" :min="1" :max="100000" :step="1" :precision="2" />
            <span class="text-gray-500">USDT（同时用于提现校验）</span>
          </div>
        </el-form-item>

        <el-divider />

        <div class="mb-2 text-base font-medium">汇率数据源</div>
        <el-form-item label="Frankfurter URL">
          <el-input v-model="form.frankfurter_url" placeholder="https://api.frankfurter.dev/v1/latest?from=USD&to=CNY" />
        </el-form-item>
        <el-form-item label="汇率缓存">
          <div class="flex items-center gap-2">
            <el-input-number v-model="form.exchange_rate_cache_seconds" :min="30" :max="86400" :step="30" />
            <span class="text-gray-500">秒</span>
          </div>
        </el-form-item>

        <el-form-item>
          <el-button type="primary" :loading="saving" @click="saveConfig">保存配置</el-button>
          <el-button @click="loadData">刷新</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-row :gutter="12">
      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>
            <span class="font-medium">当前汇率预览</span>
          </template>
          <div v-if="ratePreview.error" class="text-amber-600 text-sm mb-2">
            汇率暂不可用：{{ ratePreview.error }}
          </div>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="Frankfurter 基准">
              {{ fmtRate(ratePreview.base_usd_cny) }} CNY / USD
            </el-descriptions-item>
            <el-descriptions-item label="USDT→99币 买入价">
              {{ fmtRate(ratePreview.buy_cny_per_usdt) }} 元 / USDT
            </el-descriptions-item>
            <el-descriptions-item label="99币→USDT 卖出价">
              {{ fmtRate(ratePreview.sell_cny_per_usdt) }} 元 / USDT
            </el-descriptions-item>
            <el-descriptions-item label="中间价">
              {{ fmtRate(ratePreview.mid_cny_per_usdt) }} 元 / USDT
            </el-descriptions-item>
            <el-descriptions-item label="示例：1 USDT 闪兑">
              {{ fmtYuan(ratePreview.example_one_usdt_to_platform_yuan) }}
            </el-descriptions-item>
            <el-descriptions-item label="汇率更新时间">
              {{ formatTime(ratePreview.fetched_at) }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>

      <el-col :xs="24" :md="12">
        <el-card shadow="never">
          <template #header>
            <span class="font-medium">平台统计（只读）</span>
          </template>
          <el-descriptions :column="1" border size="small">
            <el-descriptions-item label="闪兑取整盈余">
              {{ fmtYuan(stats.total_exchange_surplus_yuan) }}
              <span class="text-gray-400 text-xs ml-1">（{{ stats.total_exchange_surplus_fen ?? 0 }} 分）</span>
            </el-descriptions-item>
            <el-descriptions-item label="累计 USDT 手续费">
              {{ microToUsdt(stats.total_fee_usdt_micro ?? 0).toFixed(6) }} USDT
            </el-descriptions-item>
            <el-descriptions-item label="累计 99币 手续费">
              {{ ((stats.total_fee_platform_fen ?? 0) / 100).toFixed(2) }} 元
            </el-descriptions-item>
            <el-descriptions-item label="配置更新时间">
              {{ formatTime(config?.updated_at) }}
            </el-descriptions-item>
          </el-descriptions>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
