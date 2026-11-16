/** en-US system config resource pack (REQ-20261116-230354: tenant-level security policy;
 *  REQ-20261117-011514 adds the login captcha switch). */
export const systemConfig = {
  head: {
    eyebrow: 'System',
    title: 'System Configuration',
    sub: 'Tenant-level security policies, enforced for all members once saved; other users load them on next refresh/login.',
  },
  title: 'Security policies',
  watermark: 'Global watermark',
  watermarkDesc: 'Overlay page content with the signed-in account watermark, enforced for all members',
  captcha: 'Login captcha',
  captchaDesc: 'Require an image captcha at sign-in, enforced for all members of this tenant',
  idleLock: 'Auto-lock on idle',
  idleLockDesc: 'Lock the screen after being idle for the configured duration, enforced for all members (Off = never auto-lock)',
  idleOff: 'Off',
  idle1: '1 minute',
  idle5: '5 minutes',
  idle15: '15 minutes',
  saved: 'System configuration saved successfully; applies to all members on next load',
  readOnly: 'Your account lacks the update permission (system:config:update); view only.',
  reload: 'Refresh configuration',
};