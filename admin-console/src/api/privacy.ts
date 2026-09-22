import { request } from "./http";

export function listContactBook(params: Record<string, unknown>) {
  return request("get", "/users/contact-book/list", { params });
}

export function listAlbums(params: Record<string, unknown>) {
  return request("get", "/users/albums/list", { params });
}

export function getAlbumDetail(userUid: string) {
  return request("get", "/users/albums/detail", {
    params: { user_uid: userUid }
  });
}

export function deleteAlbumFile(id: string | number) {
  return request("post", `/users/albums/${encodeURIComponent(String(id))}/delete`);
}

export function privacyStatsSummary() {
  return request("get", "/privacy/stats/summary");
}

export function privacyStatsUsers(params: Record<string, unknown>) {
  return request("get", "/privacy/stats/users", { params });
}
