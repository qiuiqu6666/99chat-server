<script setup lang="ts">
import { ref } from "vue";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import { deviceDetection } from "@pureadmin/utils";
import { useWalletLedger } from "./utils/useWalletLedger";

defineOptions({
  name: "ImAccountWalletLedger"
});

const TX_TYPE_OPTIONS = [
  { value: "1", label: "1 · 入账/充值类等" },
  { value: "2", label: "2 · 出账/提现类等" },
  { value: "3", label: "3 · 转出" },
  { value: "4", label: "4 · 转入" },
  { value: "5", label: "5 · 红包发出" },
  { value: "6", label: "6 · 红包领取" },
  { value: "7", label: "7 · 红包退回" }
];

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
} = useWalletLedger(tableRef);
</script>

<template>
  <div :class="[deviceDetection() ? 'flex flex-col gap-3' : '']">
    <el-form
      ref="formRef"
      :inline="true"
      :model="form"
      class="search-form bg-bg_color w-full pl-8 pt-3 overflow-auto"
    >
      <el-form-item label="用户 UID" prop="uid">
        <el-input v-model="form.uid" placeholder="可选，不填查全站" clearable class="w-40!" />
      </el-form-item>
      <el-form-item label="账变类型" prop="transactionTypes">
        <el-select
          v-model="form.transactionTypes"
          multiple
          collapse-tags
          collapse-tags-tooltip
          placeholder="不传则 1～7 全部"
          clearable
          class="min-w-72!"
        >
          <el-option
            v-for="o in TX_TYPE_OPTIONS"
            :key="o.value"
            :label="o.label"
            :value="o.value"
          />
        </el-select>
      </el-form-item>
      <el-form-item label="关键字（前端）" prop="keyword">
        <el-input
          v-model="form.keyword"
          placeholder="流水号 / 附言 / UID"
          clearable
          class="w-45!"
        />
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

    <PureTableBar title="账变记录" :columns="columns" @refresh="onSearch">
      <template #buttons />
      <template v-slot="{ size, dynamicColumns }">
        <pure-table
          ref="tableRef"
          row-key="ledgerRowKey"
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
