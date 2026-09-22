import { request } from "./http";
export function listDevices(params: Record<string, unknown>) {
  return request("get", "/devices", { params });
}
export function banDevice(id: string | number, data?: Record<string, unknown>) {
  return request("post", `/devices/${id}/ban`, { data });
}
export function unbanDevice(id: string | number) {
  return request("post", `/devices/${id}/unban`);
}
export function kickDevice(id: string | number) {
  return request("post", `/devices/${id}/kick`);
}
