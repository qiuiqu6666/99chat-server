import { h } from 'vue';
import { NAvatar, NImage, NTag } from 'naive-ui';
import type { DataTableColumns } from 'naive-ui';
import UserUidLink from '@/components/business/user-uid-link.vue';
import { formatTime } from '@/utils/format';

export const DEFAULT_AVATAR = 'https://99chat.oss-cn-hongkong.aliyuncs.com/moren/default_c2c_head.png';

type Row = Record<string, unknown>;

const LABELS: Record<string, string> = {
  id: '编号',
  task_no: '任务号',
  status: '状态',
  status_label: '状态',
  created_by: '创建人',
  requested_count: '计划数量',
  processed_count: '已处理',
  success_count: '成功数',
  fail_count: '失败数',
  last_error: '最后错误',
  started_at: '开始时间',
  finished_at: '结束时间',
  created_at: '创建时间',
  updated_at: '更新时间',
  expires_at: '过期时间',
  user_uid: 'IM 号',
  nickname: '昵称',
  user_nickname: '昵称',
  phone_num: '手机号',
  user_status: '账号状态',
  device_id: '设备号',
  device_fingerprint: '设备指纹',
  device_model: '机型',
  brand: '品牌',
  system_type: '系统',
  system_version: '系统版本',
  app_version: '应用版本',
  client_version: '版本',
  push_token_masked: '推送令牌',
  first_login_time: '首次登录',
  last_login_time: '最近登录',
  last_seen_at: '最后在线',
  last_heartbeat_at: '最后心跳',
  last_login_ip: '登录 IP',
  last_login_region: '登录地区',
  is_banned: '封禁',
  is_online: '在线',
  history_id: '记录号',
  login_ip: '登录 IP',
  login_time: '登录时间',
  login_time2: '登录时间',
  device_type: '平台',
  device_info: '设备信息',
  device_token_masked: '设备号',
  hardware_id: '硬件号',
  is_current: '当前设备',
  msg_type: '类型',
  text_preview: '内容',
  msg_time: '时间',
  msg_key: '消息号',
  file_name: '文件名',
  media_kind: '媒体',
  from_account: '发送方',
  to_account: '接收方',
  chat_type: '会话',
  reason: '原因',
  reason_label: '原因',
  content: '内容',
  reply: '回复',
  images: '图片',
  group_id: '群号',
  group_name: '群名',
  group_type: '类型',
  member_count: '人数',
  join_time: '入群时间',
  role: '角色',
  add_time: '添加时间',
  remark: '备注',
  shared_ip: '共享 IP',
  shared_device_id: '共享设备',
  g_id: '群号',
  g_name: '群名',
  g_status: '状态',
  g_member_count: '人数',
  max_member_count: '人数上限',
  create_time: '创建时间',
  create_user_uid: '创建人',
  create_user_nickname: '创建人昵称',
  g_mute_mode: '禁言',
  kind: '类型',
  source_table: '来源',
  occurred_at_ms: '时间',
  summary: '摘要',
  detail: '详情',
  group_mode: '群模式',
  transaction_no: '单号',
  transaction_type: '类型',
  amount: '金额',
  fee: '手续费',
  balance_before: '变动前',
  balance_after: '变动后',
  currency: '币种',
  biz_no: '单号',
  biz_type: '类型',
  channel: '渠道',
  external_order_no: '外部单号',
  address: '地址',
  order_no: '单号',
  direction_label: '方向',
  direction: '方向',
  input_amount: '兑出数量',
  input_currency: '兑出币种',
  output_amount: '兑入数量',
  output_currency: '兑入币种',
  rate: '汇率',
  packet_type: '红包类型',
  target_summary: '对象',
  total_amount: '总金额',
  receive_count: '已领',
  total_count: '总数',
  admin_user_id: '管理员编号',
  username_attempted: '尝试账号',
  success: '结果',
  ip: 'IP',
  geo_address: '地区',
  user_agent: '浏览器',
  fail_reason: '失败原因',
  login_at: '登录时间',
  action: '操作',
  resource_type: '对象类型',
  resource_id: '对象编号',
  content_type: '内容类型',
  content_type_label: '内容类型',
  content_summary: '摘要',
  scope_type: '范围',
  scope_label: '范围',
  target_user_id: '目标用户',
  im_push_status: '推送状态',
  im_push_status_label: '推送状态',
  publish_at: '发布时间',
  contact_name: '姓名',
  contact_phone: '手机号',
  contact_phone_masked: '手机号',
  contact_remark: '备注',
  source: '来源',
  is_platform_user: '平台用户',
  first_uploaded_at: '首次上传',
  last_updated_at: '更新时间',
  contact_count: '通讯录',
  album_count: '相册',
  file_type: '类型',
  media_type: '类型',
  file_size: '大小',
  upload_time: '上传时间',
  storage_status: '状态',
  from_address: '来源地址',
  hot_wallet_address: '热钱包',
  usdt_swept: '归集 USDT',
  trx_swept: '归集 TRX',
  usdt_tx_id: 'USDT 交易',
  trx_tx_id: 'TRX 交易',
  trigger_type: '触发方式',
  operator: '操作人',
  nickname_ingroup: '群昵称',
  be_invite_user_id: '邀请人',
  be_owner_time: '成为群主',
  user_record_id: '成员编号',
  code: '编码',
  name: '名称',
  enabled: '启用',
  worker_id: '工人',
  platform: '平台',
  version: '版本',
  url: '地址',
  balance: '余额',
  logo_url: '图标',
  platform_coin: '平台币',
  deposit_enabled: '充值',
  withdraw_enabled: '提现',
  sort_order: '排序',
  withdraw_fee_type: '提现手续费类型',
  withdraw_fee_value: '提现手续费',
  withdraw_fee_min: '最低手续费',
  withdraw_fee_max: '最高手续费',
  withdraw_fee_enabled: '手续费开关',
  withdraw_fee_label: '手续费说明',
  scene: '场景',
  fee_type: '计费方式',
  feeType: '计费方式',
  fee_value: '费率',
  feeValue: '费率',
  min_fee: '最低',
  minFee: '最低',
  max_fee: '最高',
  maxFee: '最高',
  per_tx_max: '单笔上限',
  perTxMax: '单笔上限',
  daily_max: '每日上限',
  dailyMax: '每日上限',
  updatedAt: '更新时间',
  tron_address: '链上地址',
  platform_usdt: '平台 USDT',
  platform_trx: '平台 TRX',
  chain_usdt: '链上 USDT',
  chain_trx: '链上 TRX',
  chain_loaded: '已查链',
  chain_balance_at: '链上余额时间',
  platform_usdt_total: '平台 USDT 合计',
  platform_trx_total: '平台 TRX 合计',
  chain_usdt_total: '链上 USDT 合计',
  chain_trx_total: '链上 TRX 合计',
  hot_wallet_usdt: '热钱包 USDT',
  hot_wallet_trx: '热钱包 TRX',
  wallet_count: '钱包数',
  collect_ready: '可归集',
  running: '进行中',
  cursor_after_id: '游标',
  scanned: '已扫描',
  skipped: '已跳过',
  system_ok: '系统好友成功',
  system_fail: '系统好友失败',
  wallet_ok: '支付助手成功',
  wallet_fail: '支付助手失败',
  start_cursor: '起始游标',
  end_cursor: '结束游标',
  completed: '已完成',
  official_account_id: '公众号',
  officialAccountId: '公众号',
  face_url: '头像',
  faceUrl: '头像',
  introduction: '简介',
  organization: '机构',
  owner_account: '归属账号',
  ownerAccount: '归属账号',
  subscriber_num: '订阅数',
  subscriberNum: '订阅数',
  max_subscriber_num: '订阅上限',
  maxSubscriberNum: '订阅上限',
  createTime: '创建时间',
  image_url: '图片',
  image_md5: '图片校验',
  width: '宽',
  height: '高',
  bytes: '大小',
  fit: '适配',
  start_at: '开始时间',
  end_at: '结束时间',
  min_app_version: '最低版本',
  platforms: '平台',
  channels: '渠道',
  method: '方法',
  path: '接口',
  count: '次数',
  error_count: '错误数',
  avg_ms: '平均耗时',
  max_ms: '最长耗时',
  last_ms: '最近耗时',
  last_status: '最近状态',
  last_at: '最近请求',
  duration_ms: '耗时',
  at: '时间',
  error: '错误',
  started_at: '开始统计',
  collected_at: '采集时间',
  slow_threshold_ms: '慢请求阈值',
  total_requests: '总请求',
  total_errors: '总错误',
  path_count: '接口数',
  buffer_size: '缓冲条数',
  buffer_capacity: '缓冲容量',
  g_notice: '群公告',
  owner_nickname: '群主昵称',
  group_type: '群类型',
  game_enabled: '游戏',
  gameid: '游戏号',
  markup_bps: '加点（基点）',
  float_bps: '浮动（基点）',
  min_withdraw_usdt_micro: '最低提现 USDT',
  frankfurter_url: '汇率源',
  exchange_rate_cache_seconds: '汇率缓存秒数',
  base_usd_cny: '美元兑人民币',
  buy_cny_per_usdt: '买入价',
  sell_cny_per_usdt: '卖出价',
  mid_cny_per_usdt: '中间价',
  example_one_usdt_to_platform_yuan: '1 USDT 折合',
  fetched_at: '汇率时间',
  total_exchange_surplus_fen: '闪兑结余（分）',
  total_exchange_surplus_yuan: '闪兑结余（元）',
  total_fee_usdt_micro: '手续费 USDT',
  total_fee_platform_fen: '手续费（分）',
  username: '账号',
  display_name: '显示名',
  displayName: '显示名',
  permissions: '权限',
  email: '邮箱',
  phone: '手机号',
  avatar: '头像',
  push_enabled: '推送开关',
  skip_when_online: '在线不推送',
  voip_push_enabled: '音视频推送',
  jpush_enabled: '极光推送',
  jpush_app_key: '极光 AppKey',
  jpush_master_secret: '极光密钥',
  jpush_base_url: '极光地址',
  im_callback_enabled: '回调开关',
  chat_push_enabled: '聊天推送',
  callback_token: '回调令牌',
  allowed_sdk_app_ids: '允许的应用',
  chat_push_skip_when_online: '在线跳过聊天推送',
  skip_sender_ids: '跳过发送方',
  max_group_members_per_push: '单次群推上限',
  dedup_ttl_hours: '去重小时',
  pay_pin_max_failures: '支付密码错误上限',
  pay_pin_lock_minutes: '支付密码锁定分钟',
  red_packet_expire_hours: '红包过期小时',
  min_deposit_usdt_micro: '最低充值 USDT',
  deposit_confirmations: '充值确认数',
  deposit_mode: '充值模式',
  deposit_mnemonic_configured: '已配置充值助记词',
  hot_wallet_configured: '已配置热钱包',
  trongrid_api_key: 'TronGrid 密钥',
  group_create_limit_enabled: '建群限制',
  group_join_limit_max: '加群上限',
  group_join_limit_max_community: '社群加群上限',
  group_create_limit_max_community: '社群建群上限',
  group_create_limit_enforce: '强制建群限制',
  group_create_limit_log_only: '建群限制仅记录',
  group_create_limit_use_im_count_fallback: '用 IM 人数兜底',
  community_create_price_currency: '建社群币种',
  community_create_price_minor: '建社群价格',
  website: '官网',
  customer_service_url: '客服地址',
  feedback_prefix: '反馈前缀',
  max_feedback_screenshots: '反馈截图上限',
  max_feedback_content_length: '反馈字数上限',
  scene_label: '场景',
  currency_label: '币种',
  label: '名称',
  set: '已配置',
  preview: '预览',
  secret: '敏感',
  slug: '标识',
  ok: '结果',
  password: '登录密码',
  version_code: '构建号',
  min_version: '最低版本',
  min_version_code: '最低构建号',
  update_type: '更新类型',
  download_url: '下载地址',
  changelog: '更新说明',
  gray_percent: '灰度比例',
  published_at: '发布时间'
};

