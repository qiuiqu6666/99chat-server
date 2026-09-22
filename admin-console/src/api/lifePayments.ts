import { request } from "./http";
export function listOrders(params: Record<string, unknown>) {
  return request("get", "/life-payments/orders", { params });
}
export function listTasks(params: Record<string, unknown>) {
  return request("get", "/life-payments/tasks", { params });
}
export function listWorkers(params: Record<string, unknown>) {
  return request("get", "/life-payments/workers", { params });
}
export function listProviders(params: Record<string, unknown>) {
  return request("get", "/life-payments/providers", { params });
}
export function manualOrder(orderNo: string, data?: Record<string, unknown>) {
  return request("post", `/life-payments/orders/${encodeURIComponent(orderNo)}/manual`, { data });
}
export function failRefundOrder(orderNo: string, data?: Record<string, unknown>) {
  return request("post", `/life-payments/orders/${encodeURIComponent(orderNo)}/fail-refund`, { data });
}
export function retryTask(taskNo: string, data?: Record<string, unknown>) {
  return request("post", `/life-payments/tasks/${encodeURIComponent(taskNo)}/retry`, {
    data: data || {}
  });
}
export function setProviderEnabled(code: string, data: Record<string, unknown>) {
  return request("post", `/life-payments/providers/${encodeURIComponent(code)}/enabled`, { data });
}
export function issueWorkerToken(workerId: string, data?: Record<string, unknown>) {
  return request("post", `/life-payments/workers/${encodeURIComponent(workerId)}/issue-token`, {
    data: data || {}
  });
}
