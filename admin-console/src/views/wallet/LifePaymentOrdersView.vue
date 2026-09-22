<script setup lang="ts">
import { computed, onMounted, reactive, ref } from "vue";
import { ElMessage, ElMessageBox } from "element-plus";
import PageHeader from "@/components/PageHeader.vue";
import UserUidLink from "@/components/UserUidLink.vue";
import { failRefundOrder, listOrders, manualOrder } from "@/api/lifePayments";
import { amountText, pickList, str } from "@/utils/financeRows";
import { errMessage, formatTime } from "@/utils/format";
import { hasPerm } from "@/utils/perms";

const canWrite = computed(() => hasPerm("user.write"));
const loading = ref(false);
const actionLoading = ref("");
const rows = ref<Record<string, unknown>[]>([]);
const total = ref(0);
const query = reactive({
  page: 1,
  page_size: 20,
  user_uid: "",
  order_no: "",
  service_type: "",
  order_status: ""
});

const serviceOptions = [
  { label: "手机充值", value: "mobile" },
  { label: "水费", value: "water" },
  { label: "电费", value: "electric" },
  { label: "燃气费", value: "gas" }
];

const orderStatusOptions = [
  { label: "已支付", value: "paid" },
  { label: "执行中", value: "running" },
  { label: "成功", value: "success" },
  { label: "失败", value: "failed" },
  { label: "人工处理", value: "need_manual" },
  { label: "待补机主字", value: "need_owner_last_char" },
  { label: "已取消", value: "cancelled" }
];

function actionKey(orderNo: string, action: "manual" | "fail-refund") {
  return `${orderNo}:${action}`;
}

function orderStatusTag(status: string) {
  if (status === "success") return "success";
  if (status === "failed" || status === "cancelled") return "danger";
  if (status === "need_manual" || status === "need_owner_last_char") return "warning";
  if (status === "running" || status === "processing" || status === "cashier_confirm") return "primary";
  return "info";
}

function payStatusTag(status: string) {
  if (status === "paid") return "warning";
  if (status === "refunded") return "success";
  if (status === "failed") return "danger";
  return "info";
}

function canMarkManual(row: Record<string, unknown>) {
  const orderStatus = str(row, "order_status", "orderStatus");
  return !["success", "cancelled", "need_manual"].includes(orderStatus);
}

function canFailRefund(row: Record<string, unknown>) {
  const orderStatus = str(row, "order_status", "orderStatus");
  const payStatus = str(row, "platform_pay_status", "platformPayStatus");
  if (["success", "cancelled"].includes(orderStatus)) return false;
  return payStatus === "paid";
}

async function load() {
  loading.value = true;
  try {
    const raw = await listOrders({
      page: query.page,
      page_size: query.page_size,
      user_uid: query.user_uid || undefined,
      order_no: query.order_no || undefined,
      service_type: query.service_type || undefined,
      order_status: query.order_status || undefined
    });
    const picked = pickList(raw);
    rows.value = picked.items;
    total.value = picked.total;
  } catch (e) {
    rows.value = [];
    total.value = 0;
    ElMessage.warning(errMessage(e));
  } finally {
    loading.value = false;
  }
}

async function doManual(row: Record<string, unknown>) {
  const orderNo = str(row, "order_no", "orderNo");
  if (!orderNo) return;
  let reason = "";
  try {
    const { value } = await ElMessageBox.prompt("请填写人工处理原因", "人工处理", {
      inputPlaceholder: "支付宝页面异常，需要人工核对",
      inputValue: "支付宝页面异常，需要人工核对",
      confirmButtonText: "确认",
      cancelButtonText: "取消"
    });
    reason = String(value || "").trim();
  } catch {
    return;
  }
  actionLoading.value = actionKey(orderNo, "manual");
  try {
    await manualOrder(orderNo, { reason });
    ElMessage.success("已标记人工处理");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = "";
  }
}

async function doFailRefund(row: Record<string, unknown>) {
  const orderNo = str(row, "order_no", "orderNo");
  const userId = str(row, "user_id", "userId");
  if (!orderNo) return;
  try {
    await ElMessageBox.confirm(
      `确认将订单 ${orderNo}（UID ${userId || "—"}，金额 ${amountText(row.amount)}）标记失败并原路退款？此操作不可撤销。`,
      "失败并退款",
      { type: "warning", confirmButtonText: "下一步", cancelButtonText: "取消" }
    );
  } catch {
    return;
  }
  let reason = "";
  try {
    const { value } = await ElMessageBox.prompt("请填写失败退款原因", "失败并退款", {
      inputPlaceholder: "Worker 超时未处理，运营人工关闭",
      inputValue: "Worker 超时未处理，运营人工关闭",
      confirmButtonText: "确认退款",
      cancelButtonText: "取消"
    });
    reason = String(value || "").trim();
  } catch {
    return;
  }
  actionLoading.value = actionKey(orderNo, "fail-refund");
  try {
    await failRefundOrder(orderNo, { reason });
    ElMessage.success("已标记失败并完成退款");
    await load();
  } catch (e) {
    ElMessage.error(errMessage(e));
  } finally {
    actionLoading.value = "";
  }
}

