<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { listContactBook } from "@/api/privacy";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

const route = useRoute();
const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  user_uid: String(route.query.user_uid || ""),
  keyword: ""
});

async function load() {
  loading.value = true;
  try {
    const raw = await listContactBook({
      page: query.page,
      page_size: query.page_size,
      user_uid: query.user_uid || undefined,
      keyword: query.keyword || undefined
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
    <PageHeader title="通讯录" />
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
          placeholder="姓名/手机"
          style="width: 180px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }"><UserUidLink :uid="str(row, 'user_uid', 'userUid')" /></template>
        </el-table-column>
        <el-table-column label="用户昵称" min-width="110">
          <template #default="{ row }">{{ str(row, "user_nickname", "userNickname") || "—" }}</template>
        </el-table-column>
        <el-table-column label="联系人" min-width="120">
          <template #default="{ row }">{{ str(row, "contact_name", "contactName") || "—" }}</template>
        </el-table-column>
        <el-table-column label="手机(掩码)" min-width="130">
          <template #default="{ row }">{{ str(row, "contact_phone_masked", "contactPhoneMasked") || str(row, "contact_phone", "contactPhone") || "—" }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "contact_remark", "contactRemark") || "—" }}</template>
        </el-table-column>
        <el-table-column label="来源" width="100">
          <template #default="{ row }">{{ row.source || "—" }}</template>
        </el-table-column>
        <el-table-column label="平台用户" width="90">
          <template #default="{ row }">{{ (row.is_platform_user ?? row.isPlatformUser) ? "是" : "否" }}</template>
        </el-table-column>
        <el-table-column label="关联 UID" min-width="130">
          <template #default="{ row }"><UserUidLink :uid="str(row, 'related_uid', 'relatedUid')" /></template>
        </el-table-column>
        <el-table-column label="更新时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.last_updated_at ?? row.lastUpdatedAt) }}</template>
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
