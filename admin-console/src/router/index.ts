import { createRouter, createWebHistory } from "vue-router";
import { getToken } from "@/utils/auth";
import { useAuthStore } from "@/stores/auth";
import { routes } from "./routes";

const router = createRouter({
  history: createWebHistory(),
  routes,
  scrollBehavior() {
    return { left: 0, top: 0 };
  }
});

let meLoaded = false;

router.beforeEach(async (to, _from, next) => {
  document.title = `${String(to.meta.title || "")} · ${import.meta.env.VITE_APP_TITLE || "99Chat"}`;
  if (to.path === "/login") {
    meLoaded = false;
    next();
    return;
  }
  if (!getToken()) {
    meLoaded = false;
    next({ path: "/login", query: { redirect: to.fullPath } });
    return;
  }
  const auth = useAuthStore();
  if (!meLoaded || !auth.username || !auth.permissions.length) {
    try {
      await auth.fetchMe();
      meLoaded = true;
    } catch {
      meLoaded = false;
      await auth.logout();
      next({ path: "/login", query: { redirect: to.fullPath } });
      return;
    }
  }
  next();
});

export default router;
