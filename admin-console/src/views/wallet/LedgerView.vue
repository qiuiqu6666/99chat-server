<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { listLedger } from "@/api/finance";
import {
  amountText,
  currencyLabel,
  LEDGER_STATUS_OPTIONS,
  LEDGER_TYPE_OPTIONS,
  ledgerStatusLabel,
  ledgerTypeLabel,
  nicknameForUid,
  pickList,
  str
} from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const route = useRoute();
const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: "",
  user_uid: String(route.query.user_uid || ""),
  transaction_type: "",
  status: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listLedger({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      user_uid: query.user_uid || undefined,
      transaction_type: query.transaction_type || undefined,
      status: query.status || undefined
    });
    const picked = pickList(raw);
    rows.value = picked.items;
    total.value = picked.total;
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

watch(
  () => route.query.user_uid,
  v => {
    query.user_uid = String(v || "");
    query.page = 1;
    load();
  }
);
onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="账变记录" />
    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="query.user_uid"
          clearable
          placeholder="用户 UID"
          style="width: 180px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="关键词"
          style="width: 180px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-select v-model="query.transaction_type" clearable placeholder="类型" style="width: 140px">
          <el-option
            v-for="o in LEDGER_TYPE_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
        <el-select v-model="query.status" clearable placeholder="状态" style="width: 120px">
          <el-option
            v-for="o in LEDGER_STATUS_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="流水号" min-width="150">
          <template #default="{ row }">{{ str(row, "transaction_no", "transactionNo") || "—" }}</template>
        </el-table-column>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <UserUidLink
              :uid="str(row, 'from_uid', 'fromUid', 'user_uid', 'userUid', 'to_uid', 'toUid')"
            />
          </template>
        </el-table-column>
        <el-table-column label="昵称" min-width="110">
          <template #default="{ row }">
            {{
              nicknameForUid(
                row,
                str(row, "from_uid", "fromUid", "user_uid", "userUid", "to_uid", "toUid")
              )
            }}
          </template>
        </el-table-column>
        <el-table-column label="类型" min-width="120">
          <template #default="{ row }">
            {{ ledgerTypeLabel(row.transaction_type ?? row.transactionType) }}
          </template>
        </el-table-column>
        <el-table-column label="金额" width="120">
          <template #default="{ row }">{{ amountText(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="币种" width="100">
          <template #default="{ row }">{{ currencyLabel(row.currency) }}</template>
        </el-table-column>
        <el-table-column label="变动前" width="120">
          <template #default="{ row }">{{ amountText(row.balance_before || row.balanceBefore) }}</template>
        </el-table-column>
        <el-table-column label="变动后" width="120">
          <template #default="{ row }">{{ amountText(row.balance_after || row.balanceAfter) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ ledgerStatusLabel(row.status) }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.remark || "—" }}</template>
        </el-table-column>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.create_time || row.createTime) }}</template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px; display: flex; justify-content: flex-end">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.page_size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>
  </div>
</template>
