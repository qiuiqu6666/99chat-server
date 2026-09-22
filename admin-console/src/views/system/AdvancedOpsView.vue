<script setup lang="ts">
import { computed, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import {
  opsFriendSyncStatus,
  opsGroupProjectionStatus,
  opsPushTest,
  opsSettingsReload
} from "@/api/adminOps";
import { errMessage } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canManage = computed(() => hasPerm("admin.manage"));
const loading = ref("");
const output = ref("");

const pushForm = reactive({
  to_user_id: "",
  title: "test",
  body: "",
  data_json: "{}"
});

async function run(label: string, key: string, fn: () => Promise<unknown>) {
  if (!canManage.value) {
    ElMessage.warning("需要 admin.manage 权限");
    return;
  }
  loading.value = key;
  try {
    output.value = JSON.stringify(await fn(), null, 2);
    ElMessage.success(label + " 完成");
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = "";
  }
}

async function doPushTest() {
  let data: Record<string, string> | undefined;
  try {
    const parsed = JSON.parse(pushForm.data_json || "{}");
    if (parsed && typeof parsed === "object" && !Array.isArray(parsed)) {
      data = Object.fromEntries(
        Object.entries(parsed as Record<string, unknown>).map(([k, v]) => [k, String(v)])
      );
    }
  } catch {
    ElMessage.warning("data JSON 无效");
    return;
  }
  if (!pushForm.to_user_id.trim()) {
    ElMessage.warning("请填写 to_user_id");
    return;
  }
  await run("Push 测试", "push", () =>
    opsPushTest({
      to_user_id: pushForm.to_user_id.trim(),
      title: pushForm.title || "test",
      body: pushForm.body || undefined,
      data
    })
  );
}

async function doReload() {
  try {
    await ElMessageBox.confirm("确认重载 Settings 并失效 OSS 客户端缓存？", "危险操作", {
      type: "warning"
    });
  } catch {
    return;
  }
  await run("Settings Reload", "reload", opsSettingsReload);
}
</script>

<template>
  <div class="page">
    <PageHeader title="高级运维" subtitle="低频危险操作，需 admin.manage" />
    <div class="page-card" style="margin-bottom: 12px">
      <div class="toolbar">
        <el-button :loading="loading === 'group'" :disabled="!canManage" @click="run('群投影状态', 'group', opsGroupProjectionStatus)">
          群投影状态
        </el-button>
        <el-button :loading="loading === 'friend'" :disabled="!canManage" @click="run('好友同步状态', 'friend', opsFriendSyncStatus)">
          好友同步状态
        </el-button>
        <el-button type="danger" :loading="loading === 'reload'" :disabled="!canManage" @click="doReload">
          Settings Reload
        </el-button>
      </div>
    </div>

    <div class="page-card" style="margin-bottom: 12px; max-width: 720px">
      <h3 style="margin: 0 0 12px; font-size: 15px">Push 测试</h3>
      <el-form label-width="100px">
        <el-form-item label="用户 ID" required>
          <el-input v-model="pushForm.to_user_id" placeholder="to_user_id" />
        </el-form-item>
        <el-form-item label="标题">
          <el-input v-model="pushForm.title" />
        </el-form-item>
        <el-form-item label="正文">
          <el-input v-model="pushForm.body" type="textarea" :rows="2" />
        </el-form-item>
        <el-form-item label="data JSON">
          <el-input v-model="pushForm.data_json" type="textarea" :rows="3" placeholder='如 {"k":"v"}' />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading === 'push'" :disabled="!canManage" @click="doPushTest">
            发送 Push
          </el-button>
        </el-form-item>
      </el-form>
    </div>

    <div class="page-card">
      <h3 style="margin: 0 0 12px; font-size: 15px">最近结果</h3>
      <pre style="margin: 0; white-space: pre-wrap">{{ output || "尚无结果" }}</pre>
    </div>
  </div>
</template>
