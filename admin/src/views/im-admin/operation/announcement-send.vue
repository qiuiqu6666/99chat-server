<script setup lang="ts">
import dayjs from "dayjs";
import { computed, reactive, ref } from "vue";
import type { FormRules, UploadRequestOptions } from "element-plus";
import { deviceDetection } from "@pureadmin/utils";
import {
  sendImAnnouncement,
  uploadAnnouncementImage,
  uploadAnnouncementVideo,
  type AnnouncementContentType,
  type ImAnnouncementSendBody
} from "@/api/im-announcement";
import { adminApiErrMessage } from "@/api/im-user";
import { message } from "@/utils/message";

defineOptions({ name: "ImOperationAnnouncementSend" });

type ScopeMode = "all" | "uids";
type SendMode = "immediate" | "scheduled";

const formRef = ref();
const loading = ref(false);
const uploading = ref(false);

const form = reactive({
  contentType: "text" as AnnouncementContentType,
  content: "",
  imageUrl: "",
  previewUrl: "",
  imageWidth: 0,
  imageHeight: 0,
  imageSize: 0,
  previewSize: 0,
  thumbSize: 0,
  thumbWidth: 0,
  thumbHeight: 0,
  videoUrl: "",
  videoSize: 0,
  thumbUrl: "",
  scope: "all" as ScopeMode,
  sendMode: "immediate" as SendMode,
  scheduledAt: "",
  uidsText: ""
});

const uidParseResult = computed(() => {
  const lines = String(form.uidsText ?? "")
    .split(/\r?\n/g)
    .map(v => v.trim())
    .filter(Boolean);

  const invalid: string[] = [];
  const uids: string[] = [];

  for (const raw of lines) {
    if (!/^[a-zA-Z0-9_-]{1,64}$/.test(raw)) invalid.push(raw);
    else uids.push(raw);
  }

  const uniq = Array.from(new Set(uids));
  return { lines, uids: uniq, invalid };
});

const typeLabel = computed(() => {
  if (form.contentType === "image") return "图片";
  if (form.contentType === "video") return "视频";
  return "文本";
});

const formRules = computed<FormRules>(() => ({
  contentType: [{ required: true, message: "请选择消息类型", trigger: "change" }],
  content: [
    {
      validator: (_rule, value, callback) => {
        if (form.contentType !== "text") {
          callback();
          return;
        }
        const v = value == null ? "" : String(value).trim();
        if (!v) {
          callback(new Error("请输入消息内容"));
          return;
        }
        if (v.length > 4000) {
          callback(new Error("内容长度不能超过 4000 字符"));
          return;
        }
        callback();
      },
      trigger: "blur"
    }
  ],
  imageUrl: [
    {
      validator: (_rule, value, callback) => {
        if (form.contentType !== "image") {
          callback();
          return;
        }
        const v = value == null ? "" : String(value).trim();
        if (!v) {
          callback(new Error("请上传图片或填写图片 URL"));
          return;
        }
        if (!/^https?:\/\//i.test(v)) {
          callback(new Error("图片地址须为 http(s) URL"));
          return;
        }
        callback();
      },
      trigger: ["blur", "change"]
    }
  ],
  videoUrl: [
    {
      validator: (_rule, value, callback) => {
        if (form.contentType !== "video") {
          callback();
          return;
        }
        const v = value == null ? "" : String(value).trim();
        if (!v) {
          callback(new Error("请上传视频或填写视频 URL"));
          return;
        }
        if (!/^https?:\/\//i.test(v)) {
          callback(new Error("视频地址须为 http(s) URL"));
          return;
        }
        callback();
      },
      trigger: ["blur", "change"]
    }
  ],
  scope: [{ required: true, message: "请选择发送范围", trigger: "change" }],
  scheduledAt: [
    {
      validator: (_rule, value, callback) => {
        if (form.sendMode !== "scheduled") {
          callback();
          return;
        }
        const v = value == null ? "" : String(value).trim();
        if (!v) {
          callback(new Error("请选择定时发送时间"));
          return;
        }
        const at = dayjs(v);
        if (!at.isValid()) {
          callback(new Error("定时时间格式无效"));
          return;
        }
        if (at.isBefore(dayjs().add(1, "minute"))) {
          callback(new Error("定时时间须至少 1 分钟后"));
          return;
        }
        callback();
      },
      trigger: ["change", "blur"]
    }
  ],
  uidsText: [
    {
      validator: (_rule, value, callback) => {
        if (form.scope !== "uids") {
          callback();
          return;
        }
        const v = value == null ? "" : String(value).trim();
        if (!v) {
          callback(new Error("请输入要发送的用户 ID（每行一个）"));
          return;
        }
        if (uidParseResult.value.uids.length <= 0) {
          callback(new Error("用户 ID 解析为空"));
          return;
        }
        if (uidParseResult.value.invalid.length > 0) {
          callback(new Error(`存在无效 ID：${uidParseResult.value.invalid.slice(0, 5).join(", ")}`));
          return;
        }
        callback();
      },
      trigger: ["blur", "change"]
    }
  ]
}));

