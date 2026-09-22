import { http } from "@/utils/http";
import type { ImUserUid } from "@/api/im-user";

/** 腾讯云 IM MsgType → 业务 msg_type 数字（与 imMessageDisplay 一致） */
const TIM_ELEM_TO_MSG_TYPE: Record<string, number> = {
  TIMTextElem: 0,
  TIMImageElem: 1,
  TIMSoundElem: 2,
  TIMFileElem: 5,
  TIMVideoFileElem: 6,
  TIMLocationElem: 8,
  TIMFaceElem: 0,
  TIMCustomElem: 90
};

/** 后台群 ID 校验（与 AdminMessagesService 一致） */
export const ADMIN_GROUP_ID_RE = /^[A-Za-z0-9@#_-]{1,32}$/;

export function isAdminGroupId(gid: unknown): boolean {
  const s = gid == null ? "" : String(gid).trim();
  return ADMIN_GROUP_ID_RE.test(s);
}

export type MessagesC2CParams = {
  user_a: ImUserUid;
  user_b: ImUserUid;
  keyword?: string;
  page?: number;
  page_size?: number;
  sort?: string;
  last_msg_key?: string;
};

export type MessagesGroupParams = {
  g_id: string;
  keyword?: string;
  page?: number;
  page_size?: number;
  sort?: string;
  req_msg_seq?: number;
};

export type AdminMessagesListResponse = {
  items: Record<string, unknown>[];
  page: number;
  page_size: number;
  has_more: boolean;
  last_msg_key: string | null;
  next_req_msg_seq: number | null;
  /** 当前已加载条数（IM 漫游无 total，仅用于展示） */
  total: number;
};

function num(v: unknown, fallback = 0): number {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

function strNull(v: unknown): string | null {
  if (v == null || v === "") return null;
  return String(v);
}

/** 将 Admin API 消息行映射为表格/审计组件可读字段 */
export function normalizeAdminMessageItem(
  raw: Record<string, unknown>
): Record<string, unknown> {
  const from = String(
    raw.from_account ??
      raw.fromAccount ??
      raw.src_uid ??
      raw.from_uid ??
      raw.sender_uid ??
      ""
  ).trim();
  const to = String(
    raw.to_account ??
      raw.toAccount ??
      raw.dest_uid ??
      raw.to_uid ??
      raw.group_id ??
      raw.g_id ??
      ""
  ).trim();
  const timType = String(raw.msg_type ?? raw.msgType ?? raw.tim_msg_type ?? "").trim();
  const text = String(
    raw.text_preview ??
      raw.textPreview ??
      raw.msg_content ??
      raw.content ??
      ""
  ).trim();
  const msgTime =
    raw.msg_time ??
    raw.msgTime ??
    raw.msg_time2 ??
    raw.create_time ??
    raw.updated_at;
  const msgKey = raw.msg_key ?? raw.msgKey ?? raw.collect_id ?? raw.id ?? raw.seq;

  let msgTypeNum: number | null = null;
  if (timType && TIM_ELEM_TO_MSG_TYPE[timType] !== undefined) {
    msgTypeNum = TIM_ELEM_TO_MSG_TYPE[timType];
  } else {
    const n = Number(timType);
    if (Number.isFinite(n)) msgTypeNum = n;
  }

  const isGroupDest = to.includes("@TGS") || to.startsWith("@");

  return {
    ...raw,
    src_uid: from,
    from_uid: from,
    sender_uid: from,
    dest_uid: to,
    to_uid: to,
    group_id: isGroupDest ? to : raw.group_id,
    g_id: raw.g_id ?? (isGroupDest ? to : undefined),
    msg_type: msgTypeNum ?? timType,
    tim_msg_type: timType || undefined,
    msg_content: text,
    content: text,
    text_preview: text,
    msg_time2: msgTime,
    create_time: msgTime,
    collect_id: msgKey,
    id: msgKey,
    msg_key: msgKey
  };
}

function normalizeMessagesListResponse(
  raw: Record<string, unknown>
): AdminMessagesListResponse {
  const itemsRaw = Array.isArray(raw.items) ? raw.items : [];
  const items = itemsRaw.map(it =>
    normalizeAdminMessageItem(it as Record<string, unknown>)
  );
  return {
    items,
    page: num(raw.page, 1),
    page_size: num(raw.page_size ?? raw.pageSize, items.length || 30),
    has_more: Boolean(raw.has_more ?? raw.hasMore),
    last_msg_key: strNull(raw.last_msg_key ?? raw.lastMsgKey),
    next_req_msg_seq:
      raw.next_req_msg_seq != null || raw.nextReqMsgSeq != null
        ? num(raw.next_req_msg_seq ?? raw.nextReqMsgSeq, 0)
        : null,
    total: items.length
  };
}

export async function getMessagesC2c(
  params: MessagesC2CParams
): Promise<AdminMessagesListResponse> {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/messages/c2c",
    { params }
  );
  return normalizeMessagesListResponse(raw);
}

export async function getMessagesGroup(
  params: MessagesGroupParams
): Promise<AdminMessagesListResponse> {
  const raw = await http.request<Record<string, unknown>>(
    "get",
    "/api/v1/messages/group",
    { params }
  );
  return normalizeMessagesListResponse(raw);
}

export const deleteAdminMessage = (
  messageId: string,
  data: { remark?: string } = {}
) =>
  http.request<Record<string, unknown>>(
    "post",
    `/api/v1/messages/${encodeURIComponent(messageId)}/delete`,
    { data }
  );
