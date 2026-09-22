<script setup lang="ts">
import { ref } from "vue";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import { deviceDetection } from "@pureadmin/utils";
import { useWalletSweepLogs } from "./utils/useWalletSweepLogs";

defineOptions({
  name: "ImWalletSweepLogs"
});

const tableRef = ref();
const formRef = ref();

const {
  form,
  loading,
  columns,
  dataList,
  pagination,
  onSearch,
  resetForm,
  handleSizeChange,
  handleCurrentChange
} = useWalletSweepLogs(tableRef);
</script>

<template>
  <div :class="[deviceDetection() ? 'flex flex-col gap-3' : '']">
    <el-alert
      :closable="false"
      type="info"
      class="mx-8 mt-2"
      title="记录手动归集、批量归集与定时自动归集的执行结果；失败记录含错误原因"
    />

    <el-form
      ref="formRef"
      :inline="true"
      :model="form"
      class="search-form bg-bg_color w-full pl-8 pt-3 overflow-auto"
    >
      <el-form-item label="关键字" prop="keyword">
        <el-input
          v-model="form.keyword"
          placeholder="UID / 昵称 / 地址 / TxID"
          clearable
          class="w-52!"
        />
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="form.status" placeholder="全部" clearable class="w-28!">
          <el-option label="成功" value="success" />
          <el-option label="失败" value="failed" />
        </el-select>
      </el-form-item>
      <el-form-item label="触发方式" prop="trigger">
        <el-select v-model="form.trigger" placeholder="全部" clearable class="w-32!">
          <el-option label="手动归集" value="manual" />
          <el-option label="批量归集" value="collect_all" />
          <el-option label="自动归集" value="auto" />
        </el-select>
      </el-form-item>
      <el-form-item label="起始日期" prop="createdFrom">
        <el-date-picker
          v-model="form.createdFrom"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="created_from"
          class="w-40!"
        />
      </el-form-item>
      <el-form-item label="结束日期" prop="createdTo">
        <el-date-picker
          v-model="form.createdTo"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="created_to"
          class="w-40!"
        />
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

    <PureTableBar title="归集记录" :columns="columns" @refresh="onSearch">
      <template #buttons />
      <template v-slot="{ size, dynamicColumns }">
        <pure-table
          ref="tableRef"
          row-key="id"
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
