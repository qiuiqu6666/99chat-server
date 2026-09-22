import { request, unwrap } from "./http";

export function listUsers(params: Record<string, unknown>) {
  return request<Record<string, unknown>>("get", "/users", { params });
}

export async function getUserDetail(user_uid: string) {
  const raw = await request<Record<string, unknown>>("get", "/users/detail", {
    params: { user_uid }
  });
  return unwrap(raw);
}

export async function getUserWallet(user_uid: string) {
  const raw = await request<Record<string, unknown>>("get", "/users/wallet", {
    params: { user_uid }
  });
  return unwrap(raw);
}

export function listLoginLogs(params: Record<string, unknown>) {
  return request<Record<string, unknown>>("get", "/users/login-logs", { params });
}

export function listGenerationTasks(params: Record<string, unknown>) {
  return request<Record<string, unknown>>("get", "/users/generation-tasks", { params });
}

export function getGenerationTask(task_no: string) {
  return request<Record<string, unknown>>(
    "get",
    `/users/generation-tasks/${encodeURIComponent(task_no)}`
  );
}

export function setLoginDisabled(data: {
  user_uid: string;
  disabled: boolean;
  clear_http_token?: boolean;
}) {
  return request("post", "/users/login-disabled", { data });
}

export function setGamePrivileged(data: { user_uid: string; game_privileged: boolean }) {
  return request("post", "/users/game-privileged", { data });
}

export function setSkipDeviceSms(data: { user_uid: string; enabled: boolean }) {
  return request("post", "/users/skip-device-sms", { data });
}

export function resetLoginPassword(data: { user_uid: string; new_password: string }) {
  return request("post", "/users/login-password", { data });
}

export function resetFundPassword(data: { user_uid: string; new_fund_password: string }) {
  return request("post", "/users/fund-password", { data });
}

export function updateNickname(data: { user_uid: string; nickname: string }) {
  return request("post", "/users/nickname", { data });
}

export function adjustBalance(data: {
  user_uid: string;
  currency: string;
  direction: string;
  amount: string;
  remark?: string;
}) {
  return request("post", "/users/wallet/balance-adjust", { data });
}

export function createUser(data: { nickname: string; password: string; sex?: string }) {
  return request("post", "/users/create", { data });
}

export function createByCount(data: { password: string; count: number; sex?: string }) {
  return request("post", "/users/create-by-count", { data });
}

export function createGenerationTask(data: { password: string; count: number; sex?: string }) {
  return request("post", "/users/generation-tasks", { data });
}

export function loginUnfreeze(data: { user_uid?: string; login_key?: string }) {
  return request("post", "/users/login-unfreeze", { data });
}
