<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useRouter } from "vue-router";
import PageHeader from "@/components/PageHeader.vue";
import { getDashboardOverview, type DashboardStats } from "@/api/dashboard";
import { errMessage } from "@/utils/format";

const router = useRouter();
const loading = ref(false);
const error = ref("");
const stats = ref<DashboardStats>({});

const todoItems = computed(() => [
  {
    label: "待审核提现",
    value: Number(stats.value.pending_withdraw_count ?? 0),
    path: "/wallet/withdraws"
  },
  {
    label: "待处理投诉",
    value: Number(stats.value.pending_complaint_count ?? 0),
    path: "/risk/complaints"
  },
  {
    label: "待处理反馈",
    value: Number(stats.value.pending_feedback_count ?? 0),
    path: "/ops/feedback"
  },
  {
    label: "缴费异常",
    value: Number(stats.value.life_payment_abnormal_count ?? 0),
    path: "/wallet/life-payments/orders"
  }
]);

const metricGroups = computed(() => [
  {
    title: "用户与活跃",
    items: [
      { label: "用户总数", value: stats.value.user_total ?? 0, path: "/users" },
      { label: "今日新增", value: stats.value.registered_today ?? 0, path: "/users" },
      {
        label: "今日活跃",
        value: stats.value.active_today ?? stats.value.login_today ?? 0,
        path: "/users/login-records"
      },
      { label: "当前在线", value: stats.value.online_users ?? 0 }
    ]
  },
  {
    title: "群组与消息",
    items: [
      { label: "群组总数", value: stats.value.group_total ?? 0, path: "/risk/groups" },
      { label: "今日新群", value: stats.value.group_created_today ?? 0, path: "/risk/groups" },
      { label: "今日消息", value: stats.value.message_today ?? 0, path: "/risk/messages" },
      {
        label: "单聊 / 群聊",
        value: `${stats.value.c2c_message_today ?? 0} / ${stats.value.group_message_today ?? 0}`
      }
    ]
  },
  {
    title: "资金（今日）",
    items: [
      { label: "充值", value: stats.value.recharge_amount_today ?? "0", path: "/wallet/recharges" },
      { label: "提现", value: stats.value.withdraw_amount_today ?? "0", path: "/wallet/withdraws" },
      { label: "红包", value: stats.value.red_packet_amount_today ?? "0", path: "/wallet/red-packets" },
      { label: "转账", value: stats.value.transfer_amount_today ?? "0", path: "/wallet/transfers" }
    ]
  }
]);

async function load() {
  loading.value = true;
  error.value = "";
  try {
    const res = await getDashboardOverview();
    stats.value = res.stats || {};
  } catch (e) {
    error.value = errMessage(e, "看板加载失败");
  } finally {
    loading.value = false;
  }
}

onMounted(load);
</script>

<template>
  <div class="page" v-loading="loading">
    <PageHeader title="工作台总览" subtitle="待办优先，指标一览" />
    <el-alert v-if="error" :title="error" type="warning" show-icon class="mb" />

    <div class="page-card mb">
      <h3 class="sec">待办</h3>
      <div class="todo-grid">
        <div
          v-for="t in todoItems"
          :key="t.path"
          class="todo"
          :class="{ hot: t.value > 0 }"
          @click="router.push(t.path)"
        >
          <div class="todo-label">{{ t.label }}</div>
          <div class="todo-value">{{ t.value }}</div>
        </div>
      </div>
    </div>

    <div v-for="g in metricGroups" :key="g.title" class="page-card mb">
      <h3 class="sec">{{ g.title }}</h3>
      <div class="metric-grid">
        <div
          v-for="m in g.items"
          :key="m.label"
          class="metric"
          :class="{ link: !!m.path }"
          @click="m.path && router.push(m.path)"
        >
          <div class="metric-label">{{ m.label }}</div>
          <div class="metric-value">{{ m.value }}</div>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.mb { margin-bottom: 12px; }
.sec { margin: 0 0 12px; font-size: 15px; }
.todo-grid, .metric-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(160px, 1fr));
  gap: 10px;
}
.todo, .metric {
  background: #f8fafc;
  border-radius: 8px;
  padding: 12px 14px;
  border: 1px solid #e2e8f0;
}
.todo { cursor: pointer; }
.todo.hot { border-color: #f59e0b; background: #fffbeb; }
.todo-label, .metric-label { color: #64748b; font-size: 12px; }
.todo-value, .metric-value { margin-top: 6px; font-size: 22px; font-weight: 700; }
.metric.link { cursor: pointer; }
.metric.link:hover { border-color: #38bdf8; }
</style>
