<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { listFeeConfig, updateFeeConfig } from "@/api/feeConfig";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

type FeeRow = {
  id: number | string;
  scene?: string;
  currency?: string;
  fee_type: string;
  fee_value: number;
  min_fee: number | null;
  max_fee: number | null;
  enabled: boolean;
  updated_at?: unknown;
};

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const savingId = ref<string | number>("");
const rows = ref<FeeRow[]>([]);
const feeTypes = ["NONE", "PERCENT", "FIXED"];

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

function normalize(r: Record<string, unknown>): FeeRow {
  return {
    id: (r.id as number | string) ?? "",
    scene: String(r.scene ?? ""),
    currency: String(r.currency ?? ""),
    fee_type: String(r.fee_type ?? r.feeType ?? "NONE"),
    fee_value: Number(r.fee_value ?? r.feeValue ?? 0),
    min_fee: r.min_fee != null || r.minFee != null ? Number(r.min_fee ?? r.minFee) : null,
    max_fee: r.max_fee != null || r.maxFee != null ? Number(r.max_fee ?? r.maxFee) : null,
    enabled: Boolean(r.enabled),
    updated_at: r.updated_at ?? r.updatedAt
  };
}

async function load() {
  loading.value = true;
  try {
    rows.value = asArray(await listFeeConfig()).map(normalize);
  } catch (e) {
    rows.value = [];
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function save(row: FeeRow) {
  if (row.id === "" || row.id == null) return;
  savingId.value = row.id;
  try {
    await updateFeeConfig(row.id, {
      fee_type: row.fee_type,
      fee_value: Number(row.fee_value || 0),
      min_fee: row.min_fee,
      max_fee: row.max_fee,
      enabled: Boolean(row.enabled)
    });
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
    <PageHeader title="手续费配置" />
    <div class="page-card">
      <div class="toolbar">
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border empty-text="暂无配置">
        <el-table-column prop="id" label="ID" width="70" />
        <el-table-column prop="scene" label="场景" min-width="140" />
        <el-table-column prop="currency" label="币种" width="100" />
        <el-table-column label="计费类型" width="140">
          <template #default="{ row }">
            <el-select v-model="row.fee_type" :disabled="!canWrite" style="width: 120px">
              <el-option v-for="t in feeTypes" :key="t" :label="t" :value="t" />
            </el-select>
          </template>
        </el-table-column>
        <el-table-column label="费用值" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.fee_value" :disabled="!canWrite" :controls="false" style="width: 120px" />
          </template>
        </el-table-column>
        <el-table-column label="最低" width="120">
          <template #default="{ row }">
            <el-input-number v-model="row.min_fee" :disabled="!canWrite" :controls="false" style="width: 100px" />
          </template>
        </el-table-column>
        <el-table-column label="最高" width="120">
          <template #default="{ row }">
            <el-input-number v-model="row.max_fee" :disabled="!canWrite" :controls="false" style="width: 100px" />
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
  </div>
</template>
