import { http } from "@/utils/http";
import {
  normalizeAdminUserListResponse,
  normalizeAdminUserListItem,
  normalizeWriteOkResponse
} from "@/utils/chat99AdminApi";
import {
  deviceTypeToPlatform,
  displayDeviceModel
} from "@/utils/deviceModelDisplay";

/** 腾讯云 IM 账号（users.user_id），非数据库自增 id */
export type ImUserUid = string;

export function normalizeImUserUid(v: unknown): ImUserUid {
  if (v == null || v === "") return "";
  return String(v).trim();
}

export function isImUserUid(v: unknown): boolean {
  return normalizeImUserUid(v).length > 0;
}

/** `GET /api/v1/users` 列表项 */
export type AdminUserListItem = {
  user_uid: ImUserUid;
  user_mail: string | null;
  nickname: string | null;
  user_sex: number;
  phone_num: string | null;
  register_ip: string | null;
  register_time: string | null;
  latest_login_time: string | null;
  /** 实时在线判断优先使用：客户端心跳/最后活跃时间 */
  last_seen_at?: string | null;
  last_heartbeat_at?: string | null;
  online_until?: string | null;
  latest_login_ip: string | null;
  user_status: number;
  is_online: number;
  user_avatar_file_name: string | null;
  what_s_up: string | null;
  user_desc: string | null;
  user_type: number;
  user_regieon: string | null;
  device_type: number | null;
  device_model?: string | null;
  termination_time: string | null;
  nickname_last_modified_time: string | null;
  nickname_last_modified_time2: number;
  wallet_balance: string | null;
  wallet_frozen_amount: string | null;
  /** 多币种余额 `{ USDT, CNY }`（可选；TRX 后台不展示） */
  wallet_balances?: Record<string, string> | null;
  wallet_frozen_by_currency?: Record<string, string> | null;
  /** 充值地址（USDT 链上；CNY 为平台内账户） */
  deposit_address?: string | null;
  trx_address?: string | null;
  /** 文档 3.4 关系统计 */
  friend_count?: number | null;
  group_count?: number | null;
  /** 游戏功能特权用户 */
  game_privileged?: boolean;
  /** 密码登录永久免新设备短信验证 */
  skip_device_sms?: boolean;
  /** 最新上报位置城市文案（逆地理） */
  location_city_label?: string | null;
};

export type AdminUserListResponse = {
  items: AdminUserListItem[];
  total: number;
  page: number;
  page_size: number;
  sort: string;
};

export type AdminUserListParams = {
  page: number;
  page_size: number;
  keyword?: string;
  /** `0` 仅禁用，`1` 仅正常 */
  status?: string;
  /** `1` 仅在线，`0` 仅离线（与 `is_online` 一致；服务端需支持该 Query） */
  is_online?: string;
  sort?: string;
};

/** GET /api/v1/users/detail 嵌套列表 */
export type AdminUserDetailSection<T = Record<string, unknown>> = {
  total: number;
  limit: number;
  truncated: boolean;
  items: T[];
};

/** GET /api/v1/users/detail 聚合响应 */
export type AdminUserDetailResponse = {
  profile: AdminUserListItem;
  groups: AdminUserDetailSection;
  friends: AdminUserDetailSection;
  accounts_same_ip: AdminUserDetailSection & {
    shared_ips?: string[];
    hint?: string | null;
  };
  devices: AdminUserDetailSection;
  /** 可选：与 `GET /users/wallet` 同结构，若聚合响应内嵌则优先用于币种表 */
  wallet?: AdminUserWalletResponse | null;
};

/** IM 用户分页（99chat Admin API） */
export const getAdminUsers = async (params: AdminUserListParams) => {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/users",
    { params }
  );
  return normalizeAdminUserListResponse(raw);
};

