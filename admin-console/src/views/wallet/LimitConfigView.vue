<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { listLimitConfig, updateLimitConfig } from "@/api/limitConfig";
import {
  currencyLabel,
  WALLET_LIMIT_SCENE_GROUPS,
  walletAmountFromRaw,
  walletAmountPrecision,
  walletAmountToRaw,
  walletAmountUnit
} from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

type LimitRow = {
  id: number | string;
  scene?: string;
  currency?: string;
  per_tx_max: number;
  daily_max: number;
  per_tx_display: number;
  daily_display: number;
  enabled: boolean;
  updated_at?: unknown;
};

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const savingId = ref<string | number>("");
const rows = ref<LimitRow[]>([]);

const primaryGroups = WALLET_LIMIT_SCENE_GROUPS.filter(g =>
  g.scene === "TRANSFER" || g.scene === "RED_PACKET"
);
const otherGroups = WALLET_LIMIT_SCENE_GROUPS.filter(
  g => g.scene !== "TRANSFER" && g.scene !== "RED_PACKET"
);

function asArray(raw: unknown): Record<string, unknown>[] {
  if (Array.isArray(raw)) return raw as Record<string, unknown>[];
  const obj = (raw || {}) as Record<string, unknown>;
  if (Array.isArray(obj.data)) return obj.data as Record<string, unknown>[];
  if (obj.data && typeof obj.data === "object") {
    const items = (obj.data as Record<string, unknown>).items;
    if (Array.isArray(items)) return items as Record<string, unknown>[];
  }
  return Array.isArray(obj.items) ? (obj.items as Record<string, unknown>[]) : [];
}

function normalize(r: Record<string, unknown>): LimitRow {
  const currency = String(r.currency ?? "");
  const perTxMax = Number(r.per_tx_max ?? r.perTxMax ?? 0);
  const dailyMax = Number(r.daily_max ?? r.dailyMax ?? 0);
  return {
    id: (r.id as number | string) ?? "",
    scene: String(r.scene ?? ""),
    currency,
    per_tx_max: perTxMax,
    daily_max: dailyMax,
    per_tx_display: walletAmountFromRaw(currency, perTxMax),
    daily_display: walletAmountFromRaw(currency, dailyMax),
    enabled: Boolean(r.enabled),
    updated_at: r.updated_at ?? r.updatedAt
  };
}

function rowsForScene(scene: string) {
  return rows.value.filter(r => String(r.scene ?? "").toUpperCase() === scene);
}

