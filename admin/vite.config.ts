import http from "node:http";
import https from "node:https";
import { getPluginsList } from "./build/plugins";
import { include, exclude } from "./build/optimize";
import { type UserConfigExport, type ConfigEnv, loadEnv } from "vite";
import {
  root,
  alias,
  wrapperEnv,
  pathResolve,
  __APP_INFO__
} from "./build/utils";

export default async ({ mode }: ConfigEnv): Promise<UserConfigExport> => {
  const env = wrapperEnv(loadEnv(mode, root));
  const {
    VITE_CDN,
    VITE_PORT,
    VITE_COMPRESSION,
    VITE_PUBLIC_PATH
  } = env;

  const adminProxyRaw = env.VITE_ADMIN_API_PROXY_TARGET;
  const adminProxyTarget =
    typeof adminProxyRaw === "string" && adminProxyRaw.trim().length > 0
      ? adminProxyRaw.trim().replace(/\/$/, "")
      : "http://47.239.60.107:8081";

  const adminProxyAgent = adminProxyTarget.startsWith("https:")
    ? new https.Agent({ family: 4 })
    : new http.Agent({ family: 4 });

  const adminProxy = {
    target: adminProxyTarget,
    changeOrigin: true,
    agent: adminProxyAgent,
    configure(proxy) {
      proxy.on("error", err => {
        console.error("[vite proxy admin-api]", err.message);
      });
    }
  };

  return {
    base: VITE_PUBLIC_PATH,
    root,
    resolve: {
      alias
    },
    server: {
      port: VITE_PORT,
      host: "0.0.0.0",
      proxy: {
        "/api": adminProxy,
        "/im-account": adminProxy,
        "/im-user": adminProxy,
        "/im-group": adminProxy,
        "/get-async-routes": adminProxy,
        "/refresh-token": adminProxy,
        "/mine": adminProxy,
        "/mine-logs": adminProxy
      },
      warmup: {
        clientFiles: ["./index.html", "./src/{views,components}/*"]
      }
    },
    plugins: await getPluginsList(VITE_CDN, VITE_COMPRESSION, {
      enableDev: false,
      enableProd: false
    }),
    optimizeDeps: {
      include,
      exclude
    },
    build: {
      target: "es2015",
      sourcemap: false,
      chunkSizeWarningLimit: 4000,
      rolldownOptions: {
        input: {
          index: pathResolve("./index.html", import.meta.url)
        },
        output: {
          chunkFileNames: "static/js/[name]-[hash].js",
          entryFileNames: "static/js/[name]-[hash].js",
          assetFileNames: "static/[ext]/[name]-[hash].[ext]"
        },
        checks: {
          pluginTimings: false,
          toleratedTransform: false
        }
      }
    },
    define: {
      __INTLIFY_PROD_DEVTOOLS__: false,
      __APP_INFO__: JSON.stringify(__APP_INFO__)
    }
  };
};
