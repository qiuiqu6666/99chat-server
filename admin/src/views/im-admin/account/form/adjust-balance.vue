<script setup lang="ts">
import { computed, reactive, ref, watch } from "vue";
import type { FormRules } from "element-plus";
import type { ImAdjustBalanceFormInline } from "../utils/balanceAdjust";
import { resolveAdjustFinalBalance } from "../utils/balanceAdjust";
import {
  WALLET_CURRENCY_OPTIONS,
  formatWalletCurrencyAmount,
  getAvailableBalanceForCurrency,
  getWalletCurrencyMeta,
  type WalletCurrencyCode
} from "../utils/walletCurrencyPolicy";

const props = defineProps<{
  formInline: ImAdjustBalanceFormInline;
}>();

const ruleFormRef = ref();
const model = reactive<ImAdjustBalanceFormInline>({ ...props.formInline });

watch(
  () => props.formInline,
  v => Object.assign(model, v),
  { deep: true }
);

const currencyMeta = computed(() => getWalletCurrencyMeta(model.currency));
const previewFinalBalance = computed(() => resolveAdjustFinalBalance(model));

watch(
  () => model.balanceAdjustKind,
  () => ruleFormRef.value?.clearValidate?.(["balanceAdjustAmount"])
);

watch(
  () => model.currency,
  code => {
    model.initialBalance = getAvailableBalanceForCurrency(
      model.walletSnapshot,
      code as WalletCurrencyCode
    );
    const meta = getWalletCurrencyMeta(code);
    const minStep = meta.decimals === 6 ? 0.000001 : 0.01;
    if (
      !Number.isFinite(Number(model.balanceAdjustAmount)) ||
      Number(model.balanceAdjustAmount) < minStep
    ) {
      model.balanceAdjustAmount = minStep;
    }
    ruleFormRef.value?.clearValidate?.(["balanceAdjustAmount"]);
  }
);

const formRules = computed<FormRules>(() => ({
  currency: [{ required: true, message: "请选择币种", trigger: "change" }],
  balanceAdjustAmount: [
    {
      validator: (_rule, value, callback) => {
        const n = Number(value);
        if (!Number.isFinite(n) || n <= 0) {
          callback(new Error("请输入大于 0 的金额"));
          return;
        }
        const meta = getWalletCurrencyMeta(model.currency);
        const minStep = meta.decimals === 6 ? 0.000001 : 0.01;
        if (n < minStep) {
          callback(new Error(`最小 ${minStep}`));
          return;
        }
        if (n > 999999999) {
          callback(new Error("金额过大"));
          return;
        }
        if (
          model.balanceAdjustKind === "decrease" &&
          n > model.initialBalance + 1e-12
        ) {
          callback(new Error("不能超过可用余额"));
          return;
        }
        const after = resolveAdjustFinalBalance(model);
        if (after < 0 || !Number.isFinite(after)) {
          callback(new Error("调整后余额无效"));
          return;
        }
        callback();
      },
      trigger: ["blur", "change"]
    }
  ]
}));

function getRef() {
  return ruleFormRef.value;
}

function syncToOutbound() {
  Object.assign(props.formInline, model);
}

defineExpose({ getRef, syncToOutbound });
</script>

