import { onBeforeUnmount, onMounted } from "vue";
import { adminRealtime } from "@/realtime/adminRealtime";
import { ADMIN_REALTIME_EVENTS } from "@/realtime/realtimeEvents";
import type {
  AdminRealtimeEventName,
  AdminRealtimePayload
} from "@/realtime/realtimeEvents";

export type AdminRealtimeInvalidateOptions = {
  debounceMs?: number;
  enabled?: () => boolean;
  shouldRefresh?: (payload: AdminRealtimePayload) => boolean;
};

/**
 * 页面局部实时刷新 hook：
 * - 不刷新浏览器；
 * - 不重载 router-view；
 * - 多个事件合并成一次当前页数据刷新；
 * - 页面未满足查询条件时不弹错误、不强刷。
 */
export function useAdminRealtimeInvalidate(
  events: AdminRealtimeEventName[],
  refresh: (payload?: AdminRealtimePayload) => void | Promise<void>,
  options: AdminRealtimeInvalidateOptions = {}
) {
  let timer: ReturnType<typeof setTimeout> | null = null;
  let unsubs: Array<() => void> = [];
  let lastPayload: AdminRealtimePayload | undefined;

  const clear = () => {
    if (!timer) return;
    clearTimeout(timer);
    timer = null;
  };

  const trigger = (payload?: AdminRealtimePayload) => {
    if (options.enabled && !options.enabled()) return;
    if (payload && options.shouldRefresh && !options.shouldRefresh(payload)) return;
    lastPayload = payload;
    clear();
    timer = setTimeout(() => {
      timer = null;
      void refresh(lastPayload);
    }, options.debounceMs ?? 500);
  };

  onMounted(() => {
    const mergedEvents = Array.from(
      new Set<AdminRealtimeEventName>([
        ...events,
        ADMIN_REALTIME_EVENTS.CONNECTED
      ])
    );
    unsubs = mergedEvents.map(event => adminRealtime.on(event, trigger));
  });

  onBeforeUnmount(() => {
    clear();
    unsubs.forEach(fn => fn());
    unsubs = [];
  });

  return { triggerRealtimeRefresh: trigger };
}