/** 聚合详情 */
export const getAdminUserDetail = async (user_uid: ImUserUid) => {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/users/detail",
    { params: { user_uid: normalizeImUserUid(user_uid) } }
  );
  const profile = raw.profile as Record<string, unknown> | undefined;
  const normalizedProfile = profile
    ? normalizeAdminUserListItem(profile)
    : undefined;
  const walletRaw = raw.wallet as Record<string, unknown> | undefined;
  return {
    ...raw,
    profile: normalizedProfile,
    wallet: walletRaw
      ? normalizeAdminUserWalletResponse({
          ...walletRaw,
          user_uid: normalizedProfile?.user_uid ?? user_uid
        })
      : null
  } as AdminUserDetailResponse;
};

export type RelatedUsersParams = {
  user_uid: ImUserUid;
  device_id?: string;
  page?: number;
  page_size?: number;
};

export const getUsersRelatedByIp = (params: RelatedUsersParams) => {
  return http.request<Record<string, unknown>>(
    "get",
    "/api/v1/users/related-by-ip",
    { params }
  );
};

export const getUsersRelatedByDevice = (params: RelatedUsersParams) => {
  return http.request<Record<string, unknown>>(
    "get",
    "/api/v1/users/related-by-device",
    { params }
  );
};

/** `GET /api/v1/users/wallet` 单币种行 */
export type AdminUserWalletCurrency = {
  currency: string;
  balance_available: string;
  balance_frozen: string;
  balance_total: string;
  wallet_address: string | null;
  wallet_address_label: string | null;
};

/** `GET /api/v1/users/wallet` 响应（详情页币种余额表专用） */
export type AdminUserWalletResponse = {
  user_uid: ImUserUid;
  deposit_address: string | null;
  trx_address: string | null;
  usdt_contract: string | null;
  min_deposit_usdt: string | null;
  currencies: AdminUserWalletCurrency[];
};

function normalizeAdminUserWalletCurrency(
  raw: Record<string, unknown>
): AdminUserWalletCurrency {
  return {
    currency: String(raw.currency ?? "")
      .trim()
      .toUpperCase(),
    balance_available: String(
      raw.balance_available ?? raw.balanceAvailable ?? "0"
    ),
    balance_frozen: String(raw.balance_frozen ?? raw.balanceFrozen ?? "0"),
    balance_total: String(raw.balance_total ?? raw.balanceTotal ?? "0"),
    wallet_address: strNull(raw.wallet_address ?? raw.walletAddress),
    wallet_address_label: strNull(
      raw.wallet_address_label ?? raw.walletAddressLabel
    )
  };
}

export function normalizeAdminUserWalletResponse(
  raw: Record<string, unknown>
): AdminUserWalletResponse {
  const items = Array.isArray(raw.currencies) ? raw.currencies : [];
  return {
    user_uid: normalizeImUserUid(raw.user_uid ?? raw.userUid),
    deposit_address: strNull(raw.deposit_address ?? raw.depositAddress),
    trx_address: strNull(raw.trx_address ?? raw.trxAddress),
    usdt_contract: strNull(raw.usdt_contract ?? raw.usdtContract),
    min_deposit_usdt: strNull(raw.min_deposit_usdt ?? raw.minDepositUsdt),
    currencies: items.map(it =>
      normalizeAdminUserWalletCurrency(it as Record<string, unknown>)
    )
  };
}

/** 用户钱包（多币种可用/冻结 + 充值地址，`user.read`） */
export const getAdminUserWallet = async (user_uid: ImUserUid) => {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/users/wallet",
    { params: { user_uid: normalizeImUserUid(user_uid) } }
  );
  return normalizeAdminUserWalletResponse(raw);
};

export type WalletBalanceAdjustBody = {
  user_uid: ImUserUid;
  /** 币种：USDT / CNY；TRX 不在后台展示 */
  currency: string;
  direction: "add" | "subtract";
  amount: string;
  remark?: string;
};

export type AdminCreateUserBody = {
  nickname: string;
  password: string;
  /** `0`=女 `1`=男 `2`=未知 */
  sex?: string;
};

