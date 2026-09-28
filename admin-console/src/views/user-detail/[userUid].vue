<script setup lang="ts">
import { computed, h, nextTick, onUnmounted, reactive, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { NAvatar, NButton, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import SvgIcon from '@/components/custom/svg-icon.vue';
import UserUidLink from '@/components/business/user-uid-link.vue';
import { listComplaints } from '@/service/api/complaints';
import { listLedger, listWithdraws } from '@/service/api/finance';
import { listC2cMessages, listGroupMessages } from '@/service/api/messages';
import { listAlbums, listContactBook } from '@/service/api/privacy';
import {
  adjustBalance,
  getUserDetail,
  getUserWallet,
  listLoginLogs,
  loginUnfreeze,
  resetFundPassword,
  resetLoginPassword,
  setLoginDisabled,
  updateNickname
} from '@/service/api/users';
import { unwrap } from '@/service/http';
import { getToken } from '@/store/modules/auth/shared';
import { displayDeviceModel } from '@/utils/deviceModel';
import { personCell } from '@/utils/adminTable';
import { errMessage, formatTime } from '@/utils/format';
import { hasPerm } from '@/utils/perms';

defineOptions({ name: 'user-detail' });

type Row = Record<string, unknown>;

const DEFAULT_AVATAR = 'https://99chat.oss-cn-hongkong.aliyuncs.com/moren/default_c2c_head.png';
const CURRENCY_COLOR: Record<string, string> = { USDT: '#26a17b', TRX: '#ef4444', CNY: '#f59e0b' };

const props = defineProps<{ embedded?: boolean; userUid?: string }>();
const route = useRoute();
const router = useRouter();
const canWrite = computed(() => hasPerm('user.write'));
const uid = computed(() => props.userUid || String(route.params.userUid || ''));

const loading = ref(false);
const profile = ref<Row>({});
const friends = ref<Row[]>([]);
const groups = ref<Row[]>([]);
const devices = ref<Row[]>([]);
const sameIp = ref<Row[]>([]);
const loginIps = ref<string[]>([]);
const currencies = ref<Row[]>([]);
const tab = ref('friends');
const section = ref('overview');
const chatVisible = ref(false);
const chatTitle = ref('聊天记录');
const chatKind = ref<'c2c' | 'group'>('c2c');
const chatLoading = ref(false);
const chatLoadingMore = ref(false);
const chatHasMore = ref(false);
const chatRows = ref<Row[]>([]);
const chatBox = ref<HTMLElement | null>(null);
const chatQuery = ref<Record<string, unknown>>({});
const chatCursor = ref<string | number | null>(null);
const chatPeerAvatar = ref('');
const listKeyword = ref('');
const panelLoading = ref(false);
const panelRows = ref<Row[]>([]);
const albumRows = ref<Row[]>([]);
const albumTotal = ref(0);
const albumPage = ref(1);
const albumHasMore = ref(false);
const albumLoadingMore = ref(false);
const albumSentinel = ref<HTMLElement | null>(null);
const albumH264 = reactive<Record<string, boolean>>({});
const chatH264 = reactive<Record<string, boolean>>({});
const ALBUM_PAGE_SIZE = 40;
let albumObserver: IntersectionObserver | null = null;

const nicknameVisible = ref(false);
const loginPwdVisible = ref(false);
const fundPwdVisible = ref(false);
const adjustVisible = ref(false);
const busy = ref(false);
const nickname = ref('');
const loginPwd = ref('');
const fundPwd = ref('');
const adjust = reactive({ currency: 'USDT', amount: '', direction: 'add', remark: '' });

const currencyOptions = [
  { label: 'USDT', value: 'USDT' },
  { label: 'CNY', value: 'CNY' },
  { label: 'TRX', value: 'TRX' }
];
const directionOptions = [
  { label: '增加', value: 'add' },
  { label: '扣减', value: 'subtract' }
];

const menuItems = [
  { label: '资料', key: 'overview', icon: 'mdi:card-account-details-outline' },
  { label: '好友关系', key: 'friends', icon: 'mdi:account-group-outline' },
  { label: '群组', key: 'groups', icon: 'mdi:account-multiple-outline' },
  { label: '设备终端', key: 'devices', icon: 'mdi:cellphone' },
  { label: '同 IP', key: 'same-ip', icon: 'mdi:ip-network-outline' },
  { label: '登录记录', key: 'login', icon: 'mdi:login' },
  { label: '消息审计', key: 'messages', icon: 'mdi:message-text-outline' },
  { label: '聊天投诉', key: 'complaints', icon: 'mdi:alert-circle-outline' },
  { label: '账变记录', key: 'ledger', icon: 'mdi:receipt-text-outline' },
  { label: '提现审核', key: 'withdraws', icon: 'mdi:bank-outline' },
  { label: '通讯录', key: 'contacts', icon: 'mdi:card-account-phone-outline' },
  { label: '相册', key: 'album', icon: 'mdi:image-outline' },
  { label: '改昵称', key: 'nickname', icon: 'mdi:pencil-outline' },
  { label: '重置登录密码', key: 'login-pwd', icon: 'mdi:lock-reset' },
  { label: '重置资金密码', key: 'fund-pwd', icon: 'mdi:key-outline' },
  { label: '调账', key: 'adjust', icon: 'mdi:cash-plus' },
  { label: '解除冻结', key: 'unfreeze', icon: 'mdi:lock-open-variant-outline' }
];

const sectionTitle = computed(() => menuItems.find(item => item.key === section.value)?.label || '资料');

const statusMeta = computed(() => {
  const raw = Number(profile.value.user_status);
  if (raw === 1) return { label: '正常', type: 'success' as const, disabled: false };
  if (raw === 0) return { label: '禁用', type: 'error' as const, disabled: true };
  if (raw === -2) return { label: '注销', type: 'warning' as const, disabled: true };
  return { label: '未知', type: 'default' as const, disabled: false };
});
const online = computed(() => Number(profile.value.is_online) === 1);

const walletRows = computed(() => {
  if (currencies.value.length) return currencies.value;
  const map = profile.value.wallet_balances;
  if (!map || typeof map !== 'object' || Array.isArray(map)) return [];
  return Object.entries(map as Row).map(([currency, balance]) => ({
    currency,
    balance_available: balance,
    balance_frozen: '0.00',
    balance_total: balance
  }));
});

const deviceText = computed(() => {
  const platform = platformLabel(profile.value);
  const model = displayDeviceModel(
    Number(profile.value.device_type) === 1 ? 'ios' : Number(profile.value.device_type) === 0 ? 'android' : 'web',
    profile.value.device_model
  );
  if (platform === '—' && model === '—') return '—';
  if (platform === '—') return model;
  if (model === '—') return platform;
  return `${platform} · ${model}`;
});

const registerDays = computed(() => {
  const raw = String(profile.value.register_time ?? '').trim();
  if (!raw) return '—';
  const time = new Date(raw.includes('T') ? raw : raw.replace(' ', 'T')).getTime();
  if (Number.isNaN(time)) return '—';
  const days = Math.max(0, Math.floor((Date.now() - time) / 86400000));
  return `${days}天`;
});

const stats = computed(() => [
  { label: '好友数量', value: textOf(profile.value.friend_count) === '—' ? '0' : textOf(profile.value.friend_count), icon: 'mdi:account-outline', color: '#3b82f6', bg: '#e8f1ff' },
  { label: '群组数量', value: textOf(profile.value.group_count) === '—' ? '0' : textOf(profile.value.group_count), icon: 'mdi:account-group-outline', color: '#22c55e', bg: '#e9f9ef' },
  { label: '登录设备', value: String(devices.value.length), icon: 'mdi:monitor-cellphone', color: '#8b5cf6', bg: '#f3e8ff' },
  { label: '登录 IP', value: String(loginIps.value.length), icon: 'mdi:map-marker-outline', color: '#f97316', bg: '#fff1e6', section: 'login' },
  { label: '注册时长', value: registerDays.value, icon: 'mdi:clock-outline', color: '#64748b', bg: '#f1f5f9' }
]);

const infoItems = computed(() => [
  { label: '手机号', icon: 'mdi:cellphone', value: textOf(profile.value.phone_num) },
  { label: '好友 / 群', icon: 'mdi:account-multiple-outline', value: `${textOf(profile.value.friend_count) === '—' ? '0' : textOf(profile.value.friend_count)} / ${textOf(profile.value.group_count) === '—' ? '0' : textOf(profile.value.group_count)}` },
  { label: '注册时间', icon: 'mdi:calendar-outline', value: textOf(profile.value.register_time) },
  { label: '注册地址', icon: 'mdi:map-marker-outline', value: textOf(profile.value.register_ip) },
  { label: '最近登录', icon: 'mdi:clock-outline', value: textOf(profile.value.latest_login_time) },
  { label: '最近登录 IP', icon: 'mdi:ip-network-outline', value: textOf(profile.value.latest_login_ip), extra: 'more' },
  { label: '城市', icon: 'mdi:home-outline', value: textOf(profile.value.location_city_label) },
  { label: '设备', icon: 'mdi:cellphone', value: deviceText.value }
]);

function matchKeyword(row: Row, keys: string[]) {
  const q = listKeyword.value.trim().toLowerCase();
  if (!q) return true;
  return keys.some(key => String(row[key] ?? '').toLowerCase().includes(q));
}

const shownFriends = computed(() => friends.value.filter(row => matchKeyword(row, ['friend_uid', 'nickname'])));
const shownGroups = computed(() => groups.value.filter(row => matchKeyword(row, ['group_id', 'group_name'])));
const shownDevices = computed(() => devices.value.filter(row => matchKeyword(row, ['platform', 'model'])));
const shownSameIp = computed(() => sameIp.value.filter(row => matchKeyword(row, ['user_uid', 'nickname', 'shared_ip'])));

const walletColumns: DataTableColumns<Row> = [
  {
    title: '币种',
    key: 'currency',
    width: 140,
    render: row => {
      const code = String(row.currency ?? '');
      return h('div', { class: 'flex items-center gap-8px' }, [
        h(NTag, { round: true, bordered: false, size: 'small', color: { color: `${CURRENCY_COLOR[code] || '#94a3b8'}22`, textColor: CURRENCY_COLOR[code] || '#334155' } }, { default: () => code.slice(0, 1) || '?' }),
        code || '—'
      ]);
    }
  },
  { title: '可用', key: 'balance_available' },
  { title: '冻结', key: 'balance_frozen' },
  { title: '合计', key: 'balance_total' },
  {
    title: '操作',
    key: 'op',
    width: 140,
    render: row =>
      h('div', { class: 'flex items-center gap-8px' }, [
        h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openAdjust(String(row.currency || 'USDT')) }, { default: () => '调账' }),
        h(NButton, { text: true, type: 'primary', size: 'small', onClick: () => openSection('ledger') }, { default: () => '明细' })
      ])
  }
];

