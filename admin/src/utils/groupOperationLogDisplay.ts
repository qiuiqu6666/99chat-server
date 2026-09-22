const MSG_TYPE_LABEL: Record<string, string> = {
  TIMTextElem: "文本",
  TIMCustomElem: "自定义（群通知）",
  TIMGroupTipElem: "群提示",
  TIMGroupSystemElem: "群系统消息",
  TIMFaceElem: "表情",
  TIMImageElem: "图片",
  TIMSoundElem: "语音",
  TIMVideoFileElem: "视频",
  TIMFileElem: "文件",
  UNKNOWN: "未知类型"
};

const JOIN_STATUS_LABEL: Record<string, string> = {
  pending: "待审核",
  approved: "已通过",
  rejected: "已拒绝",
  accepted: "已同意",
  cancelled: "已取消"
};

function str(v: unknown): string {
  if (v == null || v === "") return "";
  return String(v).trim();
}

function labelOf(map: Record<string, string>, key: unknown, fallback = ""): string {
  const k = str(key);
  if (!k) return fallback;
  return map[k] ?? k;
}

function formatFromAccount(v: unknown): string {
  const s = str(v);
  if (!s) return "系统";
  if (s.startsWith("@TIM")) return "IM 系统";
  if (s.toLowerCase() === "administrator") return "管理员";
  return s;
}

function pickDetail(detail: Record<string, unknown>, ...keys: string[]): string {
  for (const key of keys) {
    const v = str(detail[key]);
    if (v) return v;
  }
  return "";
}

/** 群组操作日志「摘要」列 */
export function formatGroupLogSummary(
  kind: string,
  summary: unknown,
  detail: unknown
): string {
  const d = (detail && typeof detail === "object" ? detail : {}) as Record<
    string,
    unknown
  >;
  const raw = str(summary);

  if (kind === "join_request") {
    const uid = pickDetail(d, "user_uid", "applicant_uid", "request_user_uid");
    const status = labelOf(
      JOIN_STATUS_LABEL,
      d.status ?? d.review_status,
      str(d.status ?? d.review_status)
    );
    if (uid && status) return `${uid} 申请入群 · ${status}`;
    if (uid) return `${uid} 申请入群`;
    return raw || "入群申请";
  }

  if (kind === "member_mute") {
    const uid = pickDetail(d, "user_uid", "muted_user_uid", "member_uid");
    const until = pickDetail(d, "mute_until", "mute_end_time", "expire_time");
    if (uid && until) return `禁言 ${uid} 至 ${until}`;
    if (uid) return `禁言成员 ${uid}`;
    return raw || "成员禁言";
  }

  const text = pickDetail(d, "text_preview", "msg_content", "content");
  if (text) return text.length > 120 ? `${text.slice(0, 120)}…` : text;

  const msgType = labelOf(MSG_TYPE_LABEL, d.msg_type ?? raw, raw);
  const from = formatFromAccount(d.from_account);
  if (msgType && from) return `${from} · ${msgType}`;
  if (msgType) return msgType;
  return raw || "系统消息";
}

/** 群组操作日志「详情」列：可读中文，替代原始 JSON */
export function formatGroupOperationDetail(
  kind: string,
  detail: unknown
): string {
  if (detail == null) return "—";
  if (typeof detail !== "object" || Array.isArray(detail)) {
    return str(detail) || "—";
  }

  const d = detail as Record<string, unknown>;
  const parts: string[] = [];

  if (kind === "join_request") {
    const uid = pickDetail(d, "user_uid", "applicant_uid", "request_user_uid");
    const inviter = pickDetail(d, "inviter_uid", "be_invite_user_id", "invite_user_uid");
    const status = labelOf(
      JOIN_STATUS_LABEL,
      d.status ?? d.review_status,
      str(d.status ?? d.review_status) || "—"
    );
    const wording = pickDetail(d, "wording", "request_message", "apply_reason");
    const reviewer = pickDetail(d, "reviewer_uid", "review_user_uid");
    if (uid) parts.push(`申请人：${uid}`);
    if (inviter) parts.push(`邀请人：${inviter}`);
    parts.push(`状态：${status}`);
    if (reviewer) parts.push(`审核人：${reviewer}`);
    if (wording) parts.push(`附言：${wording}`);
    return parts.length ? parts.join("；") : "—";
  }

  if (kind === "member_mute") {
    const uid = pickDetail(d, "user_uid", "muted_user_uid", "member_uid");
    const operator = pickDetail(d, "operator_uid", "mute_by", "admin_uid");
    const until = pickDetail(d, "mute_until", "mute_end_time", "expire_time");
    const seconds = pickDetail(d, "mute_seconds", "duration_seconds");
    if (uid) parts.push(`被禁言：${uid}`);
    if (operator) parts.push(`操作人：${operator}`);
    if (until) parts.push(`截止：${until}`);
    else if (seconds) parts.push(`时长：${seconds} 秒`);
    return parts.length ? parts.join("；") : "—";
  }

  const msgType = labelOf(MSG_TYPE_LABEL, d.msg_type, str(d.msg_type) || "—");
  const from = formatFromAccount(d.from_account);
  const text = pickDetail(d, "text_preview", "msg_content", "content");
  const msgKey = pickDetail(d, "msg_key", "collect_id", "id");

  parts.push(`消息类型：${msgType}`);
  parts.push(`发送方：${from}`);
  if (text) parts.push(`内容：${text}`);
  else parts.push("内容：无文本（多为群系统通知）");
  if (msgKey) parts.push(`消息键：${msgKey}`);

  return parts.join("；");
}
