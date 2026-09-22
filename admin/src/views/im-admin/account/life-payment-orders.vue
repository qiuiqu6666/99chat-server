<script setup lang="ts">
import dayjs from "dayjs";
import { onMounted, reactive, ref } from "vue";
import { ElMessageBox } from "element-plus";
import { message } from "@/utils/message";
import { adminApiErrMessage } from "@/api/im-user";
import {
  getLifePaymentOrderLogs,
  getLifePaymentOrders,
  markLifePaymentOrderManual,
  type LifePaymentLogItem,
  type LifePaymentOrderItem
} from "@/api/im-life-payments";

defineOptions({ name: "ImLifePaymentOrders" });

const loading = ref(false);
const dataList = ref<LifePaymentOrderItem[]>([]);
const logVisible = ref(false);
const logLoading = ref(false);
const logItems = ref<LifePaymentLogItem[]>([]);
const currentOrderNo = ref("");

const query = reactive({
  user_uid: "",
  order_no: "",
  service_type: "",
  order_status: ""
});

const pagination = reactive({
  total: 0,
  pageSize: 20,
  currentPage: 1
});

const serviceLabel: Record<string, string> = {
  mobile: "手机充值",
  water: "水费",
  electric: "电费",
  gas: "燃气费"
};

const statusType = (status?: string | null) => {
  const s = String(status || "");
  if (s === "success") return "success";
  if (s === "failed" || s === "cancelled") return "danger";
  if (s === "need_manual" || s === "need_owner_last_char") return "warning";
  if (s === "running" || s === "processing" || s === "cashier_confirm") return "primary";
  return "info";
};

function formatTime(v?: string | null) {
  if (!v) return "—";
  const d = dayjs(v);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : v;
}

async function loadData() {
  loading.value = true;
  try {
    const res = await getLifePaymentOrders({
      page: pagination.currentPage,
      page_size: pagination.pageSize,
      user_uid: query.user_uid.trim() || undefined,
      order_no: query.order_no.trim() || undefined,
      service_type: query.service_type || undefined,
      order_status: query.order_status || undefined
    });
    dataList.value = res.items;
    pagination.total = res.total;
  } catch (err: unknown) {
    dataList.value = [];
    pagination.total = 0;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 wallet.read）", { type: "warning" });
    } else if (ax?.response?.status !== 401) {
      message(adminApiErrMessage(err, "生活缴费订单加载失败"), { type: "warning" });
    }
  } finally {
    loading.value = false;
  }
}

function resetQuery() {
  query.user_uid = "";
  query.order_no = "";
  query.service_type = "";
  query.order_status = "";
  pagination.currentPage = 1;
  loadData();
}

function onSizeChange(v: number) {
  pagination.pageSize = v;
  pagination.currentPage = 1;
  loadData();
}

function onCurrentChange(v: number) {
  pagination.currentPage = v;
  loadData();
}

async function openLogs(row: LifePaymentOrderItem) {
  currentOrderNo.value = row.order_no;
  logVisible.value = true;
  logLoading.value = true;
  try {
    const res = await getLifePaymentOrderLogs(row.order_no);
    logItems.value = res.items;
  } catch (err: unknown) {
    logItems.value = [];
    message(adminApiErrMessage(err, "日志加载失败"), { type: "warning" });
  } finally {
    logLoading.value = false;
  }
}

async function markManual(row: LifePaymentOrderItem) {
  try {
    const { value } = await ElMessageBox.prompt("请输入人工处理原因", "标记人工处理", {
      confirmButtonText: "确认",
      cancelButtonText: "取消",
      inputPlaceholder: "支付宝页面异常，需要人工核对",
      inputValue: "支付宝页面异常，需要人工核对"
    });
    await markLifePaymentOrderManual(row.order_no, value);
    message("已标记人工处理", { type: "success" });
    await loadData();
  } catch (err: unknown) {
    if (err === "cancel" || err === "close") return;
    const ax = err as { response?: { status?: number; data?: { error?: string } } };
    if (ax?.response?.status === 403 || ax?.response?.data?.error === "forbidden") {
      message("无权限（需要 user.write）", { type: "warning" });
      return;
    }
    message(adminApiErrMessage(err, "操作失败"), { type: "warning" });
  }
}