const ENUMS: Record<string, Record<string, string>> = {
  chat_type: { c2c: '单聊', group: '群聊' },
  transaction_type: { '1': '充值', '2': '提现', '3': '转出', '4': '转入', '5': '发红包', '6': '领红包', '7': '退回' },
  device_type: { '0': '安卓', '1': '苹果', '2': '电脑', android: '安卓', ios: '苹果', web: '电脑' },
  msg_type: {
    timtextelem: '文本',
    timimageelem: '图片',
    timsoundelem: '语音',
    timvideofileelem: '视频',
    timfileelem: '文件',
    timfaceelem: '表情',
    timlocationelem: '位置',
    timcustomelem: '自定义',
    text: '文本',
    image: '图片',
    video: '视频',
    audio: '语音',
    file: '文件'
  },
  media_kind: { image: '图片', video: '视频', audio: '语音', file: '文件', text: '文本' },
  file_type: { image: '图片', photo: '图片', video: '视频' },
  media_type: { image: '图片', photo: '图片', video: '视频' },
  success: { '0': '失败', '1': '成功', true: '成功', false: '失败' },
  is_banned: { true: '已封禁', false: '正常' },
  is_online: { true: '在线', false: '离线' },
  is_platform_user: { true: '是', false: '否' },
  enabled: { true: '启用', false: '停用' },
  fail_reason: {
    need_sms: '需要短信验证',
    bad_credentials: '账号或密码错误',
    account_disabled: '账号已禁用',
    sms_code_invalid: '短信验证码错误',
    user_not_found: '账号不存在',
    challenge_expired: '验证已过期',
    invalid_credentials: '用户名或密码错误'
  },
  status: {
    '0': '处理中',
    '1': '成功',
    '2': '失败',
    pending: '待处理',
    processing: '处理中',
    success: '成功',
    failed: '失败',
    processed: '已处理',
    closed: '已关闭',
    open: '待处理',
    rejected: '已拒绝',
    approved: '已通过'
  },
  user_status: { '1': '正常', '0': '禁用', '-2': '注销' },
  g_status: { '0': '正常', '1': '已解散' },
  role: { owner: '群主', admin: '管理员', member: '普通成员', '400': '群主', '300': '管理员', '200': '普通成员' },
  scene: {
    WITHDRAW: '提现',
    TRANSFER: '转账',
    TRANSFER_PLATFORM: '转账',
    RED_PACKET: '红包',
    RED_PACKET_SEND: '发红包',
    EXCHANGE: '闪兑',
    LIVE_TIP: '打赏'
  },
  fee_type: { NONE: '无', PERCENT: '按比例', FIXED: '固定金额', fixed: '固定金额', rate: '按比例' },
  feeType: { NONE: '无', PERCENT: '按比例', FIXED: '固定金额' },
  method: { GET: '查询', POST: '提交', PUT: '更新', PATCH: '更新', DELETE: '删除' },
  platform: { android: '安卓', ios: '苹果', web: '电脑' },
  update_type: { none: '不提示', optional: '可选更新', force: '强制更新', gray: '灰度' }
};

