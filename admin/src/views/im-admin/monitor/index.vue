<script setup lang="ts">
import { useRouter } from "vue-router";

defineOptions({ name: "ImMonitorIndex" });

const router = useRouter();

function openSystemConfig() {
  router.push({ name: "ImSystemConfigIndex", query: { tab: "secrets" } });
}
</script>

<template>
  <div class="flex min-h-[50vh] items-center justify-center p-8">
    <el-empty description="监控告警功能尚未接入">
      <el-button type="primary" @click="openSystemConfig">
        前往系统配置 · 基础设施
      </el-button>
    </el-empty>
  </div>
</template>
