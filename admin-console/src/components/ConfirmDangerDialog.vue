<script setup lang="ts">
import { ref } from "vue";

const visible = ref(false);
let resolver: ((ok: boolean) => void) | null = null;
const state = ref({ title: "确认操作", message: "" });

function open(title: string, message: string) {
  state.value = { title, message };
  visible.value = true;
  return new Promise<boolean>(resolve => {
    resolver = resolve;
  });
}

function close(ok: boolean) {
  visible.value = false;
  resolver?.(ok);
  resolver = null;
}

defineExpose({ open });
</script>

<template>
  <el-dialog v-model="visible" :title="state.title" width="420px" @close="close(false)">
    <p>{{ state.message }}</p>
    <template #footer>
      <el-button @click="close(false)">取消</el-button>
      <el-button type="danger" @click="close(true)">确认</el-button>
    </template>
  </el-dialog>
</template>