export type AdminCreateUserResponse = {
  ok: boolean;
  user_uid: string;
  nickname: string;
  phone?: string | null;
  phone_num?: string | null;
  trx_address?: string | null;
  deposit_address?: string | null;
  usdt_contract?: string | null;
  min_deposit_usdt?: string | null;
};

/** 批量创建：顶层统一 password，users[] 仅 nickname + 可选 sex */
export type AdminCreateUserBatchItemInput = {
  nickname: string;
  sex?: string;
};

export type AdminCreateUserBatchBody = {
  password: string;
  users: AdminCreateUserBatchItemInput[];
};

export type AdminCreateUserBatchItem = {
  index: number;
  ok: boolean;
  user_uid?: string;
  nickname?: string;
  phone_num?: string | null;
  trx_address?: string | null;
  deposit_address?: string | null;
  usdt_contract?: string | null;
  min_deposit_usdt?: string | null;
  error?: string | null;
  message?: string | null;
  field?: string | null;
};

export type AdminCreateUserBatchResponse = {
  items: AdminCreateUserBatchItem[];
  total: number;
  success_count: number;
  fail_count: number;
};

/** 按数量批量创建（昵称自动生成） */
export type AdminCreateByCountBody = {
  password: string;
  count: number;
  /** `0`=女 `1`=男 `2`=未知 */
  sex?: string;
};

export type AdminCreateByCountAccount = {
  user_uid: string;
  nickname: string;
  phone?: string | null;
  phone_num?: string | null;
  password?: string | null;
  trx_address?: string | null;
  deposit_address?: string | null;
  usdt_contract?: string | null;
  min_deposit_usdt?: string | null;
};

export type AdminCreateByCountResponse = {
  ok: boolean;
  password: string;
  count: number;
  success_count: number;
  fail_count: number;
  accounts: AdminCreateByCountAccount[];
};

function normalizeCreateByCountAccount(
  raw: Record<string, unknown>
): AdminCreateByCountAccount {
  return {
    user_uid: String(raw.user_uid ?? raw.userUid ?? ""),
    nickname: String(raw.nickname ?? ""),
    phone: strNull(raw.phone),
    phone_num: strNull(raw.phone_num ?? raw.phoneNum),
    password: strNull(raw.password),
    trx_address: strNull(raw.trx_address ?? raw.trxAddress),
    deposit_address: strNull(raw.deposit_address ?? raw.depositAddress),
    usdt_contract: strNull(raw.usdt_contract ?? raw.usdtContract),
    min_deposit_usdt: strNull(raw.min_deposit_usdt ?? raw.minDepositUsdt)
  };
}

export const postAdminCreateUsersByCount = async (data: AdminCreateByCountBody) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/create-by-count",
    { data }
  );
  const accountsRaw = Array.isArray(raw.accounts) ? raw.accounts : [];
  return {
    ok: Boolean(raw.ok ?? true),
    password: String(raw.password ?? data.password),
    count: num(raw.count, data.count),
    success_count: num(raw.success_count ?? raw.successCount, accountsRaw.length),
    fail_count: num(raw.fail_count ?? raw.failCount, 0),
    accounts: accountsRaw.map(it =>
      normalizeCreateByCountAccount(it as Record<string, unknown>)
    )
  } as AdminCreateByCountResponse;
};

export type AdminUserGenerationStatus =
  | "pending"
  | "running"
  | "success"
  | "partial_failed"
  | "failed";

export type AdminUserGenerationTask = {
  task_no: string;
  status: AdminUserGenerationStatus;
  created_by: string;
  requested_count: number;
  processed_count: number;
  success_count: number;
  fail_count: number;
  last_error?: string | null;
  started_at?: string | number | null;
  finished_at?: string | number | null;
  created_at: string | number;
  updated_at: string | number;
  expires_at: string | number;
};

export type AdminUserGenerationAccount = {
  index: number;
  status: "pending" | "running" | "success" | "failed";
  user_uid?: string | null;
  nickname: string;
  trx_address?: string | null;
  deposit_address?: string | null;
  usdt_contract?: string | null;
  min_deposit_usdt?: string | null;
  error_code?: string | null;
  error_message?: string | null;
};

