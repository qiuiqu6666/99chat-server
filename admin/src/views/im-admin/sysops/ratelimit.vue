<script setup lang="ts">
import { onMounted } from "vue";
import { useRouter } from "vue-router";

defineOptions({ name: "ImSysopsRatelimit" });

const router = useRouter();

onMounted(() => {
  router.replace({
    name: "ImSystemConfigIndex",
    query: { tab: "secrets" }
  });
});
</script>

<template>
  <div class="flex min-h-40 items-center justify-center p-8">
    <el-text type="info">正在跳转到系统配置 · 高级密钥…</el-text>
  </div>
</template>
