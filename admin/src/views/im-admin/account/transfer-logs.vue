<script setup lang="ts">
import { ref } from "vue";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import { deviceDetection } from "@pureadmin/utils";
import { useFinanceTransferLogs } from "./utils/useFinanceTransferLogs";

defineOptions({
  name: "ImAccountTransferLogs"
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
} = useFinanceTransferLogs(tableRef);
</script>

<template>
  <div :class="[deviceDetection() ? 'flex flex-col gap-3' : '']">
    <el-form
      ref="formRef"
      :inline="true"
      :model="form"
      class="search-form bg-bg_color w-full pl-8 pt-3 overflow-auto"
    >
      <el-form-item label="IM号/昵称" prop="uid">
        <el-input
          v-model="form.uid"
          placeholder="付款或收款账号"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="关键字" prop="keyword">
        <el-input
          v-model="form.keyword"
          placeholder="流水号 / 附言 / UID"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="流水类型" prop="transactionType">
        <el-select v-model="form.transactionType" placeholder="全部" clearable class="w-36!">
          <el-option label="转出 3" value="3" />
          <el-option label="转入 4" value="4" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="form.status" placeholder="全部" clearable class="w-32!">
          <el-option label="待审核 0" value="0" />
          <el-option label="成功 1" value="1" />
          <el-option label="失败 2" value="2" />
          <el-option label="已取消 3" value="3" />
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

    <PureTableBar title="转账记录" :columns="columns" @refresh="onSearch">
      <template #buttons />
      <template v-slot="{ size, dynamicColumns }">
        <pure-table
          ref="tableRef"
          row-key="tradeNo"
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
