import { request } from "./http";
export function listLimitConfig() {
  return request("get", "/wallet/limit-config");
}
export function updateLimitConfig(id: string | number, data: Record<string, unknown>) {
  return request("put", `/wallet/limit-config/${id}`, { data });
}
