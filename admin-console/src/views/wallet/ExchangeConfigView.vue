<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { getExchangeConfig, putExchangeConfig } from "@/api/exchangeConfig";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const saving = ref(false);
const preview = ref<Record<string, unknown>>({});
const stats = ref<Record<string, unknown>>({});
const form = reactive({
  enabled: true,
  markup_bps: 0,
  float_bps: 0,
  min_withdraw_usdt_micro: 0,
  frankfurter_url: "",
  exchange_rate_cache_seconds: 0,
  updated_at: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = (await getExchangeConfig()) as Record<string, unknown>;
    form.enabled = raw.enabled === undefined ? true : Boolean(raw.enabled);
    form.markup_bps = Number(raw.markup_bps ?? raw.markupBps ?? 0);
    form.float_bps = Number(raw.float_bps ?? raw.floatBps ?? 0);
    form.min_withdraw_usdt_micro = Number(raw.min_withdraw_usdt_micro ?? raw.minWithdrawUsdtMicro ?? 0);
    form.frankfurter_url = String(raw.frankfurter_url ?? raw.frankfurterUrl ?? "");
    form.exchange_rate_cache_seconds = Number(
      raw.exchange_rate_cache_seconds ?? raw.exchangeRateCacheSeconds ?? 0
    );
    form.updated_at = String(raw.updated_at ?? raw.updatedAt ?? "");
    preview.value = (raw.rate_preview as Record<string, unknown>) || (raw.ratePreview as Record<string, unknown>) || {};
    stats.value = (raw.stats as Record<string, unknown>) || {};
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function save() {
  saving.value = true;
  try {
    await putExchangeConfig({
      enabled: form.enabled,
      markup_bps: form.markup_bps,
      float_bps: form.float_bps,
      min_withdraw_usdt_micro: form.min_withdraw_usdt_micro,
      frankfurter_url: form.frankfurter_url || undefined,
      exchange_rate_cache_seconds: form.exchange_rate_cache_seconds
    });
    ElMessage.success("已保存");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    saving.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="闪兑配置" />
    <div class="page-card" style="max-width: 720px; margin-bottom: 12px">
      <el-form label-width="180px">
        <el-form-item label="闪兑开关">
          <el-switch v-model="form.enabled" :disabled="!canWrite" active-text="开放" inactive-text="维护" />
          <div style="margin-left: 12px; color: #909399; font-size: 12px">
            关闭后用户闪兑将提示「正在维护」
          </div>
        </el-form-item>
        <el-form-item label="加点 (bps)">
          <el-input-number v-model="form.markup_bps" :controls="false" style="width: 100%" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="浮动 (bps)">
          <el-input-number v-model="form.float_bps" :controls="false" style="width: 100%" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="最小提现 USDT(micro)">
          <el-input-number
            v-model="form.min_withdraw_usdt_micro"
            :controls="false"
            style="width: 100%"
            :disabled="!canWrite"
          />
        </el-form-item>
        <el-form-item label="汇率源 URL">
          <el-input v-model="form.frankfurter_url" :disabled="!canWrite" />
        </el-form-item>
        <el-form-item label="汇率缓存秒数">
          <el-input-number
            v-model="form.exchange_rate_cache_seconds"
            :controls="false"
            style="width: 100%"
            :disabled="!canWrite"
          />
        </el-form-item>
        <el-form-item label="更新时间">{{ formatTime(form.updated_at) }}</el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" :disabled="!canWrite" @click="save">保存</el-button>
          <el-button @click="load">刷新</el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card" style="margin-bottom: 12px">
      <h3 style="margin: 0 0 12px; font-size: 15px">汇率预览</h3>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="基准 USD/CNY">{{ preview.base_usd_cny ?? preview.baseUsdCny ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="买入 CNY/USDT">{{ preview.buy_cny_per_usdt ?? preview.buyCnyPerUsdt ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="卖出 CNY/USDT">{{ preview.sell_cny_per_usdt ?? preview.sellCnyPerUsdt ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="中间价">{{ preview.mid_cny_per_usdt ?? preview.midCnyPerUsdt ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="1 USDT→平台币(元)">{{ preview.example_one_usdt_to_platform_yuan ?? preview.exampleOneUsdtToPlatformYuan ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="拉取时间">{{ formatTime(preview.fetched_at || preview.fetchedAt) }}</el-descriptions-item>
        <el-descriptions-item v-if="preview.error" label="错误" :span="2">{{ preview.error }}</el-descriptions-item>
      </el-descriptions>
    </div>

    <div class="page-card">
      <h3 style="margin: 0 0 12px; font-size: 15px">平台统计</h3>
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="闪兑盈余(分)">{{ stats.total_exchange_surplus_fen ?? stats.totalExchangeSurplusFen ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="闪兑盈余(元)">{{ stats.total_exchange_surplus_yuan ?? stats.totalExchangeSurplusYuan ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="手续费 USDT micro">{{ stats.total_fee_usdt_micro ?? stats.totalFeeUsdtMicro ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="手续费平台币(分)">{{ stats.total_fee_platform_fen ?? stats.totalFeePlatformFen ?? "—" }}</el-descriptions-item>
      </el-descriptions>
    </div>
  </div>
</template>
