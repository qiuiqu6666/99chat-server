<script setup lang="ts">
import { ref } from "vue";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import { deviceDetection } from "@pureadmin/utils";
import { useRechargeWithdrawLogs } from "./utils/useRechargeWithdrawLogs";
import WithdrawAuditTotpSetup from "./withdraw-audit-totp-setup.vue";

defineOptions({
  name: "ImAccountRechargeWithdrawLogs"
});

const tableRef = ref();
const formRef = ref();
const totpSetupRef = ref<InstanceType<typeof WithdrawAuditTotpSetup> | null>(null);

const {
  form,
  loading,
  columns,
  dataList,
  pagination,
  pageTitle,
  isWithdrawAudit,
  onSearch,
  resetForm,
  handleSizeChange,
  handleCurrentChange
} = useRechargeWithdrawLogs(tableRef, totpSetupRef);
</script>

<template>
  <div :class="[deviceDetection() ? 'flex flex-col gap-3' : '']">
    <WithdrawAuditTotpSetup
      v-if="isWithdrawAudit"
      ref="totpSetupRef"
      @configured="onSearch"
    />
    <el-form
      ref="formRef"
      :inline="true"
      :model="form"
      class="search-form bg-bg_color w-full pl-8 pt-3 overflow-auto"
    >
      <el-form-item label="IM号/昵称" prop="uid">
        <el-input
          v-model="form.uid"
          placeholder="精确或模糊"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="关键字" prop="keyword">
        <el-input
          v-model="form.keyword"
          placeholder="渠道 / 三方单号 / 业务单号"
          clearable
          class="w-55!"
        />
      </el-form-item>
      <el-form-item label="类型" prop="bizType">
        <el-select v-model="form.bizType" placeholder="全部" clearable :disabled="pageTitle === '充值记录' || pageTitle === '提现审核'" class="w-28!">
          <el-option label="充值" value="充值" />
          <el-option label="提现" value="提现" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="form.status" placeholder="全部" clearable class="w-32!">
          <template v-if="pageTitle === '提现审核'">
            <el-option label="待审核" value="待审核" />
            <el-option label="已通过" value="已通过" />
            <el-option label="已拒绝" value="已拒绝" />
            <el-option label="处理中" value="处理中" />
            <el-option label="失败" value="失败" />
          </template>
          <template v-else>
            <el-option label="成功" value="成功" />
            <el-option label="处理中" value="处理中" />
            <el-option label="失败" value="失败" />
          </template>
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

    <PureTableBar :title="pageTitle" :columns="columns" @refresh="onSearch">
      <template #buttons />
      <template v-slot="{ size, dynamicColumns }">
        <pure-table
          ref="tableRef"
          row-key="bizNo"
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
