<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { listTransfers } from "@/api/finance";
import { amountText, nickText, pickList, str } from "@/utils/financeRows";
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
  status: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listTransfers({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      user_uid: query.user_uid || undefined,
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

watch(() => route.query.user_uid, v => {
  query.user_uid = String(v || "");
  query.page = 1;
  load();
});
onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="转账记录" />
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.user_uid" clearable placeholder="用户 UID" style="width:180px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.keyword" clearable placeholder="关键词" style="width:200px" @keyup.enter="query.page=1; load()" />
        
        <el-button type="primary" @click="query.page=1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>

        <el-table-column label="流水号" min-width="150"><template #default="{ row }">{{ str(row, 'transaction_no', 'transactionNo', 'order_no') || '—' }}</template></el-table-column>
        <el-table-column label="付款方" min-width="130"><template #default="{ row }"><UserUidLink :uid="str(row, 'from_uid', 'fromUid')" /></template></el-table-column>
        <el-table-column label="付款昵称" min-width="110"><template #default="{ row }">{{ nickText(row, 'from_nickname', 'fromNickname') }}</template></el-table-column>
        <el-table-column label="收款方" min-width="130"><template #default="{ row }"><UserUidLink :uid="str(row, 'to_uid', 'toUid')" /></template></el-table-column>
        <el-table-column label="收款昵称" min-width="110"><template #default="{ row }">{{ nickText(row, 'to_nickname', 'toNickname') }}</template></el-table-column>
        <el-table-column label="金额" width="120"><template #default="{ row }">{{ amountText(row.amount) }}</template></el-table-column>
        <el-table-column label="币种" width="80"><template #default="{ row }">{{ row.currency || '—' }}</template></el-table-column>
        <el-table-column label="状态" width="100"><template #default="{ row }">{{ row.status || '—' }}</template></el-table-column>
        <el-table-column label="时间" min-width="160"><template #default="{ row }">{{ formatTime(row.create_time || row.createTime) }}</template></el-table-column>

      </el-table>
      <div style="margin-top:12px;display:flex;justify-content:flex-end">
        <el-pagination v-model:current-page="query.page" v-model:page-size="query.page_size" :total="total" layout="total, prev, pager, next" @current-change="load" />
      </div>
    </div>
  </div>
</template>
