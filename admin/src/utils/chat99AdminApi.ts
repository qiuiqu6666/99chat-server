import type { AdminUserListItem, AdminUserListResponse } from "@/api/im-user";
import type { AdminLoginOk } from "@/utils/adminAuth";
import {
  deviceTypeToPlatform,
  displayDeviceModel
} from "@/utils/deviceModelDisplay";

/** 兼容 99chat Admin API：文档 snake_case，部分端点 Jackson 默认 camelCase */
function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function str(v: unknown): string | null {
  if (v == null || v === "") return null;
  return String(v);
}

function bool01(v: unknown): number {
  if (v === true || v === 1 || v === "1") return 1;
  return 0;
}

export function unwrapApiData(raw: Record<string, unknown>): Record<string, unknown> {
  const data = raw.data;
  if (data && typeof data === "object" && !Array.isArray(data)) {
    return data as Record<string, unknown>;
  }
  return raw;
}

export function normalizeAdminLoginResponse(raw: Record<string, unknown>): AdminLoginOk {
  raw = unwrapApiData(raw);
  const userRaw = (raw.user ?? raw.admin ?? {}) as Record<string, unknown>;
  const permissions = Array.isArray(userRaw.permissions)
    ? (userRaw.permissions as string[])
    : [];

  return {
    access_token: String(raw.access_token ?? raw.accessToken ?? raw.token ?? ""),
    token_type: String(raw.token_type ?? raw.tokenType ?? "Bearer"),
    expires_in: Math.max(60, num(raw.expires_in ?? raw.expiresIn, 86400)),
    user: {
      id: num(userRaw.id, 0) || undefined,
      username: String(userRaw.username ?? userRaw.account ?? ""),
      display_name: str(userRaw.display_name ?? userRaw.displayName ?? userRaw.nickname) ?? undefined,
      role: String(userRaw.role ?? "admin"),
      permissions
    }
  };
}

function imUserUid(v: unknown): string {
  if (v == null || v === "") return "";
  return String(v).trim();
}

function parseWalletBalancesMap(v: unknown): Record<string, string> | null {
  if (v == null || typeof v !== "object" || Array.isArray(v)) return null;
  const out: Record<string, string> = {};
  for (const [k, val] of Object.entries(v as Record<string, unknown>)) {
    if (val == null || val === "") continue;
    out[String(k).toUpperCase()] = String(val);
  }
  return Object.keys(out).length ? out : null;
}

