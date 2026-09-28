<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import PageHeader from "@/components/business/page-header.vue";
import { sendAnnouncement, uploadAnnouncementImage, uploadAnnouncementVideo } from "@/service/api/announcements";
import { unwrap } from "@/service/http";
import { hasPerm } from "@/utils/perms";
import { errMessage } from "@/utils/format";
defineOptions({ name: "ops_announcements-send" });
const router = useRouter();
const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const uploading = ref(false);
const form = reactive({ title: "", content: "", image_url: "", video_url: "" });
async function upload(file: File, kind: "image" | "video") {
  if (!canWrite.value) return false;
  uploading.value = true;
  try {
    const data = unwrap(await (kind === "image" ? uploadAnnouncementImage(file) : uploadAnnouncementVideo(file)));
    const url = String(data.url || data.path || "");
    if (kind === "image") form.image_url = url; else form.video_url = url;
  } catch (e) { window.$message?.error(errMessage(e)); }
  finally { uploading.value = false; }
  return false;
}
async function submit() {
  if (!canWrite.value) return;
  loading.value = true;
  try { await sendAnnouncement({ ...form }); window.$message?.success("已发送"); }
  catch (e) { window.$message?.error(errMessage(e)); }
  finally { loading.value = false; }
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="发送公告" />
    <NForm>
      <NFormItem label="标题"><NInput v-model:value="form.title" :disabled="!canWrite" /></NFormItem>
      <NFormItem label="内容"><NInput v-model:value="form.content" type="textarea" :disabled="!canWrite" /></NFormItem>
      <NFormItem label="图片">
        <NUpload :show-file-list="false" accept="image/*" :disabled="!canWrite" @before-upload="({file}) => upload(file.file as File, 'image')"><NButton :loading="uploading" :disabled="!canWrite">选择图片</NButton></NUpload>
        <span class="ml-8px">{{ form.image_url }}</span>
      </NFormItem>
      <NFormItem label="视频">
        <NUpload :show-file-list="false" accept="video/*" :disabled="!canWrite" @before-upload="({file}) => upload(file.file as File, 'video')"><NButton :loading="uploading" :disabled="!canWrite">选择视频</NButton></NUpload>
        <span class="ml-8px">{{ form.video_url }}</span>
      </NFormItem>
      <NSpace>
        <NButton type="primary" :loading="loading" :disabled="!canWrite" @click="submit">发送</NButton>
        <NButton @click="router.push('/ops/announcements')">查看记录</NButton>
      </NSpace>
    </NForm>
  </div>
</template>
