<script setup lang="ts">
import { ref } from "vue";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import { deviceDetection } from "@pureadmin/utils";
import { useRedPacketLogs } from "./utils/useRedPacketLogs";

defineOptions({
  name: "ImAccountRedPacketLogs"
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
} = useRedPacketLogs(tableRef);
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
          placeholder="单号前缀也可模糊"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="关键字" prop="keyword">
        <el-input
          v-model="form.keyword"
          placeholder="群 ID / 祝福语"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="红包类型" prop="packetType">
        <el-select v-model="form.packetType" placeholder="全部" clearable class="w-38!">
          <el-option label="全部" value="" />
          <el-option label="普通群红包" value="NORMAL_GROUP" />
          <el-option label="拼手气群红包" value="LUCKY_GROUP" />
          <el-option label="专属红包" value="EXCLUSIVE" />
          <el-option label="单聊红包" value="NORMAL_C2C" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="form.status" placeholder="全部" clearable class="w-32!">
          <el-option label="进行中" value="0" />
          <el-option label="已抢完" value="1" />
          <el-option label="已过期" value="2" />
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

    <PureTableBar title="红包记录" :columns="columns" @refresh="onSearch">
      <template #buttons />
      <template v-slot="{ size, dynamicColumns }">
        <pure-table
          ref="tableRef"
          row-key="orderNo"
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
