<script setup lang="ts">
import { computed, ref } from "vue";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import View from "~icons/ep/view";
import { useGroupList } from "./utils/useGroupList";

defineOptions({
  name: "ImGroupList"
});

const tableRef = ref();
const formRef = ref();

const {
  form,
  loading,
  columns,
  dataList,
  imTotal,
  listTruncated,
  pagination,
  deviceDetection,
  onSearch,
  resetForm,
  handleSizeChange,
  handleCurrentChange,
  handleDetail
} = useGroupList(tableRef);

const listTitle = computed(() => {
  if (imTotal.value != null) {
  return `群组列表（共 ${imTotal.value} 个）`;
  }
  return "群组列表";
});
</script>

<template>
  <div :class="[deviceDetection() ? 'flex flex-col gap-3' : '']">
    <el-form
      ref="formRef"
      :inline="true"
      :model="form"
      class="search-form bg-bg_color w-full pl-8 pt-3 overflow-auto"
    >
      <el-form-item label="群名称" prop="name">
        <el-input
          v-model="form.name"
          placeholder="关键字"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="群号" prop="groupNo">
        <el-input
          v-model="form.groupNo"
          placeholder="支持模糊"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="群状态" prop="status">
        <el-select v-model="form.status" placeholder="全部" clearable class="w-45!">
          <el-option label="已删除" value="-1" />
          <el-option label="待激活" value="0" />
          <el-option label="正常" value="1" />
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

    <el-alert
      v-if="listTruncated"
      class="mx-4 mb-2"
      type="warning"
      :closable="false"
      show-icon
      title="群列表未完全加载：已超过当前扫描上限，请联系管理员提高上限或缩小筛选条件。"
    />

    <PureTableBar :title="listTitle" :columns="columns" @refresh="onSearch">
      <template #buttons />
      <template v-slot="{ size, dynamicColumns }">
        <pure-table
          ref="tableRef"
          row-key="gId"
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
        >
          <template #operation="{ row }">
            <el-button
              class="reset-margin"
              link
              type="primary"
              :size="size"
              :icon="useRenderIcon(View)"
              @click="handleDetail(row)"
            >
              详情
            </el-button>
          </template>
        </pure-table>
      </template>
    </PureTableBar>
  </div>
</template>
