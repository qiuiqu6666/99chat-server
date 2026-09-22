<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { listCurrencies, updateCurrency, uploadCurrencyLogo } from "@/api/currencies";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const writeOk = computed(() => hasPerm("user.write"));
const loading = ref(false);
const saving = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const editVisible = ref(false);
const form = reactive({
  code: "",
  name: "",
  logo_url: "",
  platform_coin: false,
  deposit_enabled: true,
  withdraw_enabled: true,
  sort_order: 0,
  enabled: true,
  withdraw_fee_type: "NONE",
  withdraw_fee_value: 0 as number,
  withdraw_fee_min: undefined as number | undefined,
  withdraw_fee_max: undefined as number | undefined,
  withdraw_fee_enabled: false
});

async function load() {
  loading.value = true;
  try {
    rows.value = pickList(await listCurrencies()).items;
  } catch (e) {
    rows.value = [];
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

function openEdit(row: Record<string, unknown>) {
  form.code = String(row.code || "");
  form.name = String(row.name || "");
  form.logo_url = str(row, "logo_url", "logoUrl");
  form.platform_coin = Boolean(row.platform_coin ?? row.platformCoin);
  form.deposit_enabled = Boolean(row.deposit_enabled ?? row.depositEnabled);
  form.withdraw_enabled = Boolean(row.withdraw_enabled ?? row.withdrawEnabled);
  form.sort_order = Number(row.sort_order ?? row.sortOrder ?? 0);
  form.enabled = Boolean(row.enabled);
  form.withdraw_fee_type = String(row.withdraw_fee_type ?? row.withdrawFeeType ?? "NONE");
  form.withdraw_fee_value = Number(row.withdraw_fee_value ?? row.withdrawFeeValue ?? 0);
  form.withdraw_fee_min =
    row.withdraw_fee_min != null || row.withdrawFeeMin != null
      ? Number(row.withdraw_fee_min ?? row.withdrawFeeMin)
      : undefined;
  form.withdraw_fee_max =
    row.withdraw_fee_max != null || row.withdrawFeeMax != null
      ? Number(row.withdraw_fee_max ?? row.withdrawFeeMax)
      : undefined;
  form.withdraw_fee_enabled = Boolean(row.withdraw_fee_enabled ?? row.withdrawFeeEnabled);
  editVisible.value = true;
}

async function onUploadLogo(file: File) {
  try {
    const res = await uploadCurrencyLogo(file);
    form.logo_url = String(res.logo_url ?? res.logoUrl ?? "");
    ElMessage.success("Logo 已上传");
  } catch (e) {
    ElMessage.error(errMessage(e));
  }
  return false;
}

async function save() {
  saving.value = true;
  try {
    await updateCurrency(form.code, {
      name: form.name,
      logo_url: form.logo_url || undefined,
      platform_coin: form.platform_coin,
      deposit_enabled: form.deposit_enabled,
      withdraw_enabled: form.withdraw_enabled,
      sort_order: form.sort_order,
      enabled: form.enabled,
      withdraw_fee_type: form.withdraw_fee_type,
      withdraw_fee_value: form.withdraw_fee_value,
      withdraw_fee_min: form.withdraw_fee_min,
      withdraw_fee_max: form.withdraw_fee_max,
      withdraw_fee_enabled: form.withdraw_fee_enabled
    });
    ElMessage.success("已保存");
    editVisible.value = false;
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
  <div class="page">
    <PageHeader title="币种管理" />
    <div class="page-card">
      <div class="toolbar">
        <el-button @click="load">刷新</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="Logo" width="70">
          <template #default="{ row }">
            <img
              v-if="str(row, 'logo_url', 'logoUrl')"
              :src="str(row, 'logo_url', 'logoUrl')"
              style="width: 28px; height: 28px; border-radius: 50%; object-fit: cover"
            />
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="code" label="代码" width="100" />
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="平台币" width="80">
          <template #default="{ row }">{{ (row.platform_coin ?? row.platformCoin) ? "是" : "否" }}</template>
        </el-table-column>
        <el-table-column label="充值" width="70">
          <template #default="{ row }">{{ (row.deposit_enabled ?? row.depositEnabled) ? "开" : "关" }}</template>
        </el-table-column>
        <el-table-column label="提现" width="70">
          <template #default="{ row }">{{ (row.withdraw_enabled ?? row.withdrawEnabled) ? "开" : "关" }}</template>
        </el-table-column>
        <el-table-column label="启用" width="70">
          <template #default="{ row }">
            <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? "是" : "否" }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提现手续费" min-width="160">
          <template #default="{ row }">{{ str(row, "withdraw_fee_label", "withdrawFeeLabel") || "—" }}</template>
        </el-table-column>
        <el-table-column label="排序" width="70">
          <template #default="{ row }">{{ row.sort_order ?? row.sortOrder ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.updated_at || row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column v-if="writeOk" label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <el-dialog v-model="editVisible" :title="`编辑币种 ${form.code}`" width="600px">
      <el-form label-width="120px">
        <el-form-item label="名称"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="Logo URL">
          <div style="display:flex;gap:8px;width:100%">
            <el-input v-model="form.logo_url" />
            <el-upload :show-file-list="false" accept="image/*" :before-upload="onUploadLogo">
              <el-button>上传</el-button>
            </el-upload>
          </div>
        </el-form-item>
        <el-form-item label="平台币"><el-switch v-model="form.platform_coin" /></el-form-item>
        <el-form-item label="充值"><el-switch v-model="form.deposit_enabled" /></el-form-item>
        <el-form-item label="提现"><el-switch v-model="form.withdraw_enabled" /></el-form-item>
        <el-form-item label="启用"><el-switch v-model="form.enabled" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="form.sort_order" :controls="false" /></el-form-item>
        <el-form-item label="提现费类型">
          <el-select v-model="form.withdraw_fee_type" style="width:100%">
            <el-option label="NONE" value="NONE" />
            <el-option label="PERCENT" value="PERCENT" />
            <el-option label="FIXED" value="FIXED" />
          </el-select>
        </el-form-item>
        <el-form-item label="提现费值"><el-input-number v-model="form.withdraw_fee_value" :controls="false" style="width:100%" /></el-form-item>
        <el-form-item label="最低/最高">
          <el-input-number v-model="form.withdraw_fee_min" :controls="false" placeholder="min" />
          <span style="margin:0 8px">-</span>
          <el-input-number v-model="form.withdraw_fee_max" :controls="false" placeholder="max" />
        </el-form-item>
        <el-form-item label="提现费启用"><el-switch v-model="form.withdraw_fee_enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="editVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="save">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
