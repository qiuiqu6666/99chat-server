<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { privacyStatsSummary, privacyStatsUsers } from "@/api/privacy";
import { pickList, str } from "@/utils/financeRows";
import { errMessage } from "@/utils/format";

const router = useRouter();
const loading = ref(false);
const summary = ref<Record<string, unknown>>({});
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({ page: 1, page_size: 20 });

async function load() {
  loading.value = true;
  try {
    summary.value = (await privacyStatsSummary()) as Record<string, unknown>;
    const picked = pickList(
      await privacyStatsUsers({ page: query.page, page_size: query.page_size })
    );
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
    <PageHeader title="隐私统计" />
    <div class="page-card" style="margin-bottom: 12px" v-loading="loading">
      <el-descriptions :column="3" border size="small">
        <el-descriptions-item label="用户总数">{{ summary.total_users ?? summary.totalUsers ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="有通讯录用户">{{ summary.users_with_contacts ?? summary.usersWithContacts ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="通讯录条目">{{ summary.total_contact_entries ?? summary.totalContactEntries ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="有相册用户">{{ summary.users_with_album ?? summary.usersWithAlbum ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="相册条目">{{ summary.total_album_items ?? summary.totalAlbumItems ?? "—" }}</el-descriptions-item>
        <el-descriptions-item label="照片/视频">
          {{ (summary.total_photos ?? summary.totalPhotos ?? 0) + " / " + (summary.total_videos ?? summary.totalVideos ?? 0) }}
        </el-descriptions-item>
      </el-descriptions>
    </div>
    <div class="page-card">
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="用户" min-width="140">
          <template #default="{ row }"><UserUidLink :uid="str(row, 'user_uid', 'userUid')" /></template>
        </el-table-column>
        <el-table-column label="昵称" min-width="120">
          <template #default="{ row }">{{ row.nickname || "—" }}</template>
        </el-table-column>
        <el-table-column label="通讯录数" width="110">
          <template #default="{ row }">{{ row.contact_count ?? row.contactCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="相册数" width="100">
          <template #default="{ row }">{{ row.album_count ?? row.albumCount ?? 0 }}</template>
        </el-table-column>
        <el-table-column label="快捷" width="160">
          <template #default="{ row }">
            <el-button
              link
              type="primary"
              @click="router.push({ path: '/privacy/contacts', query: { user_uid: str(row, 'user_uid', 'userUid') } })"
            >通讯录</el-button>
            <el-button
              link
              type="primary"
              @click="router.push({ path: '/privacy/album', query: { user_uid: str(row, 'user_uid', 'userUid') } })"
            >相册</el-button>
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
