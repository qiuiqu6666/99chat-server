<script setup lang="ts">
import dayjs from "dayjs";
import type { UploadProps } from "element-plus";
import { computed, onMounted, reactive, ref } from "vue";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  bpsToPercent,
  getCurrencyList,
  microToUsdt,
  percentToBps,
  updateCurrency,
  uploadCurrencyLogo,
  usdtToMicro,
  type ImCurrencyItem
} from "@/api/im-currency";

defineOptions({ name: "ImCurrencyList" });

const loading = ref(false);
const saving = ref(false);
const uploading = ref(false);
const dataList = ref<ImCurrencyItem[]>([]);
const dialogVisible = ref(false);
const editing = ref<ImCurrencyItem | null>(null);

const form = reactive({
  name: "",
  logo_url: "",
  platform_coin: false,
  deposit_enabled: false,
  withdraw_enabled: false,
  sort_order: 1,
  enabled: true,
  withdraw_fee_enabled: false,
  withdraw_fee_type: "FIXED" as "NONE" | "FIXED" | "PERCENT",
  withdraw_fee_usdt: 1,
  withdraw_fee_percent: 0,
  withdraw_fee_min_usdt: null as number | null,
  withdraw_fee_max_usdt: null as number | null
});

const showWithdrawFee = computed(
  () => !!editing.value?.withdraw_fee_applicable && form.withdraw_enabled
);