export type AdminUserGenerationTaskCreated = {
  task_no: string;
  status: AdminUserGenerationStatus;
  requested_count: number;
  created_at: string | number;
};

export type AdminUserGenerationTaskPage = {
  items: AdminUserGenerationTask[];
  total: number;
  page: number;
  page_size: number;
};

export type AdminUserGenerationTaskDetail = {
  task: AdminUserGenerationTask;
  accounts: AdminUserGenerationAccount[];
};

export type AdminUserGenerationTaskParams = {
  page: number;
  page_size: number;
  task_no?: string;
  created_by?: string;
  status?: AdminUserGenerationStatus | "";
  created_from?: string;
  created_to?: string;
};

export const postAdminUserGenerationTask = (data: AdminCreateByCountBody) =>
  http.request<AdminUserGenerationTaskCreated>(
    "post",
    "/api/v1/users/generation-tasks",
    { data }
  );

export const getAdminUserGenerationTasks = (
  params: AdminUserGenerationTaskParams
) =>
  http.request<AdminUserGenerationTaskPage>(
    "get",
    "/api/v1/users/generation-tasks",
    { params }
  );

export const getAdminUserGenerationTask = (taskNo: string) =>
  http.request<AdminUserGenerationTaskDetail>(
    "get",
    `/api/v1/users/generation-tasks/${encodeURIComponent(taskNo)}`
  );

export const postAdminCreateUser = async (data: AdminCreateUserBody) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/create",
    { data }
  );
  return {
    ok: Boolean(raw.ok ?? true),
    user_uid: String(raw.user_uid ?? raw.userUid ?? ""),
    nickname: String(raw.nickname ?? data.nickname),
    phone: strNull(raw.phone),
    phone_num: strNull(raw.phone_num ?? raw.phoneNum),
    trx_address: strNull(raw.trx_address ?? raw.trxAddress),
    deposit_address: strNull(raw.deposit_address ?? raw.depositAddress),
    usdt_contract: strNull(raw.usdt_contract ?? raw.usdtContract),
    min_deposit_usdt: strNull(raw.min_deposit_usdt ?? raw.minDepositUsdt)
  } as AdminCreateUserResponse;
};

export const postAdminCreateUsersBatch = async (data: AdminCreateUserBatchBody) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/create-batch",
    { data }
  );
  const itemsRaw = Array.isArray(raw.items) ? raw.items : [];
  const items = itemsRaw.map((it, index) => {
    const row = it as Record<string, unknown>;
    return {
      index: num(row.index, index),
      ok: Boolean(row.ok),
      user_uid:
        row.user_uid != null
          ? String(row.user_uid)
          : row.userUid != null
            ? String(row.userUid)
            : undefined,
      nickname: row.nickname != null ? String(row.nickname) : undefined,
      phone_num: strNull(row.phone_num ?? row.phoneNum),
      trx_address: strNull(row.trx_address ?? row.trxAddress),
      deposit_address: strNull(row.deposit_address ?? row.depositAddress),
      usdt_contract: strNull(row.usdt_contract ?? row.usdtContract),
      min_deposit_usdt: strNull(row.min_deposit_usdt ?? row.minDepositUsdt),
      error: row.error != null ? String(row.error) : null,
      message: row.message != null ? String(row.message) : null,
      field: row.field != null ? String(row.field) : null
    } as AdminCreateUserBatchItem;
  });
  return {
    items,
    total: num(raw.total, items.length),
    success_count: num(raw.success_count ?? raw.successCount, 0),
    fail_count: num(raw.fail_count ?? raw.failCount, 0)
  } as AdminCreateUserBatchResponse;
};

function strNull(v: unknown): string | null {
  if (v == null || v === "") return null;
  return String(v);
}

function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

export type WalletBalanceAdjustOk = {
  ok: true;
  user_uid: ImUserUid;
  currency?: string;
  direction: string;
  amount: string;
  balance_before: string;
  balance_after: string;
  transaction_no?: string;
};

