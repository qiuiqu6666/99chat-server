import { request } from "./http";
export function startFriendBackfill(data?: Record<string, unknown>) {
  return request("post", "/notify/friend-backfill", { data });
}
export function friendBackfillStatus() {
  return request("get", "/notify/friend-backfill/status");
}