function formatTime(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getCurrencyList();
    dataList.value = res.items;
  } catch (err: unknown) {
    dataList.value = [];
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 wallet.read）", { type: "warning" });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "币种列表加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function openEdit(row: ImCurrencyItem) {
  editing.value = row;
  form.name = row.name || "";
  form.logo_url = row.logo_url || "";
  form.platform_coin = !!row.platform_coin;
  form.deposit_enabled = !!row.deposit_enabled;
  form.withdraw_enabled = !!row.withdraw_enabled;
  form.sort_order = row.sort_order ?? 1;
  form.enabled = row.enabled !== false;
  form.withdraw_fee_enabled = !!row.withdraw_fee_enabled && row.withdraw_fee_type !== "NONE";
  form.withdraw_fee_type =
    row.withdraw_fee_type === "PERCENT" || row.withdraw_fee_type === "FIXED"
      ? row.withdraw_fee_type
      : "FIXED";
  form.withdraw_fee_usdt = microToUsdt(row.withdraw_fee_value ?? 0);
  form.withdraw_fee_percent = bpsToPercent(row.withdraw_fee_value ?? 0);
  form.withdraw_fee_min_usdt =
    row.withdraw_fee_min == null ? null : microToUsdt(row.withdraw_fee_min);
  form.withdraw_fee_max_usdt =
    row.withdraw_fee_max == null ? null : microToUsdt(row.withdraw_fee_max);
  dialogVisible.value = true;
}

async function saveEdit() {
  if (!editing.value?.code) return;
  if (!form.name.trim()) {
    message("请填写币种名称", { type: "warning" });
    return;
  }
  saving.value = true;
  try {
    const payload: Parameters<typeof updateCurrency>[1] = {
      name: form.name.trim(),
      logo_url: form.logo_url.trim() || undefined,
      platform_coin: form.platform_coin,
      deposit_enabled: form.deposit_enabled,
      withdraw_enabled: form.withdraw_enabled,
      sort_order: form.sort_order,
      enabled: form.enabled
    };
    if (editing.value.withdraw_fee_applicable) {
      if (!form.withdraw_enabled || !form.withdraw_fee_enabled) {
        payload.withdraw_fee_enabled = false;
        payload.withdraw_fee_type = "NONE";
      } else if (form.withdraw_fee_type === "PERCENT") {
        payload.withdraw_fee_enabled = true;
        payload.withdraw_fee_type = "PERCENT";
        payload.withdraw_fee_value = percentToBps(form.withdraw_fee_percent);
        payload.withdraw_fee_min =
          form.withdraw_fee_min_usdt == null ? null : usdtToMicro(form.withdraw_fee_min_usdt);
        payload.withdraw_fee_max =
          form.withdraw_fee_max_usdt == null ? null : usdtToMicro(form.withdraw_fee_max_usdt);
      } else {
        payload.withdraw_fee_enabled = true;
        payload.withdraw_fee_type = "FIXED";
        payload.withdraw_fee_value = usdtToMicro(form.withdraw_fee_usdt);
        payload.withdraw_fee_min = null;
        payload.withdraw_fee_max = null;
      }
    }
    await updateCurrency(editing.value.code, payload);
    message("币种配置已保存", { type: "success" });
    dialogVisible.value = false;
    await loadData();
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

const beforeLogoUpload: UploadProps["beforeUpload"] = rawFile => {
  const allowed = ["image/jpeg", "image/png", "image/webp", "image/gif"];
  if (!allowed.includes(rawFile.type)) {
    message("仅支持 JPG / PNG / WEBP / GIF", { type: "warning" });
    return false;
  }
  if (rawFile.size > 2 * 1024 * 1024) {
    message("Logo 不能超过 2MB", { type: "warning" });
    return false;
  }
  return true;
};

const handleLogoUpload: UploadProps["httpRequest"] = async options => {
  const file = options.file as File;
  uploading.value = true;
  try {
    const res = await uploadCurrencyLogo(file);
    if (res.logo_url) {
      form.logo_url = res.logo_url;
      message("Logo 上传成功", { type: "success" });
    }
    options.onSuccess?.(res);
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "Logo 上传失败"), { type: "warning" });
    options.onError?.(err as Error);
  } finally {
    uploading.value = false;
  }
};

onMounted(() => loadData());
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <div class="mb-3 text-sm text-gray-500">
        管理客户端钱包展示的币种名称、Logo、充提开关、提币手续费与排序；修改后立即对用户端生效。
      </div>

      <el-table :data="dataList" border stripe v-loading="loading" row-key="code">
        <el-table-column prop="code" label="币种代码" width="110" />
        <el-table-column label="Logo" width="88">
          <template #default="{ row }">
            <el-image
              v-if="row.logo_url"
              :src="row.logo_url"
              fit="cover"
              class="h-10 w-10 rounded"
            />
            <span v-else class="text-gray-400">—</span>
          </template>
        </el-table-column>
        <el-table-column prop="name" label="名称" min-width="120" />
        <el-table-column label="平台币" width="88">
          <template #default="{ row }">
            <el-tag :type="row.platform_coin ? 'success' : 'info'" effect="plain">
              {{ row.platform_coin ? "是" : "否" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="充值" width="80">
          <template #default="{ row }">
            <el-tag :type="row.deposit_enabled ? 'success' : 'info'" effect="plain">
              {{ row.deposit_enabled ? "开" : "关" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提现" width="80">
          <template #default="{ row }">
            <el-tag :type="row.withdraw_enabled ? 'success' : 'info'" effect="plain">
              {{ row.withdraw_enabled ? "开" : "关" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="提币手续费" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.withdraw_fee_label || "—" }}
          </template>
        </el-table-column>
        <el-table-column prop="sort_order" label="排序" width="72" />
        <el-table-column label="启用" width="80">
          <template #default="{ row }">
            <el-tag :type="row.enabled !== false ? 'success' : 'danger'" effect="plain">
              {{ row.enabled !== false ? "是" : "否" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="170">
          <template #default="{ row }">{{ formatTime(row.updated_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="100" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="openEdit(row)">编辑</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-dialog v-model="dialogVisible" :title="`编辑币种 ${editing?.code || ''}`" width="560px" destroy-on-close>
      <el-form label-width="96px">
        <el-form-item label="币种代码">
          <el-input :model-value="editing?.code || ''" disabled />
        </el-form-item>
        <el-form-item label="名称" required>
          <el-input v-model="form.name" maxlength="64" show-word-limit placeholder="如 99币 / USDT" />
        </el-form-item>
        <el-form-item label="Logo URL">
          <el-input v-model="form.logo_url" placeholder="https://..." />
        </el-form-item>
        <el-form-item label="上传 Logo">
          <div class="flex items-center gap-3">
            <el-upload
              :show-file-list="false"
              accept="image/jpeg,image/png,image/webp,image/gif"
              :before-upload="beforeLogoUpload"
              :http-request="handleLogoUpload"
            >
              <el-button :loading="uploading">选择图片上传</el-button>
            </el-upload>
            <el-image
              v-if="form.logo_url"
              :src="form.logo_url"
              fit="cover"
              class="h-12 w-12 rounded border"
            />
          </div>
        </el-form-item>
        <el-form-item label="平台币">
          <el-switch v-model="form.platform_coin" />
        </el-form-item>
        <el-form-item label="允许充值">
          <el-switch v-model="form.deposit_enabled" />
        </el-form-item>
        <el-form-item label="允许提现">
          <el-switch v-model="form.withdraw_enabled" />
        </el-form-item>
        <template v-if="showWithdrawFee">
          <el-divider content-position="left">提币手续费</el-divider>
          <el-form-item label="收取手续费">
            <el-switch v-model="form.withdraw_fee_enabled" />
          </el-form-item>
          <template v-if="form.withdraw_fee_enabled">
            <el-form-item label="计费方式">
              <el-radio-group v-model="form.withdraw_fee_type">
                <el-radio value="FIXED">固定 USDT</el-radio>
                <el-radio value="PERCENT">按提现比例</el-radio>
              </el-radio-group>
            </el-form-item>
            <el-form-item v-if="form.withdraw_fee_type === 'FIXED'" label="固定手续费">
              <div class="flex items-center gap-2">
                <el-input-number v-model="form.withdraw_fee_usdt" :min="0" :max="1000" :step="0.1" :precision="6" />
                <span class="text-gray-500">USDT</span>
              </div>
            </el-form-item>
            <template v-else>
              <el-form-item label="手续费比例">
                <div class="flex items-center gap-2">
                  <el-input-number v-model="form.withdraw_fee_percent" :min="0" :max="100" :step="0.01" :precision="2" />
                  <span class="text-gray-500">%</span>
                </div>
              </el-form-item>
              <el-form-item label="最低手续费">
                <div class="flex items-center gap-2">
                  <el-input-number
                    v-model="form.withdraw_fee_min_usdt"
                    :min="0"
                    :max="1000"
                    :step="0.1"
                    :precision="6"
                    clearable
                  />
                  <span class="text-gray-500">USDT（可选）</span>
                </div>
              </el-form-item>
              <el-form-item label="最高手续费">
                <div class="flex items-center gap-2">
                  <el-input-number
                    v-model="form.withdraw_fee_max_usdt"
                    :min="0"
                    :max="1000"
                    :step="0.1"
                    :precision="6"
                    clearable
                  />
                  <span class="text-gray-500">USDT（可选）</span>
                </div>
              </el-form-item>
            </template>
          </template>
        </template>
        <el-form-item label="排序">
          <el-input-number v-model="form.sort_order" :min="0" :max="999" />
        </el-form-item>
        <el-form-item label="启用展示">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="saveEdit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>
