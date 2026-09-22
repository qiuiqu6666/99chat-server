import { request } from "./http";

export function listAnnouncements(params: Record<string, unknown>) {
  return request("get", "/announcements", { params });
}

export function sendAnnouncement(data: Record<string, unknown>) {
  return request("post", "/announcements/send", { data });
}

export function uploadAnnouncementImage(file: File) {
  const form = new FormData();
  form.append("file", file);
  return request<Record<string, unknown>>("post", "/announcements/upload-image", {
    data: form
  });
}

export function uploadAnnouncementVideo(file: File) {
  const form = new FormData();
  form.append("file", file);
  return request<Record<string, unknown>>("post", "/announcements/upload-video", {
    data: form
  });
}
