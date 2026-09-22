import http from "./http";

export interface ApiEnvelope<T> {
  code: number;
  message: string;
  data: T;
}

export interface LoginData {
  token: string;
  expiresAt: string;
}

export interface MoneySummary {
  userCount: number;
  totalUp: string;
  totalDown: string;
  totalFlow: string;
  totalProfitLoss: string;
  totalBalance?: string;
  totalRebate: string;
  rowCount?: number;
}

export interface CurrentItem {
  machineCode: string;
  userId: string;
  playerNo: string;
  nickname: string;
  displayName: string;
  balance: string;
  totalUp: string;
  totalDown: string;
  totalFlow: string;
  totalProfitLoss: string;
  totalRebate: string;
  rebateRate: string;
  directParentUserId: string;
  levelNo: number;
}

export interface DailyItem {
  businessDate: string;
  machineCode: string;
  userId: string;
  playerNo: string;
  nickname: string;
  displayName: string;
  totalUp: string;
  totalDown: string;
  totalFlow: string;
  totalProfitLoss: string;
  totalRebate: string;
  balance: string;
  directParentUserId: string;
}

export interface UpdownItem {
  approvedAt: number;
  machineCode: string;
  playerNo: string;
  userId: string;
  nickname: string;
  direction: string;
  amount: string;
  balanceAfter: string;
  recordId: string;
}

export interface UpdownSummary {
  rowCount: number;
  totalUp: string;
  totalDown: string;
}

export interface AgentTeamItem {
  machineCode: string;
  playerNo: string;
  userId: string;
  nickname: string;
  displayName: string;
  levelNo: number;
  rebateRate: string;
  balance: string;
  totalRebate: string;
  directParentUserId: string;
  directChildCount: number;
  descendantCount: number;
}

export interface AgentTeamSummary {
  agentCount: number;
  level1AgentCount: number;
}

export interface AgentDescendantItem {
  playerNo: string;
  userId: string;
  nickname: string;
  displayName: string;
  levelNo: number;
  rebateRate: string;
  directParentUserId: string;
  directParentNo: string;
  balance: string;
  totalUp: string;
  totalDown: string;
  totalFlow: string;
  totalProfitLoss: string;
  totalRebate: string;
}

export async function login(username: string, password: string): Promise<LoginData> {
  const { data } = await http.post<ApiEnvelope<LoginData>>("/login", { username, password });
  return data.data;
}

export async function logout(): Promise<void> {
  await http.post("/logout");
}

export async function fetchMachines(): Promise<string[]> {
  const { data } = await http.get<ApiEnvelope<{ items: { machineCode: string }[] }>>("/machines");
  return (data.data.items || []).map((x) => x.machineCode);
}

export async function fetchCurrent(params: {
  machineCode?: string;
  keyword?: string;
}): Promise<{ summary: MoneySummary; items: CurrentItem[] }> {
  const { data } = await http.get<ApiEnvelope<{ summary: MoneySummary; items: CurrentItem[] }>>(
    "/report/current",
    { params }
  );
  return data.data;
}

export async function fetchDaily(params: {
  startDate: string;
  endDate: string;
  machineCode?: string;
  keyword?: string;
}): Promise<{
  startDate: string;
  endDate: string;
  summary: MoneySummary;
  items: DailyItem[];
}> {
  const { data } = await http.get<
    ApiEnvelope<{
      startDate: string;
      endDate: string;
      summary: MoneySummary;
      items: DailyItem[];
    }>
  >("/report/daily", { params });
  return data.data;
}

export async function fetchUpdown(params: {
  startDate: string;
  endDate: string;
  machineCode?: string;
  keyword?: string;
}): Promise<{
  startDate: string;
  endDate: string;
  summary: UpdownSummary;
  items: UpdownItem[];
}> {
  const { data } = await http.get<
    ApiEnvelope<{
      startDate: string;
      endDate: string;
      summary: UpdownSummary;
      items: UpdownItem[];
    }>
  >("/report/updown", { params });
  return data.data;
}

export async function fetchAgents(params: {
  machineCode?: string;
  keyword?: string;
}): Promise<{ summary: AgentTeamSummary; items: AgentTeamItem[] }> {
  const { data } = await http.get<
    ApiEnvelope<{ summary: AgentTeamSummary; items: AgentTeamItem[] }>
  >("/report/agents", { params });
  return data.data;
}

export async function fetchAgentDescendants(params: {
  machineCode: string;
  agentUserId: string;
  scope?: "all" | "direct";
}): Promise<{
  machineCode: string;
  agentUserId: string;
  agentPlayerNo: string;
  agentNickname: string;
  scope: string;
  total: number;
  items: AgentDescendantItem[];
}> {
  const { data } = await http.get<
    ApiEnvelope<{
      machineCode: string;
      agentUserId: string;
      agentPlayerNo: string;
      agentNickname: string;
      scope: string;
      total: number;
      items: AgentDescendantItem[];
    }>
  >("/report/agents/descendants", { params });
  return data.data;
}
