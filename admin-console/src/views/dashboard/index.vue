<script setup lang="ts">
import { computed, onMounted, ref } from 'vue';
import { useRouter } from 'vue-router';
import PageHeader from '@/components/business/page-header.vue';
import { getDashboardOverview, type DashboardStats } from '@/service/api/dashboard';
import { errMessage } from '@/utils/format';

defineOptions({ name: 'dashboard' });
const router = useRouter();
const loading = ref(false);
const error = ref('');
const stats = ref<DashboardStats>({});
const todos = computed(() => [
  { label: '待审核提现', value: Number(stats.value.pending_withdraw_count ?? 0), path: '/wallet/withdraws' },
  { label: '待处理投诉', value: Number(stats.value.pending_complaint_count ?? 0), path: '/risk/complaints' },
  { label: '待处理反馈', value: Number(stats.value.pending_feedback_count ?? 0), path: '/ops/feedback' },
  { label: '缴费异常', value: Number(stats.value.life_payment_abnormal_count ?? 0), path: '/wallet/life-payments/orders' }
]);
const groups = computed(() => [
  { title: '用户与活跃', items: [
    { label: '用户总数', value: stats.value.user_total ?? 0, path: '/users' },
    { label: '今日新增', value: stats.value.registered_today ?? 0, path: '/users' },
    { label: '今日活跃', value: stats.value.active_today ?? stats.value.login_today ?? 0, path: '/users/login-records' },
    { label: '当前在线', value: stats.value.online_users ?? 0 }
  ]},
  { title: '群组与消息', items: [
    { label: '群组总数', value: stats.value.group_total ?? 0, path: '/risk/groups' },
    { label: '今日新群', value: stats.value.group_created_today ?? 0, path: '/risk/groups' },
    { label: '今日消息', value: stats.value.message_today ?? 0, path: '/risk/messages' },
    { label: '单聊 / 群聊', value: `${stats.value.c2c_message_today ?? 0} / ${stats.value.group_message_today ?? 0}` }
  ]},
  { title: '资金（今日）', items: [
    { label: '充值', value: stats.value.recharge_amount_today ?? '0', path: '/wallet/recharges' },
    { label: '提现', value: stats.value.withdraw_amount_today ?? '0', path: '/wallet/withdraws' },
    { label: '红包', value: stats.value.red_packet_amount_today ?? '0', path: '/wallet/red-packets' },
    { label: '转账', value: stats.value.transfer_amount_today ?? '0', path: '/wallet/transfers' }
  ]}
]);
async function load() {
  loading.value = true;
  error.value = '';
  try {
    const res = await getDashboardOverview();
    stats.value = res.stats || {};
  } catch (e) {
    error.value = errMessage(e, '看板加载失败');
  } finally {
    loading.value = false;
  }
}
onMounted(load);
function go(path?: string) {
  if (path) router.push(path);
}
</script>
<template>
  <div class="p-16px">
    <PageHeader title="工作台总览" subtitle="待办优先，指标一览" />
    <NAlert v-if="error" type="error" class="mb-12px">{{ error }}</NAlert>
    <NSpin :show="loading">
      <NGrid :cols="4" :x-gap="12" :y-gap="12">
        <NGi v-for="item in todos" :key="item.label">
          <NCard hoverable @click="go(item.path)"><div class="text-13px text-#64748b">{{ item.label }}</div><div class="text-24px font-700">{{ item.value }}</div></NCard>
        </NGi>
      </NGrid>
      <NGrid class="mt-16px" :cols="3" :x-gap="12">
        <NGi v-for="g in groups" :key="g.title">
          <NCard :title="g.title">
            <div v-for="it in g.items" :key="it.label" class="mb-8px flex cursor-pointer justify-between" @click="go(it.path)">
              <span>{{ it.label }}</span><b>{{ it.value }}</b>
            </div>
          </NCard>
        </NGi>
      </NGrid>
    </NSpin>
  </div>
</template>
