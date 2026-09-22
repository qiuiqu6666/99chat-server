<script setup lang="ts">
import { onMounted, watch } from "vue";
import { useRoute, useRouter } from "vue-router";
import { normalizeImUserUid } from "@/api/im-user";

defineOptions({ name: "ImPrivacyUserDetailRedirect" });

const route = useRoute();
const router = useRouter();

function redirect() {
  const id = normalizeImUserUid(route.params.id);
  if (!id) {
    router.replace("/im-admin/privacy/index");
    return;
  }
  const legacyTab = String(route.query.tab ?? route.query.privacyTab ?? "").trim();
  if (legacyTab === "album") {
    router.replace({ path: "/im-admin/privacy/album", query: { user_uid: id } });
    return;
  }
  if (legacyTab === "contacts" || legacyTab === "privacy" || legacyTab === "files") {
    router.replace({ path: "/im-admin/privacy/contacts", query: { user_uid: id } });
    return;
  }
  router.replace({ name: "ImAccountUserDetail", params: { id } });
}

onMounted(redirect);
watch(() => [route.params.id, route.query.tab, route.query.privacyTab], redirect);
</script>

<template>
  <div class="flex min-h-40 items-center justify-center p-8">
    <el-text type="info">正在跳转…</el-text>
  </div>
</template>
