<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from "vue";
import { deviceDetection } from "@pureadmin/utils";
import {
  getContactBookList,
  searchContactBook,
  type ContactBookItem
} from "@/api/im-contact-book";
import { adminApiErrMessage } from "@/api/im-user";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";
import { message } from "@/utils/message";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import { useAdminRealtimeInvalidate } from "@/realtime/useAdminRealtimeInvalidate";

defineOptions({ name: "PrivacyContactBookPanel" });

const props = withDefaults(
  defineProps<{
    userUid?: string;
    embedded?: boolean;
  }>(),
  {
    userUid: "",
    embedded: false
  }
);

const loading = ref(false);
const rows = ref<ContactBookItem[]>([]);
const page = reactive({ current: 1, size: 20, total: 0 });
const form = reactive({
  keyword: "",
  contact_name: "",
  contact_phone: "",
  hit_platform_user: ""
});

const fixedUserUid = computed(() => String(props.userUid ?? "").trim());
const tableHeight = computed(() => (deviceDetection() ? 420 : props.embedded ? 520 : 620));

function safeText(v: unknown) {
  if (v == null || v === "") return "—";
  return String(v);
}

function fmtTime(v: unknown) {
  return formatAdminUnixTime(v, true);
}

async function fetchList() {
  loading.value = true;
  try {
    const params = {
      page: page.current,
      page_size: page.size,
      user_uid: fixedUserUid.value || undefined,
      keyword: form.keyword.trim() || undefined,
      contact_name: form.contact_name.trim() || undefined,
      contact_phone: form.contact_phone.trim() || undefined,
      hit_platform_user: form.hit_platform_user || undefined,
      sort: "last_updated_at_desc"
    };
    const res =
      form.keyword.trim() || form.contact_phone.trim()
        ? await searchContactBook(params)
        : await getContactBookList(params);
    rows.value = res.items;
    page.total = res.total;
    page.size = res.page_size || page.size;
  } catch (err: unknown) {
    rows.value = [];
    page.total = 0;
    message(adminApiErrMessage(err, "通讯录列表加载失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}

function resetForm() {
  form.keyword = "";
  form.contact_name = "";
  form.contact_phone = "";
  form.hit_platform_user = "";
  page.current = 1;
  void fetchList();
}

useAdminRealtimeInvalidate(
  [ADMIN_REALTIME_EVENTS.CONTACT_SYNCED, ADMIN_REALTIME_EVENTS.USER_UPDATED],
  () => fetchList(),
  {
    debounceMs: 400,
    enabled: () =>
      !!fixedUserUid.value || rows.value.length > 0 || !!form.keyword.trim()
  }
);

watch(
  () => props.userUid,
  () => {
    page.current = 1;
    void fetchList();
  }
);

onMounted(fetchList);
</script>

<template>
  <div :class="embedded ? '' : 'p-4'">
    <el-alert
      v-if="!embedded"
      class="mb-4"
      :closable="false"
      type="info"
      title="通讯录信息：查询用户上传的通讯录同步记录，支持按姓名、手机号筛选。"
    />

    <el-card shadow="never" class="mb-4">
      <el-form :inline="true" :model="form" label-width="88px">
        <el-form-item label="联系人名">
          <el-input v-model="form.contact_name" class="w-40!" clearable placeholder="姓名" />
        </el-form-item>
        <el-form-item label="手机号">
          <el-input v-model="form.contact_phone" class="w-44!" clearable placeholder="联系人手机号" />
        </el-form-item>
        <el-form-item label="命中用户">
          <el-select v-model="form.hit_platform_user" class="w-34!" clearable placeholder="全部">
            <el-option label="已注册" value="1" />
            <el-option label="未注册" value="0" />
          </el-select>
        </el-form-item>
        <el-form-item label="关键字">
          <el-input v-model="form.keyword" class="w-48!" clearable placeholder="姓名 / 手机号 / 备注" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="page.current = 1; fetchList()">查询</el-button>
          <el-button @click="resetForm">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never">
      <template #header>
        <div class="flex items-center justify-between">
          <span>{{ embedded ? "通讯录联系人" : "通讯录列表" }}</span>
          <el-button :loading="loading" @click="fetchList">刷新</el-button>
        </div>
      </template>

      <el-table v-loading="loading" :data="rows" stripe border :height="tableHeight" size="small">
        <template #empty>
          <el-empty description="暂无通讯录同步数据">
            <template #description>
              <p class="text-sm text-gray-500">该用户尚未上传通讯录，或客户端未完成云端同步。</p>
            </template>
          </el-empty>
        </template>
        <el-table-column
          v-if="!fixedUserUid"
          label="用户 UID"
          min-width="112"
          fixed="left"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ safeText(row.user_uid) }}</template>
        </el-table-column>
        <el-table-column
          v-if="!fixedUserUid"
          label="用户昵称"
          min-width="120"
          show-overflow-tooltip
        >
          <template #default="{ row }">{{ safeText(row.user_nickname) }}</template>
        </el-table-column>
        <el-table-column label="联系人姓名" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.contact_name) }}</template>
        </el-table-column>
        <el-table-column label="联系人手机号" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.contact_phone_masked ?? row.contact_phone) }}</template>
        </el-table-column>
        <el-table-column label="备注" min-width="150" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.contact_remark) }}</template>
        </el-table-column>
        <el-table-column label="来源" width="110" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.source) }}</template>
        </el-table-column>
        <el-table-column label="平台注册" width="100">
          <template #default="{ row }">
            <el-tag :type="row.is_platform_user ? 'success' : 'info'" size="small">
              {{ row.is_platform_user ? "是" : "否" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="关联 UID" min-width="116" show-overflow-tooltip>
          <template #default="{ row }">{{ safeText(row.related_uid) }}</template>
        </el-table-column>
        <el-table-column label="首次上传" min-width="170">
          <template #default="{ row }">{{ fmtTime(row.first_uploaded_at) }}</template>
        </el-table-column>
        <el-table-column label="最后更新" min-width="170">
          <template #default="{ row }">{{ fmtTime(row.last_updated_at) }}</template>
        </el-table-column>
      </el-table>

      <div class="mt-4 flex justify-end">
        <el-pagination
          v-model:current-page="page.current"
          v-model:page-size="page.size"
          :page-sizes="[20, 50, 100, 200]"
          layout="total, sizes, prev, pager, next, jumper"
          :total="page.total"
          @size-change="() => { page.current = 1; fetchList(); }"
          @current-change="fetchList"
        />
      </div>
    </el-card>
  </div>
</template>
