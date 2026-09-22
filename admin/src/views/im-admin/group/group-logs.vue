<script setup lang="ts">
import { ref, onMounted } from "vue";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import { deviceDetection } from "@pureadmin/utils";
import { useGroupLogs } from "./utils/useGroupLogs";

defineOptions({
  name: "ImGroupLogs"
});

const tableRef = ref();
const formRef = ref();

const {
  form,
  loading,
  columns,
  dataList,
  pagination,
  timelineMeta,
  onSearch,
  resetForm,
  handleSizeChange,
  handleCurrentChange
} = useGroupLogs(tableRef);

onMounted(() => {
  void onSearch();
});

const KIND_OPTIONS = [
  { value: "im_system", label: "IM 系统提示" },
  { value: "join_request", label: "入群申请" },
  { value: "member_mute", label: "成员禁言（mute）" }
];
</script>

<template>
  <div :class="[deviceDetection() ? 'flex flex-col gap-3' : '']">
    <el-alert
      :closable="false"
      type="info"
      class="ml-8 mr-8"
      title="留空群 ID 可查看全站群内事件；填写群 ID 则仅查看该群。可按事件类型筛选。"
    />

    <el-form
      ref="formRef"
      :inline="true"
      :model="form"
      class="search-form bg-bg_color w-full pl-8 pt-3 overflow-auto"
    >
      <el-form-item label="群 ID" prop="gId">
        <el-input
          v-model="form.gId"
          placeholder="留空 = 全站所有群；填写则仅该群（1～32 位字母数字）"
          clearable
          class="w-80!"
        />
      </el-form-item>
      <el-form-item label="种类 kinds" prop="kinds">
        <el-select
          v-model="form.kinds"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="不选表示全部"
          clearable
          class="min-w-70!"
        >
          <el-option
            v-for="o in KIND_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item>
        <el-button
          type="primary"
          :icon="useRenderIcon('ri/search-line')"
          :loading="loading"
          @click="
            pagination.currentPage = 1;
            onSearch();
          "
        >
          查询
        </el-button>
        <el-button :icon="useRenderIcon(Refresh)" @click="resetForm(formRef)">
          重置
        </el-button>
      </el-form-item>
    </el-form>

    <div
      v-if="
        timelineMeta.scope != null ||
        timelineMeta.g_id != null ||
        timelineMeta.g_name != null
      "
      class="px-8 text-sm text-gray-600 dark:text-gray-400"
    >
      <span v-if="timelineMeta.scope != null" class="mr-4">
        范围：<strong>{{ String(timelineMeta.scope) }}</strong>
      </span>
      <span v-if="timelineMeta.g_id != null" class="mr-4">
        顶层 g_id：{{ String(timelineMeta.g_id) }}
      </span>
      <span v-if="timelineMeta.g_name != null" class="mr-4">
        顶层群名：{{ String(timelineMeta.g_name) }}
      </span>
      <span v-if="timelineMeta.group_mode != null">
        顶层 group_mode：{{ String(timelineMeta.group_mode) }}
      </span>
    </div>

    <PureTableBar title="群组日志" :columns="columns" @refresh="onSearch">
      <template #buttons />
      <template v-slot="{ size, dynamicColumns }">
        <pure-table
          ref="tableRef"
          row-key="rowKey"
          adaptive
          :adaptive-config="{ offsetBottom: 108 }"
          align-whole="center"
          table-layout="auto"
          :loading="loading"
          :size="size"
          :data="dataList"
          :columns="dynamicColumns"
          :pagination="{ ...pagination, size }"
          :header-cell-style="{
            background: 'var(--el-fill-color-light)',
            color: 'var(--el-text-color-primary)'
          }"
          @page-size-change="handleSizeChange"
          @page-current-change="handleCurrentChange"
        />
      </template>
    </PureTableBar>
  </div>
</template>