const friendColumns: DataTableColumns<Row> = [
  { title: 'IM 号', key: 'friend_uid', width: 140, render: row => h(UserUidLink, { uid: String(row.friend_uid ?? '') }) },
  {
    title: '头像',
    key: 'friend_avatar_file_name',
    width: 64,
    render: row => h(NAvatar, { round: true, size: 32, src: avatarOf(row.friend_avatar_file_name), fallbackSrc: DEFAULT_AVATAR })
  },
  { title: '昵称', key: 'nickname', ellipsis: { tooltip: true } },
  { title: '添加时间', key: 'add_time', width: 180, render: row => formatTime(row.add_time) },
  { title: '备注', key: 'remark', ellipsis: { tooltip: true }, render: row => textOf(row.remark) },
  {
    title: '操作',
    key: 'op',
    width: 160,
    render: row =>
      h('div', { class: 'flex items-center gap-8px' }, [
        h(NButton, { text: true, type: 'primary', size: 'small', onClick: (e: MouseEvent) => { e.stopPropagation(); openFriendChat(row); } }, { default: () => '聊天记录' }),
        h(NButton, { text: true, type: 'primary', size: 'small', onClick: (e: MouseEvent) => { e.stopPropagation(); openUser(row.friend_uid); } }, { default: () => '查看资料' })
      ])
  }
];
const groupColumns: DataTableColumns<Row> = [
  { title: '群号', key: 'group_id', width: 160, ellipsis: { tooltip: true } },
  {
    title: '头像',
    key: 'face_url',
    width: 64,
    render: row => h(NAvatar, { round: true, size: 32, src: httpUrl(row.face_url) || undefined, fallbackSrc: DEFAULT_AVATAR })
  },
  { title: '群名', key: 'group_name', ellipsis: { tooltip: true } },
  { title: '群主', key: 'owner_nickname', width: 140, ellipsis: { tooltip: true }, render: row => textOf(row.owner_nickname) },
  { title: '类型', key: 'group_type', width: 110, render: row => labelOf(GROUP_TYPE_LABEL, row.group_type) },
  { title: '人数', key: 'member_count', width: 80 },
  { title: '入群时间', key: 'join_time', width: 180, render: row => formatTime(row.join_time) },
  {
    title: '操作',
    key: 'op',
    width: 100,
    render: row => h(NButton, { text: true, type: 'primary', size: 'small', onClick: (e: MouseEvent) => { e.stopPropagation(); openGroupChat(row); } }, { default: () => '聊天记录' })
  }
];
const deviceColumns: DataTableColumns<Row> = [
  { title: '平台', key: 'platform', width: 120, render: row => platformLabel(row) },
  {
    title: '机型',
    key: 'model',
    ellipsis: { tooltip: true },
    render: row => displayDeviceModel(String(row.platform ?? ''), row.model)
  },
  { title: '信任', key: 'is_trusted', width: 80, render: row => (Number(row.is_trusted) === 1 ? '是' : '否') },
  { title: '最近登录', key: 'last_login_time', width: 180, render: row => formatTime(row.last_login_time) }
];
const sameIpColumns: DataTableColumns<Row> = [
  { title: 'IM 号', key: 'user_uid', width: 140, render: row => h(UserUidLink, { uid: String(row.user_uid ?? '') }) },
  {
    title: '头像',
    key: 'user_avatar_file_name',
    width: 64,
    render: row => h(NAvatar, { round: true, size: 32, src: avatarOf(row.user_avatar_file_name), fallbackSrc: DEFAULT_AVATAR })
  },
  { title: '昵称', key: 'nickname', ellipsis: { tooltip: true } },
  { title: '手机号', key: 'phone_num', width: 140 },
  { title: '共享 IP', key: 'shared_ip', ellipsis: { tooltip: true } }
];

