<script setup lang="ts">
import { useRouter } from 'vue-router';

const props = defineProps<{ uid?: string | number | null }>();
const router = useRouter();

function open() {
  const uid = String(props.uid ?? '').trim();
  if (!uid) return;
  router.push(`/users/${encodeURIComponent(uid)}`);
}
</script>

<template>
  <NButton v-if="uid" text type="primary" @click="open">{{ uid }}</NButton>
  <span v-else>—</span>
</template>
