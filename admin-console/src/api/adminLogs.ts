import { request } from "./http";
export function listAuditLogs(params: Record<string, unknown>) {
  return request("get", "/admin/audit-logs", { params });
}
export function listAdminLoginLogs(params: Record<string, unknown>) {
  return request("get", "/admin/login-logs", { params });
}
