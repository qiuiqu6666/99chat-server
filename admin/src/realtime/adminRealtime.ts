import type { Router } from "vue-router";
import { getToken } from "@/utils/auth";
import {
  ADMIN_REALTIME_EVENTS,
  normalizeRealtimePayload,
  type AdminRealtimeEventName,
  type AdminRealtimeListener,
  type AdminRealtimePayload
} from "@/realtime/realtimeEvents";

type Unsubscribe = () => void;

function realtimeEnabled() {
  return import.meta.env.VITE_ADMIN_REALTIME_ENABLED !== "false";
}

function hasToken() {
  return realtimeEnabled() && !!getToken()?.accessToken;
}

function tokenQueryEnabled() {
  // EventSource 不能设置 Authorization header。生产推荐同源 HttpOnly Cookie；
  // 兼容期允许 access_token query，但后端必须只接受 HTTPS 内网/后台域名。
  return import.meta.env.VITE_ADMIN_REALTIME_TOKEN_QUERY !== "false";
}

function joinUrl(path: string) {
  if (/^https?:\/\//i.test(path)) return path;
  const base = (import.meta.env.VITE_API_BASE_URL || "").replace(/\/$/, "");
  const p = path.startsWith("/") ? path : `/${path}`;
  return `${base}${p}` || p;
}

function buildRealtimeUrl() {
  const endpoint =
    import.meta.env.VITE_ADMIN_REALTIME_URL || "/api/v1/admin/realtime/events";
  const url = new URL(joinUrl(endpoint), window.location.origin);

  if (tokenQueryEnabled()) {
    const token = getToken()?.accessToken;
    if (token) url.searchParams.set("access_token", token);
  }
  return url.toString();
}

function reconnectDelay(attempt: number) {
  // 大厂标准：实时通道断线只重连，不靠页面轮询补偿。
  // 指数退避 + 抖动，避免后端故障时后台同时打爆 SSE 入口。
  const base = Math.min(30000, 1000 * 2 ** attempt);
  const jitter = Math.floor(Math.random() * 400);
  return base + jitter;
}

class AdminRealtimeClient {
  private source: EventSource | null = null;
  private reconnectTimer: ReturnType<typeof setTimeout> | null = null;
  private reconnectAttempt = 0;
  private stoppedByUser = false;
  private listeners = new Map<string, Set<AdminRealtimeListener>>();
  private anyListeners = new Set<AdminRealtimeListener>();

  get connected() {
    return this.source?.readyState === EventSource.OPEN;
  }

  start() {
    if (typeof window === "undefined") return;
    if (!window.EventSource) return;
    if (!hasToken()) return;
    if (this.source && this.source.readyState !== EventSource.CLOSED) return;

    this.stoppedByUser = false;
    this.clearReconnectTimer();

    const source = new EventSource(buildRealtimeUrl(), { withCredentials: true });
    this.source = source;

    source.onopen = () => {
      this.reconnectAttempt = 0;
      this.emitLocal({
        event: ADMIN_REALTIME_EVENTS.CONNECTED,
        time: Date.now(),
        source: "system"
      });
    };

    source.onmessage = ev => this.handleMessage(ev);
    this.bindNamedSseEvents(source);

    source.onerror = () => {
      this.emitLocal({
        event: ADMIN_REALTIME_EVENTS.ERROR,
        time: Date.now(),
        source: "system"
      });
      this.closeSource();
      this.scheduleReconnect();
    };
  }

  stop() {
    this.stoppedByUser = true;
    this.clearReconnectTimer();
    this.closeSource();
    this.emitLocal({
      event: ADMIN_REALTIME_EVENTS.DISCONNECTED,
      time: Date.now(),
      source: "system"
    });
  }

  restart() {
    this.stop();
    this.stoppedByUser = false;
    this.start();
  }

  on(
    event: AdminRealtimeEventName | "*",
    listener: AdminRealtimeListener
  ): Unsubscribe {
    if (event === "*") {
      this.anyListeners.add(listener);
      return () => this.anyListeners.delete(listener);
    }
    const key = String(event);
    const set = this.listeners.get(key) ?? new Set<AdminRealtimeListener>();
    set.add(listener);
    this.listeners.set(key, set);
    return () => {
      set.delete(listener);
      if (!set.size) this.listeners.delete(key);
    };
  }

  emit(payload: AdminRealtimePayload) {
    this.emitLocal(payload);
  }

  private bindNamedSseEvents(source: EventSource) {
    const names = Array.from(new Set(Object.values(ADMIN_REALTIME_EVENTS)));
    names
      .filter(name =>
        ![
          ADMIN_REALTIME_EVENTS.CONNECTED,
          ADMIN_REALTIME_EVENTS.DISCONNECTED,
          ADMIN_REALTIME_EVENTS.ERROR
        ].includes(name as any)
      )
      .forEach(name => {
        source.addEventListener(name, ev =>
          this.handleMessage(ev as MessageEvent<string>, name)
        );
      });
  }

  private handleMessage(ev: MessageEvent<string>, fallbackEvent?: string) {
    try {
      const parsed = ev.data ? JSON.parse(ev.data) : {};
      if (
        fallbackEvent &&
        parsed &&
        typeof parsed === "object" &&
        !("event" in parsed)
      ) {
        (parsed as Record<string, unknown>).event = fallbackEvent;
      }
      this.emitLocal(normalizeRealtimePayload(parsed), ev);
    } catch {
      this.emitLocal(
        normalizeRealtimePayload({
          event: fallbackEvent ?? "admin.realtime.malformed",
          time: Date.now(),
          data: { raw: ev.data }
        }),
        ev
      );
    }
  }

  private emitLocal(payload: AdminRealtimePayload, raw?: MessageEvent<string>) {
    const set = this.listeners.get(String(payload.event));
    set?.forEach(fn => fn(payload, raw));
    this.anyListeners.forEach(fn => fn(payload, raw));
  }

  private closeSource() {
    if (!this.source) return;
    this.source.close();
    this.source = null;
  }

  private scheduleReconnect() {
    if (this.stoppedByUser) return;
    if (!hasToken()) return;
    this.clearReconnectTimer();
    const delay = reconnectDelay(this.reconnectAttempt);
    this.reconnectAttempt += 1;
    this.reconnectTimer = setTimeout(() => this.start(), delay);
  }

  private clearReconnectTimer() {
    if (!this.reconnectTimer) return;
    clearTimeout(this.reconnectTimer);
    this.reconnectTimer = null;
  }
}

export const adminRealtime = new AdminRealtimeClient();

export function setupAdminRealtime(router?: Router) {
  const syncByRoute = () => {
    const onLogin = router?.currentRoute.value?.path === "/login";
    if (hasToken() && !onLogin) adminRealtime.start();
    else adminRealtime.stop();
  };

  syncByRoute();

  router?.afterEach(to => {
    if (to.path === "/login") adminRealtime.stop();
    else if (hasToken()) adminRealtime.start();
  });

  window.addEventListener("storage", ev => {
    if (ev.key && !["authorized-token", "admin-token", "token"].includes(ev.key)) {
      return;
    }
    syncByRoute();
  });
}
