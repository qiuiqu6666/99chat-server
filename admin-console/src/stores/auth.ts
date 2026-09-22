import { defineStore } from "pinia";
import { clearToken, getToken, setToken } from "@/utils/auth";
import { loginApi, logoutApi, meApi } from "@/api/auth";

export const useAuthStore = defineStore("auth", {
  state: () => ({
    token: getToken(),
    username: "" as string,
    permissions: [] as string[]
  }),
  getters: {
    isLoggedIn: state => !!state.token
  },
  actions: {
    async login(username: string, password: string) {
      const r = await loginApi(username, password);
      this.token = r.access_token;
      setToken(r.access_token);
      this.username = r.user.username;
      this.permissions = r.user.permissions;
    },
    async fetchMe() {
      if (!this.token) return;
      const me = await meApi();
      this.username = me.username;
      this.permissions = me.permissions;
    },
    async logout() {
      try {
        if (this.token) await logoutApi();
      } catch {
        /* ignore */
      }
      this.token = "";
      this.username = "";
      this.permissions = [];
      clearToken();
    }
  }
});
