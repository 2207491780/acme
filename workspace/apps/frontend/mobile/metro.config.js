const { getDefaultConfig } = require('expo/metro-config');
const path = require('path');

const projectRoot = __dirname;
const workspaceRoot = path.resolve(projectRoot, '..');

const config = getDefaultConfig(projectRoot);

// pnpm monorepo：监听共享包源码，并从 workspace 根解析依赖。
config.watchFolders = [workspaceRoot];
config.resolver.nodeModulesPaths = [
  path.resolve(projectRoot, 'node_modules'),
  path.resolve(workspaceRoot, 'node_modules'),
  // pnpm 将传递依赖私有提升到 .pnpm/node_modules；
  // Metro 需在此解析 @babel/runtime 等被注入的运行时辅助模块。
  path.resolve(workspaceRoot, 'node_modules/.pnpm/node_modules'),
];
// 直接复用 @acme/core 的 TS 源码（与 Web 端一致，无需先构建 dist）。
config.resolver.extraNodeModules = {
  '@acme/core': path.resolve(workspaceRoot, 'packages/core/src'),
};

module.exports = config;