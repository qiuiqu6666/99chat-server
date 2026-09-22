<template>
  <div class="login-page">
    <div class="login-card">
      <h1>机器人玩家报表</h1>
      <p class="sub">请使用管理账号登录</p>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="账号">
          <el-input v-model="username" autocomplete="username" placeholder="admin" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="password"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入密码"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" native-type="submit" :loading="loading" class="submit">
          登录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref } from "vue";
import { useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { login } from "@/api/report";
import { setToken } from "@/router";

const router = useRouter();
const username = ref("admin");
const password = ref("");
const loading = ref(false);

async function onSubmit() {
  loading.value = true;
  try {
    const data = await login(username.value.trim(), password.value);
    setToken(data.token);
    ElMessage.success("登录成功");
    await router.replace("/");
  } catch (e) {
    ElMessage.error(e instanceof Error ? e.message : "登录失败");
  } finally {
    loading.value = false;
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  min-height: 100dvh;
  display: grid;
  place-items: center;
  padding: 24px;
  padding-left: max(24px, env(safe-area-inset-left, 0px));
  padding-right: max(24px, env(safe-area-inset-right, 0px));
  padding-bottom: max(24px, env(safe-area-inset-bottom, 0px));
}

.login-card {
  width: min(420px, 100%);
  background: rgba(255, 255, 255, 0.92);
  border: 1px solid #d9e2ec;
  padding: 32px 28px;
  box-shadow: 0 18px 40px rgba(15, 23, 42, 0.08);
}

.login-card h1 {
  margin: 0;
  font-size: 24px;
  font-weight: 700;
  letter-spacing: 0.02em;
}

.sub {
  margin: 8px 0 24px;
  color: #64748b;
  font-size: 14px;
}

.submit {
  width: 100%;
  margin-top: 8px;
  min-height: 40px;
}

@media (max-width: 767px) {
  .login-page {
    padding: 16px;
    align-items: flex-start;
    padding-top: max(48px, env(safe-area-inset-top, 0px));
  }

  .login-card {
    padding: 24px 18px;
  }

  .login-card h1 {
    font-size: 22px;
  }
}
</style>
