<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { listGroupLogs } from "@/api/groups";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const router = useRouter();
const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 30,
  kinds: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listGroupLogs({
      page: query.page,
      page_size: query.page_size,
      kinds: query.kinds || undefined
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

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="群动态" />
    <div class="page-card">
      <div class="toolbar">
        <el-input
          v-model="query.kinds"
          clearable
          placeholder="kinds（可选，逗号分隔）"
          style="width: 240px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.occurred_at_ms ?? row.occurredAtMs) }}</template>
        </el-table-column>
        <el-table-column label="类型" width="120">
          <template #default="{ row }">{{ row.kind || "—" }}</template>
        </el-table-column>
        <el-table-column label="群 ID" min-width="140">
          <template #default="{ row }">
            <el-button
              v-if="str(row, 'g_id', 'gId')"
              link
              type="primary"
              @click="router.push(`/risk/groups/${encodeURIComponent(str(row, 'g_id', 'gId'))}`)"
            >{{ str(row, "g_id", "gId") }}</el-button>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="群名" min-width="140">
          <template #default="{ row }">{{ str(row, "g_name", "gName") || "—" }}</template>
        </el-table-column>
        <el-table-column label="摘要" min-width="240" show-overflow-tooltip>
          <template #default="{ row }">{{ row.summary || "—" }}</template>
        </el-table-column>
        <el-table-column label="来源表" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "source_table", "sourceTable") || "—" }}</template>
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
