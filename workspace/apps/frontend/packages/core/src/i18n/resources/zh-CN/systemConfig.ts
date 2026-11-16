/** zh-CN 系统配置页资源包（REQ-20261116-230354：租户级安全策略；REQ-20261117-011514 增验证码开关；
 *  REQ-20261117-025200 增页脚版权卡）。 */
export const systemConfig = {
  head: {
    eyebrow: 'System',
    title: '系统配置',
    sub: '租户级安全策略，保存后对本租户全员强制生效；其他用户下次刷新/登录时加载。',
  },
  title: '安全策略',
  watermark: '全局水印',
  watermarkDesc: '以当前登录账号水印覆盖页面内容，对全员生效',
  captcha: '登录验证码',
  captchaDesc: '登录时要求输入图形验证码，对本租户全员生效',
  idleLock: '闲置自动锁屏',
  idleLockDesc: '无操作达到时长后锁定屏幕，对全员生效（关闭=不自动锁屏）',
  idleOff: '关闭',
  idle1: '1 分钟',
  idle5: '5 分钟',
  idle15: '15 分钟',
  footerTitle: '页脚版权',
  footerCopyright: '页脚版权文案',
  footerCopyrightPlaceholder: '如 © 2026 ACME 云平台',
  footerCopyrightDesc: '展示在应用内页脚与登录页页脚的版权声明（≤255 字符）；留空不展示文案',
  footerVisible: '显示页脚版权',
  footerVisibleDesc: '开启后按上方文案在本租户应用内页脚与登录页页脚展示',
  saved: '系统配置保存成功，全员下次加载生效',
  readOnly: '当前账号无修改权限（system:config:update），仅可查看。',
  reload: '刷新配置',
};