export const postWalletBalanceAdjust = async (data: WalletBalanceAdjustBody) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/wallet/balance-adjust",
    { data: { ...data, user_uid: normalizeImUserUid(data.user_uid) } }
  );
  return normalizeWriteOkResponse(raw) as WalletBalanceAdjustOk;
};

export const postUserLoginPassword = async (data: {
  user_uid: ImUserUid;
  new_password: string;
}) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/login-password",
    { data: { ...data, user_uid: normalizeImUserUid(data.user_uid) } }
  );
  return normalizeWriteOkResponse(raw) as { ok: boolean; user_uid: ImUserUid };
};

export const postUserFundPassword = async (data: {
  user_uid: ImUserUid;
  new_fund_password: string;
}) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/fund-password",
    { data: { ...data, user_uid: normalizeImUserUid(data.user_uid) } }
  );
  return normalizeWriteOkResponse(raw) as { ok: boolean; user_uid: ImUserUid };
};

export const postUserNickname = async (data: {
  user_uid: ImUserUid;
  nickname: string;
}) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/nickname",
    { data: { ...data, user_uid: normalizeImUserUid(data.user_uid) } }
  );
  return {
    ok: Boolean(raw.ok ?? true),
    user_uid: String(raw.user_uid ?? raw.userUid ?? data.user_uid),
    nickname: String(raw.nickname ?? data.nickname)
  };
};

/** `POST /api/v1/users/login-unfreeze`（`user.write`）§3.5：解冻 Java 内存中登录试错冻结 */
export type UserLoginUnfreezeBody = {
  user_uid?: ImUserUid;
  login_key?: string;
};

export const postUserLoginUnfreeze = (data: UserLoginUnfreezeBody) => {
  const body = {
    ...data,
    ...(data.user_uid != null
      ? { user_uid: normalizeImUserUid(data.user_uid) }
      : {})
  };
  return http.request<{
    ok: boolean;
    login_key?: string;
    hint?: string;
  }>("post", "/api/v1/users/login-unfreeze", { data: body });
};

/** `POST /api/v1/users/login-disabled`（`user.write`）§3.5：控制是否允许 IM 登录 */
export type UserLoginDisabledBody = {
  user_uid: ImUserUid;
  disabled: boolean;
  clear_http_token?: boolean;
};

export type UserLoginDisabledOk = {
  ok: boolean;
  user_uid: ImUserUid;
  disabled?: boolean;
  user_status?: number;
  http_token_cleared?: boolean;
};

export const postUserLoginDisabled = async (data: UserLoginDisabledBody) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/login-disabled",
    { data: { ...data, user_uid: normalizeImUserUid(data.user_uid) } }
  );
  return normalizeWriteOkResponse(raw) as UserLoginDisabledOk;
};

/** `POST /api/v1/users/game-privileged`（`user.write`）：游戏功能特权用户 */
export type UserGamePrivilegedBody = {
  user_uid: ImUserUid;
  game_privileged: boolean;
};

export type UserGamePrivilegedOk = {
  ok: boolean;
  user_uid: ImUserUid;
  game_privileged?: boolean;
  game_enabled_effective?: boolean;
  master_enabled?: boolean;
};

export const postUserGamePrivileged = async (data: UserGamePrivilegedBody) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/game-privileged",
    { data: { ...data, user_uid: normalizeImUserUid(data.user_uid) } }
  );
  return normalizeWriteOkResponse(raw) as UserGamePrivilegedOk;
};

/** `POST /api/v1/users/skip-device-sms`（`user.write`）：密码登录永久免设备短信验证 */
export type UserSkipDeviceSmsBody = {
  user_uid: ImUserUid;
  enabled: boolean;
};

export type UserSkipDeviceSmsOk = {
  ok: boolean;
  user_uid: ImUserUid;
  skip_device_sms?: boolean;
};

