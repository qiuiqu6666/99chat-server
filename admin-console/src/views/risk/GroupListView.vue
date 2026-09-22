<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { listGroups } from "@/api/groups";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const router = useRouter();
const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  keyword: "",
  g_status: "",
  sort: "create_time_desc"
});

async function load() {
  loading.value = true;
  try {
    const raw = await listGroups({
      page: query.page,
      page_size: query.page_size,
      keyword: query.keyword || undefined,
      g_status: query.g_status || undefined,
      sort: query.sort
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

function gId(row: Record<string, unknown>) {
  return str(row, "g_id", "gId", "group_id", "groupId");
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="群组列表" />
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.keyword" clearable placeholder="群名 / 群 ID" style="width: 200px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.g_status" clearable placeholder="状态 g_status" style="width: 140px" />
        <el-button type="primary" @click="query.page=1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="群 ID" min-width="150">
          <template #default="{ row }">{{ gId(row) || "—" }}</template>
        </el-table-column>
        <el-table-column label="群名" min-width="150">
          <template #default="{ row }">{{ str(row, "g_name", "gName", "name") || "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="80">
          <template #default="{ row }">{{ row.g_status ?? row.gStatus ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="群主" min-width="130">
          <template #default="{ row }">
            <UserUidLink :uid="str(row, 'g_owner_user_uid', 'gOwnerUserUid')" />
          </template>
        </el-table-column>
        <el-table-column label="创建者" min-width="120">
          <template #default="{ row }">{{ str(row, "create_user_nickname", "createUserNickname") || str(row, "create_user_uid", "createUserUid") || "—" }}</template>
        </el-table-column>
        <el-table-column label="成员" width="100">
          <template #default="{ row }">
            {{ (row.g_member_count ?? row.gMemberCount ?? "—") + "/" + (row.max_member_count ?? row.maxMemberCount ?? "—") }}
          </template>
        </el-table-column>
        <el-table-column label="禁言" width="100">
          <template #default="{ row }">{{ str(row, "g_mute_mode", "gMuteMode") || "—" }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.create_time ?? row.createTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push(`/risk/groups/${encodeURIComponent(gId(row))}`)"
            >详情</el-button>
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
