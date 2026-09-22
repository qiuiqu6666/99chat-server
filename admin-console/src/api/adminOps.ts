import { request } from "./http";
export function opsGroupProjectionStatus() {
  return request("get", "/admin/ops/group-projection/status");
}
export function opsFriendSyncStatus() {
  return request("get", "/admin/ops/user-friends/sync/status");
}
export function opsPushTest(data: Record<string, unknown>) {
  return request("post", "/admin/ops/push/test", { data });
}
export function opsSettingsReload() {
  return request("post", "/admin/ops/settings/reload");
}
export function opsApiMetrics(params?: Record<string, unknown>) {
  return request("get", "/admin/ops/api-metrics", { params });
}
export function opsApiMetricsRequests(params?: Record<string, unknown>) {
  return request("get", "/admin/ops/api-metrics/requests", { params });
}
export function opsApiMetricsReset() {
  return request("post", "/admin/ops/api-metrics/reset");
}