export const postUserSkipDeviceSms = async (data: UserSkipDeviceSmsBody) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/users/skip-device-sms",
    { data: { ...data, user_uid: normalizeImUserUid(data.user_uid) } }
  );
  return normalizeWriteOkResponse(raw) as UserSkipDeviceSmsOk;
};

/** 无 `VITE_USER_AVATAR_BASE_URL` 时：`.../avatar/{user_avatar_file_name}`（文件名场景） */
export const DEFAULT_USER_AVATAR_BASE_URL =
  "https://jlchat.oss-cn-hongkong.aliyuncs.com/avatar";

function resolveAvatarBaseUrl(avatarBaseUrl: string): string {
  const fromEnv = String(avatarBaseUrl || "").trim().replace(/\/$/, "");
  if (fromEnv) return fromEnv;
  return DEFAULT_USER_AVATAR_BASE_URL.replace(/\/$/, "");
}

/** 接口返回完整图片 URL 时直接使用；否则按文件名与 OSS 前缀拼接 */
function isAbsoluteAvatarSrc(value: string): boolean {
  const v = value.trim();
  if (!v) return false;
  if (/^https?:\/\//i.test(v)) return true;
  if (v.startsWith("//")) return true;
  if (v.startsWith("data:")) return true;
  return false;
}

function resolveUserAvatarDisplayUrl(
  userAvatarFileName: string | null | undefined,
  avatarBaseUrl: string
): string {
  if (userAvatarFileName == null || userAvatarFileName === "") return "";
  const raw = String(userAvatarFileName).trim();
  if (isAbsoluteAvatarSrc(raw)) return raw;
  const base = resolveAvatarBaseUrl(avatarBaseUrl);
  return base ? `${base}/${raw.replace(/^\//, "")}` : "";
}

export function walletAvailableMinor(
  balanceStr: string | null | undefined,
  frozenStr: string | null | undefined
): number {
  const b =
    balanceStr != null && balanceStr !== ""
      ? Number(balanceStr)
      : Number.NaN;
  const f =
    frozenStr != null && frozenStr !== "" ? Number(frozenStr) : Number.NaN;
  const bx = Number.isFinite(b) ? b : 0;
  const fx = Number.isFinite(f) ? f : 0;
  return Math.round(Math.max(bx - fx, 0) * 100) / 100;
}

const ADMIN_ONLINE_STALE_MS = 90 * 1000;

function parseAdminPresenceTime(value: unknown): number {
  if (value == null || value === "") return Number.NaN;
  if (typeof value === "number") {
    // 兼容秒/毫秒时间戳
    const ms = value < 1e12 ? value * 1000 : value;
    return Number.isFinite(ms) ? ms : Number.NaN;
  }
  const text = String(value).trim();
  if (!text) return Number.NaN;
  if (/^\d+$/.test(text)) {
    const n = Number(text);
    const ms = n < 1e12 ? n * 1000 : n;
    return Number.isFinite(ms) ? ms : Number.NaN;
  }
  const normalized = text.includes("T") ? text : text.replace(" ", "T");
  const t = Date.parse(normalized);
  return Number.isFinite(t) ? t : Number.NaN;
}

function isRecentAdminPresence(item: AdminUserListItem): boolean {
  const until = parseAdminPresenceTime(item.online_until);
  if (Number.isFinite(until)) return until > Date.now();

  const t = parseAdminPresenceTime(item.last_heartbeat_at ?? item.last_seen_at);
  if (!Number.isFinite(t)) {
    // 大厂标准：没有 heartbeat / online_until 权威字段时不能用最近登录猜在线。
    // 登录时间只能表示“登录过”，不能表示“当前在线”。
    return false;
  }
  return Date.now() - t <= ADMIN_ONLINE_STALE_MS;
}

export function isAdminUserActuallyOnline(item: AdminUserListItem): boolean {
  return item.is_online === 1 && isRecentAdminPresence(item);
}

/**
 * 表格/弹窗行（`id` / `uid` 均为腾讯云 IM 号 user_uid 字符串）
 */
export function mapAdminUserItemToTableRow(
  item: AdminUserListItem,
  avatarBaseUrl: string
): Record<string, unknown> {
  const uid = normalizeImUserUid(item.user_uid);
  const avatar = resolveUserAvatarDisplayUrl(
    item.user_avatar_file_name,
    avatarBaseUrl
  );

  const wallet = item.wallet_balance;
  const balance =
    wallet != null && wallet !== "" ? Number(wallet) : Number.NaN;
  const frozenNum =
    item.wallet_frozen_amount != null && item.wallet_frozen_amount !== ""
      ? Number(item.wallet_frozen_amount)
      : Number.NaN;
  const avail = walletAvailableMinor(item.wallet_balance, item.wallet_frozen_amount);

  return {
    id: uid,
    uid,
    nickname: item.nickname ?? "",
    phone: item.phone_num ?? "",
    userMail: item.user_mail ?? "",
    avatar: avatar || undefined,
    online: isAdminUserActuallyOnline(item),
    loginDevices: formatLoginDevicesLabel(item.device_type, item.device_model),
    deviceModel: item.device_model ?? "",
    loginIp: item.latest_login_ip ?? "",
    locationCityLabel: item.location_city_label ?? item.user_regieon ?? "",
    balance: Number.isFinite(balance) ? balance : null,
    walletFrozen: item.wallet_frozen_amount,
    availableBalance: avail,
    trxAddress: item.trx_address ?? item.deposit_address ?? "",
    friendCount:
      typeof item.friend_count === "number" ? item.friend_count : null,
    groupCount: typeof item.group_count === "number" ? item.group_count : null,
    signature: (item.what_s_up ?? item.user_desc ?? "") || "",
    /** 规范化：仅 1 展示为「正常」标签；禁用接口请用 `userStatusRaw` */
    status: item.user_status === 1 ? 1 : 0,
    userStatusRaw: item.user_status,
    lastLoginTime: item.latest_login_time,
    registerTime: item.register_time,
    registerIp: item.register_ip,
    userSex: item.user_sex,
    userType: item.user_type,
    frozenAmountNum: Number.isFinite(frozenNum) ? frozenNum : 0,
    _raw: item
  };
}

/** 详情页展示模型 */
export function mapAdminUserItemToDetailView(
  item: AdminUserListItem,
  avatarBaseUrl: string
): Record<string, unknown> {
  const row = mapAdminUserItemToTableRow(item, avatarBaseUrl);
  return {
    ...row,
    email: item.user_mail ?? "",
    region: item.user_regieon ?? "",
    walletBalanceStr: item.wallet_balance,
    frozenAmountStr: item.wallet_frozen_amount,
    userSexLabel:
      item.user_sex === 1 ? "男" : item.user_sex === 0 ? "女" : "—",
    nicknameLastModified: item.nickname_last_modified_time,
    nicknameLastModified2: item.nickname_last_modified_time2,
    terminationTime: item.termination_time,
    userTypeCode: item.user_type,
    avatarFileName: item.user_avatar_file_name ?? "",
    whatSup: item.what_s_up ?? "",
    userDescText: item.user_desc ?? "",
    friendCount:
      typeof item.friend_count === "number" ? item.friend_count : null,
    groupCount: typeof item.group_count === "number" ? item.group_count : null,
    gamePrivileged: Boolean(item.game_privileged),
    skipDeviceSms: Boolean(item.skip_device_sms)
  };
}

/** `GET /api/v1/users/login-logs`（全站设备登录流水，§3.4） */
export type UserLoginLogsParams = {
  page?: number;
  page_size?: number;
  sort?: string;
  user_uid?: ImUserUid;
  login_ip?: string;
  device_type?: number;
  login_time_from?: string;
  login_time_to?: string;
};

export type UserLoginLogsResponse = {
  source?: string;
  items: Record<string, unknown>[];
  total: number;
  page?: number;
  page_size?: number;
};

export const getUserLoginLogs = (params: UserLoginLogsParams) => {
  return http.request<UserLoginLogsResponse>("get", "/api/v1/users/login-logs", {
    params
  });
};

/**
 * IM 端类型展示（Rainbow / Java 与库表一致）
 *
 * - **`missu_user_device_history.device_type`**（登录日志、详情 devices）：每次登录一条，按此字段区分端。
 * - **`missu_users.device_type`**（用户列表/详情 profile）：表示近期/当前常用端，**数值含义与上表相同**，不是另一套枚举。
 *
 * | 值 | 含义 |
 * |----|------|
 * | -1 | 未定义 |
 * | 0 | Android |
 * | 1 | iOS |
 * | 2 | Web |
 *
 * 历史上前端曾误用 0～5「未分类/Android/iOS…」另一套映射，导致与用户详情、登录日志不一致。
 */
/** 登录记录「结果」列：将 fail_reason / status 码转为中文展示 */
export function formatLoginResultStatus(status: unknown): string {
  if (status == null || status === "") return "—";
  const s = String(status).trim();
  const m: Record<string, string> = {
    成功: "成功",
    失败: "失败",
    NEED_SMS: "待短信验证",
    BAD_CREDENTIALS: "账号或密码错误",
    SMS_CODE_INVALID: "验证码错误",
    ACCOUNT_DISABLED: "账号已禁用",
    DEVICE_BANNED: "设备已封禁",
    USER_NOT_FOUND: "用户不存在"
  };
  return m[s] ?? s;
}

export function formatLoginHistoryDeviceType(t: unknown): string {
  const v = Number(t);
  const m: Record<number, string> = {
    [-1]: "未定义",
    0: "Android",
    1: "iOS",
    2: "Web"
  };
  if (!Number.isFinite(v)) return "—";
  return m[v] ?? `类型(${v})`;
}

export function formatLoginDevicesLabel(
  deviceType: unknown,
  deviceModel?: string | null
): string {
  const platform = formatLoginHistoryDeviceType(deviceType);
  const model =
    displayDeviceModel(deviceTypeToPlatform(deviceType), deviceModel) ?? "";
  if (platform === "—" && !model) return "—";
  if (!model) return platform;
  if (platform === "—") return model;
  return `${platform} · ${model}`;
}

function pickLoginTimeForDisplay(raw: unknown): string | number | null {
  if (raw == null) return null;
  const n = Number(raw);
  if (Number.isFinite(n)) {
    if (n > 946684800000) return n;
    if (n > 946684800) return n * 1000;
  }
  return typeof raw === "string" ? raw : String(raw);
}

export function mapUserLoginLogRow(r: Record<string, unknown>) {
  const t = pickLoginTimeForDisplay(r.login_time2 ?? r.login_time);
  return {
    id: r.history_id ?? r.historyId ?? r.id,
    uid: String(r.user_uid ?? ""),
    nickname: String(r.user_nickname ?? ""),
    clientType: formatLoginHistoryDeviceType(r.device_type),
    deviceModel:
      displayDeviceModel(
        deviceTypeToPlatform(r.device_type),
        String(r.device_model ?? r.deviceModel ?? r.model ?? "")
      ) ?? "",
    loginAt: t,
    ip: String(r.login_ip ?? ""),
    ua: String(r.device_info ?? ""),
    hw: r.hardware_id != null ? String(r.hardware_id) : "",
    appVersion: String(r.client_version ?? r.clientVersion ?? "").trim(),
    tokenMasked:
      r.device_token_masked != null ? String(r.device_token_masked) : "",
    statusLabel: formatLoginResultStatus(r.status),
    isCurrent: r.is_current === 1 || r.is_current === true,
    _raw: r
  };
}

/** Axios 异常 `error.response.data.message`（如有） */
export function adminApiErrMessage(err: unknown, fallback = "请求失败"): string {
  const ax = err as {
    response?: { data?: { error?: string; message?: string } };
    message?: string;
  };
  const d = ax?.response?.data;
  return (
    d?.message ||
    d?.error ||
    ax?.message ||
    fallback
  );
}
