<script setup lang="ts">
import { watch } from "vue";
import {
  welcomeDailyColumns,
  createWelcomeDailyPagination,
  Empty
} from "./columns";

const props = defineProps<{
  dataList: Array<Record<string, unknown>>;
  loading?: boolean;
}>();

const pagination = createWelcomeDailyPagination(0);

watch(
  () => [props.dataList.length, props.loading] as const,
  () => {
    const list = props.dataList;
    pagination.total = list.length;
    if (
      pagination.currentPage > 1 &&
      (pagination.currentPage - 1) * (pagination.pageSize ?? 10) >= list.length &&
      list.length > 0
    ) {
      pagination.currentPage = 1;
    }
  },
  { immediate: true }
);

function onCurrentChange(page: number) {
  pagination.currentPage = page;
}

const columns = welcomeDailyColumns;
</script>

<template>
  <pure-table
    row-key="id"
    alignWhole="center"
    showOverflowTooltip
    :loading="loading"
    :loading-config="{ background: 'transparent' }"
    :data="
      props.dataList.slice(
        (pagination.currentPage - 1) * pagination.pageSize,
        pagination.currentPage * pagination.pageSize
      )
    "
    :columns="columns"
    :pagination="pagination"
    @page-current-change="onCurrentChange"
  >
    <template #empty>
      <el-empty description="暂无数据" :image-size="60">
        <template #image>
          <Empty />
        </template>
      </el-empty>
    </template>
  </pure-table>
</template>

<style lang="scss">
.pure-table-filter {
  .el-table-filter__list {
    min-width: 80px;
    padding: 0;

    li {
      line-height: 28px;
    }
  }
}
</style>

<style lang="scss" scoped>
:deep(.el-table) {
  --el-table-border: none;
  --el-table-border-color: transparent;

  .el-empty__description {
    margin: 0;
  }

  .el-scrollbar__bar {
    display: none;
  }
}
</style>
