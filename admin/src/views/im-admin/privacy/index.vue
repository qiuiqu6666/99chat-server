<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { deviceDetection } from "@pureadmin/utils";
import {
  getPrivacyStatsSummary,
  getPrivacyUserStatsList,
  type PrivacySummary,
  type PrivacyUserStatsItem
} from "@/api/im-privacy-stats";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";

defineOptions({ name: "ImPrivacyStatsIndex" });

const router = useRouter();
const loading = ref(false);
const summary = ref<PrivacySummary | null>(null);
const rows = ref<PrivacyUserStatsItem[]>([]);
const form = reactive({ keyword: "", sort: "contact_count_desc" });
const page = reactive({ current: 1, size: 20, total: 0 });

const statCards = computed(() => {
  const s = summary.value;
  if (!s) return [];
  return [
    { label: "用户总数", value: s.total_users },
    { label: "有通讯录的用户", value: s.users_with_contacts },
    { label: "通讯录条目", value: s.total_contact_entries },
    { label: "有相册的用户", value: s.users_with_album },
    { label: "相册文件总数", value: s.total_album_items },
    { label: "图片 / 视频", value: `${s.total_photos} / ${s.total_videos}` }
  ];
});

async function loadSummary() {
  try {
    summary.value = await getPrivacyStatsSummary();
  } catch (err: unknown) {
    summary.value = null;
    message(adminApiErrMessage(err, "隐私统计加载失败"), { type: "warning" });
  }
}

async function fetchList() {
  loading.value = true;
  try {
    const res = await getPrivacyUserStatsList({
      page: page.current,
      page_size: page.size,
      keyword: form.keyword.trim() || undefined,
      sort: form.sort || undefined
    });
    rows.value = res.items ?? [];
    page.total = res.total ?? 0;
    page.size = res.page_size || page.size;
  } catch (err: unknown) {
    rows.value = [];
    page.total = 0;
    message(adminApiErrMessage(err, "用户隐私列表加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

async function refreshAll() {
  loading.value = true;
  await loadSummary();
  await fetchList();
  loading.value = false;
}

function resetForm() {
  form.keyword = "";
  form.sort = "contact_count_desc";
  page.current = 1;
  void fetchList();
}

function openContacts(row: PrivacyUserStatsItem) {
  router.push({
    path: "/im-admin/privacy/contacts",
    query: { user_uid: row.user_uid, from: "stats" }
  });
}

function openAlbum(row: PrivacyUserStatsItem) {
  router.push({
    path: "/im-admin/privacy/album",
    query: { user_uid: row.user_uid, from: "stats" }
  });
}

function openUserDetail(uid: string) {
  router.push({ name: "ImAccountUserDetail", params: { id: uid } });
}

onMounted(refreshAll);
</script>

<template>
  <div class="p-4" :class="deviceDetection() ? '' : 'max-w-320 mx-auto'">
    <div class="mb-4 flex flex-wrap items-center justify-between gap-3">
      <div>
        <h2 class="m-0 text-lg font-medium">隐私数据</h2>
        <p class="mb-0 mt-1 text-sm text-gray-500 dark:text-gray-400">
          全站通讯录与相册汇总；点击用户可查看明细
        </p>
      </div>
      <el-button type="primary" plain :loading="loading" @click="refreshAll">刷新</el-button>
    </div>

    <el-row v-if="statCards.length" :gutter="16" class="mb-4">
      <el-col v-for="item in statCards" :key="item.label" :xs="24" :sm="12" :md="8" :lg="4">
        <el-card shadow="hover" class="mb-4">
          <div class="text-sm text-gray-500">{{ item.label }}</div>
          <div class="mt-2 text-2xl font-semibold">{{ item.value }}</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <el-form :inline="true" :model="form" class="mb-2">
        <el-form-item label="用户">
          <el-input v-model="form.keyword" clearable placeholder="IM 号 / 昵称 / 手机" class="w-52!" />
        </el-form-item>
        <el-form-item label="排序">
          <el-select v-model="form.sort" class="w-44!">
            <el-option label="通讯录数量 ↓" value="contact_count_desc" />
            <el-option label="相册数量 ↓" value="album_count_desc" />
            <el-option label="IM 号 ↑" value="user_id_asc" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="page.current = 1; fetchList()">查询</el-button>
          <el-button @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table v-loading="loading" :data="rows" stripe border size="small">
        <el-table-column label="IM 号" prop="user_uid" width="120">
          <template #default="{ row }">
            <el-button link type="primary" @click="openUserDetail(row.user_uid)">{{ row.user_uid }}</el-button>
          </template>
        </el-table-column>
        <el-table-column label="用户名" prop="nickname" min-width="140" show-overflow-tooltip />
        <el-table-column label="通讯录数量" prop="contact_count" width="120" align="right" />
        <el-table-column label="相册数量" prop="album_count" width="110" align="right" />
        <el-table-column label="操作" width="200" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" :disabled="row.contact_count <= 0" @click="openContacts(row)">
              查看通讯录
            </el-button>
            <el-button link type="primary" :disabled="row.album_count <= 0" @click="openAlbum(row)">
              查看相册
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :page-sizes="[20, 50, 100]"
          layout="total, sizes, prev, pager, next"
          :total="page.total"
          @size-change="() => { page.current = 1; fetchList(); }"
          @current-change="fetchList"
        />
      </div>
    </el-card>
  </div>
</template>