function canMarkManual(row: LifePaymentOrderItem) {
  const s = String(row.order_status || "");
  return !["success", "cancelled", "need_manual"].includes(s);
}

onMounted(() => loadData());
</script>

<template>
  <div class="flex flex-col gap-3 px-6 py-3">
    <el-card shadow="never">
      <el-form :inline="true" :model="query" class="search-form">
        <el-form-item label="UID">
          <el-input v-model="query.user_uid" clearable class="w-40!" placeholder="用户 UID" />
        </el-form-item>
        <el-form-item label="订单号">
          <el-input v-model="query.order_no" clearable class="w-48!" placeholder="order_no" />
        </el-form-item>
        <el-form-item label="业务">
          <el-select v-model="query.service_type" clearable placeholder="全部" class="w-36!">
            <el-option label="手机充值" value="mobile" />
            <el-option label="水费" value="water" />
            <el-option label="电费" value="electric" />
            <el-option label="燃气费" value="gas" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select v-model="query.order_status" clearable placeholder="全部" class="w-40!">
            <el-option label="已支付" value="paid" />
            <el-option label="执行中" value="running" />
            <el-option label="成功" value="success" />
            <el-option label="失败" value="failed" />
            <el-option label="人工处理" value="need_manual" />
            <el-option label="待补机主字" value="need_owner_last_char" />
            <el-option label="已取消" value="cancelled" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="loading" @click="pagination.currentPage = 1; loadData();">
            查询
          </el-button>
          <el-button @click="resetQuery">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table :data="dataList" border stripe v-loading="loading" row-key="order_no">
        <el-table-column prop="order_no" label="订单号" min-width="150" show-overflow-tooltip />
        <el-table-column prop="user_id" label="UID" min-width="110" show-overflow-tooltip />
        <el-table-column label="业务" width="100">
          <template #default="{ row }">
            {{ serviceLabel[row.service_type || ""] || row.service_type || "—" }}
          </template>
        </el-table-column>
        <el-table-column prop="account_no" label="账号/户号" min-width="130" show-overflow-tooltip />
        <el-table-column prop="provider_name" label="缴费单位" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.provider_name || "—" }}</template>
        </el-table-column>
        <el-table-column prop="amount" label="金额" width="90" />
        <el-table-column prop="pay_method" label="支付方式" width="100" />
        <el-table-column label="订单状态" width="120">
          <template #default="{ row }">
            <el-tag :type="statusType(row.order_status)" effect="plain">{{ row.order_status }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="plugin_status" label="插件状态" min-width="120" show-overflow-tooltip />
        <el-table-column prop="task_no" label="任务号" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.task_no || "—" }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="168">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="openLogs(row)">日志</el-button>
            <el-button
              v-if="canMarkManual(row)"
              link
              type="warning"
              size="small"
              @click="markManual(row)"
            >
              人工处理
            </el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="mt-3 flex justify-end">
        <el-pagination
          v-model:current-page="pagination.currentPage"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @size-change="onSizeChange"
          @current-change="onCurrentChange"
        />
      </div>
    </el-card>

    <el-drawer v-model="logVisible" :title="`订单日志 ${currentOrderNo}`" size="520px">
      <el-timeline v-loading="logLoading">
        <el-timeline-item
          v-for="(item, idx) in logItems"
          :key="idx"
          :timestamp="formatTime(item.created_at)"
          placement="top"
        >
          <div class="text-sm">
            <div>
              <el-tag size="small" effect="plain">{{ item.actor_type || "—" }}</el-tag>
              <span class="ml-2">{{ item.actor_id || "" }}</span>
            </div>
            <div class="mt-1 font-medium">{{ item.action }}</div>
            <div class="mt-1 text-gray-600">{{ item.message || "—" }}</div>
          </div>
        </el-timeline-item>
      </el-timeline>
      <el-empty v-if="!logLoading && !logItems.length" description="暂无日志" />
    </el-drawer>
  </div>
</template>
