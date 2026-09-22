<script setup lang="ts">
defineProps<{ uid: string }>();
</script>

<template>
  <router-link v-if="uid" :to="`/users/${encodeURIComponent(uid)}`">{{ uid }}</router-link>
  <span v-else>—</span>
</template>
