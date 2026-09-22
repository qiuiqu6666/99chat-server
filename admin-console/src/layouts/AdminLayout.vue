<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import * as Icons from "@element-plus/icons-vue";
import { sideMenu } from "@/router/menu";
import { useAuthStore } from "@/stores/auth";

const route = useRoute();
const router = useRouter();
const auth = useAuthStore();
const contentEl = ref<HTMLElement | null>(null);

const active = computed(
  () => (route.meta.activeMenu as string) || route.path
);

function iconOf(name?: string) {
  if (!name) return Icons.Menu;
  return (Icons as Record<string, unknown>)[name] || Icons.Menu;
}

function scrollMainToTop() {
  window.scrollTo(0, 0);
  document.documentElement.scrollTop = 0;
  document.body.scrollTop = 0;
  if (contentEl.value) contentEl.value.scrollTop = 0;
}

watch(
  () => route.fullPath,
  async () => {
    await nextTick();
    scrollMainToTop();
  }
);

async function onLogout() {
  await auth.logout();
  router.replace("/login");
}
</script>

<template>
  <div class="layout">
    <aside class="sidebar">
      <div class="brand">99Chat</div>
      <el-menu
        :default-active="active"
        background-color="#0f172a"
        text-color="#cbd5e1"
        active-text-color="#38bdf8"
        router
      >
        <template v-for="(group, gi) in sideMenu" :key="gi">
          <el-sub-menu v-if="group.children?.length" :index="`g-${gi}`">
            <template #title>
              <el-icon><component :is="iconOf(group.icon)" /></el-icon>
              <span>{{ group.title }}</span>
            </template>
            <el-menu-item
              v-for="child in group.children"
              :key="child.path"
              :index="child.path!"
            >
              {{ child.title }}
            </el-menu-item>
          </el-sub-menu>
        </template>
      </el-menu>
    </aside>
    <div class="main">
      <header class="header">
        <div class="header-title">99Chat 运营后台</div>
        <div class="header-right">
          <span class="user">{{ auth.username || "admin" }}</span>
          <el-button link type="primary" @click="onLogout">退出</el-button>
        </div>
      </header>
      <main ref="contentEl" class="content">
        <router-view />
      </main>
    </div>
  </div>
</template>

<style scoped>
.layout {
  display: flex;
  height: 100%;
  overflow: hidden;
}
.sidebar {
  width: var(--ac-sidebar-w);
  height: 100%;
  background: var(--ac-sidebar);
  color: var(--ac-sidebar-text);
  flex-shrink: 0;
  overflow-x: hidden;
  overflow-y: auto;
}
.brand {
  height: var(--ac-header-h);
  display: flex;
  align-items: center;
  padding: 0 20px;
  font-weight: 700;
  font-size: 18px;
  color: #fff;
  letter-spacing: 0.04em;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  position: sticky;
  top: 0;
  z-index: 1;
  background: var(--ac-sidebar);
}
.sidebar :deep(.el-menu) {
  border-right: none;
}
.main {
  flex: 1;
  min-width: 0;
  height: 100%;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}
.header {
  height: var(--ac-header-h);
  flex-shrink: 0;
  background: #fff;
  border-bottom: 1px solid #e2e8f0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
}
.header-title {
  font-weight: 600;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 12px;
}
.user {
  color: #64748b;
  font-size: 14px;
}
.content {
  flex: 1;
  min-height: 0;
  overflow: auto;
}
</style>
