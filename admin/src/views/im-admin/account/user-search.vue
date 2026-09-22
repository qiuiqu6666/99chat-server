<script setup lang="ts">
import { ref } from "vue";
import { PureTableBar } from "@/components/RePureTableBar";
import { useRenderIcon } from "@/components/ReIcon/src/hooks";
import Refresh from "~icons/ep/refresh";
import View from "~icons/ep/view";
import Wallet from "~icons/ep/wallet-filled";
import Key from "~icons/ep/key";
import EditPen from "~icons/ep/edit-pen";
import Lock from "~icons/ep/lock";
import Plus from "~icons/ep/plus";
import FolderOpened from "~icons/ep/folder-opened";
import More from "~icons/ep/more-filled";
import CircleCloseFilled from "~icons/ep/circle-close-filled";
import CircleCheckFilled from "~icons/ep/circle-check-filled";
import { useImUserList } from "./utils/useImUserList";

defineOptions({
  name: "ImAccountUserSearch"
});

const tableRef = ref();
const formRef = ref();

const {
  form,
  loading,
  columns,
  dataList,
  pagination,
  deviceDetection,
  onSearch,
  resetForm,
  handleSizeChange,
  handleCurrentChange,
  handleViewDetail,
  handleViewPrivacy,
  openAdjustBalance,
  openEditNickname,
  openResetLoginPassword,
  openResetTransactionPassword,
  confirmToggleLoginDisabled,
  openCreateUsersByCount,
  canUserWrite
} = useImUserList(tableRef);

function runRowAction(command: string, row: Record<string, unknown>) {
  switch (command) {
    case "detail":
      handleViewDetail(row);
      break;
    case "privacy":
      handleViewPrivacy(row);
      break;
    case "toggleLogin":
      confirmToggleLoginDisabled(row);
      break;
    case "nickname":
      openEditNickname(row);
      break;
    case "loginPwd":
      openResetLoginPassword(row);
      break;
    case "fundPwd":
      openResetTransactionPassword(row);
      break;
    case "balance":
      openAdjustBalance(row);
      break;
  }
}
</script>

<template>
  <div :class="[deviceDetection() ? 'flex flex-col gap-3' : '']">
    <el-form
      ref="formRef"
      :inline="true"
      :model="form"
      class="search-form bg-bg_color w-full pl-8 pt-3 overflow-auto"
    >
      <el-form-item label="IM 号" prop="uid">
        <el-input
          v-model="form.uid"
          placeholder="IM 号，支持模糊"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="昵称" prop="nickname">
        <el-input
          v-model="form.nickname"
          placeholder="昵称关键字"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="手机号" prop="phone">
        <el-input
          v-model="form.phone"
          placeholder="手机号片段"
          clearable
          class="w-45!"
        />
      </el-form-item>
      <el-form-item label="在线" prop="isOnline">
        <el-select v-model="form.isOnline" placeholder="全部" clearable class="w-45!">
          <el-option label="在线" value="1" />
          <el-option label="离线" value="0" />
        </el-select>
      </el-form-item>
      <el-form-item label="状态" prop="status">
        <el-select v-model="form.status" placeholder="全部" clearable class="w-45!">
          <el-option label="正常" value="1" />
          <el-option label="禁用" value="0" />
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

    <PureTableBar title="用户列表" :columns="columns" @refresh="onSearch">
      <template #buttons>
        <el-button
          v-if="canUserWrite"
          type="primary"
          :icon="useRenderIcon(Plus)"
          @click="openCreateUsersByCount"
        >
          创建用户
        </el-button>
      </template>
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
        >
          <template #operation="{ row }">
            <el-button
              class="reset-margin"
              link
              type="primary"
              :size="size"
              :icon="useRenderIcon(View)"
              @click="handleViewDetail(row)"
            >
              详情
            </el-button>
            <el-dropdown
              trigger="click"
              @command="(cmd: string) => runRowAction(cmd, row)"
            >
              <el-button
                class="reset-margin ml-1"
                link
                type="primary"
                :size="size"
                :icon="useRenderIcon(More)"
              >
                更多
              </el-button>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="privacy" :icon="useRenderIcon(FolderOpened)">
                    隐私稽查
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="canUserWrite && row.userStatusRaw === 1"
                    command="toggleLogin"
                    :icon="useRenderIcon(CircleCloseFilled)"
                  >
                    禁用登录
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="canUserWrite && row.userStatusRaw !== 1"
                    command="toggleLogin"
                    :icon="useRenderIcon(CircleCheckFilled)"
                  >
                    允许登录
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="canUserWrite"
                    command="nickname"
                    :icon="useRenderIcon(EditPen)"
                  >
                    修改昵称
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="canUserWrite"
                    command="loginPwd"
                    :icon="useRenderIcon(Key)"
                  >
                    重置密码
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="canUserWrite"
                    command="fundPwd"
                    :icon="useRenderIcon(Lock)"
                  >
                    重置资金密码
                  </el-dropdown-item>
                  <el-dropdown-item
                    v-if="canUserWrite"
                    command="balance"
                    :icon="useRenderIcon(Wallet)"
                  >
                    调整余额
                  </el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
        </pure-table>
      </template>
    </PureTableBar>
  </div>
</template>
