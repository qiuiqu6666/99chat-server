<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { listSweepLogs } from "@/api/walletTreasury";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const route = useRoute();
const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: String(route.query.user_uid || ""),
  status: "",
  trigger: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listSweepLogs({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      status: query.status || undefined,
      trigger: query.trigger || undefined
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
    query.keyword = String(v || "");
    query.page = 1;
    load();
  }
);
onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="归集记录" />
    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="UID / 地址"
          style="width: 200px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-select v-model="query.status" clearable placeholder="状态" style="width: 140px">
          <el-option label="成功" value="SUCCESS" />
          <el-option label="失败" value="FAILED" />
        </el-select>
        <el-select v-model="query.trigger" clearable placeholder="触发" style="width: 140px">
          <el-option label="手动" value="MANUAL" />
          <el-option label="自动" value="AUTO" />
          <el-option label="全部归集" value="COLLECT_ALL" />
        </el-select>
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="ID" width="80">
          <template #default="{ row }">{{ row.id ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <UserUidLink :uid="str(row, 'user_uid', 'userUid')" />
          </template>
        </el-table-column>
        <el-table-column label="昵称" min-width="100">
          <template #default="{ row }">{{ row.nickname || "—" }}</template>
        </el-table-column>
        <el-table-column label="来源地址" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, 'from_address', 'fromAddress') || "—" }}</template>
        </el-table-column>
        <el-table-column label="USDT" width="100">
          <template #default="{ row }">{{ row.usdt_swept ?? row.usdtSwept ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="TRX" width="100">
          <template #default="{ row }">{{ row.trx_swept ?? row.trxSwept ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="USDT Tx" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, 'usdt_tx_id', 'usdtTxId') || "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="100">
          <template #default="{ row }">{{ row.status || "—" }}</template>
        </el-table-column>
        <el-table-column label="触发" width="100">
          <template #default="{ row }">{{ str(row, 'trigger_type', 'triggerType') || "—" }}</template>
        </el-table-column>
        <el-table-column label="操作人" width="110">
          <template #default="{ row }">{{ row.operator || "—" }}</template>
        </el-table-column>
        <el-table-column label="失败原因" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, 'fail_reason', 'failReason') || "—" }}</template>
        </el-table-column>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.created_at || row.createdAt) }}</template>
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