onMounted(load);
</script>

<template>
  <div class="page">
    <PageHeader title="缴费订单" />
    <div class="page-card">
      <div class="toolbar">
        <el-input v-model="query.user_uid" clearable placeholder="用户 UID" style="width: 160px" @keyup.enter="query.page=1; load()" />
        <el-input v-model="query.order_no" clearable placeholder="订单号" style="width: 180px" @keyup.enter="query.page=1; load()" />
        <el-select v-model="query.service_type" clearable placeholder="服务类型" style="width: 140px">
          <el-option v-for="item in serviceOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-select v-model="query.order_status" clearable placeholder="订单状态" style="width: 140px">
          <el-option v-for="item in orderStatusOptions" :key="item.value" :label="item.label" :value="item.value" />
        </el-select>
        <el-button type="primary" @click="query.page=1; load()">查询</el-button>
      </div>
      <el-table :data="rows" v-loading="loading" stripe border>
        <el-table-column label="订单号" min-width="150">
          <template #default="{ row }">{{ str(row, "order_no", "orderNo") || "—" }}</template>
        </el-table-column>
        <el-table-column label="用户" min-width="130">
          <template #default="{ row }">
            <UserUidLink :uid="str(row, 'user_id', 'userId')" />
          </template>
        </el-table-column>
        <el-table-column label="服务" width="110">
          <template #default="{ row }">{{ str(row, "service_type", "serviceType") || "—" }}</template>
        </el-table-column>
        <el-table-column label="金额" width="100">
          <template #default="{ row }">{{ amountText(row.amount) }}</template>
        </el-table-column>
        <el-table-column label="订单状态" width="120">
          <template #default="{ row }">
            <el-tag v-if="str(row, 'order_status', 'orderStatus')" :type="orderStatusTag(str(row, 'order_status', 'orderStatus'))" effect="plain">
              {{ str(row, "order_status", "orderStatus") }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="支付状态" width="110">
          <template #default="{ row }">
            <el-tag v-if="str(row, 'platform_pay_status', 'platformPayStatus')" :type="payStatusTag(str(row, 'platform_pay_status', 'platformPayStatus'))" effect="plain">
              {{ str(row, "platform_pay_status", "platformPayStatus") }}
            </el-tag>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="执行状态" width="110">
          <template #default="{ row }">{{ str(row, "execution_status", "executionStatus") || "—" }}</template>
        </el-table-column>
        <el-table-column label="供应商" min-width="120" show-overflow-tooltip>
          <template #default="{ row }">{{ str(row, "provider_name", "providerName") || "—" }}</template>
        </el-table-column>
        <el-table-column label="城市" width="100">
          <template #default="{ row }">{{ str(row, "city_name", "cityName") || "—" }}</template>
        </el-table-column>
        <el-table-column label="任务号" min-width="130">
          <template #default="{ row }">{{ str(row, "task_no", "taskNo") || "—" }}</template>
        </el-table-column>
        <el-table-column label="创建时间" min-width="160">
          <template #default="{ row }">{{ formatTime(row.created_at || row.createdAt) }}</template>
        </el-table-column>
        <el-table-column v-if="canWrite" label="操作" width="190" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="canMarkManual(row)"
              type="warning"
              link
              :loading="actionLoading === actionKey(str(row, 'order_no', 'orderNo'), 'manual')"
              @click="doManual(row)"
            >人工处理</el-button>
            <el-button
              v-if="canFailRefund(row)"
              type="danger"
              link
              :loading="actionLoading === actionKey(str(row, 'order_no', 'orderNo'), 'fail-refund')"
              @click="doFailRefund(row)"
            >失败退款</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div style="margin-top: 12px; display: flex; justify-content: flex-end">
        <el-pagination
          v-model:current-page="query.page"
          v-model:page-size="query.page_size"
          :total="total"
          layout="total, prev, pager, next"
          @current-change="load"
        />
      </div>
    </div>
  </div>
</template>