const timeCol = (title: string, key: string): DataTableColumns<Row>[number] => ({
  title,
  key,
  width: 180,
  render: row => formatTime(row[key])
});

const panelColumnMap: Record<string, DataTableColumns<Row>> = {
  login: [
    timeCol('登录时间', 'login_time'),
    { title: '登录 IP', key: 'login_ip', width: 150 },
    { title: '平台', key: 'device_type', width: 90, render: row => platformLabel({ device_type: row.device_type }) },
    { title: '机型', key: 'device_model', ellipsis: { tooltip: true }, render: row => displayDeviceModel(String(row.device_type ?? ''), row.device_model || row.device_info) },
    { title: '版本', key: 'client_version', width: 100 },
    { title: '结果', key: 'status', width: 140, ellipsis: { tooltip: true }, render: row => loginResultText(row.status) }
  ],
  messages: [
    { title: '发送方', key: 'from_account', width: 200, render: row => personCell(row, { title: '发送方', uid: 'from_account' }) },
    { title: '接收方', key: 'to_account', width: 200, render: row => personCell(row, { title: '接收方', uid: 'to_account' }) },
    { title: '类型', key: 'msg_type', width: 90, render: row => labelOf(MSG_TYPE_LABEL, row.msg_type) },
    { title: '内容', key: 'text_preview', ellipsis: { tooltip: true } },
    timeCol('时间', 'msg_time')
  ],
  complaints: [
    { title: '类型', key: 'chat_type', width: 80, render: row => labelOf(CHAT_TYPE_LABEL, row.chat_type) },
    { title: '投诉人', key: 'reporter_uid', width: 200, render: row => personCell(row, { title: '投诉人', uid: 'reporter_uid', name: 'reporter_nickname' }) },
    { title: '被投诉人', key: 'reported_uid', width: 200, render: row => personCell(row, { title: '被投诉人', uid: 'reported_uid', name: 'reported_nickname' }) },
    { title: '原因', key: 'reason_label', width: 120, ellipsis: { tooltip: true } },
    { title: '内容', key: 'content', ellipsis: { tooltip: true } },
    { title: '状态', key: 'status_label', width: 90 },
    timeCol('时间', 'created_at')
  ],
  ledger: [
    { title: '单号', key: 'transaction_no', width: 160, ellipsis: { tooltip: true } },
    { title: '类型', key: 'transaction_type', width: 90, render: row => labelOf(TXN_TYPE_LABEL, row.transaction_type) },
    { title: '转出方', key: 'from_uid', width: 200, render: row => personCell(row, { title: '转出方', uid: 'from_uid', name: 'from_nickname', avatar: 'from_avatar' }) },
    { title: '转入方', key: 'to_uid', width: 200, render: row => personCell(row, { title: '转入方', uid: 'to_uid', name: 'to_nickname', avatar: 'to_avatar' }) },
    { title: '金额', key: 'amount', width: 110 },
    { title: '币种', key: 'currency', width: 80 },
    { title: '状态', key: 'status', width: 90, render: row => labelOf(STATUS_CODE_LABEL, row.status) },
    timeCol('时间', 'create_time')
  ],
  withdraws: [
    { title: '单号', key: 'biz_no', width: 160, ellipsis: { tooltip: true } },
    { title: '用户', key: 'user_uid', width: 200, render: row => personCell(row, { title: '用户', uid: 'user_uid', name: 'nickname', avatar: 'user_avatar_file_name' }) },
    { title: '金额', key: 'amount', width: 110 },
    { title: '渠道', key: 'channel', width: 100 },
    { title: '状态', key: 'status', width: 90 },
    { title: '地址', key: 'address', ellipsis: { tooltip: true } },
    timeCol('时间', 'create_time')
  ],
  contacts: [
    { title: '姓名', key: 'contact_name', width: 140, ellipsis: { tooltip: true } },
    { title: '手机号', key: 'contact_phone', width: 150 },
    { title: '备注', key: 'contact_remark', ellipsis: { tooltip: true } },
    { title: '平台用户', key: 'is_platform_user', width: 100, render: row => (row.is_platform_user ? '是' : '否') },
    { title: '关联用户', key: 'related_uid', width: 200, render: row => personCell(row, { title: '关联用户', uid: 'related_uid' }) },
    timeCol('更新时间', 'last_updated_at')
  ],
  album: [
    { title: '文件名', key: 'file_name', ellipsis: { tooltip: true } },
    { title: '类型', key: 'media_type', width: 90, render: row => labelOf(MEDIA_TYPE_LABEL, row.media_type || row.file_type) },
    { title: '大小', key: 'file_size', width: 100 },
    timeCol('上传时间', 'upload_time'),
    { title: '预览', key: 'preview_url', width: 80, render: row => (row.preview_url || row.oss_url ? '有' : '—') }
  ]
};

