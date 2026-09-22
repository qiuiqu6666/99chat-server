import { request } from "./http";
export function listC2cMessages(params: Record<string, unknown>) {
  return request("get", "/messages/c2c", { params });
}
export function listGroupMessages(params: Record<string, unknown>) {
  return request("get", "/messages/group", { params });
}
