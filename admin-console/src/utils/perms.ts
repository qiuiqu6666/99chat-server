import { useAuthStore } from '@/store/modules/auth';

export function hasPerm(code: string): boolean {
  const auth = useAuthStore();
  const perms = auth.userInfo.buttons || [];
  if (perms.includes('admin.manage') || perms.includes('*')) return true;
  return perms.includes(code);
}
