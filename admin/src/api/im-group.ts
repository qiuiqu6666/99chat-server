import { http } from "@/utils/http";
import { normalizeWriteOkResponse } from "@/utils/chat99AdminApi";
import type { AxiosRequestConfig } from "axios";

export type GroupListParams = {
  page: number;
  page_size: number;
  keyword?: string;
  g_status?: string;
  sort?: string;
};

export type GroupListResponse = {
  items: Record<string, unknown>[];
  total: number;
  page: number;
  page_size: number;
  sort: string;
  /** 腾讯 IM 应用内群总数 */
  im_total?: number;
  /** 是否因扫描上限未拉全 */
  truncated?: boolean;
};

export function getGroupsList(params: GroupListParams) {
  return http.request<GroupListResponse>("get", "/api/v1/groups", { params });
}

export function getGroupDetail(g_id: string) {
  return http.request<Record<string, unknown>>("get", "/api/v1/groups/detail", {
    params: { g_id }
  });
}

/** `POST /api/v1/groups/game-enabled`（`group.write`）：群游戏开关 */
export type GroupGameEnabledBody = {
  g_id: string;
  game_enabled: boolean;
};

export type GroupGameEnabledOk = {
  ok: boolean;
  g_id: string;
  game_enabled?: boolean;
};

export const postGroupGameEnabled = async (data: GroupGameEnabledBody) => {
  const raw = await http.request<Record<string, unknown>>(
    "post",
    "/api/v1/groups/game-enabled",
    { data }
  );
  return normalizeWriteOkResponse(raw) as GroupGameEnabledOk;
};

export type GroupMembersParams = {
  g_id: string;
  page?: number;
  page_size?: number;
  sort?: string;
};

export type GroupMembersResponse = {
  g_id: string;
  items: Record<string, unknown>[];
  total: number;
  page: number;
  page_size: number;
  sort: string;
};

export function getGroupsMembers(params: GroupMembersParams) {
  return http.request<GroupMembersResponse>("get", "/api/v1/groups/members", {
    params
  });
}

const GSTATUS_TAG: Record<
  number,
  { label: string; type: "success" | "danger" | "warning" | "info" }
> = {
  [-1]: { label: "已删除", type: "info" },
  0: { label: "状态0", type: "success" },
  1: { label: "状态1", type: "danger" },
  2: { label: "全员禁言", type: "warning" }
};

export function mapGroupItemToRow(item: Record<string, unknown>) {
  const gid = String(item.g_id ?? "");
  const gstatus = Number(item.g_status);
  const tag =
    GSTATUS_TAG[gstatus as keyof typeof GSTATUS_TAG] ?? {
      label: `状态(${item.g_status})`,
      type: "info" as const
    };

  const createNicknameRaw = item.create_user_nickname;
  const createUserNickname =
    createNicknameRaw != null && String(createNicknameRaw).trim() !== ""
      ? String(createNicknameRaw)
      : "";

  return {
    id: gid,
    gId: gid,
    groupNo: gid,
    name: String(item.g_name ?? ""),
    ownerUid: String(item.g_owner_user_uid ?? ""),
    createUserUid:
      item.create_user_uid != null && `${item.create_user_uid}` !== ""
        ? String(item.create_user_uid)
        : "",
    createUserNickname,
    memberCount: Number(item.g_member_count ?? 0),
    memberLimit: item.max_member_count ?? null,
    joinType: String(itemInviteJoin(item)),
    gStatusRaw: item.g_status,
    statusDisplay: tag,
    createTime:
      typeof item.create_time === "number"
        ? item.create_time
        : typeof item.create_time === "string"
          ? item.create_time
          : null,
    _raw: item
  };
}

function itemInviteJoin(rec: Record<string, unknown>): string {
  const keys = ["invite_mode", "join_mode", "g_need_invite", "join_type"];
  for (const k of keys) {
    if (rec[k] != null && `${rec[k]}` !== "") return String(rec[k]);
  }
  return "—";
}

export type MessagesGroupParams = {
  g_id: string;
  keyword?: string;
  page?: number;
  page_size?: number;
  sort?: string;
};

export function getGroupMessagesApi(params: MessagesGroupParams) {
  return http.request<Record<string, unknown>>(
    "get",
    "/api/v1/messages/group",
    { params } as AxiosRequestConfig
  );
}

export type GroupsOperationLogsParams = {
  g_id: string;
  page?: number;
  page_size?: number;
  kinds?: string;
};

export function getGroupsOperationLogsApi(params: GroupsOperationLogsParams) {
  return http.request<Record<string, unknown>>(
    "get",
    "/api/v1/groups/operation-logs",
    { params }
  );
}

export type GroupsOperationLogsAllParams = {
  page?: number;
  page_size?: number;
  kinds?: string;
};

export function getGroupsOperationLogsAllApi(params: GroupsOperationLogsAllParams) {
  return http.request<Record<string, unknown>>(
    "get",
    "/api/v1/groups/operation-logs-all",
    { params }
  );
}
