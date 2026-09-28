<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import PageHeader from "@/components/business/page-header.vue";
import { getGroupDetail, listGroupMembers, setGroupGameEnabled, setGroupGameid } from "@/service/api/groups";
import { unwrap } from "@/service/http";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { buildAdminColumns } from "@/utils/adminTable";
defineOptions({ name: "group-detail" });
const route = useRoute();
const router = useRouter();
const gId = computed(() => String(route.params.gId || ""));
const canWrite = computed(() => hasPerm("group.write"));
const detail = ref<Record<string, unknown>>({});
const members = ref<Record<string, unknown>[]>([]);
const gameid = ref("");
const actionLoading = ref(false);
async function load() {
  detail.value = unwrap(await getGroupDetail({ g_id: gId.value, gId: gId.value }));
  const group = (detail.value.group && typeof detail.value.group === 'object' ? detail.value.group : {}) as Record<string, unknown>;
  gameid.value = String(group.gameid ?? detail.value.gameid ?? '');
  const raw = unwrap(await listGroupMembers({ g_id: gId.value, gId: gId.value, page: 1, page_size: 50 }));
  const items = (raw.items || raw.list || raw.members || []) as Record<string, unknown>[];
  members.value = Array.isArray(items) ? items : [];
}
const groupRow = computed(() => {
  const group = detail.value.group;
  return group && typeof group === 'object' ? (group as Record<string, unknown>) : {};
});
const infoColumns = computed(() => buildAdminColumns(Object.keys(groupRow.value).length ? [groupRow.value] : []));
const memberColumns = computed(() => buildAdminColumns(members.value));
onMounted(load);
async function toggleGame(enabled: boolean) {
  if (!canWrite.value) return;
  actionLoading.value = true;
  try { await setGroupGameEnabled({ g_id: gId.value, enabled }); window.$message?.success("已更新"); await load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
  finally { actionLoading.value = false; }
}
async function saveGameid() {
  if (!canWrite.value) return;
  actionLoading.value = true;
  try { await setGroupGameid({ g_id: gId.value, gameid: gameid.value }); window.$message?.success("已保存"); }
  catch (e) { window.$message?.error(errMessage(e)); }
  finally { actionLoading.value = false; }
}
async function clearGameid() { gameid.value = ""; await saveGameid(); }
</script>
<template>
  <div class="p-16px">
    <PageHeader :title="`群详情 ${gId}`"><NButton @click="router.push('/risk/groups')">返回</NButton></PageHeader>
    <NCard v-if="canWrite" class="mb-12px" title="游戏">
      <NSpace>
        <NButton size="small" :loading="actionLoading" @click="toggleGame(true)">开启</NButton>
        <NButton size="small" :loading="actionLoading" @click="toggleGame(false)">关闭</NButton>
        <NInput v-model:value="gameid" placeholder="游戏号" style="width: 220px" />
        <NButton size="small" :loading="actionLoading" @click="saveGameid">保存</NButton>
        <NButton size="small" :loading="actionLoading" @click="clearGameid">清空</NButton>
      </NSpace>
    </NCard>
    <NDataTable class="mb-12px" bordered striped :single-line="false" size="small" :scroll-x="1100" :columns="infoColumns" :data="Object.keys(groupRow).length ? [groupRow] : []" />
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1100" :data="members" :columns="memberColumns" />
  </div>
</template>
