import { request } from "./http";
export function sendWalletNotice(data: Record<string, unknown>) {
  return request("post", "/im/platform-wallet-notice", { data });
}
