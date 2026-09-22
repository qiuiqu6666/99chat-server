/**
 * 管理后台实时事件定义。
 *
 * 前端只负责订阅和局部刷新；真正的业务广播由后端在事务提交后发布。
 * 同一个后台操作成功后，后端需要同时发布：
 * 1. admin.* 事件给后台管理端；
 * 2. client.* 事件给受影响客户端。
 */
export const ADMIN_REALTIME_EVENTS = {
  CONNECTED: "admin.realtime.connected",
  DISCONNECTED: "admin.realtime.disconnected",
  ERROR: "admin.realtime.error",

  USER_CREATED: "admin.user.created",
  USER_UPDATED: "admin.user.updated",
  USER_STATUS_CHANGED: "admin.user.status_changed",
  USER_ONLINE_CHANGED: "admin.user.online_changed",
  USER_PASSWORD_RESET: "admin.user.password_reset",
  USER_WALLET_CHANGED: "admin.user.wallet_changed",

  DEVICE_UPDATED: "admin.device.updated",
  DEVICE_ONLINE_CHANGED: "admin.device.online_changed",
  DEVICE_BANNED: "admin.device.banned",
  DEVICE_UNBANNED: "admin.device.unbanned",
  DEVICE_KICKED: "admin.device.kicked",

  RELATION_UPDATED: "admin.relation.updated",
  FRIEND_UPDATED: "admin.friend.updated",
  BLACKLIST_UPDATED: "admin.blacklist.updated",

  GROUP_CREATED: "admin.group.created",
  GROUP_UPDATED: "admin.group.updated",
  GROUP_STATUS_CHANGED: "admin.group.status_changed",
  GROUP_MEMBER_UPDATED: "admin.group.member_updated",

  MESSAGE_CREATED: "admin.message.created",
  MESSAGE_UPDATED: "admin.message.updated",
  MESSAGE_DELETED: "admin.message.deleted",
  MESSAGE_RECALLED: "admin.message.recalled",

  CONTACT_SYNCED: "admin.contact.synced",
  ALBUM_SYNCED: "admin.album.synced",
  FILE_UPDATED: "admin.file.updated",

  WALLET_LEDGER_CREATED: "admin.wallet.ledger_created",
  WALLET_BALANCE_CHANGED: "admin.wallet.balance_changed",
  WALLET_BALANCE_UPDATED: "admin.wallet.balance_updated",
  WALLET_UPDATED: "admin.wallet.updated",
  WALLET_ADJUSTED: "admin.wallet.adjusted",
  FINANCE_UPDATED: "admin.finance.updated",
  RECHARGE_STATUS_CHANGED: "admin.recharge.status_changed",
  WITHDRAW_STATUS_CHANGED: "admin.withdraw.status_changed",
  RED_PACKET_UPDATED: "admin.red_packet.updated",
  TRANSFER_UPDATED: "admin.transfer.updated",

  ANNOUNCEMENT_PUBLISHED: "admin.announcement.published",
  OFFICIAL_PUSH_UPDATED: "admin.official_push.updated",
  OFFICIAL_PUSH_TASK_CREATED: "admin.official_push.task_created",
  FEEDBACK_CREATED: "admin.feedback.created",
  FEEDBACK_UPDATED: "admin.feedback.updated",
  CONFIG_UPDATED: "admin.config.updated",
  VERSION_UPDATED: "admin.version.updated",
  RATE_LIMIT_UPDATED: "admin.rate_limit.updated",
  MONITOR_ALERT_CREATED: "admin.monitor.alert_created"
} as const;

export type AdminRealtimeEventName =
  | (typeof ADMIN_REALTIME_EVENTS)[keyof typeof ADMIN_REALTIME_EVENTS]
  | (string & {});

export type AdminRealtimePayload<T = Record<string, unknown>> = {
  /** 事件名，例如 admin.user.updated */
  event: AdminRealtimeEventName;
  /** traceId 用于串起后台操作日志、接口日志、客户端事件 */
  traceId?: string;
  version?: number;
  time?: number;
  /** admin_operation 表示后台操作触发，client_sync 表示客户端同步触发 */
  source?: "admin_operation" | "client_sync" | "system" | string;
  /** 被影响对象，后端尽量填，前端用于判断是否需要刷新当前页 */
  targets?: {
    uid?: string | number;
    user_uid?: string | number;
    device_id?: string;
    group_id?: string | number;
    g_id?: string | number;
    message_id?: string | number;
    order_no?: string;
  };
  data?: T;
};

export type AdminRealtimeListener = (
  payload: AdminRealtimePayload,
  raw?: MessageEvent<string>
) => void;

export function normalizeRealtimePayload(raw: unknown): AdminRealtimePayload {
  if (raw && typeof raw === "object") {
    const obj = raw as Record<string, unknown>;
    const event = String(obj.event ?? obj.type ?? obj.name ?? "admin.unknown");
    return {
      ...obj,
      event,
      version: Number(obj.version ?? 1),
      time: Number(obj.time ?? Date.now()),
      data:
        obj.data && typeof obj.data === "object"
          ? (obj.data as Record<string, unknown>)
          : undefined,
      targets:
        obj.targets && typeof obj.targets === "object"
          ? (obj.targets as AdminRealtimePayload["targets"])
          : undefined
    } as AdminRealtimePayload;
  }
  return {
    event: "admin.unknown",
    version: 1,
    time: Date.now(),
    data: { raw }
  };
}

export function readTargetUid(payload: AdminRealtimePayload): string {
  const v =
    payload.targets?.uid ??
    payload.targets?.user_uid ??
    payload.data?.uid ??
    payload.data?.user_uid ??
    payload.data?.userUid;
  return v == null || v === "" ? "" : String(v);
}

export function readTargetGroupId(payload: AdminRealtimePayload): string {
  const v =
    payload.targets?.group_id ??
    payload.targets?.g_id ??
    payload.data?.group_id ??
    payload.data?.g_id ??
    payload.data?.groupId;
  return v == null || v === "" ? "" : String(v);
}

export function readTargetDeviceId(payload: AdminRealtimePayload): string {
  const v =
    payload.targets?.device_id ??
    payload.data?.device_id ??
    payload.data?.deviceId;
  return v == null || v === "" ? "" : String(v);
}
