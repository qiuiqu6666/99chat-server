import { request } from "./http";
export function listClientVersions(params?: Record<string, unknown>) {
  return request("get", "/client-versions", { params });
}
export function createClientVersion(data: Record<string, unknown>) {
  return request("post", "/client-versions", { data });
}
export function updateClientVersion(id: string | number, data: Record<string, unknown>) {
  return request("put", `/client-versions/${id}`, { data });
}
export function deleteClientVersion(id: string | number) {
  return request("delete", `/client-versions/${id}`);
}