const TIME_KEYS = /(_time|_at)$|^(login_at|publish_at|create_time|add_time|join_time|msg_time|occurred_at_ms|started_at|finished_at|created_at|updated_at|expires_at)$/;

type Identity = {
  title: string;
  uid: string;
  name?: string;
  avatar?: string;
  link?: boolean;
};

const IDENTITIES: Identity[] = [
  { title: '好友', uid: 'friend_uid', name: 'friend_nickname', avatar: 'friend_avatar_file_name' },
  { title: '转出方', uid: 'from_uid', name: 'from_nickname', avatar: 'from_avatar' },
  { title: '转入方', uid: 'to_uid', name: 'to_nickname', avatar: 'to_avatar' },
  { title: '发送方', uid: 'from_account' },
  { title: '接收方', uid: 'to_account' },
  { title: '投诉人', uid: 'reporter_uid', name: 'reporter_nickname' },
  { title: '被投诉人', uid: 'reported_uid', name: 'reported_nickname' },
  { title: '发送方', uid: 'sender_uid', name: 'nickname', avatar: 'user_avatar_file_name' },
  { title: '用户', uid: 'user_uid', name: 'nickname', avatar: 'user_avatar_file_name' },
  { title: '用户', uid: 'user_uid', name: 'user_nickname', avatar: 'user_avatar_file_name' },
  { title: '平台用户', uid: 'related_uid' },
  { title: '群主', uid: 'g_owner_user_uid', name: 'owner_nickname', avatar: 'owner_avatar' },
  { title: '创建人', uid: 'create_user_uid', name: 'create_user_nickname' },
  { title: '群', uid: 'g_id', name: 'g_name', avatar: 'g_custom_avatar', link: false },
  { title: '公众号', uid: 'official_account_id', name: 'name', avatar: 'face_url', link: false },
  { title: '公众号', uid: 'officialAccountId', name: 'name', avatar: 'faceUrl', link: false },
  { title: '群', uid: 'group_id', name: 'group_name', avatar: 'face_url', link: false },
  { title: '管理员', uid: 'admin_username', name: 'admin_display_name', link: false }
];

