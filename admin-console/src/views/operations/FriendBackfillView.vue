<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { friendBackfillStatus, startFriendBackfill } from "@/api/friendBackfill";
import { errMessage } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const starting = ref(false);
const status = ref<Record<string, unknown>>({});
const form = reactive({
  max_users: 0,
  reset_cursor: false
});

async function load() {
  loading.value = true;
  try {
    status.value = (await friendBackfillStatus()) as Record<string, unknown>;
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function start() {
  try {
    await ElMessageBox.confirm(
      `确认开始好友补全？max_users=${form.max_users || "不限"}，reset_cursor=${form.reset_cursor}`,
      "补全确认",
      { type: "warning" }
    );
  } catch {
    return;
  }
  starting.value = true;
  try {
    const res = (await startFriendBackfill({
      max_users: form.max_users || 0,
      reset_cursor: form.reset_cursor
    })) as Record<string, unknown>;
    if (res.ok === false) {
      ElMessage.warning(String(res.error || "启动失败"));
    } else {
      ElMessage.success("已启动");
    }
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    starting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="系统好友补全" />
    <div class="page-card" style="margin-bottom: 12px; max-width: 640px" v-loading="loading">
      <el-descriptions :column="2" border size="small">
        <el-descriptions-item label="运行中">{{ status.running ? "是" : "否" }}</el-descriptions-item>
        <el-descriptions-item label="游标">{{ status.cursor_after_id ?? status.cursorAfterId ?? "—" }}</el-descriptions-item>
      </el-descriptions>
      <pre style="margin-top: 12px; white-space: pre-wrap">{{ JSON.stringify(status.last || {}, null, 2) }}</pre>
      <el-button style="margin-top: 8px" @click="load">刷新状态</el-button>
    </div>
    <div class="page-card" style="max-width: 640px">
      <el-form label-width="120px">
        <el-form-item label="max_users">
          <el-input-number v-model="form.max_users" :min="0" :controls="false" style="width: 100%" />
          <div style="color: #888; font-size: 12px; margin-top: 4px">0 表示不限制</div>
        </el-form-item>
        <el-form-item label="reset_cursor">
          <el-switch v-model="form.reset_cursor" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="starting" :disabled="!canWrite" @click="start">开始补全</el-button>
        </el-form-item>
      </el-form>
    </div>
  </div>
</template>
