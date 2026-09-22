<script setup lang="ts">
defineProps<{ title?: string; subtitle?: string }>();
</script>

<template>
  <div class="ph">
    <div>
      <h2 class="ph-title">{{ title }}</h2>
      <p v-if="subtitle" class="ph-sub">{{ subtitle }}</p>
    </div>
    <div class="ph-extra"><slot /></div>
  </div>
</template>

<style scoped>
.ph {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  margin-bottom: 12px;
  gap: 12px;
}
.ph-title {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
}
.ph-sub {
  margin: 4px 0 0;
  color: #64748b;
  font-size: 13px;
}
</style>
