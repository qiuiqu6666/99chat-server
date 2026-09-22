import { http } from "@/utils/http";

export type PrivacySummary = {
  total_users: number;
  users_with_contacts: number;
  total_contact_entries: number;
  users_with_album: number;
  total_album_items: number;
  total_photos: number;
  total_videos: number;
};

export type PrivacyUserStatsItem = {
  user_uid: string;
  nickname: string;
  contact_count: number;
  album_count: number;
};

export type PrivacyUserStatsListResponse = {
  items: PrivacyUserStatsItem[];
  total: number;
  page: number;
  page_size: number;
  has_more: boolean;
};

function unwrap<T>(raw: unknown): T {
  if (raw && typeof raw === "object" && !Array.isArray(raw)) {
    const obj = raw as Record<string, unknown>;
    if (obj.data && typeof obj.data === "object") return obj.data as T;
    return raw as T;
  }
  return raw as T;
}

export async function getPrivacyStatsSummary() {
  const raw = await http.request<unknown>("get", "/api/v1/privacy/stats/summary");
  return unwrap<PrivacySummary>(raw);
}

export async function getPrivacyUserStatsList(params: {
  page?: number;
  page_size?: number;
  keyword?: string;
  sort?: string;
}) {
  const raw = await http.request<unknown>("get", "/api/v1/privacy/stats/users", { params });
  return unwrap<PrivacyUserStatsListResponse>(raw);
}
