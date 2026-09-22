<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import {
  sendAnnouncement,
  uploadAnnouncementImage,
  uploadAnnouncementVideo
} from "@/api/announcements";
import { errMessage } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const router = useRouter();
const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const uploading = ref(false);

const form = reactive({
  content_type: "text" as "text" | "image" | "video",
  content: "",
  scope: "all" as "all" | "uids",
  user_uids_text: "",
  scheduled_at: null as Date | null,
  image_url: "",
  preview_url: "",
  thumb_url: "",
  width: undefined as number | undefined,
  height: undefined as number | undefined,
  image_size: undefined as number | undefined,
  preview_size: undefined as number | undefined,
  thumb_size: undefined as number | undefined,
  thumb_width: undefined as number | undefined,
  thumb_height: undefined as number | undefined,
  video_url: "",
  video_size: undefined as number | undefined,
  video_second: undefined as number | undefined
});

function parseUids(text: string) {
  return text
    .split(/[\s,;，；\n]+/)
    .map(s => s.trim())
    .filter(Boolean);
}

async function onUploadImage(file: File) {
  uploading.value = true;
  try {
    const res = await uploadAnnouncementImage(file);
    form.image_url = String(res.image_url ?? res.imageUrl ?? "");
    form.preview_url = String(res.preview_url ?? res.previewUrl ?? "");
    form.thumb_url = String(res.thumb_url ?? res.thumbUrl ?? "");
    form.width = Number(res.width ?? 0) || undefined;
    form.height = Number(res.height ?? 0) || undefined;
    form.image_size = Number(res.image_size ?? res.imageSize ?? 0) || undefined;
    form.preview_size = Number(res.preview_size ?? res.previewSize ?? 0) || undefined;
    form.thumb_size = Number(res.thumb_size ?? res.thumbSize ?? 0) || undefined;
    form.thumb_width = Number(res.thumb_width ?? res.thumbWidth ?? 0) || undefined;
    form.thumb_height = Number(res.thumb_height ?? res.thumbHeight ?? 0) || undefined;
    ElMessage.success("图片已上传");
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    uploading.value = false;
  }
  return false;
}

async function onUploadVideo(file: File) {
  uploading.value = true;
  try {
    const res = await uploadAnnouncementVideo(file);
    form.video_url = String(res.video_url ?? res.videoUrl ?? "");
    form.thumb_url = String(res.thumb_url ?? res.thumbUrl ?? "");
    form.video_size = Number(res.video_size ?? res.videoSize ?? 0) || undefined;
    ElMessage.success("视频已上传");
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    uploading.value = false;
  }
  return false;
}

async function submit() {
  if (!canWrite.value) return;
  const payload: Record<string, unknown> = {
    content_type: form.content_type,
    scope: form.scope,
    scheduled_at: form.scheduled_at ? form.scheduled_at.toISOString() : undefined
  };
  if (form.content_type === "text") {
    payload.content = form.content;
  } else if (form.content_type === "image") {
    Object.assign(payload, {
      image_url: form.image_url,
      preview_url: form.preview_url || undefined,
      thumb_url: form.thumb_url || undefined,
      width: form.width,
      height: form.height,
      image_size: form.image_size,
      preview_size: form.preview_size,
      thumb_size: form.thumb_size,
      thumb_width: form.thumb_width,
      thumb_height: form.thumb_height
    });
  } else {
    Object.assign(payload, {
      video_url: form.video_url,
      thumb_url: form.thumb_url || undefined,
      video_size: form.video_size,
      video_second: form.video_second
    });
  }
  if (form.scope === "uids") {
    payload.user_uids = parseUids(form.user_uids_text);
  }
  loading.value = true;
  try {
    const res = (await sendAnnouncement(payload)) as Record<string, unknown>;
    ElMessage.success(
      res.scheduled ? `已定时：${res.scheduled_at || res.scheduledAt || ""}` : `已发送 ${res.sent_count ?? res.sentCount ?? ""} 条`
    );
    router.push("/ops/announcements");
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="page">
    <PageHeader title="发送公告" />
    <div class="page-card" style="max-width: 760px">
      <el-form label-width="100px">
        <el-form-item label="类型">
          <el-radio-group v-model="form.content_type">
            <el-radio-button value="text">文本</el-radio-button>
            <el-radio-button value="image">图片</el-radio-button>
            <el-radio-button value="video">视频</el-radio-button>
          </el-radio-group>
        </el-form-item>

        <el-form-item v-if="form.content_type === 'text'" label="内容">
          <el-input v-model="form.content" type="textarea" :rows="6" maxlength="4000" show-word-limit />
        </el-form-item>

        <template v-else-if="form.content_type === 'image'">
          <el-form-item label="上传图片">
            <el-upload :show-file-list="false" accept="image/*" :before-upload="onUploadImage" :disabled="!canWrite">
              <el-button :loading="uploading" :disabled="!canWrite">选择图片</el-button>
            </el-upload>
          </el-form-item>
          <el-form-item label="图片 URL">
            <el-input v-model="form.image_url" placeholder="上传后自动填充，也可手填" />
          </el-form-item>
          <el-form-item v-if="form.preview_url || form.thumb_url" label="预览">
            <img v-if="form.thumb_url || form.preview_url" :src="(form.thumb_url || form.preview_url) as string" style="max-height:120px;border-radius:6px" />
          </el-form-item>
        </template>

        <template v-else>
          <el-form-item label="上传视频">
            <el-upload :show-file-list="false" accept="video/*" :before-upload="onUploadVideo" :disabled="!canWrite">
              <el-button :loading="uploading" :disabled="!canWrite">选择视频</el-button>
            </el-upload>
          </el-form-item>
          <el-form-item label="视频 URL">
            <el-input v-model="form.video_url" placeholder="上传后自动填充，也可手填" />
          </el-form-item>
          <el-form-item label="封面 URL">
            <el-input v-model="form.thumb_url" />
          </el-form-item>
          <el-form-item label="时长(秒)">
            <el-input-number v-model="form.video_second" :min="0" :controls="false" />
          </el-form-item>
        </template>

        <el-form-item label="范围">
          <el-radio-group v-model="form.scope">
            <el-radio-button value="all">全员</el-radio-button>
            <el-radio-button value="uids">指定用户</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="form.scope === 'uids'" label="用户 UID">
          <el-input
            v-model="form.user_uids_text"
            type="textarea"
            :rows="3"
            placeholder="多个 UID 用逗号或换行分隔，最多 500"
          />
        </el-form-item>
        <el-form-item label="定时发送">
          <el-date-picker
            v-model="form.scheduled_at"
            type="datetime"
            placeholder="留空则立即发送"
            style="width: 100%"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" :disabled="!canWrite" @click="submit">发送</el-button>
          <el-button @click="router.push('/ops/announcements')">查看记录</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>
