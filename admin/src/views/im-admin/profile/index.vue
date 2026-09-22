<script setup lang="ts">
import { onMounted, reactive, ref } from "vue";
import { message } from "@/utils/message";
import type { FormInstance, FormRules } from "element-plus";
import {
  changeAdminPassword,
  getAdminAuthMe,
  getAdminProfile,
  getMyAdminLoginLogs,
  updateAdminProfile,
  type AdminLoginLogItem,
  type AdminProfile
} from "@/api/admin-auth";
import { useUserStoreHook } from "@/store/modules/user";
import { displayIpRegion, resolveIpRegions } from "@/utils/ipRegion";
import { formatAdminUnixTime } from "@/utils/adminTimestamp";

defineOptions({ name: "ImAdminProfile" });

const loading = ref(false);
const saving = ref(false);
const changingPassword = ref(false);
const profileFormRef = ref<FormInstance>();
const passwordFormRef = ref<FormInstance>();

const profile = reactive<AdminProfile>({
  id: 0,
  username: "",
  nickname: "",
  display_name: "",
  role: "admin",
  permissions: [],
  email: "",
  phone: "",
  avatar: "",
  last_login_time: "",
  last_login_ip: "",
  created_at: "",
  status: "normal"
});

const passwordForm = reactive({
  old_password: "",
  new_password: "",
  confirm_password: ""
});

const profileRules: FormRules = {
  nickname: [{ max: 32, message: "昵称不能超过 32 个字符", trigger: "blur" }],
  email: [{ type: "email", message: "邮箱格式不正确", trigger: "blur" }]
};

const passwordRules: FormRules = {
  old_password: [{ required: true, message: "请输入当前密码", trigger: "blur" }],
  new_password: [
    { required: true, message: "请输入新密码", trigger: "blur" },
    { min: 8, message: "新密码至少 8 位", trigger: "blur" }
  ],
  confirm_password: [
    { required: true, message: "请再次输入新密码", trigger: "blur" },
    {
      validator: (_rule, value, callback) => {
        if (value !== passwordForm.new_password) {
          callback(new Error("两次输入的新密码不一致"));
        } else {
          callback();
        }
      },
      trigger: "blur"
    }
  ]
};

const loginLogs = ref<AdminLoginLogItem[]>([]);
const regionByIp = ref<Record<string, string>>({});

function roleLabel(role?: string) {
  if (role === "super_admin") return "超级管理员";
  return role || "admin";
}

function isSuccess(row: AdminLoginLogItem) {
  return row.success === 1 || row.success === true || row.success === "1";
}

function failReasonText(reason?: string | null) {
  if (!reason) return "—";
  const map: Record<string, string> = {
    invalid_credentials: "用户名或密码错误"
  };
  return map[reason] || reason;
}

async function loadProfile() {
  loading.value = true;
  try {
    try {
      Object.assign(profile, await getAdminProfile());
    } catch (_error) {
      const { user } = await getAdminAuthMe();
      Object.assign(profile, user);
    }
  } finally {
    loading.value = false;
  }
}

async function loadLoginLogs() {
  try {
    const raw = await getMyAdminLoginLogs({ page: 1, page_size: 10 });
    loginLogs.value = Array.isArray(raw.items) ? raw.items : [];
    regionByIp.value = await resolveIpRegions(
      loginLogs.value.map(r => r.ip as string | null | undefined)
    );
  } catch (_error) {
    loginLogs.value = [];
  }
}

async function submitProfile() {
  await profileFormRef.value?.validate();
  saving.value = true;
  try {
    await updateAdminProfile({
      nickname: profile.nickname,
      display_name: profile.display_name,
      email: profile.email,
      phone: profile.phone,
      avatar: profile.avatar
    });
    message("管理员资料已保存", { type: "success" });
    await loadProfile();
  } finally {
    saving.value = false;
  }
}

async function submitPassword() {
  await passwordFormRef.value?.validate();
  changingPassword.value = true;
  try {
    await changeAdminPassword({
      old_password: passwordForm.old_password,
      new_password: passwordForm.new_password
    });
    message("密码已修改，请重新登录", { type: "success" });
    await useUserStoreHook().logOut();
  } finally {
    changingPassword.value = false;
  }
}

