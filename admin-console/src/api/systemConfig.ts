import { request } from "./http";

export function getPlatformConfig() {
  return request("get", "/admin/system-config/platform");
}

export function putPlatformConfig(data: Record<string, unknown>) {
  return request("put", "/admin/system-config/platform", { data });
}

export function getPushBusiness() {
  return request("get", "/admin/system-config/push-business");
}

export function putPushBusiness(data: Record<string, unknown>) {
  return request("put", "/admin/system-config/push-business", { data });
}

export function getInfrastructure() {
  return request("get", "/admin/system-config/infrastructure");
}

export function putInfrastructure(key: string, value: string) {
  return request("put", `/admin/system-config/infrastructure/${encodeURIComponent(key)}`, {
    data: { value }
  });
}
