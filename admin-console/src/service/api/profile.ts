import { request } from "../http";
export function getProfile() {
  return request("get", "/admin/profile");
}
export function putProfile(data: Record<string, unknown>) {
  return request("put", "/admin/profile", { data });
}

export function changePasswordApi(oldPassword: string, newPassword: string) {
  return request("post", "/auth/change-password", {
    data: { old_password: oldPassword, new_password: newPassword }
  });
}
