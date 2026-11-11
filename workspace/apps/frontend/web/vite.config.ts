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
  server: { port: 5173, strictPort: true },
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