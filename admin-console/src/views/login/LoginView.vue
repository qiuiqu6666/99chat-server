<script setup lang="ts">
import { reactive, ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { ElMessage } from "element-plus";
import { useAuthStore } from "@/stores/auth";
import { errMessage } from "@/utils/format";

const auth = useAuthStore();
const router = useRouter();
const route = useRoute();
const loading = ref(false);
const form = reactive({ username: "", password: "" });

async function onSubmit() {
  if (!form.username || !form.password) {
    ElMessage.warning("请输入账号和密码");
    return;
  }
  loading.value = true;
  try {
    await auth.login(form.username, form.password);
    const redirect = String(route.query.redirect || "/dashboard");
    router.replace(redirect);
  } catch (e) {
    ElMessage.error(errMessage(e, "登录失败"));
  } finally {
    loading.value = false;
  }
}
</script>

<template>
  <div class="login-page">
    <div class="login-card">
      <h1>99Chat 运营后台</h1>
      <p class="muted">请使用管理员账号登录</p>
      <el-form label-position="top" @submit.prevent="onSubmit">
        <el-form-item label="账号">
          <el-input v-model="form.username" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <el-input
            v-model="form.password"
            type="password"
            show-password
            autocomplete="current-password"
            @keyup.enter="onSubmit"
          />
        </el-form-item>
        <el-button type="primary" style="width: 100%" :loading="loading" @click="onSubmit">
          登录
        </el-button>
      </el-form>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100%;
  display: grid;
  place-items: center;
  background: linear-gradient(145deg, #0f172a 0%, #1e3a5f 55%, #0ea5e9 140%);
}
.login-card {
  width: 380px;
  background: #fff;
  border-radius: 12px;
  padding: 28px 28px 32px;
  box-shadow: 0 20px 50px rgba(0, 0, 0, 0.25);
}
.login-card h1 {
  margin: 0 0 4px;
  font-size: 22px;
}
</style>
