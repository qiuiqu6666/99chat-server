<script setup lang="ts">
import { onMounted, reactive, ref, watch } from "vue";
import { useRoute } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import {
  listFriends,
  listRelationGroups,
  listSameDevice,
  listSameIp
} from "@/api/relations";
import { pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";

type Tab = "friends" | "groups" | "same_ip" | "same_device";

const route = useRoute();
const tab = ref<Tab>("friends");
const loading = ref(false);
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const hint = ref("");
const query = reactive({
  page: 1,
  page_size: 20,
  user_uid: String(route.query.user_uid || ""),
  keyword: "",
  ip: "",
  device_id: ""
});

async function load() {
  if (!query.user_uid.trim() && tab.value !== "same_device") {
    rows.value = [];
    total.value = 0;
    hint.value = "请先填写用户 UID";
    return;
  }
  if (tab.value === "same_device" && !query.user_uid.trim() && !query.device_id.trim()) {
    rows.value = [];
    total.value = 0;
    hint.value = "请填写用户 UID 或设备 ID";
    return;
  }
  loading.value = true;
  hint.value = "";
  try {
    let raw: unknown;
    if (tab.value === "friends") {
      raw = await listFriends({
        user_uid: query.user_uid,
        keyword: query.keyword || undefined,
        page: query.page,
        page_size: query.page_size
      });
    } else if (tab.value === "groups") {
      raw = await listRelationGroups({
        user_uid: query.user_uid,
        keyword: query.keyword || undefined,
        page: query.page,
        page_size: query.page_size
      });
    } else if (tab.value === "same_ip") {
      raw = await listSameIp({
        user_uid: query.user_uid,
        ip: query.ip || undefined,
        page: query.page,
        page_size: query.page_size
      });
    } else {
      raw = await listSameDevice({
        user_uid: query.user_uid || undefined,
        device_id: query.device_id || undefined,
        page: query.page,
        page_size: query.page_size
      });
    }
    const picked = pickList(raw);
    rows.value = picked.items;
    total.value = picked.total;
    const obj = (raw || {}) as Record<string, unknown>;
    hint.value = String(obj.hint || "");
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

watch(tab, () => {
  query.page = 1;
  load();
});
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
    <PageHeader title="好友关系" subtitle="按用户查好友 / 群 / 同 IP / 同设备" />
    <div class="page-card">
      <div class="toolbar">
        <el-radio-group v-model="tab">
          <el-radio-button value="friends">好友</el-radio-button>
          <el-radio-button value="groups">所在群</el-radio-button>
          <el-radio-button value="same_ip">同 IP</el-radio-button>
          <el-radio-button value="same_device">同设备</el-radio-button>
        </el-radio-group>
        <el-input
          v-model="query.user_uid"
          clearable
          placeholder="用户 UID"
          style="width: 180px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-input
          v-if="tab === 'friends' || tab === 'groups'"
          v-model="query.keyword"
          clearable
          placeholder="关键词"
          style="width: 160px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-input
          v-if="tab === 'same_ip'"
          v-model="query.ip"
          clearable
          placeholder="IP（可选）"
          style="width: 160px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-input
          v-if="tab === 'same_device'"
          v-model="query.device_id"
          clearable
          placeholder="设备 ID（可选）"
          style="width: 180px"
          @keyup.enter="query.page = 1; load()"
        />
        <el-button type="primary" @click="query.page = 1; load()">查询</el-button>
      </div>
      <el-alert v-if="hint" :title="hint" type="info" show-icon :closable="false" style="margin-bottom: 12px" />

      <el-table v-if="tab === 'friends'" :data="rows" v-loading="loading" stripe border>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }"><UserUidLink :uid="str(row, 'user_uid', 'userUid')" /></template>
        </el-table-column>
        <el-table-column label="好友" min-width="130">
          <template #default="{ row }"><UserUidLink :uid="str(row, 'friend_uid', 'friendUid')" /></template>
        </el-table-column>
        <el-table-column label="好友昵称" min-width="120">
          <template #default="{ row }">{{ str(row, "friend_nickname", "friendNickname") || "—" }}</template>
        </el-table-column>
        <el-table-column label="备注昵称" min-width="120">
          <template #default="{ row }">{{ row.nickname || "—" }}</template>
        </el-table-column>
        <el-table-column label="添加时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.add_time ?? row.addTime) }}</template>
        </el-table-column>
      </el-table>

      <el-table v-else-if="tab === 'groups'" :data="rows" v-loading="loading" stripe border>
        <el-table-column label="群 ID" min-width="140">
          <template #default="{ row }">{{ str(row, "group_id", "groupId") || "—" }}</template>
        </el-table-column>
        <el-table-column label="群名" min-width="140">
          <template #default="{ row }">{{ str(row, "group_name", "groupName") || "—" }}</template>
        </el-table-column>
        <el-table-column label="类型" width="100">
          <template #default="{ row }">{{ str(row, "group_type", "groupType") || "—" }}</template>
        </el-table-column>
        <el-table-column label="角色" width="100">
          <template #default="{ row }">{{ row.role || "—" }}</template>
        </el-table-column>
        <el-table-column label="成员数" width="90">
          <template #default="{ row }">{{ row.member_count ?? row.memberCount ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="加入时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.join_time ?? row.joinTime) }}</template>
        </el-table-column>
      </el-table>

      <el-table v-else :data="rows" v-loading="loading" stripe border>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }"><UserUidLink :uid="str(row, 'user_uid', 'userUid')" /></template>
        </el-table-column>
        <el-table-column label="昵称" min-width="120">
          <template #default="{ row }">{{ row.nickname || "—" }}</template>
        </el-table-column>
        <el-table-column label="手机" min-width="120">
          <template #default="{ row }">{{ str(row, "phone_num", "phoneNum") || "—" }}</template>
        </el-table-column>
        <el-table-column label="状态" width="90">
          <template #default="{ row }">{{ row.user_status ?? row.userStatus ?? "—" }}</template>
        </el-table-column>
        <el-table-column label="共享 IP" min-width="140">
          <template #default="{ row }">{{ str(row, "shared_ip", "sharedIp") || "—" }}</template>
        </el-table-column>
        <el-table-column label="共享设备" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "shared_device_id", "sharedDeviceId") || "—" }}</template>
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
