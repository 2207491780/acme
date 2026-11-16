/** zh-CN 系统配置页资源包（REQ-20261116-230354：租户级安全策略）。 */
export const systemConfig = {
  head: {
    eyebrow: 'System',
    title: '系统配置',
    sub: '租户级安全策略，保存后对本租户全员强制生效；其他用户下次刷新/登录时加载。',
  },
  title: '安全策略',
  watermark: '全局水印',
  watermarkDesc: '以当前登录账号水印覆盖页面内容，对全员生效',
  idleLock: '闲置自动锁屏',
  idleLockDesc: '无操作达到时长后锁定屏幕，对全员生效（关闭=不自动锁屏）',
  idleOff: '关闭',
  idle1: '1 分钟',
  idle5: '5 分钟',
  idle15: '15 分钟',
  saved: '系统配置保存成功，全员下次加载生效',
  readOnly: '当前账号无修改权限（system:config:update），仅可查看。',
  reload: '刷新配置',
};