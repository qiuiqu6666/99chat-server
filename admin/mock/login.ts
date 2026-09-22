// 精聊管理端登录 Mock：与 `POST /api/v1/auth/login` 文档响应一致（用于未连 PHP 时本地演示）
import { defineFakeRoute } from "vite-plugin-fake-server/client";
import type { IncomingMessage, ServerResponse } from "node:http";

function parseBody(req: IncomingMessage): Promise<Record<string, unknown>> {
  return new Promise((resolve, reject) => {
    let raw = "";
    req.setEncoding("utf8");
    req.on("data", chunk => {
      raw += chunk;
    });
    req.on("end", () => {
      try {
        resolve(raw ? (JSON.parse(raw) as Record<string, unknown>) : {});
      } catch {
        resolve({});
      }
    });
    req.on("error", reject);
  });
}

export default defineFakeRoute([
  {
    url: "/api/v1/auth/login",
    method: "post",
    rawResponse: async (req: IncomingMessage, res: ServerResponse) => {
      const body = await parseBody(req);
      const username = String(body.username ?? "").trim();
      const password = String(body.password ?? "").trim();
      res.setHeader("Content-Type", "application/json");

      const okResp = {
        access_token: `mock.jwt.${username || "guest"}.${Date.now()}`,
        token_type: "Bearer",
        expires_in: 86400,
        user: {
          id: 1,
          username: "admin",
          display_name: "演示管理员",
          role: "super_admin",
          permissions: [
            "admin.manage",
            "dashboard.view",
            "group.read",
            "group.write",
            "system.config",
            "user.read",
            "user.write",
            "wallet.read"
          ]
        }
      };

      if (username === "admin" && password === "admin123") {
        res.statusCode = 200;
        res.end(JSON.stringify(okResp));
        return;
      }

      res.statusCode = 401;
      res.end(JSON.stringify({ error: "invalid_credentials" }));
    }
  }
]);