async function load() {
  loading.value = true;
  try {
    rows.value = asArray(await listLimitConfig()).map(normalize);
  } catch (e) {
    rows.value = [];
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function save(row: LimitRow) {
  if (row.id === "" || row.id == null) return;
  savingId.value = row.id;
  try {
    const perTxMax = walletAmountToRaw(row.currency, row.per_tx_display);
    const dailyMax = walletAmountToRaw(row.currency, row.daily_display);
    await updateLimitConfig(row.id, {
      per_tx_max: perTxMax,
      daily_max: dailyMax,
      enabled: Boolean(row.enabled)
    });
    row.per_tx_max = perTxMax;
    row.daily_max = dailyMax;
    ElMessage.success("已保存");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    savingId.value = "";
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="限额配置" subtitle="配置转账、红包等场景的单笔与每日累计上限" />

    <div v-loading="loading">
      <div v-for="group in primaryGroups" :key="group.scene" class="page-card limit-section">
        <div class="section-head">
          <h3>{{ group.title }}</h3>
          <p>{{ group.description }}</p>
        </div>
        <el-table :data="rowsForScene(group.scene)" stripe border empty-text="暂无配置，请重启服务触发默认种子">
          <el-table-column label="币种" width="110">
            <template #default="{ row }">{{ currencyLabel(row.currency) }}</template>
          </el-table-column>
          <el-table-column label="单笔上限" min-width="180">
            <template #default="{ row }">
              <div class="amount-cell">
                <el-input-number
                  v-model="row.per_tx_display"
                  :disabled="!canWrite"
                  :controls="false"
                  :precision="walletAmountPrecision(row.currency)"
                  :min="0"
                  style="width: 140px"
                />
                <span class="unit">{{ walletAmountUnit(row.currency) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="每日上限" min-width="180">
            <template #default="{ row }">
              <div class="amount-cell">
                <el-input-number
                  v-model="row.daily_display"
                  :disabled="!canWrite"
                  :controls="false"
                  :precision="walletAmountPrecision(row.currency)"
                  :min="0"
                  style="width: 140px"
                />
                <span class="unit">{{ walletAmountUnit(row.currency) }}</span>
              </div>
            </template>
          </el-table-column>
          <el-table-column label="启用" width="90">
            <template #default="{ row }">
              <el-switch v-model="row.enabled" :disabled="!canWrite" />
            </template>
          </el-table-column>
          <el-table-column label="更新时间" min-width="160">
            <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
          </el-table-column>
          <el-table-column v-if="canWrite" label="操作" width="100" fixed="right">
            <template #default="{ row }">
              <el-button type="primary" link :loading="savingId === row.id" @click="save(row)">保存</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <el-collapse class="page-card other-limits">
        <el-collapse-item title="其他限额（提现 / 直播打赏）" name="other">
          <div v-for="group in otherGroups" :key="group.scene" class="limit-section nested">
            <div class="section-head compact">
              <h4>{{ group.title }}</h4>
              <p>{{ group.description }}</p>
            </div>
            <el-table :data="rowsForScene(group.scene)" stripe border empty-text="暂无配置">
              <el-table-column label="币种" width="110">
                <template #default="{ row }">{{ currencyLabel(row.currency) }}</template>
              </el-table-column>
              <el-table-column label="单笔上限" min-width="180">
                <template #default="{ row }">
                  <div class="amount-cell">
                    <el-input-number
                      v-model="row.per_tx_display"
                      :disabled="!canWrite"
                      :controls="false"
                      :precision="walletAmountPrecision(row.currency)"
                      :min="0"
                      style="width: 140px"
                    />
                    <span class="unit">{{ walletAmountUnit(row.currency) }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="每日上限" min-width="180">
                <template #default="{ row }">
                  <div class="amount-cell">
                    <el-input-number
                      v-model="row.daily_display"
                      :disabled="!canWrite"
                      :controls="false"
                      :precision="walletAmountPrecision(row.currency)"
                      :min="0"
                      style="width: 140px"
                    />
                    <span class="unit">{{ walletAmountUnit(row.currency) }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="启用" width="90">
                <template #default="{ row }">
                  <el-switch v-model="row.enabled" :disabled="!canWrite" />
                </template>
              </el-table-column>
              <el-table-column label="更新时间" min-width="160">
                <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
              </el-table-column>
              <el-table-column v-if="canWrite" label="操作" width="100" fixed="right">
                <template #default="{ row }">
                  <el-button type="primary" link :loading="savingId === row.id" @click="save(row)">保存</el-button>
                </template>
              </el-table-column>
            </el-table>
          </div>
        </el-collapse-item>
      </el-collapse>

      <div class="toolbar">
        <el-button @click="load">刷新</el-button>
      </div>
    </div>
  </div>
</template>

<style scoped>
.limit-section {
  margin-bottom: 12px;
}
.limit-section.nested + .limit-section.nested {
  margin-top: 16px;
}
.section-head h3,
.section-head h4 {
  margin: 0 0 6px;
  font-size: 15px;
}
.section-head.compact h4 {
  font-size: 14px;
}
.section-head p {
  margin: 0 0 12px;
  color: #909399;
  font-size: 12px;
  line-height: 1.5;
}
.amount-cell {
  display: flex;
  align-items: center;
  gap: 8px;
}
.unit {
  color: #606266;
  font-size: 12px;
  white-space: nowrap;
}
.other-limits {
  margin-bottom: 12px;
  border: none;
  background: transparent;
}
.toolbar {
  display: flex;
  justify-content: flex-end;
}
</style>
