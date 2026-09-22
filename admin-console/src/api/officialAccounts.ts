import { request } from "./http";

export function listOfficialAccounts() {
  return request("get", "/official-accounts");
}

export function createOfficialAccount(data: Record<string, unknown>) {
  return request("post", "/official-accounts", { data });
}

export function linkOfficialAccount(data: Record<string, unknown>) {
  return request("post", "/official-accounts/link", { data });
}

export function updateOfficialAccount(id: string, data: Record<string, unknown>) {
  return request("patch", `/official-accounts/${encodeURIComponent(id)}`, { data });
}

export function deleteOfficialAccount(id: string) {
  return request("delete", `/official-accounts/${encodeURIComponent(id)}`);
}

export function broadcastOfficial(id: string, data: Record<string, unknown>) {
  return request("post", `/official-accounts/${encodeURIComponent(id)}/broadcast`, { data });
}