/** 99chat users 列表单行 → 前端 AdminUserListItem */
export function normalizeAdminUserListItem(
  raw: Record<string, unknown>
): AdminUserListItem {
  return {
    user_uid: imUserUid(raw.user_uid ?? raw.userUid ?? raw.user_id ?? raw.userId),
    user_mail: str(raw.user_mail ?? raw.userMail ?? raw.email),
    nickname: str(raw.nickname),
    user_sex: num(raw.user_sex ?? raw.userSex, 2),
    phone_num: str(raw.phone_num ?? raw.phoneNum ?? raw.phone),
    register_ip: str(raw.register_ip ?? raw.registerIp),
    register_time: str(raw.register_time ?? raw.registerTime),
    latest_login_time: str(raw.latest_login_time ?? raw.latestLoginTime),
    last_seen_at: str(raw.last_seen_at ?? raw.lastSeenAt ?? raw.last_active_at ?? raw.lastActiveAt),
    last_heartbeat_at: str(raw.last_heartbeat_at ?? raw.lastHeartbeatAt ?? raw.heartbeat_at ?? raw.heartbeatAt),
    online_until: str(raw.online_until ?? raw.onlineUntil ?? raw.presence_expires_at ?? raw.presenceExpiresAt),
    latest_login_ip: str(raw.latest_login_ip ?? raw.latestLoginIp),
    user_status: num(raw.user_status ?? raw.userStatus ?? raw.status, 1),
    is_online: bool01(raw.is_online ?? raw.isOnline),
    user_avatar_file_name: str(
      raw.user_avatar_file_name ?? raw.userAvatarFileName ?? raw.avatar_url ?? raw.avatarUrl
    ),
    what_s_up: str(raw.what_s_up ?? raw.whatSUp ?? raw.signature),
    user_desc: str(raw.user_desc ?? raw.userDesc),
    user_type: num(raw.user_type ?? raw.userType, 0),
    user_regieon: str(
      raw.user_regieon ??
        raw.userRegieon ??
        raw.region ??
        raw.location_city_label ??
        raw.locationCityLabel
    ),
    location_city_label: str(
      raw.location_city_label ?? raw.locationCityLabel ?? raw.user_regieon ?? raw.userRegieon
    ),
    device_type:
      raw.device_type != null || raw.deviceType != null
        ? num(raw.device_type ?? raw.deviceType, -1)
        : null,
    device_model: displayDeviceModel(
      deviceTypeToPlatform(
        raw.device_type != null || raw.deviceType != null
          ? num(raw.device_type ?? raw.deviceType, -1)
          : null
      ),
      str(raw.device_model ?? raw.deviceModel ?? raw.model)
    ),
    termination_time: str(raw.termination_time ?? raw.terminationTime),
    nickname_last_modified_time: str(
      raw.nickname_last_modified_time ?? raw.nicknameLastModifiedTime
    ),
    nickname_last_modified_time2: num(
      raw.nickname_last_modified_time2 ?? raw.nicknameLastModifiedTime2,
      0
    ),
    wallet_balance: str(raw.wallet_balance ?? raw.walletBalance),
    wallet_frozen_amount: str(raw.wallet_frozen_amount ?? raw.walletFrozenAmount),
    wallet_balances: parseWalletBalancesMap(raw.wallet_balances ?? raw.walletBalances),
    wallet_frozen_by_currency: parseWalletBalancesMap(
      raw.wallet_frozen_by_currency ?? raw.walletFrozenByCurrency
    ),
    deposit_address: str(raw.deposit_address ?? raw.depositAddress),
    trx_address: str(
      raw.trx_address ??
        raw.trxAddress ??
        raw.tron_address ??
        raw.tronAddress ??
        raw.usdt_trc20_address ??
        raw.usdtTrc20Address ??
        raw.deposit_address ??
        raw.depositAddress
    ),
    friend_count:
      raw.friend_count != null || raw.friendCount != null
        ? num(raw.friend_count ?? raw.friendCount)
        : null,
    group_count:
      raw.group_count != null || raw.groupCount != null
        ? num(raw.group_count ?? raw.groupCount)
        : null,
    game_privileged: Boolean(raw.game_privileged ?? raw.gamePrivileged),
    skip_device_sms: Boolean(raw.skip_device_sms ?? raw.skipDeviceSms)
  };
}

export function normalizeAdminUserListResponse(
  raw: Record<string, unknown>
): AdminUserListResponse {
  const itemsRaw = Array.isArray(raw.items) ? raw.items : [];
  return {
    items: itemsRaw.map(it =>
      normalizeAdminUserListItem(it as Record<string, unknown>)
    ),
    total: num(raw.total),
    page: num(raw.page, 1),
    page_size: num(raw.page_size ?? raw.pageSize, 10),
    sort: String(raw.sort ?? "register_time_desc")
  };
}

/** 写操作通用 ok 响应 */
export function normalizeWriteOkResponse(
  raw: Record<string, unknown>
): Record<string, unknown> {
  if (raw.ok != null || raw.user_uid != null || raw.userUid != null) {
    return {
      ...raw,
      ok: raw.ok ?? true,
      user_uid: imUserUid(raw.user_uid ?? raw.userUid)
    };
  }
  return raw;
}

export function hasAdminAccessToken(raw: Record<string, unknown>): boolean {
  const t = raw.access_token ?? raw.accessToken;
  return typeof t === "string" && t.length > 0;
}
