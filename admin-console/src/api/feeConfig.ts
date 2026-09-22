import { request } from "./http";
export function listFeeConfig() {
  return request("get", "/wallet/fee-config");
}
export function updateFeeConfig(id: string | number, data: Record<string, unknown>) {
  return request("put", `/wallet/fee-config/${id}`, { data });
}