function resetForm() {
  form.contentType = "text";
  form.content = "";
  form.imageUrl = "";
  form.previewUrl = "";
  form.imageWidth = 0;
  form.imageHeight = 0;
  form.imageSize = 0;
  form.previewSize = 0;
  form.thumbSize = 0;
  form.thumbWidth = 0;
  form.thumbHeight = 0;
  form.videoUrl = "";
  form.videoSize = 0;
  form.thumbUrl = "";
  form.scope = "all";
  form.sendMode = "immediate";
  form.scheduledAt = "";
  form.uidsText = "";
  formRef.value?.clearValidate?.();
}

function onContentTypeChange() {
  form.content = "";
  form.imageUrl = "";
  form.previewUrl = "";
  form.imageWidth = 0;
  form.imageHeight = 0;
  form.imageSize = 0;
  form.previewSize = 0;
  form.thumbSize = 0;
  form.thumbWidth = 0;
  form.thumbHeight = 0;
  form.videoUrl = "";
  form.videoSize = 0;
  form.thumbUrl = "";
  formRef.value?.clearValidate?.(["content", "imageUrl", "videoUrl"]);
}

async function handleImageUpload(options: UploadRequestOptions) {
  const raw = options.file as File;
  uploading.value = true;
  try {
    const res = await uploadAnnouncementImage(raw);
    const url = String(res.image_url ?? "").trim();
    if (!url) throw new Error("upload empty url");
    form.imageUrl = url;
    form.previewUrl = String(res.preview_url ?? "").trim();
    form.thumbUrl = String(res.thumb_url ?? "").trim();
    form.imageWidth = Number(res.width ?? 0);
    form.imageHeight = Number(res.height ?? 0);
    form.imageSize = Number(res.image_size ?? 0);
    form.previewSize = Number(res.preview_size ?? 0);
    form.thumbSize = Number(res.thumb_size ?? 0);
    form.thumbWidth = Number(res.thumb_width ?? 0);
    form.thumbHeight = Number(res.thumb_height ?? 0);
    message("图片上传成功", { type: "success" });
    options.onSuccess?.(res as never);
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "图片上传失败"), { type: "warning" });
    options.onError?.(err as never);
  } finally {
    uploading.value = false;
  }
}

async function handleVideoUpload(options: UploadRequestOptions) {
  const raw = options.file as File;
  uploading.value = true;
  try {
    const res = await uploadAnnouncementVideo(raw);
    const url = String(res.video_url ?? "").trim();
    if (!url) throw new Error("upload empty url");
    form.videoUrl = url;
    form.videoSize = Number(res.video_size ?? 0);
    form.thumbUrl = String(res.thumb_url ?? "").trim();
    message("视频上传成功", { type: "success" });
    options.onSuccess?.(res as never);
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "视频上传失败"), { type: "warning" });
    options.onError?.(err as never);
  } finally {
    uploading.value = false;
  }
}

