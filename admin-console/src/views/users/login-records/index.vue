<script setup lang="ts">
import { computed, h, reactive } from 'vue';
import { useRoute } from 'vue-router';
import { NButton, NTag } from 'naive-ui';
import type { PaginationData } from '@sa/hooks';
import UserUidLink from '@/components/business/user-uid-link.vue';
import { useNaivePaginatedTable } from '@/hooks/common/table';
import { useAppStore } from '@/store/modules/app';
import { listLoginLogs } from '@/service/api/users';
import { displayDeviceModel } from '@/utils/deviceModel';

defineOptions({ name: 'users_login-records' });

type Row = Record<string, unknown>;

const route = useRoute();
const appStore = useAppStore();

const platformOptions = [
  { label: '安卓', value: 0 },
  { label: '苹果', value: 1 },
  { label: '电脑', value: 2 }
];

const searchParams = reactive({
  user_uid: String(route.query.user_uid || route.query.user_a || ''),
  login_ip: '',
  device_type: null as number | null,
  range: null as [number, number] | null,
  page: 1,
  page_size: 50
});

function textOf(value: unknown) {
  const text = String(value ?? '').trim();
  return text || '—';
}

function platformKey(row: Row) {
  const kind = Number(row.device_type);
  if (kind === 0) return 'android';
  if (kind === 1) return 'ios';
  if (kind === 2) return 'web';
  return null;
}

function platformOf(row: Row) {
  const kind = Number(row.device_type);
  if (kind === 0) return '安卓';
  if (kind === 1) return '苹果';
  if (kind === 2) return '电脑';
  return '—';
}

const LOGIN_RESULT: Record<string, string> = {
  need_sms: '需要短信验证',
  bad_credentials: '账号或密码错误',
  account_disabled: '账号已禁用',
  sms_code_invalid: '短信验证码错误',
  user_not_found: '账号不存在',
  challenge_expired: '验证已过期，请重新登录'
};

function resultOf(row: Row) {
  const text = String(row.status ?? '').trim();
  if (!text) return { label: '—', type: 'default' as const };
  const label = LOGIN_RESULT[text.toLowerCase()] || text;
  if (label === '成功') return { label: '成功', type: 'success' as const };
  return { label, type: 'error' as const };
}

function dayOf(ts: number) {
  const date = new Date(ts);
  const month = String(date.getMonth() + 1).padStart(2, '0');
  const day = String(date.getDate()).padStart(2, '0');
  return `${date.getFullYear()}-${month}-${day}`;
}

function transform(raw: unknown): PaginationData<Row> {
  const obj = (raw || {}) as Row;
  const data = obj.data && typeof obj.data === 'object' && !Array.isArray(obj.data) ? (obj.data as Row) : obj;
  const items = (data.items || data.list || data.rows || []) as Row[];
  const pageSize = Number(data.page_size ?? data.pageSize ?? searchParams.page_size) || searchParams.page_size;
  return {
    data: Array.isArray(items) ? items : [],
    pageNum: Number(data.page ?? searchParams.page) || 1,
    pageSize,
    total: Number(data.total ?? 0) || 0
  };
}

const { columns, columnChecks, data, loading, mobilePagination, getData, getDataByPage } = useNaivePaginatedTable<
  unknown,
  Row
