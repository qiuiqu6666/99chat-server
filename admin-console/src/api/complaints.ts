import { request } from "./http";
export function listComplaints(params: Record<string, unknown>) {
  return request("get", "/chat-complaints", { params });
}
export function updateComplaintStatus(id: string | number, data: Record<string, unknown>) {
  return request("post", `/chat-complaints/${id}/status`, { data });
}
