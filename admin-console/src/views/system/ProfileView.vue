<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { ElMessage } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import { changePasswordApi } from "@/api/auth";
import { getProfile, putProfile } from "@/api/profile";
import { errMessage, formatTime } from "@/utils/format";

const loading = ref(false);
const saving = ref(false);
const meta = reactive({
  id: "",
  username: "",
  role: "",
  permissions: [] as string[],
  last_login_time: "",
  last_login_ip: "",
  created_at: "",
  status: ""
});
const form = reactive({
  nickname: "",
  display_name: "",
  email: "",
  phone: "",
  avatar: ""
});
const pwd = reactive({ old_password: "", new_password: "" });

function pickUser(raw: unknown): Record<string, unknown> {
  const obj = (raw || {}) as Record<string, unknown>;
  if (obj.user && typeof obj.user === "object") return obj.user as Record<string, unknown>;
  if (obj.data && typeof obj.data === "object") {
    const data = obj.data as Record<string, unknown>;
    if (data.user && typeof data.user === "object") return data.user as Record<string, unknown>;
    return data;
  }
  return obj;
}

async function load() {
  loading.value = true;
  try {
    const user = pickUser(await getProfile());
    meta.id = String(user.id ?? "");
    meta.username = String(user.username ?? "");
    meta.role = String(user.role ?? "");
    meta.permissions = Array.isArray(user.permissions) ? (user.permissions as string[]) : [];
    meta.last_login_time = String(user.last_login_time ?? user.lastLoginTime ?? "");
    meta.last_login_ip = String(user.last_login_ip ?? user.lastLoginIp ?? "");
    meta.created_at = String(user.created_at ?? user.createdAt ?? "");
    meta.status = String(user.status ?? "");
    form.nickname = String(user.nickname ?? "");
    form.display_name = String(user.display_name ?? user.displayName ?? "");
    form.email = String(user.email ?? "");
    form.phone = String(user.phone ?? "");
    form.avatar = String(user.avatar ?? "");
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function saveProfile() {
  saving.value = true;
  try {
    await putProfile({
      nickname: form.nickname,
      display_name: form.display_name,
      email: form.email,
      phone: form.phone,
      avatar: form.avatar
    });
    ElMessage.success("资料已保存");
    await load();
  } catch (e) {
    ElMessage.warning(errMessage(e));
  } finally {
    saving.value = false;
  }
}

async function changePwd() {
  if (!pwd.old_password || !pwd.new_password) {
    ElMessage.warning("请填写旧密码与新密码");
    return;
  }
  try {
    await changePasswordApi(pwd.old_password, pwd.new_password);
    ElMessage.success("密码已修改");
    pwd.old_password = "";
    pwd.new_password = "";
  } catch (e) {
    ElMessage.warning(errMessage(e));
  }
}

onMounted(load);
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="个人设置" />
    <div class="page-card" style="margin-bottom: 12px; max-width: 720px">
      <el-descriptions :column="2" border size="small" style="margin-bottom: 16px">
        <el-descriptions-item label="用户名">{{ meta.username || "—" }}</el-descriptions-item>
        <el-descriptions-item label="角色">{{ meta.role || "—" }}</el-descriptions-item>
        <el-descriptions-item label="状态">{{ meta.status || "—" }}</el-descriptions-item>
        <el-descriptions-item label="上次登录 IP">{{ meta.last_login_ip || "—" }}</el-descriptions-item>
        <el-descriptions-item label="上次登录">{{ formatTime(meta.last_login_time) }}</el-descriptions-item>
        <el-descriptions-item label="创建时间">{{ formatTime(meta.created_at) }}</el-descriptions-item>
        <el-descriptions-item label="权限" :span="2">
          <el-tag v-for="p in meta.permissions" :key="p" size="small" style="margin: 2px">{{ p }}</el-tag>
          <span v-if="!meta.permissions.length">—</span>
        </el-descriptions-item>
      </el-descriptions>
      <el-form label-width="100px">
        <el-form-item label="昵称"><el-input v-model="form.nickname" /></el-form-item>
        <el-form-item label="显示名"><el-input v-model="form.display_name" /></el-form-item>
        <el-form-item label="邮箱"><el-input v-model="form.email" /></el-form-item>
        <el-form-item label="手机"><el-input v-model="form.phone" /></el-form-item>
        <el-form-item label="头像 URL"><el-input v-model="form.avatar" /></el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="saving" @click="saveProfile">保存资料</el-button>
        </el-form-item>
      </el-form>
    </div>
    <div class="page-card" style="max-width: 720px">
      <h3 style="margin: 0 0 12px; font-size: 15px">修改密码</h3>
      <el-form label-width="100px">
        <el-form-item label="旧密码"><el-input v-model="pwd.old_password" type="password" show-password /></el-form-item>
        <el-form-item label="新密码"><el-input v-model="pwd.new_password" type="password" show-password /></el-form-item>
        <el-form-item><el-button type="primary" @click="changePwd">修改密码</el-button></el-form-item>
      </el-form>
    </div>
  </div>
</template>