<template>
  <div class="adjust-flat">
    <p class="adjust-flat__user">
      {{ model.nickname }}
      <span class="adjust-flat__uid">{{ model.uid }}</span>
    </p>

    <el-form
      ref="ruleFormRef"
      :model="model"
      :rules="formRules"
      label-position="top"
      class="adjust-flat__form"
    >
      <div class="adjust-flat__balance">
        <el-form-item label="币种" prop="currency" class="adjust-flat__currency">
          <el-select v-model="model.currency" size="default">
            <el-option
              v-for="opt in WALLET_CURRENCY_OPTIONS"
              :key="opt.code"
              :label="opt.code"
              :value="opt.code"
            />
          </el-select>
        </el-form-item>
        <div class="adjust-flat__avail">
          <span class="adjust-flat__avail-label">可用</span>
          <span class="adjust-flat__avail-value">
            {{ formatWalletCurrencyAmount(model.currency, model.initialBalance) }}
          </span>
        </div>
      </div>

      <el-form-item label="调整" class="adjust-flat__kind">
        <div class="kind-tabs">
          <button
            type="button"
            class="kind-tabs__btn"
            :class="{ 'is-active': model.balanceAdjustKind === 'increase' }"
            @click="model.balanceAdjustKind = 'increase'"
          >
            增加
          </button>
          <button
            type="button"
            class="kind-tabs__btn"
            :class="{ 'is-active': model.balanceAdjustKind === 'decrease' }"
            @click="model.balanceAdjustKind = 'decrease'"
          >
            减少
          </button>
        </div>
      </el-form-item>

      <el-form-item label="金额" prop="balanceAdjustAmount">
        <el-input-number
          v-model="model.balanceAdjustAmount"
          class="adjust-flat__amount"
          controls-position="right"
          :min="currencyMeta.decimals === 6 ? 0.000001 : 0.01"
          :max="999999999"
          :precision="currencyMeta.decimals"
          :step="currencyMeta.decimals === 6 ? 1 : 10"
        />
      </el-form-item>

      <p class="adjust-flat__preview">
        调整后
        <strong>{{
          formatWalletCurrencyAmount(model.currency, previewFinalBalance)
        }}</strong>
      </p>
    </el-form>
  </div>
</template>

<style scoped>
.adjust-flat {
  padding: 0 2px 4px;
}

.adjust-flat__user {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  line-height: 1.4;
}

.adjust-flat__uid {
  margin-left: 8px;
  font-family: ui-monospace, monospace;
  font-size: 12px;
  color: var(--el-text-color-placeholder);
}

.adjust-flat__form :deep(.el-form-item) {
  margin-bottom: 14px;
}

.adjust-flat__form :deep(.el-form-item__label) {
  padding-bottom: 6px;
  font-size: 13px;
  font-weight: 500;
  color: var(--el-text-color-regular);
  line-height: 1.2;
}

.adjust-flat__balance {
  display: flex;
  align-items: flex-end;
  gap: 12px;
  margin-bottom: 4px;
  padding: 12px 14px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.adjust-flat__currency {
  flex: 0 0 108px;
  margin-bottom: 0 !important;
}

.adjust-flat__currency :deep(.el-form-item__label) {
  padding-bottom: 4px;
}

.adjust-flat__currency :deep(.el-select) {
  width: 100%;
}

.adjust-flat__avail {
  flex: 1;
  min-width: 0;
  padding-bottom: 2px;
}

.adjust-flat__avail-label {
  display: block;
  margin-bottom: 4px;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}

.adjust-flat__avail-value {
  display: block;
  font-size: 20px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  line-height: 1.2;
  word-break: break-all;
}

.adjust-flat__kind {
  margin-bottom: 12px !important;
}

.kind-tabs {
  display: flex;
  width: 100%;
  padding: 2px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
}

.kind-tabs__btn {
  flex: 1;
  height: 32px;
  border: none;
  border-radius: 4px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 13px;
  cursor: pointer;
  transition: background 0.15s, color 0.15s;
}

.kind-tabs__btn.is-active {
  background: var(--el-bg-color);
  color: var(--el-color-primary);
  font-weight: 500;
  box-shadow: none;
}

.kind-tabs__btn:not(.is-active):hover {
  color: var(--el-text-color-primary);
}

.adjust-flat__amount {
  width: 100%;
}

.adjust-flat__amount :deep(.el-input__wrapper) {
  border-radius: 6px;
}

.adjust-flat__preview {
  margin: 0;
  padding-top: 2px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
  text-align: right;
}

.adjust-flat__preview strong {
  margin-left: 6px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
</style>