const SKIP = new Set(['source', 'page', 'page_size', 'has_more', 'truncated', 'hint']);

function keysOf(rows: Row[]) {
  const keys: string[] = [];
  const seen = new Set<string>();
  for (const row of rows.slice(0, 30)) {
    for (const key of Object.keys(row)) {
      if (!seen.has(key)) {
        seen.add(key);
        keys.push(key);
      }
    }
  }
  return keys;
}

function hasKey(keys: Set<string>, key?: string) {
  return Boolean(key && keys.has(key));
}

export function avatarSrc(value: unknown) {
  const raw = String(value ?? '').trim();
  if (/^https?:\/\//i.test(raw) || raw.startsWith('//')) return raw;
  return DEFAULT_AVATAR;
}

export function labelOf(key: string) {
  return LABELS[key] || key;
}

export function listOf(raw: unknown): Row[] {
  if (Array.isArray(raw)) return raw.filter(item => item && typeof item === 'object') as Row[];
  if (!raw || typeof raw !== 'object') return [];
  const obj = raw as Row;
  if (Array.isArray(obj.data)) return (obj.data as unknown[]).filter(item => item && typeof item === 'object') as Row[];
  const data = obj.data && typeof obj.data === 'object' ? (obj.data as Row) : obj;
  if (Array.isArray(data)) return data.filter(item => item && typeof item === 'object') as Row[];
  for (const key of ['items', 'list', 'rows', 'paths', 'currencies', 'members']) {
    const value = data[key];
    if (Array.isArray(value)) return value.filter(item => item && typeof item === 'object') as Row[];
  }
  return [];
}

export function kvRows(source: Row | null | undefined) {
  const rows: { key: string; label: string; value: string }[] = [];
  if (!source) return rows;
  const push = (key: string, value: unknown) => {
    rows.push({ key, label: labelOf(key), value: textOf(value) });
  };
  for (const [key, value] of Object.entries(source)) {
    if (Array.isArray(value)) continue;
    if (value && typeof value === 'object') {
      for (const [childKey, childValue] of Object.entries(value as Row)) {
        if (childValue && typeof childValue === 'object') continue;
        push(childKey, childValue);
      }
      continue;
    }
    push(key, value);
  }
  return rows;
}

export function personCell(row: Row, spec: Identity) {
  const uid = String(row[spec.uid] ?? '').trim();
  const name = spec.name ? String(row[spec.name] ?? '').trim() : '';
  if (!uid && !name) return '—';
  const avatar = spec.avatar ? row[spec.avatar] : '';
  return h('div', { class: 'flex items-center gap-8px' }, [
    h(NAvatar, { round: true, size: 28, src: avatarSrc(avatar), fallbackSrc: DEFAULT_AVATAR }),
    h('div', { class: 'min-w-0' }, [
      h('div', { class: 'truncate text-13px' }, name || '—'),
      spec.link === false
        ? h('div', { class: 'truncate text-12px text-#64748b' }, uid || '')
        : uid
          ? h(UserUidLink, { uid })
          : null
    ])
  ]);
}

function textOf(value: unknown) {
  if (value == null || value === '') return '—';
  if (typeof value === 'boolean') return value ? '是' : '否';
  if (Array.isArray(value)) return value.length ? `${value.length} 项` : '—';
  if (typeof value === 'object') {
    const text = JSON.stringify(value);
    return text.length > 80 ? `${text.slice(0, 80)}…` : text;
  }
  return String(value);
}

function renderValue(key: string, value: unknown) {
  if (TIME_KEYS.test(key)) return formatTime(value);
  const map = ENUMS[key];
  if (map) {
    const raw = String(value ?? '').trim();
    if (!raw) return '—';
    return map[raw] || map[raw.toLowerCase()] || raw;
  }
  if (typeof value === 'boolean') {
    return h(NTag, { size: 'small', bordered: false, type: value ? 'success' : 'default' }, { default: () => (value ? '是' : '否') });
  }
  const text = textOf(value);
  if (isImageUrl(value) && (key.endsWith('_url') || key === 'url' || key === 'preview_url')) {
    return h(NImage, { src: String(value), width: 48, height: 48, objectFit: 'cover', previewSrc: String(value), class: 'rd-4px' });
  }
  return text;
}

function isImageUrl(value: unknown) {
  return /^https?:\/\//i.test(String(value ?? '')) && /\.(png|jpe?g|gif|webp|svg|bmp)(\?|$)/i.test(String(value ?? ''));
}

export function buildAdminColumns(rows: Row[], extra: DataTableColumns<Row> = []): DataTableColumns<Row> {
  const keys = keysOf(rows);
  const keySet = new Set(keys);
  const used = new Set<string>();
  const columns: DataTableColumns<Row> = [];
  const usedUid = new Set<string>();

  for (const spec of IDENTITIES) {
    if (!hasKey(keySet, spec.uid) || usedUid.has(spec.uid)) continue;
    if (spec.uid === 'user_uid' && spec.name === 'nickname' && !keySet.has('nickname')) continue;
    if (spec.uid === 'user_uid' && spec.name === 'user_nickname' && (!keySet.has('user_nickname') || keySet.has('nickname'))) continue;
    if (spec.uid === 'sender_uid' && keySet.has('user_uid')) continue;
    usedUid.add(spec.uid);
    used.add(spec.uid);
    if (spec.name) used.add(spec.name);
    if (spec.avatar) used.add(spec.avatar);
    columns.push({
      title: spec.title,
      key: spec.uid,
      width: 220,
      render: row => personCell(row, spec)
    });
  }

  const imageKey = ['oss_thumb_url', 'thumb_url', 'preview_url', 'oss_url', 'media_url'].find(key => keySet.has(key));
  if (imageKey && !used.has(imageKey)) {
    used.add(imageKey);
    for (const key of ['oss_thumb_url', 'thumb_url', 'preview_url', 'oss_url', 'media_url']) used.add(key);
    columns.push({
      title: '预览',
      key: imageKey,
      width: 72,
      render: row => {
        const src = ['oss_thumb_url', 'thumb_url', 'preview_url', 'oss_url', 'media_url', 'url']
          .map(key => String(row[key] ?? ''))
          .find(value => /^https?:\/\//i.test(value));
        if (!src) return '—';
        return h(NImage, { src, width: 48, height: 48, objectFit: 'cover', previewSrc: src, class: 'rd-4px' });
      }
    });
  }

  for (const key of keys) {
    if (used.has(key) || SKIP.has(key)) continue;
    columns.push({
      title: LABELS[key] || key,
      key,
      minWidth: TIME_KEYS.test(key) ? 168 : 120,
      ellipsis: { tooltip: true },
      render: row => renderValue(key, row[key])
    });
  }
  return columns.concat(extra);
}
