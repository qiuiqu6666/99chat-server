<script setup lang="ts">
import { ref } from "vue";
import { useRoute } from "vue-router";
import PageHeader from "@/components/business/page-header.vue";
import { usePagedList } from "@/hooks/business/use-paged-list";
import { deleteAlbumFile, listAlbums } from "@/service/api/privacy";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
import { computed } from 'vue';
import { buildAdminColumns } from '@/utils/adminTable';
defineOptions({ name: "privacy_album" });
const columns = computed(() => buildAdminColumns(rows.value));
const route = useRoute();
const canWrite = hasPerm("user.write");
const video = ref<Record<string, unknown> | null>(null);
const { loading, rows, total, query, load } = usePagedList(listAlbums, () => ({ user_uid: route.query.user_uid || undefined }));
async function remove(row: Record<string, unknown>) {
  try { await deleteAlbumFile(row.id as string|number); window.$message?.success("已删除"); load(); }
  catch (e) { window.$message?.error(errMessage(e)); }
}
function isVideo(row: Record<string, unknown>) { return String(row.type || row.media_type || "").includes("video") || String(row.url || "").match(/mp4|webm/); }
</script>
<template>
  <div class="p-16px">
    <PageHeader title="相册" subtitle="图片与视频混合列表；默认可看全部类型" />
    <NSpace class="mb-12px"><NButton type="primary" @click="query.page=1; load()">查询</NButton></NSpace>
    <NDataTable bordered striped :single-line="false" size="small" :scroll-x="1200" :loading="loading" :data="rows" :pagination="{ page: query.page, pageSize: query.page_size, itemCount: total, onUpdatePage: (p: number) => { query.page = p; load(); } }" :columns="columns" />
    <div v-for="row in rows" :key="String(row.id)" class="mt-8px flex gap-8px">
      <NButton v-if="isVideo(row)" text type="primary" @click="video = row">播放</NButton>
      <NButton v-if="canWrite" text type="error" @click="remove(row)">删除</NButton>
    </div>
    <NModal :show="!!video" preset="card" title="视频" style="width: 640px" @update:show="v => { if (!v) video = null }">
      <video v-if="video" :src="String(video.oss_url || video.url || video.file_url || '')" controls class="w-full" />
    </NModal>
  </div>
</template>
