import { request } from "./http";

export function listCurrencies() {
  return request("get", "/currencies");
}

export function updateCurrency(code: string, data: Record<string, unknown>) {
  return request("put", `/currencies/${encodeURIComponent(code)}`, { data });
}

export function uploadCurrencyLogo(file: File) {
  const form = new FormData();
  form.append("file", file);
  return request<Record<string, unknown>>("post", "/currencies/upload-logo", { data: form });
}
