import { request } from "./http";
export function listFeedback(params: Record<string, unknown>) {
  return request("get", "/feedback", { params });
}
export function updateFeedbackStatus(id: string | number, data: Record<string, unknown>) {
  return request("post", `/feedback/${id}/status`, { data });
}
