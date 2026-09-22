<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { sendWalletNotice } from "@/api/walletNotice";
import { errMessage } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

type NoticeRow = { label: string; value: string; emphasize: boolean };

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const form = reactive({
  user_uid: "",
  notice_type: "custom",
  title: "支付助手通知",
  service_name: "",
  status_label: "",
  summary: "",
  action_label: "",
  action_url: "",
  order_id: "",
  rows: [{ label: "", value: "", emphasize: false }] as NoticeRow[]
});

function addRow() {
  form.rows.push({ label: "", value: "", emphasize: false });
}

function removeRow(i: number) {
  if (form.rows.length <= 1) {
    form.rows[0] = { label: "", value: "", emphasize: false };
    return;
  }
  form.rows.splice(i, 1);
}

async function send() {
  if (!form.user_uid.trim()) {
    ElMessage.warning("请填写用户 UID");
    return;
  }
  loading.value = true;
  try {
    const rows = form.rows
      .filter(r => r.label.trim() || r.value.trim())
      .map(r => ({
        label: r.label,
        value: r.value,
        emphasize: r.emphasize
      }));
    await sendWalletNotice({
      user_uid: form.user_uid.trim(),
      notice_type: form.notice_type || "custom",
      title: form.title || "支付助手通知",
      service_name: form.service_name || undefined,
      status_label: form.status_label || undefined,
      summary: form.summary || undefined,
      rows: rows.length ? rows : undefined,
      action_label: form.action_label || undefined,
      action_url: form.action_url || undefined,
      order_id: form.order_id || undefined
    });
    ElMessage.success("已发送");
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="page">
    <PageHeader title="支付助手通知" subtitle="对接 /api/v1/im/platform-wallet-notice" />
    <div class="page-card" style="max-width: 720px">
      <el-form label-width="110px">
        <el-form-item label="用户 UID" required>
          <el-input v-model="form.user_uid" placeholder="接收用户" />
        </el-form-item>
        <el-form-item label="通知类型">
          <el-input v-model="form.notice_type" placeholder="默认 custom" />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="form.title" />
        </el-form-item>
        <el-form-item label="服务名">
          <el-input v-model="form.service_name" />
        </el-form-item>
        <el-form-item label="状态文案">
          <el-input v-model="form.status_label" />
        </el-form-item>
        <el-form-item label="摘要">
          <el-input v-model="form.summary" type="textarea" :rows="3" />
        </el-form-item>
        <el-form-item label="明细行">
          <div style="width: 100%">
            <div v-for="(row, i) in form.rows" :key="i" style="display:flex;gap:8px;margin-bottom:8px;align-items:center">
              <el-input v-model="row.label" placeholder="标签" style="width:140px" />
              <el-input v-model="row.value" placeholder="值" style="flex:1" />
              <el-checkbox v-model="row.emphasize">强调</el-checkbox>
              <el-button link type="danger" @click="removeRow(i)">删</el-button>
            </div>
            <el-button link type="primary" @click="addRow">添加一行</el-button>
          </div>
        </el-form-item>
        <el-form-item label="按钮文案">
          <el-input v-model="form.action_label" />
        </el-form-item>
        <el-form-item label="按钮链接">
          <el-input v-model="form.action_url" />
        </el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="form.order_id" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" :disabled="!canWrite" @click="send">发送</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>