async function onSubmit() {
  const ok = await formRef.value?.validate?.().catch(() => false);
  if (!ok) return;

  loading.value = true;
  try {
    const scope = form.scope;
    const data: ImAnnouncementSendBody = {
      content_type: form.contentType,
      scope
    };
    if (form.contentType === "text") {
      data.content = form.content.trim();
    } else if (form.contentType === "image") {
      data.image_url = form.imageUrl.trim();
      if (form.previewUrl) data.preview_url = form.previewUrl;
      if (form.thumbUrl) data.thumb_url = form.thumbUrl;
      if (form.imageWidth > 0) data.width = form.imageWidth;
      if (form.imageHeight > 0) data.height = form.imageHeight;
      if (form.imageSize > 0) data.image_size = form.imageSize;
      if (form.previewSize > 0) data.preview_size = form.previewSize;
      if (form.thumbSize > 0) data.thumb_size = form.thumbSize;
      if (form.thumbWidth > 0) data.thumb_width = form.thumbWidth;
      if (form.thumbHeight > 0) data.thumb_height = form.thumbHeight;
    } else {
      data.video_url = form.videoUrl.trim();
      if (form.videoSize > 0) data.video_size = form.videoSize;
      if (form.thumbUrl.trim()) {
        data.thumb_url = form.thumbUrl.trim();
      }
    }
    if (scope === "uids") {
      data.user_uids = uidParseResult.value.uids;
    }
    if (form.sendMode === "scheduled") {
      data.scheduled_at = dayjs(form.scheduledAt).toISOString();
    }

    const res = await sendImAnnouncement(data);
    const count = res.sent_count ?? 1;
    if (res.scheduled) {
      message(
        scope === "all"
          ? `${typeLabel.value}消息已创建定时任务，将于 ${dayjs(res.scheduled_at || form.scheduledAt).format("YYYY-MM-DD HH:mm:ss")} 全站推送`
          : `${typeLabel.value}消息已创建定时任务，将于 ${dayjs(res.scheduled_at || form.scheduledAt).format("YYYY-MM-DD HH:mm:ss")} 推送给 ${count} 位用户`,
        { type: "success" }
      );
    } else {
      message(
        scope === "all"
          ? `${typeLabel.value}消息已提交，99Messenger 正在向全站用户分批发送`
          : `${typeLabel.value}消息已通过 99Messenger 发送给 ${count} 位用户`,
        { type: "success" }
      );
    }
    resetForm();
  } catch (err: unknown) {
    message(adminApiErrMessage(err, "消息发送失败"), { type: "warning" });
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3" :class="[deviceDetection() ? '' : 'max-w-240 mx-auto']">
    <el-alert
      :closable="false"
      type="info"
      title="由 99Messenger 向用户发送普通单聊消息（文本 / 图片 / 视频），全站推送将分批发送。"
    />

    <el-form ref="formRef" :model="form" :rules="formRules" label-width="96px">
      <el-form-item label="消息类型" prop="contentType">
        <el-radio-group v-model="form.contentType" @change="onContentTypeChange">
          <el-radio-button value="text">文本</el-radio-button>
          <el-radio-button value="image">图片</el-radio-button>
          <el-radio-button value="video">视频</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.contentType === 'text'" label="消息内容" prop="content">
        <el-input
          v-model="form.content"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 12 }"
          placeholder="输入要发送的文本消息"
          maxlength="4000"
          show-word-limit
        />
      </el-form-item>

      <template v-else-if="form.contentType === 'image'">
        <el-form-item label="图片" prop="imageUrl">
          <div class="flex flex-col gap-3 w-full">
            <el-upload
              drag
              :show-file-list="false"
              accept="image/jpeg,image/png,image/webp"
              :http-request="handleImageUpload"
              :disabled="uploading || loading"
            >
              <div class="py-4">
                <div class="text-sm text-[var(--el-text-color-regular)]">
                  拖拽或点击上传 JPG / PNG / WebP（最大 5MB）
                </div>
                <div v-if="uploading" class="mt-2 text-xs text-[var(--el-color-primary)]">上传中…</div>
              </div>
            </el-upload>
            <el-input v-model="form.imageUrl" clearable placeholder="或直接粘贴图片 URL（https://…）" />
            <el-image
              v-if="form.imageUrl"
              :src="form.imageUrl"
              fit="contain"
              class="max-h-60 w-full rounded border border-[var(--el-border-color)] bg-[var(--el-fill-color-light)]"
            />
          </div>
        </el-form-item>
      </template>

      <template v-else>
        <el-form-item label="视频" prop="videoUrl">
          <div class="flex flex-col gap-3 w-full">
            <el-upload
              drag
              :show-file-list="false"
              accept="video/mp4,video/webm,video/quicktime"
              :http-request="handleVideoUpload"
              :disabled="uploading || loading"
            >
              <div class="py-4">
                <div class="text-sm text-[var(--el-text-color-regular)]">
                  拖拽或点击上传 MP4 / WebM / MOV（最大 50MB）
                </div>
                <div v-if="uploading" class="mt-2 text-xs text-[var(--el-color-primary)]">上传中…</div>
              </div>
            </el-upload>
            <el-input v-model="form.videoUrl" clearable placeholder="或直接粘贴视频 URL（https://…）" />
            <el-input
              v-model="form.thumbUrl"
              clearable
              placeholder="可选：视频封面图 URL（https://…）"
            />
            <video
              v-if="form.videoUrl"
              :src="form.videoUrl"
              controls
              class="max-h-60 w-full rounded border border-[var(--el-border-color)] bg-black"
            />
          </div>
        </el-form-item>
      </template>

      <el-form-item label="发送方式">
        <el-radio-group v-model="form.sendMode">
          <el-radio-button value="immediate">立即发送</el-radio-button>
          <el-radio-button value="scheduled">定时发送</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.sendMode === 'scheduled'" label="发送时间" prop="scheduledAt">
        <el-date-picker
          v-model="form.scheduledAt"
          type="datetime"
          placeholder="选择定时发送时间"
          format="YYYY-MM-DD HH:mm:ss"
          value-format="YYYY-MM-DD HH:mm:ss"
          :disabled-date="(date: Date) => dayjs(date).isBefore(dayjs().startOf('day'))"
          class="w-full!"
        />
      </el-form-item>

      <el-form-item label="发送范围" prop="scope">
        <el-radio-group v-model="form.scope" class="flex flex-wrap">
          <el-radio-button value="all">全站</el-radio-button>
          <el-radio-button value="uids">指定用户 ID</el-radio-button>
        </el-radio-group>
      </el-form-item>

      <el-form-item v-if="form.scope === 'uids'" label="用户 ID" prop="uidsText">
        <el-input
          v-model="form.uidsText"
          type="textarea"
          :autosize="{ minRows: 6, maxRows: 10 }"
          placeholder="每行一个用户 ID，例如：&#10;c88wbzp8nc&#10;2wcsfkchoi"
        />
      </el-form-item>

      <div v-if="form.scope === 'uids'" class="-mt-3 mb-3 pl-[96px]">
        <el-text size="small" type="info">
          解析结果：{{ uidParseResult.uids.length }} 个 UID
          <span v-if="uidParseResult.invalid.length > 0" class="ml-2">
            无效：{{ uidParseResult.invalid.slice(0, 5).join(", ") }}
            <span v-if="uidParseResult.invalid.length > 5">
              等 {{ uidParseResult.invalid.length }} 条
            </span>
          </span>
        </el-text>
      </div>

      <el-form-item>
        <el-button type="primary" :loading="loading" @click="onSubmit">
          {{ form.sendMode === "scheduled" ? `定时发送${typeLabel}消息` : `立即发送${typeLabel}消息` }}
        </el-button>
        <el-button :disabled="loading || uploading" @click="resetForm">重置</el-button>
      </el-form-item>
    </el-form>
  </div>
</template>
