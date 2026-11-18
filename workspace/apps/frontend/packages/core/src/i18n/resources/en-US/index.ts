import { common } from './common';
import { nav } from './nav';
import { login } from './login';
import { dashboard } from './dashboard';
import { releases } from './releases';
import { apps } from './apps';
import { tenants } from './tenants';
import { users } from './users';
import { roles } from './roles';
import { depts } from './depts';
import { positions } from './positions';
import { permissions } from './permissions';
import { menus } from './menus';
import { dicts } from './dicts';
import { notices } from './notices';
import { audit } from './audit';
import { loginLogs } from './loginLogs';
import { onlineUsers } from './onlineUsers';
import { opExceptions } from './opExceptions';
import { jobs } from './jobs';
import { files } from './files';
import { systemConfig } from './systemConfig';
import { settings } from './settings';
import { components } from './components';

/** en-US resource aggregation (per-domain split files; aggregation only, no copy here). */
export const enUS = {
  common,
  nav,
  login,
  dashboard,
  releases,
  apps,
  tenants,
  users,
  roles,
  depts,
  positions,
  permissions,
  menus,
  dicts,
  notices,
  audit,
  loginLogs,
  onlineUsers,
  opExceptions,
  jobs,
  files,
  systemConfig,
  settings,
  components,
};
