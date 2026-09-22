import dayjs from "dayjs";
import type { AdminAuditLogItem } from "@/api/im-admin-logs";

/** ???? ? ???? */
const ACTION_LABELS: Record<string, string> = {
  "client_version.create": "???????",
  "client_version.update": "???????",
  "client_version.delete": "???????",
  "splash.create": "\u4e0a\u4f20\u542f\u52a8\u56fe",
  "splash.update": "\u66f4\u65b0\u542f\u52a8\u56fe",
  "splash.delete": "\u5220\u9664\u542f\u52a8\u56fe",
  "exchange_config.update": "??????",
  "currency.update": "??????",
  "feedback.status.update": "??????",
  "announcement.send": "????",
  "announcement.schedule": "??????",
  "wallet.withdraw.approve": "??????",
  "wallet.withdraw.reject": "??????",
  "album.delete": "??????",
  "device.ban": "????",
  "device.unban": "????",
  "device.kick": "????",
  "user.login_disabled.set": "??????",
  "user.login_password.reset": "??????",
  "user.nickname.update": "\u4fee\u6539\u7528\u6237\u6635\u79f0",
  "user.fund_password.set": "??????",
  "user.wallet.balance_adjust": "??????",
  "user.create": "????",
  "user.create_by_count": "??????",
  "user.login_unfreeze": "??????"
};

/** ???? ? ?? */
const RESOURCE_TYPE_LABELS: Record<string, string> = {
  user: "??",
  client_version: "?????",
  announcement: "??",
  currency: "??",
  wallet: "??",
  feedback: "????",
  exchange_config: "????",
  device: "??",
  album: "??"
};

const PLATFORM_LABELS: Record<string, string> = {
  android: "Android",
  ios: "iOS",
  web: "Web"
};

const CONTENT_TYPE_LABELS: Record<string, string> = {
  text: "??",
  image: "??",
  video: "??"
};

const SCOPE_LABELS: Record<string, string> = {
  all: "????",
  group: "????",
  users: "????"
};

const FIELD_LABELS: Record<string, string> = {
  id: "ID",
  platform: "??",
  version: "???",
  code: "????",
  name: "??",
  count: "??",
  scheduled_at: "??????",
  content_type: "????",
  scope: "????",
  announcement_ids: "?? ID",
  withdraw_id: "????",
  remark: "??",
  feedbackId: "?? ID",
  status: "??",
  currency: "??",
  direction: "??",
  amount: "??",
  transaction_no: "???",
  old_nickname: "\u539f\u6635\u79f0",
  new_nickname: "\u65b0\u6635\u79f0",
  nickname: "??",
  phone: "???",
  sex: "??",
  enabled: "??",
  deviceId: "?? ID",
  groupId: "?? ID"
};

function asObject(detail: unknown): Record<string, unknown> | null {
  if (detail == null) return null;
  if (typeof detail === "string") {
    try {
      const parsed = JSON.parse(detail);
      return typeof parsed === "object" && parsed !== null && !Array.isArray(parsed)
        ? (parsed as Record<string, unknown>)
        : null;
    } catch {
      return null;
    }
  }
  if (typeof detail === "object" && !Array.isArray(detail)) {
    return detail as Record<string, unknown>;
  }
  return null;
}

function fmtTime(v: unknown): string {
  if (v == null || v === "") return "";
  const s = String(v);
  const d = dayjs(s);
  return d.isValid() ? d.format("YYYY-MM-DD HH:mm:ss") : s;
}

function fmtValue(key: string, value: unknown): string {
  if (value == null || value === "") return "\u2014";
  if (Array.isArray(value)) {
    return value.length ? value.map(String).join("\u3001") : "\u2014";
  }
  if (key === "platform") {
    const p = String(value).toLowerCase();
    return PLATFORM_LABELS[p] || String(value);
  }
  if (key === "content_type") {
    return CONTENT_TYPE_LABELS[String(value).toLowerCase()] || String(value);
  }
  if (key === "scope") {
    return SCOPE_LABELS[String(value).toLowerCase()] || String(value);
  }
  if (key === "direction") {
    return String(value) === "add" ? "??" : String(value) === "subtract" ? "??" : String(value);
  }
  if (key === "scheduled_at" || key.endsWith("_at") || key.endsWith("_time")) {
    return fmtTime(value) || String(value);
  }
  if (typeof value === "boolean") return value ? "\u662f" : "\u5426";
  return String(value);
}

export function formatAuditAction(action?: string | null): string {
  if (!action) return "\u2014";
  return ACTION_LABELS[action] || action.replace(/\./g, " \u00b7 ");
}

export function formatResourceType(type?: string | null): string {
  if (!type) return "\u2014";
  return RESOURCE_TYPE_LABELS[type] || type;
}

export function formatResourceTarget(row: AdminAuditLogItem): string {
  const type = formatResourceType(row.resource_type);
  const id = row.resource_id?.trim();
  if (type === "\u2014" && !id) return "\u2014";
  if (type === "\u7528\u6237" && id) return `\u7528\u6237 ${id}`;
  if (id) return `${type} \u00b7 ${id}`;
  return type;
}

