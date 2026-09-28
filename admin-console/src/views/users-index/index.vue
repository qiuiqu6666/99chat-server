<script setup lang="ts">
import { computed, h, reactive, ref } from 'vue';
import { NAvatar, NButton, NEllipsis, NPopconfirm, NSpace, NTag } from 'naive-ui';
import type { PaginationData } from '@sa/hooks';
import UserUidLink from '@/components/business/user-uid-link.vue';
import { useNaivePaginatedTable } from '@/hooks/common/table';
import { useAppStore } from '@/store/modules/app';
import { createGenerationTask, createUser, listUsers, setLoginDisabled } from '@/service/api/users';
import { hasPerm } from '@/utils/perms';
import { displayDeviceModel } from '@/utils/deviceModel';
import { errMessage } from '@/utils/format';
import { randomAccountPassword } from '@/utils/randomPassword';

defineOptions({ name: 'users-index' });

type UserRow = Record<string, unknown>;

const DEFAULT_AVATAR = 'https://99chat.oss-cn-hongkong.aliyuncs.com/moren/default_c2c_head.png';

const appStore = useAppStore();
const canWrite = computed(() => hasPerm('user.write'));
const searchParams = reactive({
  keyword: '',
  status: null as string | null,
  online: null as string | null,
  page: 1,
  page_size: 50
});
const createVisible = ref(false);
const genVisible = ref(false);
const busy = ref(false);
const createForm = reactive({ nickname: '', password: '', sex: '' });
const genForm = reactive({ count: 10, sex: '' });

const statusOptions = [
  { label: '正常', value: '1' },
  { label: '禁用', value: '0' },
  { label: '注销', value: '-2' }
];
const onlineOptions = [
  { label: '在线', value: '1' },
  { label: '离线', value: '0' }
];

function uidOf(row: UserRow) {
  return String(row.user_uid ?? row.userUid ?? row.uid ?? '');
}

function textOf(value: unknown) {
  const text = String(value ?? '').trim();
  return text || '—';
}

