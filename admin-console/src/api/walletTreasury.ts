import { request } from "./http";
export function getTreasurySummary() {
  return request("get", "/wallet/treasury/summary");
}
export function listTreasuryAddresses(params: Record<string, unknown>) {
  return request("get", "/wallet/treasury/addresses", { params });
}
export function listSweepLogs(params: Record<string, unknown>) {
  return request("get", "/wallet/treasury/sweep-logs", { params });
}
export function collectUser(userUid: string) {
  return request("post", `/wallet/treasury/collect/${encodeURIComponent(userUid)}`);
}
export function collectAll() {
  return request("post", "/wallet/treasury/collect-all");
}
