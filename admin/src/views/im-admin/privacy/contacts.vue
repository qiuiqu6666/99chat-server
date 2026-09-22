<script setup lang="ts">
import { computed, onMounted } from "vue";
import { useRoute, useRouter } from "vue-router";
import ContactBookPanel from "./components/ContactBookPanel.vue";
import { normalizeImUserUid } from "@/api/im-user";

defineOptions({ name: "ImPrivacyUserContacts" });

const route = useRoute();
const router = useRouter();

const userUid = computed(() => normalizeImUserUid(route.query.user_uid));

function goBack() {
  if (route.query.from === "stats") {
    router.push("/im-admin/privacy/index");
    return;
  }
  if (userUid.value) {
    router.push({ name: "ImAccountUserDetail", params: { id: userUid.value } });
    return;
  }
  router.push("/im-admin/privacy/index");
}

onMounted(() => {
  if (!userUid.value) {
    router.replace("/im-admin/privacy/index");
  }
});
</script>

<template>
  <div class="p-4">
    <el-page-header class="mb-4!" @back="goBack">
      <template #content>
        <span class="text-lg font-medium">用户通讯录</span>
        <el-text v-if="userUid" type="info" size="small" class="ml-2">{{ userUid }}</el-text>
      </template>
    </el-page-header>
    <ContactBookPanel v-if="userUid" :user-uid="userUid" />
  </div>
</template>
