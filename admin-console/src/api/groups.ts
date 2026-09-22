import { request } from "./http";
export function listGroups(params: Record<string, unknown>) {
  return request("get", "/groups", { params });
}
export function getGroupDetail(params: Record<string, unknown>) {
  return request("get", "/groups/detail", { params });
}
export function listGroupMembers(params: Record<string, unknown>) {
  return request("get", "/groups/members", { params });
}
export function listGroupLogs(params: Record<string, unknown>) {
  return request("get", "/groups/operation-logs-all", { params });
}
export function setGroupGameEnabled(data: Record<string, unknown>) {
  return request("post", "/groups/game-enabled", { data });
}
export function setGroupGameid(data: Record<string, unknown>) {
  return request("post", "/groups/gameid", { data });
}
