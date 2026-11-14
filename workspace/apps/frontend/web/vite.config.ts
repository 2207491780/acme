import { defineConfig, type Plugin } from 'vite';
import react from '@vitejs/plugin-react';
import { fileURLToPath } from 'node:url';

const CSP =
  "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data:; font-src 'self'; connect-src 'self'; manifest-src 'self'; object-src 'none'; base-uri 'self'; form-action 'self'";

// 仅生产构建注入严格 CSP；开发态保留 Vite HMR 所需的内联脚本。
function injectCsp(): Plugin {
  return {
    name: 'acme-inject-csp',
    apply: 'build',
    transformIndexHtml(html) {
      return html.replace(
        '<meta charset="utf-8">',
        `<meta charset="utf-8">\n    <meta http-equiv="Content-Security-Policy" content="${CSP}">`,
      );
    },
  };
}

export default defineConfig({
  plugins: [react(), injectCsp()],
  resolve: {
    // 仅对直接依赖去重，避免主题上下文分裂及 pnpm 隔离依赖的解析失败。
    dedupe: ['react', 'react-dom', 'antd'],
    alias: {
      '@acme/core': fileURLToPath(new URL('../packages/core/src/index.ts', import.meta.url)),
    },
  },
  // /admin 代理到网关：目标经 WEB_API_TARGET 注入（缺省本地 9888；远程联调设
  // WEB_API_TARGET=http://dev.acmecloud.site），同源请求规避 CSP connect-src 'self' 与开发态 CORS；
  // 路由为 Hash 形态（#/...），代理前缀不与 SPA 路由冲突
  server: {
    port: 5173,
    strictPort: true,
    proxy: {
      '/admin': { target: process.env.WEB_API_TARGET ?? 'http://localhost:9888', changeOrigin: true },
      // WS 实时推送（REQ-20261114-120102）：/ws/system/** 经网关路由到 platform-system（ws:true 升级协议）
      '/ws': { target: process.env.WEB_API_TARGET ?? 'http://localhost:9888', changeOrigin: true, ws: true },
    },
  },
  preview: { port: 4173, strictPort: true },
  build: {
    outDir: 'dist',
    sourcemap: true,
    rollupOptions: {
      output: {
        manualChunks: {
          'react-vendor': ['react', 'react-dom', 'react-router-dom'],
          'antd-vendor': ['antd', '@ant-design/icons'],
        },
      },
    },
  },
});