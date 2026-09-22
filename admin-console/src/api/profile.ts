import { request } from "./http";
export function getProfile() {
  return request("get", "/admin/profile");
}
export function putProfile(data: Record<string, unknown>) {
  return request("put", "/admin/profile", { data });
}
