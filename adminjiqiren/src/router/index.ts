import { createRouter, createWebHistory } from "vue-router";
import LoginView from "@/views/LoginView.vue";
import ReportView from "@/views/ReportView.vue";

const TOKEN_KEY = "adminjiqiren_token";

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY);
}

export function setToken(token: string): void {
  localStorage.setItem(TOKEN_KEY, token);
}

export function clearToken(): void {
  localStorage.removeItem(TOKEN_KEY);
}

const router = createRouter({
  history: createWebHistory("/adminjiqiren/"),
  routes: [
    { path: "/login", name: "login", component: LoginView, meta: { public: true } },
    { path: "/", name: "report", component: ReportView }
  ]
});

router.beforeEach((to) => {
  const token = getToken();
  if (to.meta.public) {
    if (token && to.path === "/login") {
      return { path: "/" };
    }
    return true;
  }
  if (!token) {
    return { path: "/login" };
  }
  return true;
});

export default router;
