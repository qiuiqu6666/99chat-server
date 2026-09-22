import { request } from "./http";
export function listFriends(params: Record<string, unknown>) {
  return request("get", "/relations/friends", { params });
}
export function listRelationGroups(params: Record<string, unknown>) {
  return request("get", "/relations/groups", { params });
}
export function listSameIp(params: Record<string, unknown>) {
  return request("get", "/relations/same-ip", { params });
}
export function listSameDevice(params: Record<string, unknown>) {
  return request("get", "/relations/same-device", { params });
}