function avatarOf(row: UserRow) {
  const raw = String(row.user_avatar_file_name ?? '').trim();
  if (/^https?:\/\//i.test(raw) || raw.startsWith('//')) return raw;
  return DEFAULT_AVATAR;
}

function statusOf(row: UserRow) {
  const raw = Number(row.user_status);
  if (raw === 1) return { label: '正常', type: 'success' as const, disabled: false };
  if (raw === 0) return { label: '禁用', type: 'error' as const, disabled: true };
  if (raw === -2) return { label: '注销', type: 'warning' as const, disabled: true };
  return { label: '未知', type: 'default' as const, disabled: false };
}

function platformKey(row: UserRow) {
  const kind = Number(row.device_type);
  if (kind === 0) return 'android';
  if (kind === 1) return 'ios';
  if (kind === 2) return 'web';
  return null;
}

function platformOf(row: UserRow) {
  const kind = Number(row.device_type);
  if (kind === 0) return '安卓';
  if (kind === 1) return '苹果';
  if (kind === 2) return '电脑';
  return '—';
}

function modelOf(row: UserRow) {
  return displayDeviceModel(platformKey(row), row.device_model);
}

function balanceOf(row: UserRow, code: 'USDT' | 'CNY') {
  const map = row.wallet_balances;
  if (map && typeof map === 'object' && !Array.isArray(map)) {
    const value = (map as Record<string, unknown>)[code];
    if (value != null && value !== '') return String(value);
  }
  if (code === 'USDT' && row.wallet_balance != null && row.wallet_balance !== '') return String(row.wallet_balance);
  return '0.00';
}

function addressOf(row: UserRow) {
  return String(row.trx_address ?? row.deposit_address ?? '').trim();
}

function countOf(value: unknown) {
  return typeof value === 'number' ? String(value) : '—';
}

function transformUsers(raw: unknown): PaginationData<UserRow> {
  const obj = (raw || {}) as Record<string, unknown>;
  const data =
    obj.data && typeof obj.data === 'object' && !Array.isArray(obj.data) ? (obj.data as Record<string, unknown>) : obj;
  const items = (data.items || data.list || data.rows || []) as UserRow[];
  const pageSize = Number(data.page_size ?? data.pageSize ?? searchParams.page_size) || searchParams.page_size;
  return {
    data: Array.isArray(items) ? items : [],
    pageNum: Number(data.page ?? searchParams.page) || 1,
    pageSize,
    total: Number(data.total ?? 0) || 0
  };
}

async function applyToggle(row: UserRow) {
  const userUid = uidOf(row);
  if (!userUid || !canWrite.value || Number(row.user_status) === -2) return;
  const disabled = !statusOf(row).disabled;
  try {
    await setLoginDisabled({ user_uid: userUid, disabled });
    window.$message?.success(disabled ? '已禁用' : '已启用');
    getData();
  } catch (e) {
    window.$message?.error(errMessage(e));
  }
}

async function copyAddress(address: string) {
  try {
    await navigator.clipboard.writeText(address);
    window.$message?.success('TRX 地址已复制');
  } catch (e) {
    window.$message?.error(errMessage(e, '复制失败'));
  }
}

const { columns, columnChecks, data, loading, mobilePagination, getData, getDataByPage } = useNaivePaginatedTable<
  unknown,
  UserRow
>({
  api: () =>
    listUsers({
      page: searchParams.page,
      page_size: searchParams.page_size,
      keyword: searchParams.keyword.trim() || undefined,
      status: searchParams.status || undefined,
      is_online: searchParams.online || undefined,
      sort: 'register_time_desc'
    }),
  transform: transformUsers,
  paginationProps: { size: 'small', pageSize: 50, pageSizes: [50, 100] },
  onPaginationParamsChange(params) {
    searchParams.page = params.page ?? 1;
    searchParams.page_size = params.pageSize ?? 50;
  },
  columns: () => [
    { title: 'IM 号', key: 'user_uid', width: 128, render: row => h(UserUidLink, { uid: uidOf(row) }) },
    {
      title: '头像',
      key: 'avatar',
      width: 56,
      align: 'center',
      render: row => h(NAvatar, { round: true, size: 28, src: avatarOf(row), fallbackSrc: DEFAULT_AVATAR })
    },
    { title: '昵称', key: 'nickname', minWidth: 100, ellipsis: { tooltip: true }, render: row => textOf(row.nickname) },
    { title: '手机号', key: 'phone_num', width: 124, ellipsis: { tooltip: true }, render: row => textOf(row.phone_num) },
    {
      title: '在线',
      key: 'is_online',
      width: 72,
      align: 'center',
      render: row => {
        const online = Number(row.is_online) === 1;
        return h(NTag, { size: 'small', bordered: false, type: online ? 'success' : 'default' }, { default: () => (online ? '在线' : '离线') });
      }
    },
    {
      title: '状态',
      key: 'user_status',
      width: 72,
      align: 'center',
      render: row => {
        const status = statusOf(row);
        return h(NTag, { size: 'small', bordered: false, type: status.type }, { default: () => status.label });
      }
    },
    { title: '好友', key: 'friend_count', width: 64, align: 'center', render: row => countOf(row.friend_count) },
    { title: '群', key: 'group_count', width: 56, align: 'center', render: row => countOf(row.group_count) },
    { title: '注册时间', key: 'register_time', width: 156, ellipsis: { tooltip: true }, render: row => textOf(row.register_time) },
    { title: '注册地址', key: 'register_ip', width: 132, ellipsis: { tooltip: true }, render: row => textOf(row.register_ip) },
    { title: '登录 IP', key: 'latest_login_ip', width: 132, ellipsis: { tooltip: true }, render: row => textOf(row.latest_login_ip) },
    { title: '城市', key: 'location_city_label', width: 120, ellipsis: { tooltip: true }, render: row => textOf(row.location_city_label) },
    { title: '平台', key: 'device_type', width: 88, align: 'center', render: row => platformOf(row) },
    { title: '机型', key: 'device_model', width: 180, ellipsis: { tooltip: true }, render: row => modelOf(row) },
    { title: 'USDT', key: 'wallet_usdt', width: 110, align: 'right', render: row => balanceOf(row, 'USDT') },
    { title: 'CNY', key: 'wallet_cny', width: 110, align: 'right', render: row => balanceOf(row, 'CNY') },
    {
      title: 'TRX 地址',
      key: 'trx_address',
      width: 188,
      render: row => {
        const address = addressOf(row);
        if (!address) return '—';
        return h(NSpace, { size: 4, align: 'center', wrap: false }, {
          default: () => [
            h(NEllipsis, { style: 'max-width: 112px' }, { default: () => address }),
            h(NButton, { size: 'tiny', text: true, type: 'primary', onClick: () => copyAddress(address) }, { default: () => '复制' })
          ]
        });
      }
    },
    { title: '最近登录', key: 'latest_login_time', width: 156, ellipsis: { tooltip: true }, render: row => textOf(row.latest_login_time) },
    {
      title: '操作',
      key: 'op',
      width: 72,
      align: 'center',
      fixed: 'right',
      render: row => {
        if (!canWrite.value || Number(row.user_status) === -2) return '—';
        const disabled = statusOf(row).disabled;
        return h(
          NPopconfirm,
          { onPositiveClick: () => applyToggle(row) },
          {
            trigger: () =>
              h(NButton, { text: true, size: 'small', type: disabled ? 'success' : 'error' }, { default: () => (disabled ? '启用' : '禁用') }),
            default: () => (disabled ? '确认启用该账号登录？' : '确认禁用该账号登录？')
          }
        );
      }
    }
  ]
});

const hiddenByDefault = new Set([
  'register_time',
  'register_ip',
  'latest_login_ip',
  'location_city_label',
  'device_type',
  'device_model',
  'trx_address'
]);
columnChecks.value.forEach(column => {
  if (hiddenByDefault.has(column.key)) column.checked = false;
});

const scrollX = computed(() => columns.value.reduce((sum, column) => sum + Number(column.width ?? column.minWidth ?? 120), 0));

function resetSearch() {
  searchParams.keyword = '';
  searchParams.status = null;
  searchParams.online = null;
  getDataByPage();
}

function openCreate() {
  createForm.password = randomAccountPassword();
  createVisible.value = true;
}

function openGen() {
  genVisible.value = true;
}

async function submitCreate() {
  if (!createForm.password) createForm.password = randomAccountPassword();
  busy.value = true;
  try {
    await createUser({ nickname: createForm.nickname, password: createForm.password, sex: createForm.sex || undefined });
    createVisible.value = false;
    window.$message?.success('已创建');
    getData();
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    busy.value = false;
  }
}

async function submitGen() {
  busy.value = true;
  try {
    await createGenerationTask({ count: genForm.count, sex: genForm.sex || undefined });
    genVisible.value = false;
    window.$message?.success('已提交，每个账号密码不同，请到账号生成任务查看');
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    busy.value = false;
  }
}
</script>

<template>
  <div class="h-full min-h-0 flex-col-stretch gap-12px overflow-hidden lt-sm:overflow-auto">
    <NCard :bordered="false" size="small" class="card-wrapper">
      <NForm inline size="small" :show-feedback="false" label-placement="left">
        <NFormItem label="关键词">
          <NInput v-model:value="searchParams.keyword" clearable placeholder="IM 号 / 昵称 / 手机号" class="w-220px" @keyup.enter="getDataByPage()" />
        </NFormItem>
        <NFormItem label="状态">
          <NSelect v-model:value="searchParams.status" clearable placeholder="全部" :options="statusOptions" class="w-120px" />
        </NFormItem>
        <NFormItem label="在线">
          <NSelect v-model:value="searchParams.online" clearable placeholder="全部" :options="onlineOptions" class="w-120px" />
        </NFormItem>
        <NFormItem>
          <NSpace :size="8">
            <NButton size="small" type="primary" @click="getDataByPage()">查询</NButton>
            <NButton size="small" @click="resetSearch">重置</NButton>
          </NSpace>
        </NFormItem>
      </NForm>
    </NCard>
    <NCard :bordered="false" size="small" title="用户列表" class="card-wrapper min-h-0 flex-1 flex-col" content-class="min-h-0 flex-1 overflow-hidden">
      <template #header-extra>
        <TableHeaderOperation v-model:columns="columnChecks" :loading="loading" @refresh="getData">
          <NButton v-if="canWrite" size="small" ghost type="primary" @click="openCreate">创建用户</NButton>
          <NButton v-if="canWrite" size="small" @click="openGen">批量生成</NButton>
        </TableHeaderOperation>
      </template>
      <NDataTable
        remote
        bordered
        :single-line="false"
        striped
        size="small"
        :flex-height="!appStore.isMobile"
        :class="appStore.isMobile ? undefined : 'h-full'"
        :columns="columns"
        :data="data"
        :loading="loading"
        :row-key="uidOf"
        :pagination="mobilePagination"
        :scroll-x="scrollX"
      />
    </NCard>
    <NModal v-model:show="createVisible" preset="card" title="创建用户" class="w-440px">
      <NForm size="small" label-placement="left" label-width="64">
        <NFormItem label="昵称"><NInput v-model:value="createForm.nickname" /></NFormItem>
        <NFormItem label="密码">
          <NInputGroup>
            <NInput v-model:value="createForm.password" placeholder="8 位字母和数字" />
            <NButton @click="createForm.password = randomAccountPassword()">换一个</NButton>
          </NInputGroup>
        </NFormItem>
        <NFormItem label="性别"><NInput v-model:value="createForm.sex" /></NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton size="small" @click="createVisible = false">取消</NButton>
          <NButton size="small" type="primary" :loading="busy" @click="submitCreate">创建</NButton>
        </NSpace>
      </template>
    </NModal>
    <NModal v-model:show="genVisible" preset="card" title="批量生成任务" class="w-440px">
      <NForm size="small" label-placement="left" label-width="64">
        <NFormItem label="数量"><NInputNumber v-model:value="genForm.count" :min="1" class="w-full" /></NFormItem>
        <div class="mb-12px text-13px text-#64748b">每个账号单独生成 8 位字母和数字密码，完成后到账号生成任务里查看。</div>
        <NFormItem label="性别"><NInput v-model:value="genForm.sex" /></NFormItem>
      </NForm>
      <template #footer>
        <NSpace justify="end">
          <NButton size="small" @click="genVisible = false">取消</NButton>
          <NButton size="small" type="primary" :loading="busy" @click="submitGen">提交任务</NButton>
        </NSpace>
      </template>
    </NModal>
  </div>
</template>
