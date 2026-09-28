<script setup lang="ts">
defineProps<{ title?: string; subtitle?: string }>();
</script>

<template>
  <div class="mb-16px flex items-start justify-between gap-12px">
    <div>
      <h2 class="m-0 text-18px font-600">{{ title }}</h2>
      <p v-if="subtitle" class="m-0 mt-4px text-13px text-#64748b">{{ subtitle }}</p>
    </div>
    <div class="flex items-center gap-8px">
      <slot />
    </div>
  </div>
</template>