/** ?????????????? */
export function formatAuditDetailSummary(
  action?: string | null,
  detail: unknown = null,
  resourceId?: string | null
): string {
  const obj = asObject(detail);
  if (!obj || Object.keys(obj).length === 0) {
    if (resourceId) return `\u5173\u8054\uff1a${resourceId}`;
    return "\u2014";
  }

  switch (action) {
    case "client_version.create":
    case "client_version.update":
    case "client_version.delete":
      return `${fmtValue("platform", obj.platform)} ${obj.version ?? ""}`.trim();
    case "splash.create":
    case "splash.update":
    case "splash.delete":
      return [obj.version != null ? String(obj.version) : "", obj.id != null ? `#${obj.id}` : ""]
        .filter(Boolean)
        .join(" \u00b7 ");
    case "announcement.send":
    case "announcement.schedule":
      return [
        fmtValue("scope", obj.scope),
        fmtValue("content_type", obj.content_type),
        obj.count != null ? `${obj.count} \u6761` : "",
        action === "announcement.schedule" && obj.scheduled_at
          ? `\u8ba1\u5212\u4e8e ${fmtValue("scheduled_at", obj.scheduled_at)}`
          : ""
      ]
        .filter(Boolean)
        .join(" \u00b7 ");
    case "wallet.withdraw.approve":
    case "wallet.withdraw.reject":
      return [
        obj.withdraw_id != null ? `\u63d0\u73b0\u5355 #${obj.withdraw_id}` : "",
        obj.remark ? `\u5907\u6ce8\uff1a${obj.remark}` : ""
      ]
        .filter(Boolean)
        .join(" \u00b7 ");
    case "currency.update":
      return `\u5e01\u79cd ${obj.code ?? ""}\uff08${obj.name ?? ""}\uff09`.trim();
    case "user.wallet.balance_adjust":
      return `${fmtValue("direction", obj.direction)} ${obj.amount ?? ""} ${obj.currency ?? ""}`.trim();
    case "user.create":
      return [obj.nickname, obj.phone].filter(Boolean).map(String).join(" \u00b7 ") || "\u65b0\u5efa\u7528\u6237";
    case "user.nickname.update":
      return `${obj.old_nickname ?? "\u2014"} \u2192 ${obj.new_nickname ?? "\u2014"}`;
    case "feedback.status.update":
      return `\u53cd\u9988 #${obj.feedbackId ?? "\u2014"} \u2192 ${obj.status ?? "\u2014"}`;
    default:
      break;
  }

  const parts: string[] = [];
  for (const [key, value] of Object.entries(obj)) {
    if (value == null || value === "") continue;
    const label = FIELD_LABELS[key] || key;
    parts.push(`${label}\uff1a${fmtValue(key, value)}`);
  }
  return parts.length ? parts.slice(0, 4).join(" \u00b7 ") : "\u2014";
}

export type AuditDetailLine = { label: string; value: string };

/** ????????? */
export function formatAuditDetailLines(
  action?: string | null,
  detail: unknown = null,
  row?: AdminAuditLogItem
): AuditDetailLine[] {
  const lines: AuditDetailLine[] = [
    { label: "\u64cd\u4f5c", value: formatAuditAction(action) },
    { label: "\u64cd\u4f5c\u5bf9\u8c61", value: row ? formatResourceTarget(row) : "\u2014" }
  ];
  const obj = asObject(detail);
  if (!obj) return lines;

  const preferredKeys: Record<string, string[]> = {
    "client_version.create": ["platform", "version", "id"],
    "client_version.update": ["platform", "version", "id"],
    "client_version.delete": ["platform", "version", "id"],
    "splash.create": ["version", "id", "enabled"],
    "splash.update": ["version", "id", "enabled"],
    "splash.delete": ["id"],
    "announcement.send": ["scope", "content_type", "count", "announcement_ids"],
    "announcement.schedule": ["scope", "content_type", "count", "scheduled_at", "announcement_ids"],
    "wallet.withdraw.approve": ["withdraw_id", "remark"],
    "wallet.withdraw.reject": ["withdraw_id", "remark"],
    "currency.update": ["code", "name"],
    "user.wallet.balance_adjust": ["currency", "direction", "amount", "transaction_no"],
    "user.nickname.update": ["old_nickname", "new_nickname"],
    "user.create": ["nickname", "phone", "sex"],
    "feedback.status.update": ["feedbackId", "status"]
  };

  const keys = preferredKeys[action || ""] || Object.keys(obj);
  const seen = new Set<string>();
  for (const key of keys) {
    if (seen.has(key) || !(key in obj)) continue;
    seen.add(key);
    lines.push({
      label: FIELD_LABELS[key] || key,
      value: fmtValue(key, obj[key])
    });
  }
  for (const [key, value] of Object.entries(obj)) {
    if (seen.has(key)) continue;
    lines.push({
      label: FIELD_LABELS[key] || key,
      value: fmtValue(key, value)
    });
  }
  return lines;
}
