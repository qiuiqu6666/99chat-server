<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import {
  collectAll,
  collectUser,
  getTreasurySummary,
  listTreasuryAddresses
} from "@/api/walletTreasury";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const router = useRouter();
const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const actionLoading = ref("");
const summary = ref<Record<string, unknown>>({});
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: "",
  refresh_chain: false
});

async function load() {
  loading.value = true;
  try {
    summary.value = (await getTreasurySummary()) as Record<string, unknown>;
    const raw = await listTreasuryAddresses({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      refresh_chain: query.refresh_chain
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

async function doCollect(uid: string) {
  if (!uid) return;
  try {
    await ElMessageBox.confirm(`确认归集用户 ${uid} 的链上余额？`, "归集确认", { type: "warning" });
  } catch {
    return;
  }
  actionLoading.value = uid;
  try {
    const res = (await collectUser(uid)) as Record<string, unknown>;
    ElMessage.success(String(res.message || res.status || "归集已提交"));
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = "";
  }
}

async function doCollectAll() {
  try {
    await ElMessageBox.confirm("确认对全部可用地址执行归集？该操作影响面大。", "一键归集", {
      type: "warning"
    });
  } catch {
    return;
  }
  actionLoading.value = "all";
  try {
    const res = (await collectAll()) as Record<string, unknown>;
    ElMessage.success(
      `归集完成：成功 ${res.success ?? 0} / 失败 ${res.failed ?? 0} / 跳过 ${res.skipped ?? 0}`
    );
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = "";
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="链上钱包" />
    <div class="page-card" style="margin-bottom: 12px">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="平台 USDT">{{ summary.platform_usdt_total ?? summary.platformUsdtTotal ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="平台 TRX">{{ summary.platform_trx_total ?? summary.platformTrxTotal ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="钱包数">{{ summary.wallet_count ?? summary.walletCount ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="链上 USDT">{{ summary.chain_usdt_total ?? summary.chainUsdtTotal ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="链上 TRX">{{ summary.chain_trx_total ?? summary.chainTrxTotal ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="归集就绪">{{ (summary.collect_ready ?? summary.collectReady) ? "是" : "否" }}</el-descriptions-item>
        <el-descriptions-item label="热钱包" :span="2">{{ summary.hot_wallet_address ?? summary.hotWalletAddress ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="热钱包余额">
          USDT {{ summary.hot_wallet_usdt ?? summary.hotWalletUsdt ?? "—" }} /
          TRX {{ summary.hot_wallet_trx ?? summary.hotWalletTrx ?? "—" }}
        </el-descriptions-item>
      </el-descriptions>
    </div>
    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="query.keyword"
          clearable
          placeholder="UID / 地址 / 昵称"
          style="width: 220px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-checkbox v-model="query.refresh_chain">刷新链上余额</el-checkbox>
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
        <el-button
          v-if="canWrite"
          type="danger"
          :loading="actionLoading === 'all'"
          @click="doCollectAll"
        >一键归集</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <UserUidLink :uid="str(row, 'user_uid', 'userUid')" />
          </template>
        </el-table-column>
        <el-table-column label="昵称" min-width="100">
          <template #default="{ row }">{{ row.nickname || "—" }}</template>
        </el-table-column>
        <el-table-column label="TRON 地址" min-width="180" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, 'tron_address', 'tronAddress') || "—" }}</template>
        </el-table-column>
        <el-table-column label="平台 USDT" width="110">
          <template #default="{ row }">{{ row.platform_usdt ?? row.platformUsdt ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="平台 TRX" width="110">
          <template #default="{ row }">{{ row.platform_trx ?? row.platformTrx ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="链上 USDT" width="110">
          <template #default="{ row }">{{ row.chain_usdt ?? row.chainUsdt ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="链上 TRX" width="110">
          <template #default="{ row }">{{ row.chain_trx ?? row.chainTrx ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="余额时间" min-width="150">
          <template #default="{ row }">{{ formatTime(row.chain_balance_at || row.chainBalanceAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="160" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canWrite"
              type="primary"
              link
              :loading="actionLoading === str(row, 'user_uid', 'userUid')"
              @click="doCollect(str(row, 'user_uid', 'userUid'))"
            >归集</el-button>
            <el-button
              link
              @click="router.push({ path: '/wallet/sweep-logs', query: { user_uid: str(row, 'user_uid', 'userUid') } })"
            >记录</el-button>
          </template>
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
