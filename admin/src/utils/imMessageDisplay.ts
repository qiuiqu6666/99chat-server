/**
 * IM 消息摘要（与后台列表 / 审计展示约定一致，见产品文档 §3.8.1）
 * ——msg_type、msg_content 与 missu_user_msgs_collect / 读扩散表一致
 */

import { expandImBracketEmoticons } from "@/utils/imBracketEmoticons";

const MSG_TYPE_NAMES: Record<number, string> = {
  0: "文本",
  1: "图片",
  2: "语音",
  3: "送礼(出)",
  4: "送礼(索)",
  5: "文件",
  6: "短视频",
  7: "名片",
  8: "位置",
  9: "通话",
  10: "红包",
  11: "转账",
  90: "系统",
  91: "撤回"
};

function tryParseJson<T = unknown>(s: string): T | null {
  try {
    return JSON.parse(s) as T;
  } catch {
    return null;
  }
}

function coerceInt(v: unknown): number | null {
  if (v === null || v === undefined || v === "") return null;
  if (typeof v === "number" && Number.isFinite(v)) return v;
  const n = Number(v);
  return Number.isFinite(n) ? n : null;
}

function normalizeToString(v: unknown): string {
  if (v == null) return "";
  if (typeof v === "string") return v;
  if (typeof v === "object") return JSON.stringify(v);
  return String(v);
}

function shorten(s: string, max = 200): string {
  const t = s.trim();
  if (t.length <= max) return t;
  return `${t.slice(0, max)}…`;
}

/** 秒 → mm:ss 或 hh:mm:ss */
export function formatVoipDurationSeconds(sec: number): string {
  const s = Math.max(0, Math.floor(sec));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const r = s % 60;
  if (h > 0) {
    return `${h}:${String(m).padStart(2, "0")}:${String(r).padStart(2, "0")}`;
  }
  return `${m}:${String(r).padStart(2, "0")}`;
}

export function unwrapMsgContent(
  msgType: unknown,
  msgContent: unknown
): { effectiveMsgType: number | null; bodyForFormat: string } {
  let effectiveType = coerceInt(msgType);

  let raw = normalizeToString(msgContent).trim();
  const parsed = tryParseJson<Record<string, unknown>>(raw);

  if (
    parsed &&
    typeof parsed === "object" &&
    parsed !== null &&
    "m" in parsed &&
    parsed.m !== undefined
  ) {
    const mVal = parsed.m;
    raw =
      typeof mVal === "string"
        ? mVal
        : mVal != null && typeof mVal === "object"
          ? JSON.stringify(mVal)
          : normalizeToString(mVal);

    const innerTy = coerceInt(parsed.ty);
    if (innerTy !== null) {
      effectiveType = innerTy;
    }
  }

  return { effectiveMsgType: effectiveType, bodyForFormat: raw };
}

function formatVoipBody(body: string): string {
  const o = tryParseJson<{
    voipType?: number;
    recordType?: number;
    duration?: number;
    type?: string;
  }>(body.trim());
  if (!o || typeof o !== "object") return shorten(body);

  let vt = o.voipType;
  if (vt !== 0 && vt !== 1) {
    if (o.type === "voice") vt = 0;
    else if (o.type === "video") vt = 1;
  }
  const medium =
    vt === 1 ? "视频通话" : vt === 0 ? "语音通话" : "音视频通话";

  const rt = o.recordType;
  if (rt === 0) return `${medium} · 已取消`;
  if (rt === 1) {
    return `${medium} · 对方已拒绝`;
  }
  if (rt === 2) {
    return `${medium} · 对方无应答`;
  }
  if (rt === 3) {
    const d = typeof o.duration === "number" ? o.duration : 0;
    return `${medium} · 已接通 ${formatVoipDurationSeconds(d)}`;
  }
  return `${medium} · ${shorten(body)}`;
}

function formatImageBody(body: string): string {
  const t = body.trim();
  if (!t) return "图片";
  const j = tryParseJson<{ f?: string; w?: number; h?: number }>(t);
  if (j && typeof j.f === "string" && j.f) {
    const wh =
      typeof j.w === "number" && typeof j.h === "number"
        ? ` · ${j.w}×${j.h}`
        : "";
    return `图片 · ${j.f}${wh}`;
  }
  if (/^[a-f0-9]{32}$/i.test(t)) return `图片 · ${t}`;
  return `图片 · ${shorten(t)}`;
}