>({
  api: () =>
    listLoginLogs({
      page: searchParams.page,
      page_size: searchParams.page_size,
      user_uid: searchParams.user_uid.trim() || undefined,
      login_ip: searchParams.login_ip.trim() || undefined,
      device_type: searchParams.device_type ?? undefined,
      login_time_from: searchParams.range ? dayOf(searchParams.range[0]) : undefined,
      login_time_to: searchParams.range ? dayOf(searchParams.range[1]) : undefined,
      sort: 'login_time_desc'
    }),
  transform,
  paginationProps: { size: 'small', pageSize: 50, pageSizes: [50, 100] },
  onPaginationParamsChange(params) {
    searchParams.page = params.page ?? 1;
    searchParams.page_size = params.pageSize ?? 50;
  },
  columns: () => [
    { title: 'IM 号', key: 'user_uid', width: 140, render: row => h(UserUidLink, { uid: String(row.user_uid ?? '') }) },
    { title: '昵称', key: 'user_nickname', minWidth: 120, ellipsis: { tooltip: true }, render: row => textOf(row.user_nickname) },
    { title: '平台', key: 'device_type', width: 80, align: 'center', render: row => platformOf(row) },
    {
      title: '机型',
      key: 'device_model',
      minWidth: 160,
      ellipsis: { tooltip: true },
      render: row => displayDeviceModel(platformKey(row), row.device_model || row.device_info)
    },
    { title: '版本', key: 'client_version', width: 100, ellipsis: { tooltip: true }, render: row => textOf(row.client_version) },
    {
      title: '登录 IP',
      key: 'login_ip',
      width: 150,
      render: row => {
        const ip = String(row.login_ip ?? '').trim();
        if (!ip) return '—';
        return h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => filterIp(ip) }, { default: () => ip });
      }
    },
    { title: '登录时间', key: 'login_time2', width: 170, render: row => textOf(row.login_time2) },
    {
      title: '结果',
      key: 'status',
      width: 120,
      ellipsis: { tooltip: true },
      render: row => {
        const result = resultOf(row);
        return h(NTag, { size: 'small', bordered: false, type: result.type }, { default: () => result.label });
      }
    },
    { title: '设备号', key: 'device_token_masked', width: 160, ellipsis: { tooltip: true }, render: row => textOf(row.device_token_masked) },
    { title: '设备信息', key: 'device_info', minWidth: 220, ellipsis: { tooltip: true }, render: row => textOf(row.device_info) }
  ]
});

const hiddenByDefault = new Set(['device_token_masked', 'device_info']);
columnChecks.value.forEach(column => {
  if (hiddenByDefault.has(column.key)) column.checked = false;
});

const scrollX = computed(() => columns.value.reduce((sum, column) => sum + Number(column.width ?? column.minWidth ?? 120), 0));

function filterIp(ip: string) {
  searchParams.login_ip = ip;
  getDataByPage();
}

function resetSearch() {
  searchParams.user_uid = '';
  searchParams.login_ip = '';
  searchParams.device_type = null;
  searchParams.range = null;
  getDataByPage();
}
</script>

<template>
  <div class="h-full min-h-0 flex-col-stretch gap-12px overflow-hidden lt-sm:overflow-auto">
    <NCard :bordered="false" size="small" class="card-wrapper">
      <NForm inline size="small" :show-feedback="false" label-placement="left">
        <NFormItem label="IM 号">
          <NInput v-model:value="searchParams.user_uid" clearable placeholder="用户 IM 号" class="w-160px" @keyup.enter="getDataByPage()" />
        </NFormItem>
        <NFormItem label="登录 IP">
          <NInput v-model:value="searchParams.login_ip" clearable placeholder="精确 IP" class="w-160px" @keyup.enter="getDataByPage()" />
        </NFormItem>
        <NFormItem label="平台">
          <NSelect v-model:value="searchParams.device_type" clearable placeholder="全部" :options="platformOptions" class="w-120px" />
        </NFormItem>
        <NFormItem label="时间">
          <NDatePicker v-model:value="searchParams.range" type="daterange" clearable class="w-260px" />
        </NFormItem>
        <NFormItem>
          <NSpace :size="8">
            <NButton size="small" type="primary" @click="getDataByPage()">查询</NButton>
            <NButton size="small" @click="resetSearch">重置</NButton>
          </NSpace>
        </NFormItem>
      </NForm>
    </NCard>
    <NCard :bordered="false" size="small" title="登录记录" class="card-wrapper min-h-0 flex-1 flex-col" content-class="min-h-0 flex-1 overflow-hidden">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @refresh="getData" />
      </template>
      <NDataTable
        remote
        bordered
        striped
        :single-line="false"
        size="small"
        :flex-height="!appStore.isMobile"
        :class="appStore.isMobile ? undefined : 'h-full'"
        :columns="columns"
        :data="data"
        :loading="loading"
        :row-key="row => String(row.history_id ?? row.login_time ?? '')"
        :pagination="mobilePagination"
        :scroll-x="scrollX"
      />
    </NCard>
  </div>
</template>
