/** 模拟网络延迟（毫秒） */
export const MOCK_DELAY_MS = 80;

export function mockResolve<T>(data: T, ms = MOCK_DELAY_MS): Promise<T> {
  return new Promise(resolve => {
    setTimeout(() => resolve(data), ms);
  });
}

export function paginate<T>(
  list: T[],
  page = 1,
  pageSize = 10
): { items: T[]; total: number; page: number; page_size: number } {
  const p = Math.max(1, page);
  const ps = Math.min(Math.max(1, pageSize), 100);
  const total = list.length;
  const start = (p - 1) * ps;
  return {
    items: list.slice(start, start + ps),
    total,
    page: p,
    page_size: ps
  };
}

export function isoRecent(days: number): string {
  const d = new Date(Date.now() - Math.random() * days * 86400000);
  return d.toISOString().slice(0, 19).replace("T", " ");
}

export function unixMsRecent(days: number): number {
  return Date.now() - Math.floor(Math.random() * days * 86400000);
}
