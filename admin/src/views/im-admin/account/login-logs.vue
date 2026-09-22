<script setup lang="ts">
import { ref, onMounted } from "vue";
import { useRoute } from "vue-router";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import { deviceDetection } from "@pureadmin/utils";
import { useLoginLogs } from "./utils/useLoginLogs";

defineOptions({
  name: "ImAccountLoginLogs"
});

const tableRef = ref();
const formRef = ref();
const route = useRoute();

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
} = useLoginLogs(tableRef);

onMounted(() => {
  const uid = String(route.query.user_uid ?? "").trim();
  if (uid) {
    form.userUid = uid;
    void onSearch();
  }
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
      <el-form-item label="用户 UID" prop="userUid">
        <el-input
          v-model="form.userUid"
          placeholder="可选"
          clearable
          class="w-40!"
        />
      </el-form-item>
      <el-form-item label="登录 IP" prop="loginIp">
        <el-input
          v-model="form.loginIp"
          placeholder="精确匹配"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="起始日期" prop="loginTimeFrom">
        <el-date-picker
          v-model="form.loginTimeFrom"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="login_time_from"
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="结束日期" prop="loginTimeTo">
        <el-date-picker
          v-model="form.loginTimeTo"
          type="date"
          value-format="YYYY-MM-DD"
          placeholder="login_time_to"
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="终端类型" prop="deviceType">
        <el-select v-model="form.deviceType" placeholder="全部" clearable class="w-38!">
          <el-option label="未定义 -1" value="-1" />
          <el-option label="Android 0" value="0" />
          <el-option label="iOS 1" value="1" />
          <el-option label="Web 2" value="2" />
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

    <PureTableBar title="登录日志" :columns="columns" @refresh="onSearch">
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
