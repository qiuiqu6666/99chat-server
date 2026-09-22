import { useAuthStore } from "@/stores/auth";

export function hasPerm(code: string): boolean {
  const auth = useAuthStore();
  const perms = auth.permissions || [];
  if (perms.includes("admin.manage") || perms.includes("*")) return true;
  return perms.includes(code);
}
