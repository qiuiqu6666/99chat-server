import { request } from "./http";
export function getExchangeConfig() {
  return request("get", "/wallet/exchange-config");
}
export function putExchangeConfig(data: Record<string, unknown>) {
  return request("put", "/wallet/exchange-config", { data });
}