function formatPipeFile(body: string, label: string): string {
  const parts = body.split("|").map(s => s.trim());
  const name = parts[0] || body;
  return `${label} · ${shorten(name || body)}`;
}

function formatGiftBody(body: string, outgoing: boolean): string {
  return outgoing ? `送礼(发出) · ${shorten(body)}` : `送礼(索取) · ${shorten(body)}`;
}

function formatJsonWalletSnippet(body: string, label: string): string {
  const o = tryParseJson<Record<string, unknown>>(body.trim());
  if (o && typeof o === "object") {
    const pid =
      o.packet_id ?? o.transfer_id ?? o.id ?? o.biz_id ?? o.order_id;
    if (
      typeof pid === "string" ||
      typeof pid === "number"
    ) {
      return `${label} · id=${pid}`;
    }
  }
  return `${label} · ${shorten(body)}`;
}

function formatBodyByMsgType(typeNum: number, body: string): string {
  const b = body.trim();
  if (!b && typeNum !== 0) return MSG_TYPE_NAMES[typeNum] ?? `[类型${typeNum}]`;

  switch (typeNum) {
    case 0:
      return shorten(b);
    case 1:
      return formatImageBody(b);
    case 2:
      return `语音 · ${shorten(b)}`;
    case 3:
      return formatGiftBody(b, true);
    case 4:
      return formatGiftBody(b, false);
    case 5:
      return formatPipeFile(b, "文件");
    case 6:
      return formatPipeFile(b, "短视频");
    case 7:
      return `名片 · ${shorten(b)}`;
    case 8:
      return `位置 · ${shorten(b)}`;
    case 9:
      return formatVoipBody(b);
    case 10:
      return formatJsonWalletSnippet(b, "红包");
    case 11:
      return formatJsonWalletSnippet(b, "转账");
    case 90:
      return `系统 · ${shorten(b)}`;
    case 91:
      return `撤回 · ${shorten(b) || "已撤回"}`;
    default:
      return `[类型${typeNum}] ${shorten(b)}`;
  }
}

export function pickMsgRowFields(row: Record<string, unknown>): {
  msgType: unknown;
  msgContent: unknown;
} {
  return {
    msgType: row.msg_type ?? row.type ?? row.tim_msg_type,
    msgContent:
      row.msg_content ??
      row.history_content ??
      row.content ??
      row.text_preview ??
      row.textPreview ??
      ""
  };
}

/**
 * 表格「内容」列：嵌套 JSON（ty/m）先解包，再按有效 msg_type 做摘要
 */
export function formatImMessageForAudit(row: Record<string, unknown>): string {
  const { msgType, msgContent } = pickMsgRowFields(row);
  const { effectiveMsgType, bodyForFormat } = unwrapMsgContent(
    msgType,
    msgContent
  );

  const fallbackOuter = coerceInt(msgType);
  const typeNum =
    effectiveMsgType !== null ? effectiveMsgType : fallbackOuter;

  if (typeNum === null) {
    const raw = normalizeToString(msgContent);
    if (!raw.trim()) return "—";
    return expandImBracketEmoticons(shorten(raw));
  }

  const summary = formatBodyByMsgType(typeNum, bodyForFormat);
  if (!summary.trim()) return "—";
  return expandImBracketEmoticons(summary);
}

/** 表格「类型」列：如 `9·通话`（仅外层 msg_type） */
export function describeMsgTypeForCell(msgType: unknown): string {
  const n = coerceInt(msgType);
  if (n === null) return "—";
  const name = MSG_TYPE_NAMES[n];
  return name ? `${n}·${name}` : String(n);
}

/**
 * 表格「类型」列（嵌套同步结构）：若 `msg_content` 解包后含 `ty`，与内容列口径一致
 */
export function describeMsgTypeForAuditRow(row: Record<string, unknown>): string {
  const { msgType, msgContent } = pickMsgRowFields(row);
  const { effectiveMsgType } = unwrapMsgContent(msgType, msgContent);
  const n =
    effectiveMsgType !== null ? effectiveMsgType : coerceInt(msgType);
  if (n === null) return "—";
  const name = MSG_TYPE_NAMES[n];
  return name ? `${n}·${name}` : String(n);
}

/** 仅标签文案（不含数字） */
export function msgTypeLabel(msgType: unknown): string {
  const n = coerceInt(msgType);
  if (n === null) return "";
  return MSG_TYPE_NAMES[n] ?? "";
}
