import { request } from "./http";

export function listSplash() {
  return request("get", "/splash");
}

export function createSplash(file: File, fields: Record<string, string | boolean | undefined>) {
  const form = new FormData();
  form.append("file", file);
  for (const [k, v] of Object.entries(fields)) {
    if (v === undefined || v === "") continue;
    form.append(k, String(v));
  }
  return request("post", "/splash", { data: form });
}

export function updateSplash(id: string | number, data: Record<string, unknown>) {
  return request("put", `/splash/${id}`, { data });
}

export function deleteSplash(id: string | number) {
  return request("delete", `/splash/${id}`);
}