onMounted(async () => {
  await loadProfile();
  await loadLoginLogs();
});
</script>

<template>
  <div class="p-4">
    <el-row :gutter="16">
      <el-col :xs="24" :lg="14">
        <el-card v-loading="loading" shadow="never">
          <template #header>
            <span class="font-medium">管理员资料</span>
          </template>

          <el-form
            ref="profileFormRef"
            label-width="110px"
            :model="profile"
            :rules="profileRules"
          >
            <el-form-item label="管理员账号">
              <el-input v-model="profile.username" disabled />
            </el-form-item>
            <el-form-item label="管理员昵称" prop="nickname">
              <el-input v-model="profile.nickname" placeholder="请输入昵称" />
            </el-form-item>
            <el-form-item label="显示名称">
              <el-input v-model="profile.display_name" placeholder="请输入显示名称" />
            </el-form-item>
            <el-form-item label="绑定邮箱" prop="email">
              <el-input v-model="profile.email" placeholder="请输入邮箱" />
            </el-form-item>
            <el-form-item label="绑定手机号">
              <el-input v-model="profile.phone" placeholder="请输入手机号" />
            </el-form-item>
            <el-form-item label="角色">
              <el-tag type="success">{{ roleLabel(profile.role) }}</el-tag>
            </el-form-item>
            <el-form-item label="最后登录 IP">
              <el-text>{{ profile.last_login_ip || "-" }}</el-text>
            </el-form-item>
            <el-form-item label="最后登录时间">
              <el-text>{{ profile.last_login_time || "-" }}</el-text>
            </el-form-item>
            <el-form-item>
              <el-button type="primary" :loading="saving" @click="submitProfile">
                保存资料
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>

      <el-col :xs="24" :lg="10">
        <el-card shadow="never">
          <template #header>
            <span class="font-medium">修改密码</span>
          </template>
          <el-alert
            class="mb-4"
            type="warning"
            :closable="false"
            title="修改密码成功后会自动退出当前登录，旧 Token 需要后端同步失效。"
          />
          <el-form
            ref="passwordFormRef"
            label-width="100px"
            :model="passwordForm"
            :rules="passwordRules"
          >
            <el-form-item label="当前密码" prop="old_password">
              <el-input
                v-model="passwordForm.old_password"
                type="password"
                show-password
              />
            </el-form-item>
            <el-form-item label="新密码" prop="new_password">
              <el-input
                v-model="passwordForm.new_password"
                type="password"
                show-password
              />
            </el-form-item>
            <el-form-item label="确认密码" prop="confirm_password">
              <el-input
                v-model="passwordForm.confirm_password"
                type="password"
                show-password
              />
            </el-form-item>
            <el-form-item>
              <el-button
                type="danger"
                :loading="changingPassword"
                @click="submitPassword"
              >
                修改密码
              </el-button>
            </el-form-item>
          </el-form>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="mt-4" shadow="never">
      <template #header>
        <span class="font-medium">最近登录日志</span>
      </template>
      <el-table :data="loginLogs" border>
        <el-table-column label="账号" min-width="120">
          <template #default="{ row }">
            {{ row.admin_username || row.username_attempted || row.username || "-" }}
          </template>
        </el-table-column>
        <el-table-column prop="ip" label="IP" min-width="140" />
        <el-table-column label="地区" min-width="140">
          <template #default="{ row }">
            {{ displayIpRegion(row.ip, regionByIp) }}
          </template>
        </el-table-column>
        <el-table-column label="结果" min-width="100">
          <template #default="{ row }">
            <el-tag :type="isSuccess(row) ? 'success' : 'danger'" size="small">
              {{ isSuccess(row) ? "成功" : "失败" }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="说明" min-width="180">
          <template #default="{ row }">
            {{ isSuccess(row) ? "—" : failReasonText(row.fail_reason) }}
          </template>
        </el-table-column>
        <el-table-column label="时间" min-width="180">
          <template #default="{ row }">
            {{ formatAdminUnixTime(row.login_at) }}
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>