const panelColumns = computed<DataTableColumns<Row>>(() => panelColumnMap[section.value] || []);

const writeKeys = new Set(['nickname', 'login-pwd', 'fund-pwd', 'adjust', 'unfreeze']);

function textOf(value: unknown) {
  const text = String(value ?? '').trim();
  return text || '—';
}

function avatarOf(value: unknown) {
  const raw = String(value ?? '').trim();
  if (/^https?:\/\//i.test(raw) || raw.startsWith('//')) return raw;
  return DEFAULT_AVATAR;
}

function httpUrl(value: unknown) {
  const raw = String(value ?? '').trim();
  if (/^https?:\/\//i.test(raw) || raw.startsWith('//')) return raw;
  return '';
}

function albumThumb(row: Row) {
  return httpUrl(row.oss_thumb_url) || httpUrl(row.preview_url) || httpUrl(row.oss_url);
}

function albumPreview(row: Row) {
  return httpUrl(row.oss_url) || httpUrl(row.preview_url) || albumThumb(row);
}

function isAlbumVideo(row: Row) {
  const type = `${row.file_type || ''} ${row.media_type || ''} ${row.mime_type || ''}`.toLowerCase();
  if (type.includes('video')) return true;
  return /\.(mp4|mov|m4v|webm|mkv|avi|3gp)(\?|$)/i.test(albumPreview(row));
}

function mediaStreamUrl(kind: 'file' | 'h264', url: string) {
  const token = getToken() || '';
  return `/api/v1/media/${kind}?url=${encodeURIComponent(url)}&access_token=${encodeURIComponent(token)}`;
}

function albumPlayUrl(row: Row) {
  const origin = albumPreview(row);
  return albumH264[origin] ? mediaStreamUrl('h264', origin) : mediaStreamUrl('file', origin);
}

function onAlbumVideoError(row: Row) {
  const origin = albumPreview(row);
  if (!origin || albumH264[origin]) return;
  albumH264[origin] = true;
}

function chatVideoKey(row: Row) {
  return String(row.msg_key || row.media_url || '');
}

function shownChatVideo(row: Row) {
  const origin = httpUrl(row.media_url);
  return chatH264[chatVideoKey(row)] ? mediaStreamUrl('h264', origin) : origin;
}

function onChatVideoError(row: Row) {
  const key = chatVideoKey(row);
  if (!httpUrl(row.media_url) || chatH264[key]) return;
  chatH264[key] = true;
}

function itemsOf(block: unknown) {
  if (!block || typeof block !== 'object') return [];
  const items = (block as Row).items;
  return Array.isArray(items) ? (items as Row[]) : [];
}

const PLATFORM_LABEL: Record<string, string> = {
  '0': '安卓',
  android: '安卓',
  '1': '苹果',
  ios: '苹果',
  iphone: '苹果',
  ipad: '苹果',
  '2': '电脑',
  web: '电脑',
  desktop: '电脑',
  pc: '电脑',
  windows: '电脑',
  macos: '苹果电脑',
  linux: '电脑'
};
const GROUP_TYPE_LABEL: Record<string, string> = {
  public: '公开群',
  private: '私有群',
  chatroom: '聊天室',
  avchatroom: '直播群',
  community: '社群',
  bchatroom: '在线成员群'
};
const MSG_TYPE_LABEL: Record<string, string> = {
  timtextelem: '文本',
  timimageelem: '图片',
  timsoundelem: '语音',
  timvideofileelem: '视频',
  timfileelem: '文件',
  timfaceelem: '表情',
  timlocationelem: '位置',
  timcustomelem: '自定义',
  timrelayelem: '合并转发',
  timgrouptipelem: '群提示',
  timgroupsystemnoticeelem: '群通知',
  text: '文本',
  image: '图片',
  video: '视频',
  audio: '语音',
  file: '文件'
};
const TXN_TYPE_LABEL: Record<string, string> = {
  '1': '充值',
  '2': '提现',
  '3': '转出',
  '4': '转入',
  '5': '发红包',
  '6': '领红包',
  '7': '退回'
};
const STATUS_CODE_LABEL: Record<string, string> = {
  '0': '处理中',
  '1': '成功',
  '2': '失败'
};
const CHAT_TYPE_LABEL: Record<string, string> = {
  c2c: '单聊',
  group: '群聊'
};
const MEDIA_TYPE_LABEL: Record<string, string> = {
  image: '图片',
  photo: '图片',
  video: '视频',
  audio: '语音',
  file: '文件'
};

function labelOf(map: Record<string, string>, value: unknown) {
  const raw = String(value ?? '').trim();
  if (!raw) return '—';
  return map[raw.toLowerCase()] || raw;
}

function platformLabel(row: Row) {
  const raw = String(row.platform ?? row.device_type ?? '').trim().toLowerCase();
  if (!raw || raw === 'nan' || raw === 'undefined') return '—';
  return PLATFORM_LABEL[raw] || '其他';
}

function pickItems(raw: unknown) {
  const obj = (raw || {}) as Row;
  const data = obj.data && typeof obj.data === 'object' && !Array.isArray(obj.data) ? (obj.data as Row) : obj;
  const items = (data.items || data.files || data.list || data.rows || data.content || []) as Row[];
  return Array.isArray(items) ? items : [];
}

async function copyText(value: unknown) {
  const text = String(value ?? '').trim();
  if (!text) return;
  try {
    await navigator.clipboard.writeText(text);
    window.$message?.success('已复制');
  } catch (e) {
    window.$message?.error(errMessage(e, '复制失败'));
  }
}

function senderName(account: unknown) {
  const id = String(account ?? '').trim();
  if (!id) return '—';
  if (id === uid.value) return textOf(profile.value.nickname);
  const friend = friends.value.find(row => String(row.friend_uid ?? '') === id);
  if (friend?.nickname) return String(friend.nickname);
  return id;
}

function isMine(row: Row) {
  return String(row.from_account ?? '').trim() === uid.value;
}

function chatAvatar(row: Row) {
  if (isMine(row)) return avatarOf(profile.value.user_avatar_file_name);
  const id = String(row.from_account ?? '').trim();
  const friend = friends.value.find(item => String(item.friend_uid ?? '') === id);
  return avatarOf(friend?.friend_avatar_file_name || chatPeerAvatar.value);
}

function mediaKind(row: Row) {
  const kind = String(row.media_kind ?? '').trim();
  if (kind) return kind;
  const type = String(row.msg_type ?? '').toLowerCase();
  if (type.includes('image')) return 'image';
  if (type.includes('video')) return 'video';
  if (type.includes('sound') || type.includes('audio')) return 'audio';
  if (type.includes('file')) return 'file';
  return 'text';
}

function chatText(row: Row) {
  const text = String(row.text_preview ?? '').trim();
  if (!text) return '';
  if (mediaKind(row) !== 'text' && /^\[(图片|语音|视频|文件|表情)\]$/.test(text)) return '';
  return text;
}

function openFriendChat(row: Row) {
  chatKind.value = 'c2c';
  chatTitle.value = `与 ${textOf(row.nickname)} 的聊天`;
  chatPeerAvatar.value = String(row.friend_avatar_file_name ?? '');
  chatVisible.value = true;
  loadChat({ user_a: uid.value, user_b: String(row.friend_uid ?? '') });
}

function openGroupChat(row: Row) {
  chatKind.value = 'group';
  chatTitle.value = `${textOf(row.group_name)} 的群聊`;
  chatPeerAvatar.value = String(row.face_url ?? '');
  chatVisible.value = true;
  loadChat({ g_id: String(row.group_id ?? '') });
}

const LOGIN_RESULT: Record<string, string> = {
  need_sms: '需要短信验证',
  bad_credentials: '账号或密码错误',
  account_disabled: '账号已禁用',
  sms_code_invalid: '短信验证码错误',
  user_not_found: '账号不存在',
  challenge_expired: '验证已过期，请重新登录'
};

function loginResultText(value: unknown) {
  const raw = String(value ?? '').trim();
  if (!raw) return '—';
  return LOGIN_RESULT[raw.toLowerCase()] || raw;
}

async function loadChat(extra: Record<string, unknown>, older = false) {
  if (older) {
    if (!chatHasMore.value || chatLoadingMore.value || chatLoading.value || chatCursor.value == null) return;
    chatLoadingMore.value = true;
  } else {
    chatQuery.value = extra;
    chatCursor.value = null;
    chatHasMore.value = false;
    chatLoading.value = true;
    chatRows.value = [];
  }
  const box = chatBox.value;
  const prevHeight = box?.scrollHeight ?? 0;
  const prevTop = box?.scrollTop ?? 0;
  try {
    const params: Record<string, unknown> = { page: 1, page_size: 50, ...chatQuery.value };
    if (older) {
      if (chatKind.value === 'group') params.req_msg_seq = chatCursor.value;
      else params.last_msg_key = chatCursor.value;
    }
    const raw = chatKind.value === 'group' ? await listGroupMessages(params) : await listC2cMessages(params);
    const data = payloadOf(raw);
    const items = pickItems(raw);
    const seen = new Set(chatRows.value.map(row => String(row.msg_key ?? '')));
    const fresh = items.filter(row => {
      const key = String(row.msg_key ?? '');
      return !key || !seen.has(key);
    });
    chatRows.value = (older ? fresh.concat(chatRows.value) : items).sort(
      (a, b) => Number(a.msg_time || 0) - Number(b.msg_time || 0)
    );
    const next = chatKind.value === 'group' ? data.next_req_msg_seq : data.last_msg_key;
    chatCursor.value = next == null || next === '' ? null : (next as string | number);
    chatHasMore.value = Boolean(data.has_more) && chatCursor.value != null && (!older || fresh.length > 0);
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    chatLoading.value = false;
    chatLoadingMore.value = false;
    await nextTick();
    if (chatBox.value) {
      chatBox.value.scrollTop = older ? chatBox.value.scrollHeight - prevHeight + prevTop : chatBox.value.scrollHeight;
    }
  }
}

function onChatScroll() {
  const box = chatBox.value;
  if (!box || box.scrollTop > 40) return;
  loadChat(chatQuery.value, true);
}

function openUser(id: unknown) {
  const next = String(id ?? '').trim();
  if (next) router.push(`/users/${encodeURIComponent(next)}`);
}

function openAdjust(currency = 'USDT') {
  adjust.currency = currency;
  openSection('adjust');
}

function payloadOf(raw: unknown) {
  const obj = (raw || {}) as Row;
  const data = obj.data && typeof obj.data === 'object' && !Array.isArray(obj.data) ? (obj.data as Row) : obj;
  return data;
}

async function loadPanel(key: string) {
  const params = { user_uid: uid.value, user_a: uid.value, page: 1, page_size: 20 };
  const loaders: Record<string, () => Promise<unknown>> = {
    login: () => listLoginLogs(params),
    messages: () => listC2cMessages(params),
    complaints: () => listComplaints(params),
    ledger: () => listLedger(params),
    withdraws: () => listWithdraws(params),
    contacts: () => listContactBook(params)
  };
  const loader = loaders[key];
  if (!loader) return;
  panelLoading.value = true;
  panelRows.value = [];
  try {
    panelRows.value = pickItems(await loader());
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    panelLoading.value = false;
  }
}

async function loadAlbum(reset = true) {
  if (!reset && (albumLoadingMore.value || panelLoading.value || !albumHasMore.value)) return;
  if (reset) {
    albumPage.value = 1;
    panelLoading.value = true;
  } else {
    albumLoadingMore.value = true;
  }
  try {
    const raw = await listAlbums({
      user_uid: uid.value,
      page: albumPage.value,
      page_size: ALBUM_PAGE_SIZE
    });
    const data = payloadOf(raw);
    const items = pickItems(raw);
    albumTotal.value = Number(data.total ?? items.length);
    albumHasMore.value = Boolean(data.has_more);
    albumRows.value = reset ? items : albumRows.value.concat(items);
  } catch (e) {
    if (!reset) albumPage.value = Math.max(1, albumPage.value - 1);
    window.$message?.error(errMessage(e));
  } finally {
    panelLoading.value = false;
    albumLoadingMore.value = false;
    await nextTick();
    bindAlbumObserver();
  }
}

function loadMoreAlbum() {
  if (!albumHasMore.value || albumLoadingMore.value || panelLoading.value) return;
  albumPage.value += 1;
  loadAlbum(false);
}

function bindAlbumObserver() {
  albumObserver?.disconnect();
  if (section.value !== 'album' || !albumSentinel.value) return;
  albumObserver = new IntersectionObserver(entries => {
    if (entries.some(entry => entry.isIntersecting)) loadMoreAlbum();
  }, { rootMargin: '240px' });
  albumObserver.observe(albumSentinel.value);
}

function openSection(key: string) {
  section.value = key;
  if (key === 'album') loadAlbum(true);
  else loadPanel(key);
}

onUnmounted(() => albumObserver?.disconnect());

async function load(resetSection = false) {
  if (!uid.value) return;
  if (resetSection) section.value = 'overview';
  tab.value = 'friends';
  panelRows.value = [];
  loading.value = true;
  try {
    const body = unwrap(await getUserDetail(uid.value));
    profile.value = (body.profile && typeof body.profile === 'object' ? body.profile : body) as Row;
    nickname.value = String(profile.value.nickname ?? '');
    friends.value = itemsOf(body.friends);
    groups.value = itemsOf(body.groups);
    devices.value = itemsOf(body.devices);
    sameIp.value = itemsOf(body.accounts_same_ip);
    const sameBlock = (body.accounts_same_ip && typeof body.accounts_same_ip === 'object' ? body.accounts_same_ip : {}) as Row;
    loginIps.value = Array.isArray(sameBlock.shared_ips) ? sameBlock.shared_ips.map(ip => String(ip)).filter(Boolean) : [];
    const wallet = unwrap(await getUserWallet(uid.value));
    currencies.value = Array.isArray(wallet.currencies) ? (wallet.currencies as Row[]) : [];
  } catch (e) {
    window.$message?.error(errMessage(e));
  } finally {
    loading.value = false;
  }
}

watch(uid, () => load(true), { immediate: true });

async function run(fn: () => Promise<unknown>, ok = '已保存') {
  if (!canWrite.value) return false;
  busy.value = true;
  try {
    await fn();
    window.$message?.success(ok);
    await load();
    return true;
  } catch (e) {
    window.$message?.error(errMessage(e));
    return false;
  } finally {
    busy.value = false;
  }
}

async function saveNickname() {
  if (!nickname.value.trim()) {
    window.$message?.warning('请填写昵称');
    return;
  }
  if (await run(() => updateNickname({ user_uid: uid.value, nickname: nickname.value.trim() }))) nicknameVisible.value = false;
}

async function saveLoginPwd() {
  if (!loginPwd.value) {
    window.$message?.warning('请填写新登录密码');
    return;
  }
  if (await run(() => resetLoginPassword({ user_uid: uid.value, new_password: loginPwd.value }))) {
    loginPwd.value = '';
    loginPwdVisible.value = false;
  }
}

async function saveFundPwd() {
  if (!fundPwd.value) {
    window.$message?.warning('请填写新资金密码');
    return;
  }
  if (await run(() => resetFundPassword({ user_uid: uid.value, new_fund_password: fundPwd.value }))) {
    fundPwd.value = '';
    fundPwdVisible.value = false;
  }
}

async function saveAdjust() {
  if (!adjust.amount.trim()) {
    window.$message?.warning('请填写金额');
    return;
  }
  if (
    await run(() =>
      adjustBalance({
        user_uid: uid.value,
        currency: adjust.currency,
        amount: adjust.amount.trim(),
        direction: adjust.direction,
        remark: adjust.remark || undefined
      })
    )
  ) {
    adjust.amount = '';
    adjust.remark = '';
    adjustVisible.value = false;
  }
}
</script>

<template>
  <div>
    <NSpin :show="loading">
      <NSpace vertical :size="12">
        <NSpace justify="space-between" align="center">
          <NButton v-if="!embedded" text @click="router.push('/users')">
            <template #icon><SvgIcon icon="mdi:chevron-left" /></template>
            用户详情
          </NButton>
          <span v-else />
          <NSpace :size="8" align="center">
            <NTag round :bordered="false" :type="statusMeta.type">{{ statusMeta.label }}</NTag>
            <NPopconfirm
              v-if="canWrite && Number(profile.user_status) !== -2"
              @positive-click="run(() => setLoginDisabled({ user_uid: uid, disabled: !statusMeta.disabled }))"
            >
              <template #trigger>
                <NButton size="small" round secondary :type="statusMeta.disabled ? 'success' : 'error'">
                  {{ statusMeta.disabled ? '启用' : '禁用' }}
                </NButton>
              </template>
              {{ statusMeta.disabled ? '确认启用该账号登录？' : '确认禁用该账号登录？' }}
            </NPopconfirm>
            <NTag round :bordered="false" :type="online ? 'success' : 'default'">{{ online ? '在线' : '离线' }}</NTag>
          </NSpace>
        </NSpace>

        <div class="flex items-start gap-12px lt-md:flex-col">
          <div class="min-w-0 flex-1">
            <NCard :bordered="false" class="card-wrapper min-h-480px" size="small" :title="sectionTitle">
            <template v-if="section === 'overview'">
            <NSpace vertical :size="12">
            <NCard :bordered="false" embedded size="small">
              <div class="flex gap-24px lt-sm:flex-col">
                <div class="flex flex-col items-center gap-8px">
                  <NAvatar round :size="72" :src="avatarOf(profile.user_avatar_file_name)" :fallback-src="DEFAULT_AVATAR" />
                  <div class="text-18px font-600">{{ textOf(profile.nickname) }}</div>
                  <NButton text size="tiny" @click="copyText(uid)">
                    {{ uid }}
                    <template #icon><SvgIcon icon="mdi:content-copy" class="text-14px" /></template>
                  </NButton>
                </div>
                <div class="grid flex-1 grid-cols-2 gap-x-24px gap-y-14px lt-sm:grid-cols-1">
                  <div v-for="item in infoItems" :key="item.label" class="flex items-start gap-8px">
                    <SvgIcon :icon="item.icon" class="mt-2px text-16px text-#94a3b8" />
                    <div class="min-w-0">
                      <div class="text-12px text-#94a3b8">{{ item.label }}</div>
                      <NSpace :size="6" align="center">
                        <NEllipsis style="max-width: 220px">{{ item.value }}</NEllipsis>
                        <span v-if="item.extra === 'more' && loginIps.length" class="text-12px text-#64748b">共 {{ loginIps.length }} 个</span>
                        <NButton v-if="item.extra === 'more'" text type="primary" size="tiny" @click="openSection('login')">查看更多</NButton>
                      </NSpace>
                    </div>
                  </div>
                  <div class="col-span-2 flex items-start gap-8px lt-sm:col-span-1">
                    <SvgIcon icon="mdi:wallet-outline" class="mt-2px text-16px text-#94a3b8" />
                    <div class="min-w-0">
                      <div class="text-12px text-#94a3b8">充值地址</div>
                      <NSpace :size="6" align="center">
                        <NEllipsis style="max-width: 360px">{{ textOf(profile.trx_address || profile.deposit_address) }}</NEllipsis>
                        <NButton text size="tiny" @click="copyText(profile.trx_address || profile.deposit_address)">
                          <template #icon><SvgIcon icon="mdi:content-copy" class="text-14px" /></template>
                        </NButton>
                      </NSpace>
                    </div>
                  </div>
                </div>
              </div>
            </NCard>

        <NGrid cols="2 m:5" responsive="screen" :x-gap="12" :y-gap="12">
          <NGi v-for="item in stats" :key="item.label">
            <NCard :bordered="false" class="card-wrapper" size="small" :class="item.section ? 'cursor-pointer' : ''" @click="item.section && openSection(item.section)">
              <NSpace align="center" :size="12">
                <div class="h-36px w-36px flex items-center justify-center rounded-full" :style="{ background: item.bg }">
                  <SvgIcon :icon="item.icon" class="text-18px" :style="{ color: item.color }" />
                </div>
                <div>
                  <div class="text-20px font-600 leading-none">{{ item.value }}</div>
                  <div class="mt-4px text-12px text-#94a3b8">{{ item.label }}</div>
                </div>
              </NSpace>
            </NCard>
          </NGi>
        </NGrid>

        <NCard :bordered="false" class="card-wrapper" size="small" title="账户余额">
          <template #header-extra>
            <NButton text type="primary" @click="openSection('ledger')">资金记录</NButton>
          </template>
          <NDataTable size="small" bordered striped :single-line="false" :columns="walletColumns" :data="walletRows" />
        </NCard>

            </NSpace>
            </template>
            <NDataTable
              v-else-if="section === 'friends'"
              size="small"
              bordered
              striped
              :single-line="false"
              :columns="friendColumns"
              :data="shownFriends"
              :max-height="520"
              :scroll-x="860"
              :row-props="row => ({ style: 'cursor: pointer', onClick: () => openFriendChat(row) })"
            />
            <NDataTable
              v-else-if="section === 'groups'"
              size="small"
              bordered
              striped
              :single-line="false"
              :columns="groupColumns"
              :data="shownGroups"
              :max-height="520"
              :scroll-x="860"
              :row-props="row => ({ style: 'cursor: pointer', onClick: () => openGroupChat(row) })"
            />
            <NDataTable v-else-if="section === 'devices'" size="small" bordered striped :single-line="false" :columns="deviceColumns" :data="shownDevices" :max-height="520" :scroll-x="640" />
            <NDataTable v-else-if="section === 'same-ip'" size="small" bordered striped :single-line="false" :columns="sameIpColumns" :data="shownSameIp" :max-height="520" :scroll-x="720" />
            <NForm v-else-if="section === 'nickname'" class="max-w-420px" size="small" label-placement="left" label-width="64">
              <NFormItem label="昵称"><NInput v-model:value="nickname" /></NFormItem>
              <NButton type="primary" :loading="busy" :disabled="!canWrite" @click="saveNickname">保存</NButton>
            </NForm>
            <NForm v-else-if="section === 'login-pwd'" class="max-w-420px" size="small" label-placement="left" label-width="88">
              <NFormItem label="新密码"><NInput v-model:value="loginPwd" type="password" show-password-on="click" /></NFormItem>
              <NButton type="primary" :loading="busy" :disabled="!canWrite" @click="saveLoginPwd">保存</NButton>
            </NForm>
            <NForm v-else-if="section === 'fund-pwd'" class="max-w-420px" size="small" label-placement="left" label-width="88">
              <NFormItem label="新密码"><NInput v-model:value="fundPwd" type="password" show-password-on="click" /></NFormItem>
              <NButton type="primary" :loading="busy" :disabled="!canWrite" @click="saveFundPwd">保存</NButton>
            </NForm>
            <NForm v-else-if="section === 'adjust'" class="max-w-440px" size="small" label-placement="left" label-width="64">
              <NFormItem label="币种"><NSelect v-model:value="adjust.currency" :options="currencyOptions" /></NFormItem>
              <NFormItem label="方向"><NSelect v-model:value="adjust.direction" :options="directionOptions" /></NFormItem>
              <NFormItem label="金额"><NInput v-model:value="adjust.amount" placeholder="正数" /></NFormItem>
              <NFormItem label="备注"><NInput v-model:value="adjust.remark" /></NFormItem>
              <NButton type="warning" :loading="busy" :disabled="!canWrite" @click="saveAdjust">提交调账</NButton>
            </NForm>
            <NSpin v-else-if="section === 'album'" :show="panelLoading && !albumRows.length">
              <div class="mb-12px text-14px text-#334155">共 {{ albumTotal }} 张，已显示 {{ albumRows.length }} 张</div>
              <NEmpty v-if="!panelLoading && !albumRows.length" description="暂无照片" class="py-48px" />
              <NImageGroup v-else>
                <div class="grid grid-cols-2 gap-12px sm:grid-cols-3 xl:grid-cols-4">
                  <div v-for="row in albumRows" :key="String(row.id || row.file_name)" class="overflow-hidden rd-8px bg-#f8fafc">
                    <div v-if="isAlbumVideo(row)" class="relative h-160px bg-black">
                      <video
                        :key="albumPlayUrl(row)"
                        :src="albumPlayUrl(row)"
                        controls
                        playsinline
                        preload="metadata"
                        class="block h-160px w-full bg-black object-contain"
                        @error="onAlbumVideoError(row)"
                      />
                      <div v-if="albumH264[albumPreview(row)]" class="pointer-events-none absolute left-8px top-8px rd-4px bg-black/60 px-6px py-2px text-12px text-white">转码中</div>
                    </div>
                    <NImage
                      v-else-if="albumThumb(row)"
                      :src="albumThumb(row)"
                      :preview-src="albumPreview(row)"
                      object-fit="cover"
                      width="100%"
                      height="160"
                      class="block h-160px w-full"
                    />
                    <div v-else class="h-160px flex items-center justify-center px-8px text-center text-12px text-#94a3b8">无图片</div>
                    <div class="truncate px-8px py-6px text-12px text-#64748b">{{ isAlbumVideo(row) ? '视频 · ' : '' }}{{ formatTime(row.upload_time) }}</div>
                  </div>
                </div>
              </NImageGroup>
              <div ref="albumSentinel" class="py-16px text-center text-12px text-#94a3b8">
                {{ albumLoadingMore ? '加载中…' : albumHasMore ? '继续下滑加载更多' : albumRows.length ? '已全部加载' : '' }}
              </div>
            </NSpin>
            <NSpace v-else-if="section === 'unfreeze'" vertical>
              <NAlert type="info">解除该账号的登录冻结。禁用账号请用顶部的禁用按钮。</NAlert>
              <NButton type="primary" :loading="busy" :disabled="!canWrite" @click="run(() => loginUnfreeze({ user_uid: uid }), '已解除冻结')">确认解除冻结</NButton>
            </NSpace>
            <div v-else-if="section === 'login'">
              <div class="mb-8px text-14px text-#334155">共 {{ loginIps.length }} 个登录 IP</div>
              <div v-if="loginIps.length" class="mb-12px flex flex-wrap gap-8px">
                <NTag v-for="ip in loginIps" :key="ip" size="small" :bordered="false">{{ ip }}</NTag>
              </div>
              <NDataTable size="small" bordered striped :single-line="false" :loading="panelLoading" :columns="panelColumns" :data="panelRows" :max-height="520" :scroll-x="960" />
            </div>
            <NDataTable v-else size="small" bordered striped :single-line="false" :loading="panelLoading" :columns="panelColumns" :data="panelRows" :max-height="520" :scroll-x="960" />
            </NCard>
          </div>
          <NCard :bordered="false" class="card-wrapper sticky top-12px w-220px shrink-0 lt-md:w-full" size="small" title="菜单">
            <div class="flex flex-col">
              <NButton
                v-for="item in menuItems"
                :key="item.key"
                block
                :quaternary="section !== item.key"
                :secondary="section === item.key"
                :type="section === item.key ? 'primary' : 'default'"
                class="justify-start"
                :disabled="!canWrite && writeKeys.has(item.key)"
                @click="openSection(item.key)"
              >
                <template #icon><SvgIcon :icon="item.icon" /></template>
                {{ item.label }}
              </NButton>
            </div>
          </NCard>
        </div>
      </NSpace>
    </NSpin>
    <NModal v-model:show="chatVisible" preset="card" :title="chatTitle" class="w-720px">
      <NSpin :show="chatLoading">
        <div ref="chatBox" class="h-520px overflow-y-auto rounded-8px bg-#f4f6f8 px-16px py-12px" @scroll="onChatScroll">
          <div v-if="chatLoadingMore" class="py-8px text-center text-12px text-#94a3b8">加载更早的消息…</div>
          <div v-else-if="chatHasMore && chatRows.length" class="py-8px text-center text-12px text-#94a3b8">上滑加载更早的消息</div>
          <NEmpty v-if="!chatLoading && !chatRows.length" description="暂无消息" class="py-80px" />
          <div
            v-for="(row, index) in chatRows"
            :key="String(row.msg_key || index)"
            class="mb-14px flex"
            :class="isMine(row) ? 'justify-end' : 'justify-start'"
          >
            <div class="max-w-75% flex gap-8px" :class="isMine(row) ? 'flex-row-reverse' : ''">
              <NAvatar round :size="36" :src="chatAvatar(row)" :fallback-src="DEFAULT_AVATAR" />
              <div>
                <div v-if="chatKind === 'group' && !isMine(row)" class="mb-4px text-12px text-#94a3b8">{{ senderName(row.from_account) }}</div>
                <div class="rd-8px px-12px py-8px" :class="isMine(row) ? 'bg-#18a058 text-white' : 'bg-white'">
                  <NImage
                    v-if="mediaKind(row) === 'image' && httpUrl(row.media_url || row.thumb_url)"
                    :src="httpUrl(row.thumb_url) || httpUrl(row.media_url)"
                    :preview-src="httpUrl(row.media_url) || httpUrl(row.thumb_url)"
                    object-fit="cover"
                    width="180"
                    class="mb-6px block max-w-180px rd-6px"
                  />
                  <video
                    v-else-if="mediaKind(row) === 'video' && shownChatVideo(row)"
                    :key="shownChatVideo(row)"
                    :src="shownChatVideo(row)"
                    :poster="httpUrl(row.thumb_url) || undefined"
                    controls
                    playsinline
                    preload="metadata"
                    class="mb-6px max-h-240px max-w-220px rd-6px"
                    @error="onChatVideoError(row)"
                  />
                  <div v-if="mediaKind(row) === 'video' && chatH264[chatVideoKey(row)]" class="mb-6px text-12px">正在转码，请稍候</div>
                  <audio
                    v-else-if="mediaKind(row) === 'audio' && httpUrl(row.media_url)"
                    :src="httpUrl(row.media_url)"
                    controls
                    class="mb-6px max-w-220px"
                  />
                  <a
                    v-else-if="httpUrl(row.media_url)"
                    :href="httpUrl(row.media_url)"
                    target="_blank"
                    rel="noopener"
                    class="mb-6px block underline"
                    :class="isMine(row) ? 'text-white' : 'text-#18a058'"
                  >{{ textOf(row.file_name) === '—' ? '打开文件' : textOf(row.file_name) }}</a>
                  <div v-if="chatText(row)" class="whitespace-pre-wrap break-all">{{ chatText(row) }}</div>
                </div>
                <div class="mt-4px text-12px text-#94a3b8" :class="isMine(row) ? 'text-right' : ''">{{ formatTime(row.msg_time) }}</div>
              </div>
            </div>
          </div>
        </div>
      </NSpin>
    </NModal>
  </div>
</template>
